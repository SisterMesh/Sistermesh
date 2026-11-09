package com.sistermesh.app.communication

import com.sistermesh.app.data.local.LocalEventFactory
import androidx.room.withTransaction
import com.sistermesh.app.data.Decision
import com.sistermesh.app.data.RequestStatus
import com.sistermesh.app.data.ResponseStatus
import com.sistermesh.app.data.local.DonorResponseEntity
import com.sistermesh.app.data.local.EmergencyRequestEntity
import com.sistermesh.app.data.local.MeshAcknowledgementEntity
import com.sistermesh.app.data.local.MeshForwardingHistoryEntity
import com.sistermesh.app.data.local.MeshMessageEntity
import com.sistermesh.app.data.local.MeshRequestRouteEntity
import com.sistermesh.app.data.local.RequestActivityEntity
import com.sistermesh.app.data.local.SisterMeshDatabase
import com.sistermesh.app.data.local.SyncEventEntity
import com.sistermesh.app.data.local.TrustedMeshPeerEntity
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class MeshReceiveResult(
    val acknowledgement: MeshAckPacket?,
    val stored: Boolean,
    val error: String? = null
)

class MeshRepository(
    private val database: SisterMeshDatabase,
    private val signer: MeshSigner
) {
    private val messages = database.meshMessageDao()
    private val history = database.meshForwardingHistoryDao()
    private val acknowledgements = database.meshAcknowledgementDao()
    private val peers = database.trustedMeshPeerDao()
    private val routes = database.meshRequestRouteDao()

    fun observeTrustedPeers(): Flow<List<TrustedMeshPeerEntity>> = peers.observeAll()
    fun observeMessages(): Flow<List<MeshMessageEntity>> = messages.observeAll()

    suspend fun localDeviceId(): String =
        requireNotNull(database.deviceInfoDao().get()) { "Local device identity is not initialized." }
            .clientDeviceId.toString()

    suspend fun identityForLocalDevice(): DeviceIdentity = signer.identity(localDeviceId())

    suspend fun pinDirectPeer(identity: DeviceIdentity): Boolean {
        if (!identity.deviceId.isCanonicalUuid()) return false
        if (!signer.verify(
                identity.publicKeyBase64,
                MeshCanonical.identity(identity.deviceId, identity.publicKeyBase64),
                identity.identitySignature
            )
        ) return false

        val now = Instant.now().toString()
        val known = peers.get(identity.deviceId)
        if (known != null && known.publicKeyBase64 != identity.publicKeyBase64) return false
        peers.upsert(
            TrustedMeshPeerEntity(
                deviceId = identity.deviceId,
                publicKeyBase64 = identity.publicKeyBase64,
                trustLevel = "DIRECT",
                trustedViaDeviceId = null,
                displayName = "SisterMesh device",
                lastSeenAt = now,
                lastConnectedAt = now
            )
        )
        return true
    }

    suspend fun removeTrustedPeer(deviceId: String) = peers.remove(deviceId)

    suspend fun ensureOutgoing(event: SyncEventEntity) {
        val localId = localDeviceId()
        if (event.originDeviceId != localId || !MeshEventValidator.isSupported(event.eventType)) return
        if (messages.get(event.eventId) != null) return
        if (event.localEntityId.isNullOrBlank()) return

        val request = database.emergencyRequestDao().get(event.localEntityId)
        if (
            event.eventType == "request.create" &&
            request?.status !in setOf(RequestStatus.OPEN.name, RequestStatus.PARTIALLY_FILLED.name)
        ) return
        val route = routes.byLocalRequest(event.localEntityId)
        val now = Instant.now()
        val expiry = messageExpiry(event.payloadJson, now)
        val meshPayload = if (event.eventType == "request.create" && request != null) {
            JSONObject()
                .put("requestedBloodGroup", LocalEventFactory.apiBloodGroup(request.bloodGroup))
                .put("urgency", request.urgency)
                .put("units", request.units)
                .put("publicationStatus", request.status)
                .toString()
        } else event.payloadJson
        val isDonorAction = event.eventType in setOf(
            "donor-response.accept", "donor-response.decline", "donor.withdraw"
        )
        val identity = signer.identity(localId)
        val base = MeshEventPacket(
            messageId = event.eventId,
            eventType = event.eventType,
            localEntityId = event.localEntityId,
            entityId = event.entityId,
            originDeviceId = event.originDeviceId,
            originPublicKey = identity.publicKeyBase64,
            originIdentitySignature = identity.identitySignature,
            originSignature = "",
            payloadSchemaVersion = event.payloadSchemaVersion,
            payloadJson = meshPayload,
            displayFacility = request?.facility?.takeIf { event.eventType == "request.create" }?.take(120),
            displayDonorRef = if (isDonorAction) pseudonym(localId) else null,
            relatedRequestOriginId = if (isDonorAction) route?.originRequestLocalId else null,
            targetDeviceId = if (isDonorAction) route?.originDeviceId else null,
            originCreatedAt = event.localCreatedAt,
            expiresAt = expiry.toString()
        )
        val packet = base.copy(originSignature = signer.sign(MeshCanonical.event(base)))
        val invalidReason = MeshEventValidator.validate(packet)
        if (invalidReason != null) {
            messages.insertIfAbsent(packet.toEntity("REJECTED", invalidReason))
            return
        }

        database.withTransaction {
            if (messages.insertIfAbsent(packet.toEntity("CREATED")) < 0) return@withTransaction
            if (event.eventType == "request.create") {
                val request = request ?: return@withTransaction
                routes.upsert(
                    MeshRequestRouteEntity(
                        requestLocalId = request.localId,
                        originEventId = event.eventId,
                        originDeviceId = localId,
                        originRequestLocalId = request.localId,
                        previousHopDeviceId = null,
                        expiresAt = expiry.toString()
                    )
                )
            }
        }
    }

    suspend fun prepareEventForPeer(messageId: String, peerDeviceId: String): ByteArray? {
        val localId = localDeviceId()
        val row = messages.get(messageId) ?: return null
        if (row.eventType == "request.create" && !isApprovedMeshRequest(row, localId)) return null
        val now = Instant.now()
        val relayDecision = RelayPolicy.decide(row.toPacket(), localId, peerDeviceId, seen = false, now = now)
        if (relayDecision == RelayPolicy.Decision.Expired) {
            messages.setState(messageId, "EXPIRED", null)
            return null
        }
        if (relayDecision != RelayPolicy.Decision.Forward) return null
        val priorAttempt = history.get(messageId, peerDeviceId)
        if (priorAttempt != null) {
            val retryAfter = runCatching { Instant.parse(priorAttempt.forwardedAt).plusSeconds(60) }.getOrNull()
            if (priorAttempt.status in setOf("RECEIVED", "ACK_STORED", "ACK_DELIVERED", "DELIVERED")) return null
            if (priorAttempt.status in setOf("TRANSMITTED", "SENDING") && retryAfter?.isAfter(now) == true) return null
        }

        val originTrust = if (row.originDeviceId == localId) null else peers.get(row.originDeviceId)
        if (row.originDeviceId != localId &&
            (originTrust == null || originTrust.publicKeyBase64 != row.originPublicKey)
        ) return null

        val packet = row.toPacket().copy(
            hopCount = row.hopCount + 1,
            previousHopDeviceId = localId,
            relayAttestorDeviceId = if (row.originDeviceId == localId) null else localId,
            relayAttestationSignature = null
        )
        val signedPacket = if (row.originDeviceId == localId) packet else packet.copy(
            relayAttestationSignature = signer.sign(MeshCanonical.relayAttestation(packet))
        )
        return MeshCodec.encodeEvent(signedPacket)
    }

    private suspend fun isApprovedMeshRequest(row: MeshMessageEntity, localId: String): Boolean {
        val packet = row.toPacket()
        if (JSONObject(packet.payloadJson).optString("publicationStatus") !in
            setOf(RequestStatus.OPEN.name, RequestStatus.PARTIALLY_FILLED.name)
        ) return false
        val originRequestId = row.localEntityId ?: return false
        val localRequestId = if (row.originDeviceId == localId) {
            originRequestId
        } else {
            routes.byOriginRequest(row.originDeviceId, originRequestId)?.requestLocalId ?: return false
        }
        val request = database.emergencyRequestDao().get(localRequestId) ?: return false
        return request.status in setOf(RequestStatus.OPEN.name, RequestStatus.PARTIALLY_FILLED.name)
    }

    suspend fun recordEventTransmitted(messageId: String, peerDeviceId: String) {
        val row = messages.get(messageId) ?: return
        val now = Instant.now().toString()
        history.upsert(
            MeshForwardingHistoryEntity(
                messageId = messageId,
                peerDeviceId = peerDeviceId,
                hopCount = row.hopCount + 1,
                forwardedAt = now,
                status = "TRANSMITTED"
            )
        )
        if (row.originDeviceId == localDeviceId()) messages.setState(messageId, "TRANSMITTED", null)
        else messages.setState(messageId, "RELAYED", null)
    }

    suspend fun recordEventAttempt(messageId: String, peerDeviceId: String) {
        val row = messages.get(messageId) ?: return
        history.upsert(
            MeshForwardingHistoryEntity(
                messageId = messageId,
                peerDeviceId = peerDeviceId,
                hopCount = row.hopCount + 1,
                forwardedAt = Instant.now().toString(),
                status = "SENDING"
            )
        )
    }

    suspend fun recordEventSendFailure(messageId: String, peerDeviceId: String, reason: String) {
        val row = messages.get(messageId) ?: return
        history.upsert(
            MeshForwardingHistoryEntity(
                messageId = messageId,
                peerDeviceId = peerDeviceId,
                hopCount = row.hopCount + 1,
                forwardedAt = Instant.now().toString(),
                status = "FAILED",
                error = reason.take(200)
            )
        )
        messages.setState(messageId, "RETRY", reason.take(200))
    }

    suspend fun pendingMessages(): List<MeshMessageEntity> = messages.pending(Instant.now().toString())

    suspend fun receiveEvent(packet: MeshEventPacket, senderDeviceId: String): MeshReceiveResult {
        val localId = localDeviceId()
        val sender = peers.get(senderDeviceId)
            ?: return MeshReceiveResult(null, false, "The connected device is not trusted.")
        if (sender.trustLevel != "DIRECT") {
            return MeshReceiveResult(null, false, "The connected peer has no direct trust record.")
        }

        val invalid = validateOrigin(packet, senderDeviceId, sender)
            ?: MeshEventValidator.validate(packet)
        if (invalid != null) {
            if (packet.messageId.isCanonicalUuid()) {
                messages.insertIfAbsent(packet.toEntity("REJECTED", invalid))
                val rejectedAck = createAck(packet.messageId, packet.originDeviceId, senderDeviceId, "REJECTED")
                return MeshReceiveResult(rejectedAck, false, invalid)
            }
            return MeshReceiveResult(null, false, invalid)
        }

        val existing = messages.get(packet.messageId)
        if (existing != null) {
            return MeshReceiveResult(
                createAck(packet.messageId, packet.originDeviceId, senderDeviceId, if (packet.targetDeviceId == localId) "DELIVERED" else "STORED"),
                false
            )
        }

        val now = Instant.now().toString()
        val targetReached = packet.targetDeviceId == localId
        val row = packet.toEntity(if (targetReached) "DELIVERED" else "STORED_BY_PEER").copy(
            receivedFromDeviceId = senderDeviceId
        )
        database.withTransaction {
            if (messages.insertIfAbsent(row) < 0) return@withTransaction
            history.insertIfAbsent(
                MeshForwardingHistoryEntity(
                    messageId = packet.messageId,
                    peerDeviceId = senderDeviceId,
                    hopCount = packet.hopCount,
                    forwardedAt = now,
                    status = "RECEIVED"
                )
            )
            val originPeer = peers.get(packet.originDeviceId)
            if (packet.originDeviceId != senderDeviceId &&
                (originPeer == null || originPeer.publicKeyBase64 == packet.originPublicKey)
            ) {
                if (originPeer == null) {
                    peers.upsert(
                        TrustedMeshPeerEntity(
                            deviceId = packet.originDeviceId,
                            publicKeyBase64 = packet.originPublicKey,
                            trustLevel = "RELAYED",
                            trustedViaDeviceId = senderDeviceId,
                            lastSeenAt = now
                        )
                    )
                } else if (originPeer.trustLevel != "DIRECT") {
                    peers.upsert(originPeer.copy(trustedViaDeviceId = senderDeviceId, lastSeenAt = now))
                } else {
                    peers.upsert(originPeer.copy(lastSeenAt = now))
                }
            }
            applyEvent(packet, localId, now)
        }
        val ackStatus = if (targetReached) "DELIVERED" else "STORED"
        return MeshReceiveResult(createAck(packet.messageId, packet.originDeviceId, senderDeviceId, ackStatus), true)
    }

    suspend fun receiveAcknowledgement(
        packet: MeshAckPacket,
        senderDeviceId: String
    ): MeshAckPacket? {
        val localId = localDeviceId()
        val sender = peers.get(senderDeviceId) ?: return null
        if (sender.trustLevel != "DIRECT" || !verifyAck(packet, senderDeviceId, sender)) return null
        if (packet.nextHopDeviceId != localId || packet.hopCount > packet.maxHops) return null
        val row = messages.get(packet.messageId) ?: return null
        val now = Instant.now().toString()
        val ackRow = packet.toEntity()

        var newlyStored = false
        database.withTransaction {
            if (acknowledgements.insertIfAbsent(ackRow) < 0) return@withTransaction
            newlyStored = true
            history.get(packet.messageId, senderDeviceId)?.let { attempt ->
                history.upsert(attempt.copy(status = if (packet.status == "DELIVERED") "ACK_DELIVERED" else "ACK_STORED"))
            }
            if (packet.targetDeviceId == localId) {
                val messageState = when (packet.status) {
                    "DELIVERED" -> "DELIVERED"
                    "REJECTED" -> "FAILED"
                    else -> "STORED_BY_PEER"
                }
                messages.setState(packet.messageId, messageState, null)
                acknowledgements.setState(packet.acknowledgementId, "DELIVERED")
            } else {
                val previousHop = row.previousHopDeviceId
                if (previousHop == null || packet.hopCount >= packet.maxHops) {
                    acknowledgements.setState(packet.acknowledgementId, "FAILED")
                }
            }
        }

        if (!newlyStored) return null
        if (packet.targetDeviceId == localId) return null
        val previousHop = row.previousHopDeviceId ?: return null
        if (packet.hopCount >= packet.maxHops) return null
        val forwarded = packet.copy(
            nextHopDeviceId = previousHop,
            hopCount = packet.hopCount + 1,
            relayAttestorDeviceId = localId,
            relayAttestationSignature = null
        )
        val signedForward = forwarded.copy(
            relayAttestationSignature = signer.sign(MeshCanonical.ackRelayAttestation(forwarded))
        )
        acknowledgements.upsert(signedForward.toEntity().copy(deliveryState = "QUEUED"))
        return signedForward
    }

    suspend fun pendingAcknowledgementsFor(peerDeviceId: String): List<MeshAcknowledgementEntity> =
        acknowledgements.pending(Instant.now().toString()).filter { it.nextHopDeviceId == peerDeviceId }

    suspend fun acknowledgementPacket(row: MeshAcknowledgementEntity): MeshAckPacket? {
        val localId = localDeviceId()
        if (row.nextHopDeviceId == null) return null
        val ackIdentityTrusted = row.acknowledgingDeviceId == localId ||
            peers.get(row.acknowledgingDeviceId)?.publicKeyBase64 == row.acknowledgingPublicKey ||
            (row.relayAttestorDeviceId != null &&
                peers.get(row.relayAttestorDeviceId)?.publicKeyBase64 != null)
        if (!ackIdentityTrusted) return null
        return row.toPacket()
    }

    suspend fun recordAcknowledgementTransmitted(ackId: String) =
        acknowledgements.setState(ackId, "TRANSMITTED")

    suspend fun recordAcknowledgementAttempt(ackId: String) =
        acknowledgements.setState(ackId, "SENDING")

    suspend fun recordAcknowledgementFailure(ackId: String) =
        acknowledgements.setState(ackId, "RETRY")

    suspend fun cleanupExpired() {
        val now = Instant.now()
        messages.expire(now.toString())
        messages.deleteExpired(now.minus(Duration.ofDays(1)).toString())
        routes.deleteExpired(now.toString())
    }

    suspend fun recoverInterruptedTransfers() {
        history.recoverInterruptedSends()
        acknowledgements.recoverInterruptedSends()
    }

    private suspend fun validateOrigin(
        packet: MeshEventPacket,
        senderDeviceId: String,
        sender: TrustedMeshPeerEntity
    ): String? {
        if (!packet.originDeviceId.isCanonicalUuid()) return "Invalid origin device ID."
        if (packet.hopCount !in 1..packet.maxHops || packet.previousHopDeviceId != senderDeviceId) {
            return "Nearby forwarding metadata is invalid."
        }
        if (!signer.verify(
                packet.originPublicKey,
                MeshCanonical.identity(packet.originDeviceId, packet.originPublicKey),
                packet.originIdentitySignature
            )
        ) return "Origin identity proof is invalid."
        if (!signer.verify(packet.originPublicKey, MeshCanonical.event(packet), packet.originSignature)) {
            return "Origin event signature is invalid."
        }
        if (packet.originDeviceId == senderDeviceId) {
            if (packet.relayAttestorDeviceId != null || packet.relayAttestationSignature != null) {
                return "An origin event cannot carry a relay attestation."
            }
            if (sender.publicKeyBase64 != packet.originPublicKey) return "Direct peer key does not match the event origin."
        } else {
            if (packet.relayAttestorDeviceId != senderDeviceId || packet.relayAttestationSignature.isNullOrBlank()) {
                return "A relayed event has no trusted forwarding proof."
            }
            if (!signer.verify(
                    sender.publicKeyBase64,
                    MeshCanonical.relayAttestation(packet),
                    packet.relayAttestationSignature
                )
            ) return "Relay proof is invalid."
            val knownOrigin = peers.get(packet.originDeviceId)
            if (knownOrigin?.publicKeyBase64 != null && knownOrigin.publicKeyBase64 != packet.originPublicKey) {
                return "Origin key does not match the stored device identity."
            }
        }
        return null
    }

    private suspend fun verifyAck(
        packet: MeshAckPacket,
        senderDeviceId: String,
        sender: TrustedMeshPeerEntity
    ): Boolean {
        val now = Instant.now()
        if (!packet.acknowledgementId.isCanonicalUuid() ||
            !packet.messageId.isCanonicalUuid() ||
            !packet.acknowledgingDeviceId.isCanonicalUuid() ||
            !packet.targetDeviceId.isCanonicalUuid() ||
            packet.status !in setOf("STORED", "DELIVERED", "REJECTED") ||
            packet.maxHops !in 1..MESH_MAX_HOPS ||
            packet.hopCount !in 0..packet.maxHops ||
            !runCatching {
                val expiry = Instant.parse(packet.expiresAt)
                expiry.isAfter(now) && !expiry.isAfter(now.plus(Duration.ofMinutes(MESH_TTL_MINUTES + 5)))
            }.getOrDefault(false)
        ) return false
        if (!signer.verify(
                packet.acknowledgingPublicKey,
                MeshCanonical.identity(packet.acknowledgingDeviceId, packet.acknowledgingPublicKey),
                packet.acknowledgingIdentitySignature
            )
        ) return false
        if (!signer.verify(
                packet.acknowledgingPublicKey,
                MeshCanonical.acknowledgement(packet),
                packet.signature
            )
        ) return false
        if (packet.acknowledgingDeviceId == senderDeviceId) {
            return sender.publicKeyBase64 == packet.acknowledgingPublicKey
        }
        return packet.relayAttestorDeviceId == senderDeviceId &&
            !packet.relayAttestationSignature.isNullOrBlank() &&
            signer.verify(
                sender.publicKeyBase64,
                MeshCanonical.ackRelayAttestation(packet),
                packet.relayAttestationSignature
            )
    }

    private suspend fun createAck(
        messageId: String,
        targetDeviceId: String,
        nextHopDeviceId: String,
        status: String
    ): MeshAckPacket {
        val localId = localDeviceId()
        val identity = signer.identity(localId)
        val now = Instant.now()
        val ackId = UUID.nameUUIDFromBytes("$messageId|$localId|$status".toByteArray(StandardCharsets.UTF_8)).toString()
        val unsigned = MeshAckPacket(
            acknowledgementId = ackId,
            messageId = messageId,
            acknowledgingDeviceId = localId,
            acknowledgingPublicKey = identity.publicKeyBase64,
            acknowledgingIdentitySignature = identity.identitySignature,
            targetDeviceId = targetDeviceId,
            status = status,
            createdAt = now.toString(),
            expiresAt = now.plus(Duration.ofMinutes(MESH_TTL_MINUTES)).toString(),
            nextHopDeviceId = nextHopDeviceId,
            signature = ""
        )
        val packet = unsigned.copy(signature = signer.sign(MeshCanonical.acknowledgement(unsigned)))
        acknowledgements.insertIfAbsent(packet.toEntity())
        return packet
    }

    private suspend fun applyEvent(packet: MeshEventPacket, localId: String, now: String) {
        val requestDao = database.emergencyRequestDao()
        val payload = JSONObject(packet.payloadJson)
        when (packet.eventType) {
            "request.create" -> {
                val originRequestId = requireNotNull(packet.localEntityId)
                val localRequestId = remoteRequestId(packet.originDeviceId, originRequestId)
                requestDao.upsert(
                    EmergencyRequestEntity(
                        localId = localRequestId,
                        kind = "INCOMING",
                        bloodGroup = localBloodGroup(payload.getString("requestedBloodGroup")),
                        urgency = payload.getString("urgency"),
                        units = payload.getInt("units"),
                        facility = packet.displayFacility ?: "Nearby health facility",
                        createdAt = packet.originCreatedAt,
                        updatedAt = packet.originCreatedAt,
                        status = if (payload.optString("publicationStatus") in setOf(
                                RequestStatus.OPEN.name,
                                RequestStatus.PARTIALLY_FILLED.name
                            )
                        ) payload.optString("publicationStatus") else RequestStatus.PENDING_FACILITY_VERIFICATION.name,
                        receivedAt = "Just now",
                        expiresIn = "Expires in 30 min",
                        decision = Decision.NONE.name,
                        syncStatus = "RELAYED"
                    )
                )
                routes.upsert(
                    MeshRequestRouteEntity(
                        requestLocalId = localRequestId,
                        originEventId = packet.messageId,
                        originDeviceId = packet.originDeviceId,
                        originRequestLocalId = originRequestId,
                        previousHopDeviceId = packet.previousHopDeviceId,
                        expiresAt = packet.expiresAt
                    )
                )
                addActivity(localRequestId, "REQUEST_RECEIVED", packet.originDeviceId, now, packet.messageId)
            }
            "request.update", "request.cancel" -> {
                val originRequestId = packet.localEntityId ?: return
                val route = routes.byOriginRequest(packet.originDeviceId, originRequestId) ?: return
                val current = requestDao.get(route.requestLocalId) ?: return
                val updated = if (packet.eventType == "request.cancel") {
                    current.copy(status = RequestStatus.CANCELLED.name, updatedAt = packet.originCreatedAt, syncStatus = "RELAYED")
                } else {
                    if (payload.getString("expectedUpdatedAt") != current.updatedAt) return
                    current.copy(
                        bloodGroup = if (payload.has("requestedBloodGroup")) {
                            localBloodGroup(payload.getString("requestedBloodGroup"))
                        } else current.bloodGroup,
                        urgency = payload.optString("urgency", current.urgency),
                        units = if (payload.has("units")) payload.getInt("units") else current.units,
                        updatedAt = packet.originCreatedAt,
                        syncStatus = "RELAYED"
                    )
                }
                requestDao.upsert(updated)
                addActivity(route.requestLocalId, packet.eventType.uppercase().replace('.', '_'), packet.originDeviceId, now, packet.messageId)
            }
            "donor-response.accept", "donor-response.decline", "donor.withdraw" -> {
                if (packet.targetDeviceId != localId) return
                val originRequestId = packet.relatedRequestOriginId ?: return
                val localRequest = requestDao.get(originRequestId) ?: return
                val donorRef = packet.displayDonorRef ?: pseudonym(packet.originDeviceId)
                val responses = database.donorResponseDao()
                when (packet.eventType) {
                    "donor.withdraw" -> {
                        responses.find(originRequestId, donorRef)?.let {
                            responses.upsert(it.copy(status = ResponseStatus.WITHDRAWN.name, syncStatus = "RELAYED"))
                        }
                        addActivity(originRequestId, "DONOR_WITHDRAWN", packet.originDeviceId, now, packet.messageId)
                    }
                    else -> {
                        responses.upsert(
                            DonorResponseEntity(
                                id = packet.messageId,
                                requestLocalId = originRequestId,
                                ref = donorRef,
                                area = "Nearby",
                                distance = "Nearby",
                                status = if (packet.eventType == "donor-response.accept") {
                                    ResponseStatus.ACCEPTED.name
                                } else ResponseStatus.DECLINED.name,
                                createdAt = packet.originCreatedAt,
                                syncStatus = "RELAYED"
                            )
                        )
                        if (packet.eventType == "donor-response.accept") {
                            requestDao.upsert(localRequest.copy(status = RequestStatus.ACCEPTED.name, updatedAt = now))
                        }
                        addActivity(originRequestId, packet.eventType.uppercase().replace('.', '_'), packet.originDeviceId, now, packet.messageId)
                    }
                }
            }
        }
    }

    private suspend fun addActivity(requestId: String, type: String, actor: String, now: String, messageId: String) {
        database.requestActivityDao().insertIfAbsent(
            RequestActivityEntity(
                id = messageId,
                requestLocalId = requestId,
                eventType = type,
                actorReference = actor,
                occurredAt = now,
                detailsJson = JSONObject().put("messageId", messageId).toString()
            )
        )
    }

    private fun messageExpiry(payloadJson: String, now: Instant): Instant {
        val default = now.plus(Duration.ofMinutes(MESH_TTL_MINUTES))
        val requested = runCatching {
            JSONObject(payloadJson).optString("expiresAt").takeIf { it.isNotBlank() }?.let(Instant::parse)
        }.getOrNull()
        return if (requested != null && requested.isBefore(default)) requested else default
    }

    private fun remoteRequestId(originDeviceId: String, originRequestId: String) =
        "MESH-${com.sistermesh.app.data.sha256("$originDeviceId|$originRequestId").take(16).uppercase()}"

    private fun localBloodGroup(apiGroup: String) = when (apiGroup) {
        "A_POSITIVE" -> "A+"
        "A_NEGATIVE" -> "A-"
        "B_POSITIVE" -> "B+"
        "B_NEGATIVE" -> "B-"
        "AB_POSITIVE" -> "AB+"
        "AB_NEGATIVE" -> "AB-"
        "O_POSITIVE" -> "O+"
        "O_NEGATIVE" -> "O-"
        else -> throw IllegalArgumentException("Unsupported blood group.")
    }

    private fun pseudonym(deviceId: String) = deviceId.takeLast(4).uppercase()

    private fun MeshEventPacket.toEntity(state: String, error: String? = null) = MeshMessageEntity(
        messageId = messageId,
        eventType = eventType,
        localEntityId = localEntityId,
        entityId = entityId,
        originDeviceId = originDeviceId,
        originPublicKey = originPublicKey,
        originIdentitySignature = originIdentitySignature,
        originSignature = originSignature,
        payloadSchemaVersion = payloadSchemaVersion,
        payloadJson = payloadJson,
        displayFacility = displayFacility,
        displayDonorRef = displayDonorRef,
        relatedRequestOriginId = relatedRequestOriginId,
        targetDeviceId = targetDeviceId,
        originCreatedAt = originCreatedAt,
        expiresAt = expiresAt,
        hopCount = hopCount,
        maxHops = maxHops,
        previousHopDeviceId = previousHopDeviceId,
        deliveryState = state,
        lastError = error
    )

    private fun MeshMessageEntity.toPacket() = MeshEventPacket(
        messageId = messageId,
        eventType = eventType,
        localEntityId = localEntityId,
        entityId = entityId,
        originDeviceId = originDeviceId,
        originPublicKey = originPublicKey,
        originIdentitySignature = originIdentitySignature,
        originSignature = originSignature,
        payloadSchemaVersion = payloadSchemaVersion,
        payloadJson = payloadJson,
        displayFacility = displayFacility,
        displayDonorRef = displayDonorRef,
        relatedRequestOriginId = relatedRequestOriginId,
        targetDeviceId = targetDeviceId,
        originCreatedAt = originCreatedAt,
        expiresAt = expiresAt,
        hopCount = hopCount,
        maxHops = maxHops,
        previousHopDeviceId = previousHopDeviceId
    )

    private fun MeshAckPacket.toEntity() = MeshAcknowledgementEntity(
        acknowledgementId = acknowledgementId,
        messageId = messageId,
        acknowledgingDeviceId = acknowledgingDeviceId,
        acknowledgingPublicKey = acknowledgingPublicKey,
        acknowledgingIdentitySignature = acknowledgingIdentitySignature,
        targetDeviceId = targetDeviceId,
        ackStatus = status,
        signature = signature,
        hopCount = hopCount,
        maxHops = maxHops,
        nextHopDeviceId = nextHopDeviceId,
        createdAt = createdAt,
        relayAttestorDeviceId = relayAttestorDeviceId,
        relayAttestationSignature = relayAttestationSignature,
        expiresAt = expiresAt,
        deliveryState = "QUEUED"
    )

    private fun MeshAcknowledgementEntity.toPacket() = MeshAckPacket(
        acknowledgementId = acknowledgementId,
        messageId = messageId,
        acknowledgingDeviceId = acknowledgingDeviceId,
        acknowledgingPublicKey = acknowledgingPublicKey,
        acknowledgingIdentitySignature = acknowledgingIdentitySignature,
        targetDeviceId = targetDeviceId,
        status = ackStatus,
        createdAt = createdAt,
        expiresAt = expiresAt,
        hopCount = hopCount,
        maxHops = maxHops,
        nextHopDeviceId = nextHopDeviceId,
        relayAttestorDeviceId = relayAttestorDeviceId,
        relayAttestationSignature = relayAttestationSignature,
        signature = signature
    )

    private fun String.isCanonicalUuid(): Boolean =
        runCatching { UUID.fromString(this).toString().equals(this, ignoreCase = true) }.getOrDefault(false)
}
