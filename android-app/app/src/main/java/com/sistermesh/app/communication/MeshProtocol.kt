package com.sistermesh.app.communication

import com.sistermesh.app.data.sha256
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.util.UUID

const val MESH_PROTOCOL_VERSION = 1
const val MESH_MAX_HOPS = 4
const val MESH_TTL_MINUTES = 30L
const val MESH_MAX_FRAME_BYTES = 24_000

data class DeviceIdentity(
    val deviceId: String,
    val publicKeyBase64: String,
    val identitySignature: String
)

data class MeshEventPacket(
    val messageId: String,
    val eventType: String,
    val localEntityId: String?,
    val entityId: String?,
    val originDeviceId: String,
    val originPublicKey: String,
    val originIdentitySignature: String,
    val originSignature: String,
    val payloadSchemaVersion: Int,
    val payloadJson: String,
    val displayFacility: String?,
    val displayDonorRef: String?,
    val relatedRequestOriginId: String?,
    val targetDeviceId: String?,
    val originCreatedAt: String,
    val expiresAt: String,
    val hopCount: Int = 0,
    val maxHops: Int = MESH_MAX_HOPS,
    val previousHopDeviceId: String? = null,
    val relayAttestorDeviceId: String? = null,
    val relayAttestationSignature: String? = null
)

data class MeshAckPacket(
    val acknowledgementId: String,
    val messageId: String,
    val acknowledgingDeviceId: String,
    val acknowledgingPublicKey: String,
    val acknowledgingIdentitySignature: String,
    val targetDeviceId: String,
    val status: String,
    val createdAt: String,
    val expiresAt: String,
    val hopCount: Int = 0,
    val maxHops: Int = MESH_MAX_HOPS,
    val nextHopDeviceId: String? = null,
    val relayAttestorDeviceId: String? = null,
    val relayAttestationSignature: String? = null,
    val signature: String
)

sealed interface MeshWirePacket {
    data class Hello(val identity: DeviceIdentity) : MeshWirePacket
    data class Event(val event: MeshEventPacket) : MeshWirePacket
    data class Acknowledgement(val ack: MeshAckPacket) : MeshWirePacket
}

object MeshCodec {
    fun encodeHello(identity: DeviceIdentity): ByteArray = encode(
        JSONObject()
            .put("kind", "HELLO")
            .put("protocolVersion", MESH_PROTOCOL_VERSION)
            .put("deviceId", identity.deviceId)
            .put("publicKey", identity.publicKeyBase64)
            .put("identitySignature", identity.identitySignature)
    )

    fun encodeEvent(packet: MeshEventPacket): ByteArray {
        val json = JSONObject()
            .put("kind", "EVENT")
            .put("protocolVersion", MESH_PROTOCOL_VERSION)
            .put("messageId", packet.messageId)
            .put("eventType", packet.eventType)
            .put("originDeviceId", packet.originDeviceId)
            .put("originPublicKey", packet.originPublicKey)
            .put("originIdentitySignature", packet.originIdentitySignature)
            .put("originSignature", packet.originSignature)
            .put("payloadSchemaVersion", packet.payloadSchemaVersion)
            .put("payloadJson", packet.payloadJson)
            .put("originCreatedAt", packet.originCreatedAt)
            .put("expiresAt", packet.expiresAt)
            .put("hopCount", packet.hopCount)
            .put("maxHops", packet.maxHops)
        json.putNullable("localEntityId", packet.localEntityId)
        json.putNullable("entityId", packet.entityId)
        json.putNullable("displayFacility", packet.displayFacility)
        json.putNullable("displayDonorRef", packet.displayDonorRef)
        json.putNullable("relatedRequestOriginId", packet.relatedRequestOriginId)
        json.putNullable("targetDeviceId", packet.targetDeviceId)
        json.putNullable("previousHopDeviceId", packet.previousHopDeviceId)
        json.putNullable("relayAttestorDeviceId", packet.relayAttestorDeviceId)
        json.putNullable("relayAttestationSignature", packet.relayAttestationSignature)
        return encode(json)
    }

