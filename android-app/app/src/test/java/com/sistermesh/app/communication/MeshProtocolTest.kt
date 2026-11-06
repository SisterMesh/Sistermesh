package com.sistermesh.app.communication

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
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class MeshProtocolTest {
    @Test
    fun relaySupportsTwoThreeAndFourDevicePathsWithoutChangingOriginEvent() {
        val origin = TestMeshSigner()
        val now = Instant.now()
        val originId = UUID.randomUUID().toString()
        val eventId = UUID.randomUUID().toString()

        for (deviceCount in 2..4) {
            val deviceIds = (0 until deviceCount).map { UUID.randomUUID().toString() }
            val base = event(
                origin,
                originId,
                eventId,
                target = deviceIds.last(),
                now = now
            )
            var packet = base
            for (index in 1 until deviceCount) {
                val relayId = deviceIds[index - 1]
                val nextId = deviceIds[index]
                assertEquals(
                    RelayPolicy.Decision.Forward,
                    RelayPolicy.decide(packet, relayId, nextId, seen = false, now = now)
                )
                packet = RelayPolicy.nextHop(packet, relayId)
            }

            assertEquals(deviceCount - 1, packet.hopCount)
            assertEquals(deviceIds[deviceCount - 2], packet.previousHopDeviceId)
            assertEquals(base.messageId, packet.messageId)
            assertEquals(base.originDeviceId, packet.originDeviceId)
            assertEquals(base.originSignature, packet.originSignature)
            assertEquals(base.payloadJson, packet.payloadJson)
            assertEquals(base.originCreatedAt, packet.originCreatedAt)
            assertEquals(
                RelayPolicy.Decision.TargetReached,
                RelayPolicy.decide(packet, deviceIds.last(), UUID.randomUUID().toString(), false, now)
            )
        }
    }

    @Test
    fun relayPolicyRejectsDuplicatesLoopsHopLimitAndExpiredMessages() {
        val signer = TestMeshSigner()
        val originId = UUID.randomUUID().toString()
        val now = Instant.now()
        val packet = event(signer, originId, UUID.randomUUID().toString(), null, now).copy(
            hopCount = 2,
            previousHopDeviceId = "previous"
        )
        assertEquals(
            RelayPolicy.Decision.AlreadySeen,
            RelayPolicy.decide(packet, "relay", "next", seen = true, now = now)
        )
        assertEquals(
            RelayPolicy.Decision.PreviousHop,
            RelayPolicy.decide(packet, "relay", "previous", seen = false, now = now)
        )
        assertEquals(
            RelayPolicy.Decision.HopLimitReached,
            RelayPolicy.decide(packet.copy(hopCount = MESH_MAX_HOPS), "relay", "next", false, now)
        )
        assertEquals(
            RelayPolicy.Decision.Expired,
            RelayPolicy.decide(
                packet.copy(expiresAt = now.minusSeconds(1).toString()),
                "relay",
                "next",
                false,
                now
            )
        )
    }

    @Test
    fun originSignatureCoversEventPayloadAndCodecPreservesRelayEnvelope() {
        val signer = TestMeshSigner()
        val now = Instant.now()
        val packet = event(signer, UUID.randomUUID().toString(), UUID.randomUUID().toString(), null, now)
        assertNull(MeshEventValidator.validate(packet, now))
        assertTrue(signer.verify(packet.originPublicKey, MeshCanonical.event(packet), packet.originSignature))

        val tampered = packet.copy(payloadJson = packet.payloadJson.replace("\"units\":2", "\"units\":3"))
        assertFalse(signer.verify(tampered.originPublicKey, MeshCanonical.event(tampered), tampered.originSignature))

        val relayed = packet.copy(
            hopCount = 2,
            previousHopDeviceId = UUID.randomUUID().toString(),
            relayAttestorDeviceId = UUID.randomUUID().toString(),
            relayAttestationSignature = "relay-proof"
        )
        val decoded = MeshCodec.decode(MeshCodec.encodeEvent(relayed)) as MeshWirePacket.Event
        assertEquals(relayed, decoded.event)
    }

    @Test
    fun validatorRejectsNotesAndPatientDetails() {
        val signer = TestMeshSigner()
        val now = Instant.now()
        val packet = event(signer, UUID.randomUUID().toString(), UUID.randomUUID().toString(), null, now)
        assertNotNull(
            MeshEventValidator.validate(
                packet.copy(payloadJson = """{"requestedBloodGroup":"O_NEGATIVE","urgency":"CRITICAL","units":2,"note":"private"}"""),
                now
            )
        )
        assertNotNull(
            MeshEventValidator.validate(
                packet.copy(payloadJson = """{"requestedBloodGroup":"O_NEGATIVE","urgency":"CRITICAL","units":2,"patientName":"A"}"""),
                now
            )
        )
    }

    private fun event(
        signer: TestMeshSigner,
        originId: String,
        eventId: String,
        target: String?,
        now: Instant
    ): MeshEventPacket {
        val identity = signer.identity(originId)
        val unsigned = MeshEventPacket(
            messageId = eventId,
            eventType = "request.create",
            localEntityId = "REQ-LOCAL",
            entityId = null,
            originDeviceId = originId,
            originPublicKey = identity.publicKeyBase64,
            originIdentitySignature = identity.identitySignature,
            originSignature = "",
            payloadSchemaVersion = 1,
            payloadJson = """{"requestedBloodGroup":"O_NEGATIVE","urgency":"CRITICAL","units":2}""",
            displayFacility = "Community Clinic",
            displayDonorRef = null,
            relatedRequestOriginId = null,
            targetDeviceId = target,
            originCreatedAt = now.minusSeconds(1).toString(),
            expiresAt = now.plusSeconds(MESH_TTL_MINUTES * 60).toString()
        )
        return unsigned.copy(originSignature = signer.sign(MeshCanonical.event(unsigned)))
    }

    private class TestMeshSigner : MeshSigner {
        private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

        override fun identity(deviceId: String): DeviceIdentity {
            val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)
            return DeviceIdentity(
                deviceId,
                publicKey,
                sign(MeshCanonical.identity(deviceId, publicKey))
            )
        }

        override fun sign(bytes: ByteArray): String {
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(keyPair.private)
            signature.update(bytes)
            return Base64.getEncoder().encodeToString(signature.sign())
        }

        override fun verify(publicKeyBase64: String, bytes: ByteArray, signatureBase64: String): Boolean =
            runCatching {
                val keyBytes = Base64.getDecoder().decode(publicKeyBase64)
                val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
                val verifier = Signature.getInstance("SHA256withECDSA")
                verifier.initVerify(publicKey)
                verifier.update(bytes)
                verifier.verify(Base64.getDecoder().decode(signatureBase64))
            }.getOrDefault(false)
    }
}
