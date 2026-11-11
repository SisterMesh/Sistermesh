package com.sistermesh.app.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.sistermesh.app.data.local.DeviceInfoEntity
import com.sistermesh.app.data.local.EmergencyRequestEntity
import com.sistermesh.app.data.local.ProfileEntity
import com.sistermesh.app.data.local.SisterMeshDatabase
import com.sistermesh.app.data.local.SyncCheckpointEntity
import com.sistermesh.app.data.local.SyncEventEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class SyncEngineTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun downloadedPageIsStoredBeforeAckAndRecoveredAfterLostAckResponse() = runBlocking {
        val database = newDatabase()
        try {
            val fixture = initialize(database)
            val api = FakeSyncApi(fixture.user, database).apply { loseFirstAckResponse = true }
            val engine = SyncEngine(database, api, AuthRepository(api, MemoryTokenStore()))

            val firstRun = engine.run()
            val pendingCheckpoint = database.syncCheckpointDao().get()
            val requestBeforeRetry = database.emergencyRequestDao().getByServerId(SERVER_REQUEST_ID)

            assertEquals(SyncOutcome.RETRY, firstRun)
            assertTrue(api.ackSawPersistedPage)
            assertNotNull(requestBeforeRetry)
            assertEquals("2026-11-07T11:30:00Z", requestBeforeRetry?.reviewedAt)
            assertEquals("0", pendingCheckpoint?.lastCursor)
            assertEquals("1", pendingCheckpoint?.pendingCursor)

            val secondRun = engine.run()
            val committedCheckpoint = database.syncCheckpointDao().get()

            assertEquals(SyncOutcome.SUCCESS, secondRun)
            assertEquals("1", committedCheckpoint?.lastCursor)
            assertNull(committedCheckpoint?.pendingCursor)
            assertEquals(1, database.syncConflictDao().observeCount().first())
            assertNotNull(database.deviceInfoDao().get()?.lastSyncAt)
        } finally {
            database.close()
        }
    }

    @Test
    fun unlinkedLocalEventsAreNotUploadedAndRequireExplicitAccountLink() = runBlocking {
        val database = newDatabase()
        try {
            val fixture = initialize(database, linkAccount = false)
            database.syncEventDao().insertIfAbsent(
                SyncEventEntity(
                    eventId = "local-event",
                    eventType = "request.create",
                    localEntityId = "local-request",
                    entityId = null,
                    originDeviceId = fixture.clientDeviceId.toString(),
                    localCreatedAt = "2026-11-07T12:00:00Z",
                    payloadJson = """{"requestedBloodGroup":"O_NEGATIVE","urgency":"URGENT","units":1}"""
                )
            )
            val api = FakeSyncApi(fixture.user, database)
            val engine = SyncEngine(database, api, AuthRepository(api, MemoryTokenStore()))

            assertEquals(SyncOutcome.ACCOUNT_LINK_REQUIRED, engine.run())
            assertEquals(0, api.registerDeviceCalls)
            assertEquals(0, api.uploadedEventIds.size)

            assertEquals(1, engine.linkLocalDataToAccount(fixture.user))
            val linkedEvent = database.syncEventDao().get("local-event")
            assertEquals(fixture.user.id, linkedEvent?.ownerAccountId)
            assertEquals(fixture.user.id, database.deviceInfoDao().get()?.accountUserId)
        } finally {
            database.close()
        }
    }

    @Test
    fun retriesReuseBatchAndEventIdsThenAttachServerRequestId() = runBlocking {
        val database = newDatabase()
        try {
            val fixture = initialize(database)
            database.emergencyRequestDao().upsert(
                EmergencyRequestEntity(
                    localId = "local-request",
                    kind = "OUTGOING",
                    bloodGroup = "O-",
                    urgency = "URGENT",
                    units = 1,
                    facility = "Local facility",
                    createdAt = "2026-11-07T12:00:00Z",
                    status = "SEARCHING",
                    syncStatus = "PENDING"
                )
            )
            database.syncEventDao().insertIfAbsent(
                SyncEventEntity(
                    eventId = "stable-event",
                    eventType = "request.create",
                    localEntityId = "local-request",
                    entityId = null,
                    originDeviceId = fixture.clientDeviceId.toString(),
                    localCreatedAt = "2026-11-07T12:00:00Z",
                    ownerAccountId = fixture.user.id,
                    payloadJson = """{"requestedBloodGroup":"O_NEGATIVE","urgency":"URGENT","units":1}"""
                )
            )
            val api = FakeSyncApi(fixture.user, database).apply {
                uploadStatuses.addAll(listOf("RECEIVED", "PROCESSED"))
            }
            val engine = SyncEngine(database, api, AuthRepository(api, MemoryTokenStore()))

            assertEquals(SyncOutcome.RETRY, engine.run())
            assertEquals(SyncOutcome.SUCCESS, engine.run())
            val event = database.syncEventDao().get("stable-event")

            assertEquals(2, api.batchIds.size)
            assertEquals(api.batchIds[0], api.batchIds[1])
            assertEquals(listOf("stable-event", "stable-event"), api.uploadedEventIds)
            assertEquals("PROCESSED", event?.status)
            assertEquals(SERVER_REQUEST_ID, event?.serverEntityId)
            assertEquals(SERVER_REQUEST_ID, database.emergencyRequestDao().get("local-request")?.serverId)
            assertFalse(api.uploadedEventIds.any { it != "stable-event" })
        } finally {
            database.close()
        }
    }

    @Test
    fun newerServerSnapshotDoesNotOverwriteFieldsWithAnUnresolvedLocalConflict() = runBlocking {
        val database = newDatabase()
        try {
            val fixture = initialize(database)
            database.emergencyRequestDao().upsert(
                EmergencyRequestEntity(
                    localId = "local-request",
                    serverId = SERVER_REQUEST_ID,
                    kind = "OUTGOING",
                    bloodGroup = "A-",
                    urgency = "CRITICAL",
                    units = 2,
                    note = "Keep this local note",
                    facility = "Old facility",
                    createdAt = "2026-11-07T10:00:00Z",
                    updatedAt = "2026-11-07T11:00:00Z",
                    status = "SEARCHING",
                    syncStatus = "CONFLICT"
                )
            )
            database.syncEventDao().insertIfAbsent(
                SyncEventEntity(
                    eventId = "conflicted-update",
                    eventType = "request.update",
                    localEntityId = "local-request",
                    entityId = SERVER_REQUEST_ID,
                    originDeviceId = fixture.clientDeviceId.toString(),
                    localCreatedAt = "2026-11-07T11:00:00Z",
                    ownerAccountId = fixture.user.id,
                    payloadJson = """{"requestedBloodGroup":"A_POSITIVE"}""",
                    status = "CONFLICT",
                    serverErrorCode = "STALE_VERSION"
                )
            )
            assertEquals("Keep this local note", database.emergencyRequestDao().get("local-request")?.note)
            assertTrue(database.syncEventDao().hasUnresolvedForEntity("local-request"))
            val api = FakeSyncApi(fixture.user, database).apply { serverRequestIsMine = true }
            val engine = SyncEngine(database, api, AuthRepository(api, MemoryTokenStore()))

            assertEquals(SyncOutcome.SUCCESS, engine.run())
            val request = database.emergencyRequestDao().get("local-request")

            assertEquals("Keep this local note", api.requestNoteAtAck)
            assertEquals("A-", request?.bloodGroup)
            assertEquals("CRITICAL", request?.urgency)
            assertEquals(2, request?.units)
            assertEquals("Keep this local note", request?.note)
            assertEquals("OPEN", request?.status)
            assertEquals("1", request?.lastServerCursor)
            assertEquals("Community Clinic", request?.facility)
            assertEquals("Central, Rivers", request?.facilityLocation)
            assertEquals("CONFLICT", request?.syncStatus)
        } finally {
            database.close()
        }
    }

    @Test
    fun legacyWorkerAccountCannotSyncAfterRoleRemoval() = runBlocking {
        val database = newDatabase()
        try {
            val fixture = initialize(database)
            val worker = fixture.user.copy(
                role = "HEALTH_WORKER",
                donorBloodGroup = null
            )
            val api = FakeSyncApi(worker, database)
            val engine = SyncEngine(database, api, AuthRepository(api, MemoryTokenStore()))

            assertEquals(SyncOutcome.ACCOUNT_MISMATCH, engine.run())
            assertEquals(0, api.registerDeviceCalls)
            assertTrue(database.deviceInfoDao().get()?.serverDeviceId == null)
        } finally {
            database.close()
        }
    }

    @Test
    fun revokedDeviceIsNeverSilentlyReRegistered() = runBlocking {
        val database = newDatabase()
        try {
            val fixture = initialize(database)
            val api = FakeSyncApi(fixture.user, database).apply {
                registrationError = BackendHttpException(409, null, "This device has been revoked and cannot be reactivated.")
            }
            val engine = SyncEngine(database, api, AuthRepository(api, MemoryTokenStore()))
            val stableClientId = database.deviceInfoDao().get()?.clientDeviceId

            assertEquals(SyncOutcome.DEVICE_REVOKED, engine.run())
            val after = database.deviceInfoDao().get()
            assertEquals("REVOKED", after?.registrationStatus)
            assertEquals(stableClientId, after?.clientDeviceId)
            assertNull(after?.serverDeviceId)
            assertEquals(0, api.uploadedEventIds.size)
        } finally {
            database.close()
        }
    }

    private fun newDatabase() = Room.inMemoryDatabaseBuilder(context, SisterMeshDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    private suspend fun initialize(database: SisterMeshDatabase, linkAccount: Boolean = true): Fixture {
        val clientDeviceId = UUID.randomUUID()
        val user = BackendUser(
            id = "user-1",
            role = "INDIVIDUAL",
            status = "ACTIVE",
            displayName = "Donor",
            phoneNumber = "+2348012345678",
            donorProfileId = "donor-1",
            donorBloodGroup = "O_NEGATIVE",
            donorLocality = "Central",
            donorProfileComplete = true,
        )
        database.profileDao().upsert(
            ProfileEntity(
                role = "INDIVIDUAL",
                name = "Local donor",
                bloodGroup = "O-",
                area = "Central",
                phone = "+2348012345678",
                facility = "",
                facilityLocation = "",
                designation = ""
            )
        )
        database.deviceInfoDao().upsert(
            DeviceInfoEntity(
                clientDeviceId = clientDeviceId,
                name = "Test phone",
                accountUserId = if (linkAccount) user.id else null
            )
        )
        database.syncCheckpointDao().upsert(SyncCheckpointEntity(updatedAt = "2026-11-07T12:00:00Z"))
        return Fixture(clientDeviceId, user)
    }

    private data class Fixture(val clientDeviceId: UUID, val user: BackendUser)

    private class MemoryTokenStore : TokenStore {
        override fun read() = StoredTokens("test-access", "test-refresh")
        override fun write(tokens: StoredTokens) = Unit
        override fun clear() = Unit
    }

    private class FakeSyncApi(
        private val user: BackendUser,
        private val databaseForAck: SisterMeshDatabase
    ) : SyncBackendApi {
        var registerDeviceCalls = 0
        var loseFirstAckResponse = false
        var ackSawPersistedPage = false
        var requestNoteAtAck: String? = null
        var registrationError: BackendHttpException? = null
        var serverCursor = "0"
        var serverRequestIsMine = false
        val batchIds = mutableListOf<String>()
        val uploadedEventIds = mutableListOf<String>()
        val uploadStatuses = ArrayDeque<String>()

        override suspend fun login(phoneNumber: String, password: String) = error("Not used.")
        override suspend fun registerIndividual(
            displayName: String,
            phoneNumber: String,
            password: String
        ) = error("Not used.")
        override suspend fun registerDonor(
            displayName: String,
            phoneNumber: String,
            password: String,
            bloodGroup: String,
            locality: String
        ) = error("Not used.")
        override suspend fun createDonorProfile(accessToken: String, bloodGroup: String, locality: String) =
            BackendDonorProfile("donor-1", bloodGroup, locality, true, true)
        override suspend fun updateDonorProfile(
            accessToken: String,
            bloodGroup: String?,
            locality: String?,
            isAvailable: Boolean?
        ) = BackendDonorProfile("donor-1", bloodGroup, locality, isAvailable ?: true, true)
        override suspend fun getMyDonations(accessToken: String) = emptyList<BackendDonorResponse>()
        override suspend fun refresh(refreshCookie: String) = error("Not used.")
        override suspend fun logout(refreshCookie: String) = Unit
        override suspend fun getMe(accessToken: String) = user
        override suspend fun registerDevice(accessToken: String, clientDeviceId: String, deviceName: String): RegisteredDevice {
            registerDeviceCalls++
            registrationError?.let { throw it }
            return RegisteredDevice("server-device", clientDeviceId, null)
        }

        override suspend fun uploadBatch(
            accessToken: String,
            deviceId: String,
            clientBatchId: String,
            events: JSONArray
        ): SyncBatchResult {
            val event = events.getJSONObject(0)
            val eventId = event.getString("eventId")
            batchIds += clientBatchId
            uploadedEventIds += eventId
            val status = if (uploadStatuses.isEmpty()) "PROCESSED" else uploadStatuses.removeFirst()
            return SyncBatchResult(
                clientBatchId,
                listOf(
                    SyncEventResult(
                        eventId = eventId,
                        status = status,
                        entityId = if (status == "PROCESSED") SERVER_REQUEST_ID else null,
                        errorCode = null,
                        explanation = null
                    )
                )
            )
        }

        override suspend fun getChanges(accessToken: String, deviceId: String, limit: Int): ServerChangesPage {
            if (serverCursor == "1") return ServerChangesPage("1", "1", false, emptyList())
            return ServerChangesPage(
                checkpoint = "0",
                nextCursor = "1",
                hasMore = false,
                changes = listOf(
                    ServerChange(
                        cursor = "1",
                        eventType = "CREATED",
                        fromStatus = null,
                        toStatus = "OPEN",
                        occurredAt = "2026-11-07T12:00:00Z",
                        request = ServerRequestSnapshot(
                            id = SERVER_REQUEST_ID,
                            requestedBloodGroup = "O_NEGATIVE",
                            urgency = "URGENT",
                            units = 1,
                            status = "OPEN",
                            expiresAt = null,
                            createdAt = "2026-11-07T11:00:00Z",
                            updatedAt = "2026-11-07T11:00:00Z",
                            facilityName = "Community Clinic",
                            facilityLocation = "Central, Rivers",
                            facilityLocality = "Central",
                            facilityState = "Rivers",
                            isMine = serverRequestIsMine,
                            reviewedAt = "2026-11-07T11:30:00Z"
                        )
                    )
                )
            )
        }

        override suspend fun acknowledgeCheckpoint(accessToken: String, deviceId: String, cursor: String) {
            val request = databaseForAck.emergencyRequestDao().getByServerId(SERVER_REQUEST_ID)
            requestNoteAtAck = request?.note
            val checkpoint = databaseForAck.syncCheckpointDao().get()
            ackSawPersistedPage = request != null && checkpoint?.pendingCursor == cursor
            serverCursor = cursor
            if (loseFirstAckResponse) {
                loseFirstAckResponse = false
                throw IOException("The checkpoint acknowledgement response was lost.")
            }
        }

        override suspend fun getConflicts(accessToken: String, deviceId: String, limit: Int) = listOf(
            ServerConflict(
                eventId = "event-conflict",
                eventType = "request.update",
                entityId = SERVER_REQUEST_ID,
                errorCode = "STALE_VERSION",
                explanation = "The server retained this conflict for review.",
                payloadJson = "{}",
                occurredAt = "2026-11-07T12:10:00Z"
            )
        )

    }

    companion object {
        private const val SERVER_REQUEST_ID = "00000000-0000-4000-8000-000000000001"
    }
}