    fun encodeAck(packet: MeshAckPacket): ByteArray {
        val json = JSONObject()
            .put("kind", "ACK")
            .put("protocolVersion", MESH_PROTOCOL_VERSION)
            .put("acknowledgementId", packet.acknowledgementId)
            .put("messageId", packet.messageId)
            .put("acknowledgingDeviceId", packet.acknowledgingDeviceId)
            .put("acknowledgingPublicKey", packet.acknowledgingPublicKey)
            .put("acknowledgingIdentitySignature", packet.acknowledgingIdentitySignature)
            .put("targetDeviceId", packet.targetDeviceId)
            .put("status", packet.status)
            .put("createdAt", packet.createdAt)
            .put("expiresAt", packet.expiresAt)
            .put("hopCount", packet.hopCount)
            .put("maxHops", packet.maxHops)
            .put("signature", packet.signature)
        json.putNullable("nextHopDeviceId", packet.nextHopDeviceId)
        json.putNullable("relayAttestorDeviceId", packet.relayAttestorDeviceId)
        json.putNullable("relayAttestationSignature", packet.relayAttestationSignature)
        return encode(json)
    }

    fun decode(bytes: ByteArray): MeshWirePacket {
        require(bytes.isNotEmpty() && bytes.size <= MESH_MAX_FRAME_BYTES) { "Invalid mesh frame size." }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.getInt("protocolVersion") == MESH_PROTOCOL_VERSION) { "Unsupported mesh protocol version." }
        return when (json.getString("kind")) {
            "HELLO" -> MeshWirePacket.Hello(
                DeviceIdentity(
                    deviceId = json.getString("deviceId"),
                    publicKeyBase64 = json.getString("publicKey"),
                    identitySignature = json.getString("identitySignature")
                )
            )
            "EVENT" -> MeshWirePacket.Event(
                MeshEventPacket(
                    messageId = json.getString("messageId"),
                    eventType = json.getString("eventType"),
                    localEntityId = json.optNullableString("localEntityId"),
                    entityId = json.optNullableString("entityId"),
                    originDeviceId = json.getString("originDeviceId"),
                    originPublicKey = json.getString("originPublicKey"),
                    originIdentitySignature = json.getString("originIdentitySignature"),
                    originSignature = json.getString("originSignature"),
                    payloadSchemaVersion = json.getInt("payloadSchemaVersion"),
                    payloadJson = json.getString("payloadJson"),
                    displayFacility = json.optNullableString("displayFacility"),
                    displayDonorRef = json.optNullableString("displayDonorRef"),
                    relatedRequestOriginId = json.optNullableString("relatedRequestOriginId"),
                    targetDeviceId = json.optNullableString("targetDeviceId"),
                    originCreatedAt = json.getString("originCreatedAt"),
                    expiresAt = json.getString("expiresAt"),
                    hopCount = json.getInt("hopCount"),
                    maxHops = json.getInt("maxHops"),
                    previousHopDeviceId = json.optNullableString("previousHopDeviceId"),
                    relayAttestorDeviceId = json.optNullableString("relayAttestorDeviceId"),
                    relayAttestationSignature = json.optNullableString("relayAttestationSignature")
                )
            )
            "ACK" -> MeshWirePacket.Acknowledgement(
                MeshAckPacket(
                    acknowledgementId = json.getString("acknowledgementId"),
                    messageId = json.getString("messageId"),
                    acknowledgingDeviceId = json.getString("acknowledgingDeviceId"),
                    acknowledgingPublicKey = json.getString("acknowledgingPublicKey"),
                    acknowledgingIdentitySignature = json.getString("acknowledgingIdentitySignature"),
                    targetDeviceId = json.getString("targetDeviceId"),
                    status = json.getString("status"),
                    createdAt = json.getString("createdAt"),
                    expiresAt = json.getString("expiresAt"),
                    hopCount = json.getInt("hopCount"),
                    maxHops = json.getInt("maxHops"),
                    nextHopDeviceId = json.optNullableString("nextHopDeviceId"),
                    relayAttestorDeviceId = json.optNullableString("relayAttestorDeviceId"),
                    relayAttestationSignature = json.optNullableString("relayAttestationSignature"),
                    signature = json.getString("signature")
                )
            )
            else -> throw IllegalArgumentException("Unsupported mesh packet kind.")
        }
    }

    private fun encode(json: JSONObject): ByteArray {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MESH_MAX_FRAME_BYTES) { "Mesh packet exceeds the safe byte-payload limit." }
        return bytes
    }

    private fun JSONObject.putNullable(key: String, value: String?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else getString(key)
}

