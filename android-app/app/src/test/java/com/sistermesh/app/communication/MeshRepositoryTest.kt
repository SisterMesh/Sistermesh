package com.sistermesh.app.communication

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sistermesh.app.data.Profile
import com.sistermesh.app.data.RequestStatus
import com.sistermesh.app.data.Role
import com.sistermesh.app.data.SisterMeshRepository
import com.sistermesh.app.data.Urgency
import com.sistermesh.app.data.local.EmergencyRequestEntity
import com.sistermesh.app.data.local.SisterMeshDatabase
import com.sistermesh.app.data.local.SyncEventEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class MeshRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val serverRequestId = "00000000-0000-4000-8000-000000000091"

    @Test
    fun queuedMessageSurvivesDatabaseReopenWhileOffline() = runBlocking {
        val databaseName = "mesh-outbox-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        var database = fileDatabase(databaseName)
        try {
            val appRepository = configuredRequester(database)
            val request = appRepository.createRequest(
                "O-", Urgency.CRITICAL, 2, "Community Clinic", "Riverside", null, null,
                false, "Patient name", "Sibling", "Local-only note"
            )
            val event = appRepository.observeAllEvents().first().single { it.eventType == "request.create" }
            val meshRepository = MeshRepository(database, TestMeshSigner())
            meshRepository.ensureOutgoing(event)

            val queued = database.meshMessageDao().get(event.eventId)
            assertNotNull(queued)
            assertEquals("CREATED", queued?.deliveryState)
            assertFalse(queued?.payloadJson.orEmpty().contains("Local-only note"))
            assertFalse(queued?.payloadJson.orEmpty().contains("Patient name"))
            assertEquals("OPEN", org.json.JSONObject(queued?.payloadJson.orEmpty()).getString("publicationStatus"))
            assertEquals(request.id, queued?.localEntityId)
            database.close()

            database = fileDatabase(databaseName)
            val afterReopen = database.meshMessageDao().get(event.eventId)
            assertNotNull(afterReopen)
            assertEquals("CREATED", afterReopen?.deliveryState)
            assertEquals(event.originDeviceId, afterReopen?.originDeviceId)
            assertEquals(queued?.payloadJson, afterReopen?.payloadJson)
        } finally {
            if (database.isOpen) database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun receiveStoresBeforeAcknowledgingAndDeduplicatesRelayedEvents() = runBlocking {
        val sourceDb = inMemoryDatabase()
        val relayDb = inMemoryDatabase()
        try {
            val sourceApp = configuredRequester(sourceDb)
            val request = sourceApp.createRequest(
                "O-", Urgency.URGENT, 1, "Community Clinic", "Riverside", null, null,
                false, "Patient X", null, "must stay local"
            )
            sourceDb.emergencyRequestDao().get(request.id)?.let {
                sourceDb.emergencyRequestDao().upsert(it.copy(status = RequestStatus.OPEN.name))
            }
            SisterMeshRepository(relayDb).initialize()
            val event = sourceApp.observeAllEvents().first().single { it.eventType == "request.create" }
            val sourceSigner = TestMeshSigner()
            val relaySigner = TestMeshSigner()
            val sourceMesh = MeshRepository(sourceDb, sourceSigner)
            val relayMesh = MeshRepository(relayDb, relaySigner)
            val sourceId = sourceMesh.localDeviceId()
            val relayId = relayMesh.localDeviceId()

            sourceMesh.ensureOutgoing(event)
            relayMesh.pinDirectPeer(sourceSigner.identity(sourceId))
            val bytes = sourceMesh.prepareEventForPeer(event.eventId, relayId)
            assertNotNull(bytes)
            val packet = (MeshCodec.decode(bytes!!) as MeshWirePacket.Event).event

            val first = relayMesh.receiveEvent(packet, sourceId)
            val stored = relayDb.meshMessageDao().get(event.eventId)
            assertTrue(first.stored)
            assertNotNull(stored)
            assertEquals("STORED_BY_PEER", stored?.deliveryState)
            assertEquals("STORED", first.acknowledgement?.status)
            assertTrue(
                relayDb.emergencyRequestDao().observeIncoming().first().any { it.localId.startsWith("MESH-") }
            )

            val duplicate = relayMesh.receiveEvent(packet, sourceId)
            assertFalse(duplicate.stored)
            assertEquals(1, relayDb.meshMessageDao().count(event.eventId))

            val returnedAck = first.acknowledgement!!
            sourceMesh.pinDirectPeer(relaySigner.identity(relayId))
            sourceMesh.recordEventAttempt(event.eventId, relayId)
            sourceMesh.recordEventTransmitted(event.eventId, relayId)
            sourceMesh.receiveAcknowledgement(returnedAck, relayId)
            assertEquals("STORED_BY_PEER", sourceDb.meshMessageDao().get(event.eventId)?.deliveryState)
        } finally {
            sourceDb.close()
            relayDb.close()
        }
    }

    @Test
    fun nearbyRequestWithServerIdMergesIntoTheBackendFeedRow() = runBlocking {
        val sourceDb = inMemoryDatabase()
        val receiverDb = inMemoryDatabase()
        try {
            val sourceApp = configuredRequester(sourceDb)
            sourceApp.createRequest(
                "O-", Urgency.URGENT, 1, "Community Clinic", "Riverside", null, null,
                false, "Private patient", null, "private note"
            )
            val event = sourceApp.observeAllEvents().first().single { it.eventType == "request.create" }
            sourceDb.syncEventDao().recordResult(
                eventId = event.eventId,
                status = "PROCESSED",
                attemptCount = 1,
                lastAttemptedAt = null,
                serverStatus = "PROCESSED",
                serverEntityId = serverRequestId,
                errorCode = null,
                explanation = null
            )
            SisterMeshRepository(receiverDb).initialize()
            receiverDb.emergencyRequestDao().upsert(
                EmergencyRequestEntity(
                    localId = "SYNC-$serverRequestId",
                    serverId = serverRequestId,
                    kind = "INCOMING",
                    bloodGroup = "O-",
                    urgency = "URGENT",
                    units = 1,
                    facility = "Community Clinic",
                    createdAt = "2026-11-07T12:00:00Z",
                    status = "OPEN",
                    syncStatus = "SYNCED"
                )
            )
            val sourceSigner = TestMeshSigner()
            val receiverSigner = TestMeshSigner()
            val sourceMesh = MeshRepository(sourceDb, sourceSigner)
            val receiverMesh = MeshRepository(receiverDb, receiverSigner)
            val sourceId = sourceMesh.localDeviceId()
            val receiverId = receiverMesh.localDeviceId()
            sourceMesh.ensureOutgoing(event)
            receiverMesh.pinDirectPeer(sourceSigner.identity(sourceId))

            val bytes = sourceMesh.prepareEventForPeer(event.eventId, receiverId)
            assertNotNull(bytes)
            val packet = (MeshCodec.decode(bytes!!) as MeshWirePacket.Event).event

            assertEquals(serverRequestId, packet.entityId)
            assertTrue(receiverMesh.receiveEvent(packet, sourceId).stored)

            assertEquals(1, receiverDb.emergencyRequestDao().count())
            assertEquals(
                "SYNC-$serverRequestId",
                receiverDb.emergencyRequestDao().getByServerId(serverRequestId)?.localId
            )
        } finally {
            sourceDb.close()
            receiverDb.close()
        }
    }

    @Test
    fun accountOwnedMeshEventsCannotBeSentAfterLogoutOrAccountSwitch() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val appRepository = configuredRequester(database)
            appRepository.createRequest(
                "O-", Urgency.URGENT, 1, "Community Clinic", "Riverside", null, null,
                false, "Patient name", null, "private note"
            )
            val device = requireNotNull(database.deviceInfoDao().get())
            database.deviceInfoDao().linkAccount("account-A")
            database.syncEventDao().bindUnownedLocalEvents("account-A", device.clientDeviceId.toString())
            val event = database.syncEventDao().get(
                appRepository.observeAllEvents().first().single { it.eventType == "request.create" }.eventId
            )!!
            val mesh = MeshRepository(database, TestMeshSigner())
            val peerId = UUID.randomUUID().toString()
            assertTrue(mesh.pinDirectPeer(TestMeshSigner().identity(peerId)))
            mesh.ensureOutgoing(event)
            assertNotNull(database.meshMessageDao().get(event.eventId))

            database.deviceInfoDao().linkAccount(null)
            assertNull(mesh.prepareEventForPeer(event.eventId, peerId))
            database.deviceInfoDao().linkAccount("account-B")
            assertNull(mesh.prepareEventForPeer(event.eventId, peerId))
            database.deviceInfoDao().linkAccount("account-A")
            assertNotNull(mesh.prepareEventForPeer(event.eventId, peerId))
        } finally {
            database.close()
        }
    }

    @Test
    fun nearbyPublicRequestDoesNotReplaceAnotherAccountsPrivateRow() = runBlocking {
        val sourceDb = inMemoryDatabase()
        val receiverDb = inMemoryDatabase()
        try {
            val sourceApp = configuredRequester(sourceDb)
            sourceApp.createRequest(
                "O-", Urgency.URGENT, 1, "Community Clinic", "Riverside", null, null,
                false, "Source beneficiary", null, "private note"
            )
            val sourceEvent = sourceApp.observeAllEvents().first().single { it.eventType == "request.create" }
            sourceDb.syncEventDao().recordResult(
                eventId = sourceEvent.eventId,
                status = "PROCESSED",
                attemptCount = 1,
                lastAttemptedAt = null,
                serverStatus = "PROCESSED",
                serverEntityId = serverRequestId,
                errorCode = null,
                explanation = null
            )
            SisterMeshRepository(receiverDb).initialize()
            val receiverDevice = requireNotNull(receiverDb.deviceInfoDao().get())
            receiverDb.deviceInfoDao().linkAccount("account-B")
            val privateLocalId = "REQ-account-A"
            receiverDb.emergencyRequestDao().upsert(
                EmergencyRequestEntity(
                    localId = privateLocalId,
                    serverId = serverRequestId,
                    kind = "OUTGOING",
                    bloodGroup = "O-",
                    urgency = "URGENT",
                    units = 1,
                    facility = "Community Clinic",
                    beneficiaryName = "Previous account beneficiary",
                    createdAt = "2026-11-07T12:00:00Z",
                    status = "OPEN"
                )
            )
            receiverDb.syncEventDao().insertIfAbsent(
                SyncEventEntity(
                    eventId = sourceEvent.eventId,
                    eventType = "request.create",
                    localEntityId = privateLocalId,
                    entityId = null,
                    originDeviceId = receiverDevice.clientDeviceId.toString(),
                    localCreatedAt = "2026-11-07T12:00:00Z",
                    payloadJson = "{}",
                    status = "PROCESSED",
                    ownerAccountId = "account-A"
                )
            )
            val sourceSigner = TestMeshSigner()
            val receiverSigner = TestMeshSigner()
            val sourceMesh = MeshRepository(sourceDb, sourceSigner)
            val receiverMesh = MeshRepository(receiverDb, receiverSigner)
            val sourceId = sourceMesh.localDeviceId()
            val receiverId = receiverMesh.localDeviceId()
            sourceMesh.ensureOutgoing(sourceEvent)
            receiverMesh.pinDirectPeer(sourceSigner.identity(sourceId))

            val bytes = sourceMesh.prepareEventForPeer(sourceEvent.eventId, receiverId)
            assertNotNull(bytes)
            val packet = (MeshCodec.decode(bytes!!) as MeshWirePacket.Event).event
            assertTrue(receiverMesh.receiveEvent(packet, sourceId).stored)

            assertEquals(
                "Previous account beneficiary",
                receiverDb.emergencyRequestDao().get(privateLocalId)?.beneficiaryName
            )
            val publicIncoming = receiverDb.emergencyRequestDao().observeIncoming().first()
                .single { it.serverId == serverRequestId }
            assertNull(publicIncoming.beneficiaryName)
            assertTrue(publicIncoming.localId.startsWith("MESH-"))
        } finally {
            sourceDb.close()
            receiverDb.close()
        }
    }

    @Test
    fun trustedPeerKeyCannotBeSilentlyReplaced() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val meshRepository = MeshRepository(database, TestMeshSigner())
            val peerId = UUID.randomUUID().toString()
            val firstKey = TestMeshSigner()
            val replacementKey = TestMeshSigner()
            assertTrue(meshRepository.pinDirectPeer(firstKey.identity(peerId)))
            assertFalse(meshRepository.pinDirectPeer(replacementKey.identity(peerId)))
            assertEquals(
                firstKey.identity(peerId).publicKeyBase64,
                database.trustedMeshPeerDao().get(peerId)?.publicKeyBase64
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun relayForwardsAcrossTwoThreeAndFourDevicesWithoutChangingOrigin() = runBlocking {
        for (deviceCount in 2..4) {
            val databases = (0 until deviceCount).map { inMemoryDatabase() }
            try {
                val appRepositories = databases.mapIndexed { index, database ->
                    if (index == 0) configuredRequester(database) else SisterMeshRepository(database).also { it.initialize() }
                }
                val request = appRepositories.first().createRequest(
                    "AB+", Urgency.CRITICAL, 3, "Community Clinic", "Riverside", null, null,
                    false, "Private patient", null, "private note"
                )
                databases.first().emergencyRequestDao().get(request.id)?.let {
                    databases.first().emergencyRequestDao().upsert(it.copy(status = RequestStatus.OPEN.name))
                }
                val event = appRepositories.first().observeAllEvents().first().single { it.eventType == "request.create" }
                val signers = (0 until deviceCount).map { TestMeshSigner() }
                val meshRepositories = databases.mapIndexed { index, database ->
                    MeshRepository(database, signers[index])
                }
                val deviceIds = meshRepositories.map { it.localDeviceId() }
                val originId = deviceIds.first()
                meshRepositories.first().ensureOutgoing(event)
                val original = databases.first().meshMessageDao().get(event.eventId)!!

                for (hop in 0 until deviceCount - 1) {
                    val senderMesh = meshRepositories[hop]
                    val receiverMesh = meshRepositories[hop + 1]
                    assertTrue(senderMesh.pinDirectPeer(signers[hop + 1].identity(deviceIds[hop + 1])))
                    assertTrue(receiverMesh.pinDirectPeer(signers[hop].identity(deviceIds[hop])))

                    val bytes = senderMesh.prepareEventForPeer(event.eventId, deviceIds[hop + 1])
                    assertNotNull(bytes)
                    val packet = (MeshCodec.decode(bytes!!) as MeshWirePacket.Event).event
                    senderMesh.recordEventAttempt(event.eventId, deviceIds[hop + 1])
                    senderMesh.recordEventTransmitted(event.eventId, deviceIds[hop + 1])
                    val received = receiverMesh.receiveEvent(packet, deviceIds[hop])
                    assertTrue("hop ${hop + 1} of $deviceCount", received.stored)
                    assertNull(receiverMesh.prepareEventForPeer(event.eventId, deviceIds[hop]))
                }

                val finalMessage = databases.last().meshMessageDao().get(event.eventId)
                assertNotNull(finalMessage)
                assertEquals(originId, finalMessage?.originDeviceId)
                assertEquals(original.originSignature, finalMessage?.originSignature)
                assertEquals(original.payloadJson, finalMessage?.payloadJson)
                assertEquals(original.originCreatedAt, finalMessage?.originCreatedAt)
                assertFalse(finalMessage?.payloadJson.orEmpty().contains("private note"))
                assertEquals(deviceCount - 1, finalMessage?.hopCount)
            } finally {
                databases.forEach { it.close() }
            }
        }
    }

    private suspend fun configuredRequester(database: SisterMeshDatabase): SisterMeshRepository {
        val repository = SisterMeshRepository(database)
        repository.initialize()
        repository.registerProfile(
            Profile(
                role = Role.INDIVIDUAL,
                name = "Requester",
                facility = "Community Clinic",
                facilityLocation = "Riverside",
                designation = "Nurse"
            )
        )
        return repository
    }

    private fun inMemoryDatabase() =
        Room.inMemoryDatabaseBuilder(context, SisterMeshDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    private fun fileDatabase(name: String) =
        Room.databaseBuilder(context, SisterMeshDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()

    private class TestMeshSigner : MeshSigner {
        private val pair: KeyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

        override fun identity(deviceId: String): DeviceIdentity {
            val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
            return DeviceIdentity(deviceId, publicKey, sign(MeshCanonical.identity(deviceId, publicKey)))
        }

        override fun sign(bytes: ByteArray): String {
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(pair.private)
            signer.update(bytes)
            return Base64.getEncoder().encodeToString(signer.sign())
        }

        override fun verify(publicKeyBase64: String, bytes: ByteArray, signatureBase64: String): Boolean =
            runCatching {
                val publicKey = KeyFactory.getInstance("EC").generatePublic(
                    X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64))
                )
                val verifier = Signature.getInstance("SHA256withECDSA")
                verifier.initVerify(publicKey)
                verifier.update(bytes)
                verifier.verify(Base64.getDecoder().decode(signatureBase64))
            }.getOrDefault(false)
    }
}