object MeshCanonical {
    fun identity(deviceId: String, publicKey: String) =
        bytes(listOf("SisterMeshIdentity/v1", deviceId, publicKey))

    fun event(packet: MeshEventPacket) = bytes(
        listOf(
            "SisterMeshEvent/v1",
            packet.messageId,
            packet.eventType,
            packet.localEntityId,
            packet.entityId,
            packet.originDeviceId,
            packet.payloadSchemaVersion.toString(),
            packet.payloadJson,
            packet.displayFacility,
            packet.displayDonorRef,
            packet.relatedRequestOriginId,
            packet.targetDeviceId,
            packet.originCreatedAt,
            packet.expiresAt
        )
    )

    fun relayAttestation(packet: MeshEventPacket) = bytes(
        listOf(
            "SisterMeshRelay/v1",
            packet.messageId,
            packet.originDeviceId,
            sha256(packet.originPublicKey),
            packet.originSignature,
            packet.hopCount.toString(),
            packet.maxHops.toString(),
            packet.previousHopDeviceId,
            packet.relayAttestorDeviceId
        )
    )

    fun acknowledgement(packet: MeshAckPacket) = bytes(
        listOf(
            "SisterMeshAck/v1",
            packet.acknowledgementId,
            packet.messageId,
            packet.acknowledgingDeviceId,
            packet.targetDeviceId,
            packet.status,
            packet.createdAt,
            packet.expiresAt
        )
    )

    fun ackRelayAttestation(packet: MeshAckPacket) = bytes(
        listOf(
            "SisterMeshAckRelay/v1",
            packet.acknowledgementId,
            packet.messageId,
            packet.acknowledgingDeviceId,
            sha256(packet.signature),
            packet.hopCount.toString(),
            packet.maxHops.toString(),
            packet.nextHopDeviceId,
            packet.relayAttestorDeviceId
        )
    )

    private fun bytes(parts: List<String?>): ByteArray =
        parts.joinToString("") { value ->
            val safe = value.orEmpty()
            "${safe.length}:$safe;"
        }.toByteArray(Charsets.UTF_8)
}

object MeshEventValidator {
    private val bloodGroups = setOf(
        "A_POSITIVE", "A_NEGATIVE", "B_POSITIVE", "B_NEGATIVE",
        "AB_POSITIVE", "AB_NEGATIVE", "O_POSITIVE", "O_NEGATIVE"
    )
    private val supportedTypes = setOf(
        "request.create",
        "request.update",
        "request.cancel",
        "donor-response.accept",
        "donor-response.decline",
        "donor.withdraw"
    )

    fun validate(packet: MeshEventPacket, now: Instant = Instant.now()): String? = runCatching {
        require(UUID.fromString(packet.messageId).toString().equals(packet.messageId, true)) { "Invalid event ID." }
        require(UUID.fromString(packet.originDeviceId).toString().equals(packet.originDeviceId, true)) { "Invalid origin device ID." }
        packet.entityId?.let { UUID.fromString(it) }
        require(packet.payloadSchemaVersion == 1) { "Unsupported event payload version." }
        require(packet.eventType in supportedTypes) { "Unsupported event type." }
        require(packet.hopCount in 0..packet.maxHops && packet.maxHops in 1..MESH_MAX_HOPS) { "Invalid hop count." }
        val created = Instant.parse(packet.originCreatedAt)
        val expires = Instant.parse(packet.expiresAt)
        require(created.isBefore(now.plus(Duration.ofMinutes(5))) && expires.isAfter(now)) { "Invalid or expired event timestamps." }
        require(!expires.isAfter(now.plus(Duration.ofMinutes(MESH_TTL_MINUTES + 5)))) { "Message TTL exceeds the allowed limit." }
        require(packet.payloadJson.toByteArray().size <= 8_192) { "Event payload is too large." }
        val payload = JSONObject(packet.payloadJson)
        when (packet.eventType) {
            "request.create" -> {
                require(payload.keys().asSequence().all { it in setOf("requestedBloodGroup", "urgency", "units", "expiresAt") }) {
                    "Unexpected request field."
                }
                require(payload.getString("requestedBloodGroup") in bloodGroups) { "Invalid requested blood group." }
                require(payload.getString("urgency") in setOf("CRITICAL", "URGENT")) { "Invalid urgency." }
                require(payload.getInt("units") in 1..6) { "Invalid requested units." }
                require(packet.localEntityId?.length in 1..128) { "Missing originating request ID." }
            }
            "request.update" -> {
                require(payload.keys().asSequence().all {
                    it in setOf("requestedBloodGroup", "urgency", "units", "expiresAt", "expectedUpdatedAt")
                }) { "Unexpected request update field." }
                require(payload.has("expectedUpdatedAt")) { "Missing request version." }
                require(payload.keys().asSequence().any { it != "expectedUpdatedAt" }) { "Empty request update." }
                if (payload.has("requestedBloodGroup")) require(payload.getString("requestedBloodGroup") in bloodGroups)
                if (payload.has("urgency")) require(payload.getString("urgency") in setOf("CRITICAL", "URGENT"))
                if (payload.has("units")) require(payload.getInt("units") in 1..6)
                runCatching { Instant.parse(payload.getString("expectedUpdatedAt")) }
                    .getOrElse { throw IllegalArgumentException("Invalid request version timestamp.") }
                require(packet.localEntityId?.length in 1..128) { "Missing originating request ID." }
            }
            else -> {
                require(payload.length() == 0) { "Unexpected operation payload." }
                require(packet.localEntityId?.length in 1..128) { "Missing originating request ID." }
            }
        }
        packet.displayFacility?.let { require(it.length <= 120) { "Facility label too long." } }
        packet.displayDonorRef?.let { require(it.matches(Regex("[A-F0-9]{4}"))) { "Invalid donor reference." } }
        packet.targetDeviceId?.let { UUID.fromString(it) }
        packet.relatedRequestOriginId?.let { require(it.length in 1..128) { "Invalid request route." } }
        null
    }.getOrElse { it.message ?: "Invalid mesh event." }

    fun isSupported(eventType: String) = eventType in supportedTypes
}

object RelayPolicy {
    sealed interface Decision {
        data object Forward : Decision
        data object Expired : Decision
        data object HopLimitReached : Decision
        data object PreviousHop : Decision
        data object AlreadySeen : Decision
        data object TargetReached : Decision
    }

    fun decide(
        packet: MeshEventPacket,
        localDeviceId: String,
        nextPeerDeviceId: String,
        seen: Boolean,
        now: Instant = Instant.now()
    ): Decision = when {
        seen -> Decision.AlreadySeen
        !runCatching { Instant.parse(packet.expiresAt).isAfter(now) }.getOrDefault(false) -> Decision.Expired
        packet.hopCount >= packet.maxHops -> Decision.HopLimitReached
        packet.previousHopDeviceId == nextPeerDeviceId -> Decision.PreviousHop
        packet.targetDeviceId == localDeviceId -> Decision.TargetReached
        else -> Decision.Forward
    }

    fun nextHop(packet: MeshEventPacket, localDeviceId: String) =
        packet.copy(hopCount = packet.hopCount + 1, previousHopDeviceId = localDeviceId)
}
