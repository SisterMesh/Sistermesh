package com.sistermesh.app.data

import androidx.room.withTransaction
import com.sistermesh.app.data.local.AppMetadataEntity
import com.sistermesh.app.data.local.DeviceInfoEntity
import com.sistermesh.app.data.local.DonationConfirmationEntity
import com.sistermesh.app.data.local.EmergencyRequestEntity
import com.sistermesh.app.data.local.LocalEventFactory
import com.sistermesh.app.data.local.ProfileEntity
import com.sistermesh.app.data.local.RequestActivityEntity
import com.sistermesh.app.data.local.SisterMeshDatabase
import com.sistermesh.app.data.local.SyncCheckpointEntity
import com.sistermesh.app.data.local.SyncEventEntity
import com.sistermesh.app.data.local.toDomain
import com.sistermesh.app.data.local.toIncomingDomain
import com.sistermesh.app.data.local.toReceipt
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.util.UUID

class SisterMeshRepository(private val database: SisterMeshDatabase) {
    private val profiles = database.profileDao()
    private val requests = database.emergencyRequestDao()
    private val responses = database.donorResponseDao()
    private val confirmations = database.donationConfirmationDao()
    private val activities = database.requestActivityDao()
    private val events = database.syncEventDao()
    private val devices = database.deviceInfoDao()
    private val checkpoints = database.syncCheckpointDao()
    private val metadata = database.appMetadataDao()

    suspend fun initialize() {
        database.withTransaction {
            if (devices.get() == null) {
                devices.upsert(
                    DeviceInfoEntity(
                        clientDeviceId = UUID.randomUUID(),
                        name = "This Android device"
                    )
                )
            }
            if (checkpoints.get() == null) {
                checkpoints.upsert(SyncCheckpointEntity(updatedAt = LocalEventFactory.now()))
            }
            val appMetadata = metadata.get()
            if (appMetadata == null) {
                seedDemoData()
                metadata.upsert(AppMetadataEntity(demoDataSeeded = true))
            } else if (!appMetadata.demoDataSeeded) {
                seedDemoData()
                metadata.upsert(appMetadata.copy(demoDataSeeded = true))
            }
        }
    }

    fun observeProfile() = profiles.observe().map { it?.toDomain() }
    fun observeRequests() = requests.observeOutgoingWithResponses().map { rows -> rows.map { it.toDomain() } }
    fun observeIncoming() = requests.observeIncoming().map { rows -> rows.map { it.toIncomingDomain() } }
    fun observeReceipts() = confirmations.observeAll().map { rows -> rows.map { it.toReceipt() } }
    fun observePendingEvents() = events.observePending()
    fun observeEventsForReview() = events.observeNeedsReview()

    suspend fun getProfile(): Profile? = profiles.get()?.toDomain()
    suspend fun getPreferences(): Pair<Boolean, String> {
        val stored = profiles.get()
        return (stored?.isAvailable ?: true) to (stored?.language ?: "English")
    }

    suspend fun registerProfile(profile: Profile) {
        val previous = profiles.get()
        profiles.upsert(
            ProfileEntity(
                role = profile.role.name,
                name = profile.name,
                bloodGroup = profile.bloodGroup,
                area = profile.area,
                phone = profile.phone,
                facility = profile.facility,
                facilityLocation = profile.facilityLocation,
                designation = profile.designation,
                isAvailable = previous?.isAvailable ?: true,
                language = previous?.language ?: "English"
            )
        )
    }

    suspend fun setAvailability(available: Boolean) = profiles.setAvailability(available)
    suspend fun setLanguage(language: String) = profiles.setLanguage(language)

    suspend fun resetDemoProfile() {
        database.withTransaction {
            profiles.clear()
            requests.deleteDemoRequests()
        }
    }

    suspend fun createRequest(group: String, urgency: Urgency, units: Int, note: String): BloodRequest {
        val now = LocalEventFactory.now()
        val localId = "REQ-${UUID.randomUUID().toString().take(8).uppercase()}"
        val profile = profiles.get()
        val deviceId = requireNotNull(devices.get()) { "Device identity has not been initialized." }.clientDeviceId.toString()
        val request = EmergencyRequestEntity(
            localId = localId,
            kind = "OUTGOING",
            bloodGroup = group,
            urgency = urgency.name,
            units = units,
            note = note,
            facility = profile?.facility.orEmpty(),
            createdAt = now,
            updatedAt = now,
            status = RequestStatus.SEARCHING.name,
            syncStatus = "PENDING"
        )
        val event = LocalEventFactory.requestCreate(
            localId = localId,
            bloodGroup = group,
            urgency = urgency.name,
            units = units,
            originDeviceId = deviceId,
            localCreatedAt = now
        )

        database.withTransaction {
            requests.upsert(request)
            activities.insertIfAbsent(activity(localId, "CREATED", profile?.role, now))
            events.insertIfAbsent(event)
        }
        return request.toDomain(emptyList())
    }

    suspend fun cancelRequest(localId: String) {
        val now = LocalEventFactory.now()
        val deviceId = requireNotNull(devices.get()) { "Device identity has not been initialized." }.clientDeviceId.toString()
        database.withTransaction {
            val request = requests.get(localId) ?: return@withTransaction
            if (request.status == RequestStatus.CANCELLED.name) return@withTransaction
            val event = LocalEventFactory.event(
                eventType = "request.cancel",
                localEntityId = localId,
                entityId = request.serverId,
                originDeviceId = deviceId,
                localCreatedAt = now,
                payload = "{}",
                dependsOnEventId = events.latestForEntity(localId)?.eventId,
                blockedReason = if (request.serverId == null) {
                    "WAITING_FOR_SERVER_ENTITY_ID" to "This operation is waiting for its request to receive a server ID."
                } else null
            )
            requests.upsert(request.copy(status = RequestStatus.CANCELLED.name, updatedAt = now, syncStatus = "PENDING"))
            activities.insertIfAbsent(activity(localId, "CANCELLED", "HEALTH_WORKER", now))
            events.insertIfAbsent(event)
        }
    }

    suspend fun respond(localId: String, accept: Boolean) {
        val request = requests.get(localId) ?: return
        if (request.kind != "INCOMING" || request.decision != Decision.NONE.name) return
        val now = LocalEventFactory.now()
        val profile = profiles.get()
        val deviceId = requireNotNull(devices.get()) { "Device identity has not been initialized." }.clientDeviceId.toString()
        val eventType = if (accept) "donor-response.accept" else "donor-response.decline"
        val event = LocalEventFactory.event(
            eventType = eventType,
            localEntityId = localId,
            entityId = request.serverId,
            originDeviceId = deviceId,
            localCreatedAt = now,
            payload = "{}",
            blockedReason = if (request.serverId == null) {
                "WAITING_FOR_SERVER_ENTITY_ID" to "This response is waiting for the request's server ID."
            } else null
        )
        val responseStatus = if (accept) ResponseStatus.ACCEPTED else ResponseStatus.DECLINED
        val donorRef = profile?.toDomain()?.donorRef ?: "ME"
        database.withTransaction {
            requests.upsert(
                request.copy(
                    decision = if (accept) Decision.ACCEPTED.name else Decision.DECLINED.name,
                    updatedAt = now,
                    syncStatus = "PENDING"
                )
            )
            responses.upsert(
                com.sistermesh.app.data.local.DonorResponseEntity(
                    id = UUID.randomUUID().toString(),
                    requestLocalId = localId,
                    ref = donorRef,
                    area = profile?.area.orEmpty(),
                    distance = request.distance,
                    status = responseStatus.name,
                    createdAt = now
                )
            )
            activities.insertIfAbsent(activity(localId, if (accept) "DONOR_ACCEPTED" else "DONOR_DECLINED", "DONOR", now))
            events.insertIfAbsent(event)
        }
    }

    suspend fun withdrawResponse(localId: String) {
        val request = requests.get(localId) ?: return
        val profile = profiles.get()
        val donorRef = profile?.toDomain()?.donorRef ?: "ME"
        val response = responses.find(localId, donorRef) ?: return
        if (response.status == ResponseStatus.WITHDRAWN.name) return
        val now = LocalEventFactory.now()
        val deviceId = requireNotNull(devices.get()) { "Device identity has not been initialized." }.clientDeviceId.toString()
        database.withTransaction {
            val event = LocalEventFactory.event(
                eventType = "donor.withdraw",
                localEntityId = localId,
                entityId = request.serverId,
                originDeviceId = deviceId,
                localCreatedAt = now,
                payload = "{}",
                dependsOnEventId = events.latestForEntity(localId)?.eventId,
                blockedReason = if (request.serverId == null) {
                    "WAITING_FOR_SERVER_ENTITY_ID" to "This withdrawal is waiting for the request's server ID."
                } else null
            )
            responses.upsert(response.copy(status = ResponseStatus.WITHDRAWN.name, syncStatus = "PENDING"))
            requests.upsert(request.copy(decision = Decision.WITHDRAWN.name, updatedAt = now, syncStatus = "PENDING"))
            activities.insertIfAbsent(activity(localId, "DONOR_WITHDRAWN", "DONOR", now))
            events.insertIfAbsent(event)
        }
    }

    suspend fun updateRequest(
        localId: String,
        bloodGroup: String?,
        urgency: Urgency?,
        units: Int?,
        expiresAt: String?,
        expectedUpdatedAt: String?
    ) {
        val request = requests.get(localId) ?: return
        val now = LocalEventFactory.now()
        val patch = JSONObject()
        bloodGroup?.let { patch.put("requestedBloodGroup", LocalEventFactory.apiBloodGroup(it)) }
        urgency?.let { patch.put("urgency", it.name) }
        units?.let { patch.put("units", it) }
        expiresAt?.let { patch.put("expiresAt", it) }
        expectedUpdatedAt?.let { patch.put("expectedUpdatedAt", it) }

        val blockedReason = when {
            expectedUpdatedAt == null -> "MISSING_SERVER_VERSION" to "A server update timestamp is required before this update can sync."
            request.serverId == null -> "WAITING_FOR_SERVER_ENTITY_ID" to "This update is waiting for the request's server ID."
            else -> null
        }
        val deviceId = requireNotNull(devices.get()) { "Device identity has not been initialized." }.clientDeviceId
            .toString()
        database.withTransaction {
            val event = LocalEventFactory.event(
                eventType = "request.update",
                localEntityId = localId,
                entityId = request.serverId,
                originDeviceId = deviceId,
                localCreatedAt = now,
                payload = patch.toString(),
                dependsOnEventId = events.latestForEntity(localId)?.eventId,
                blockedReason = blockedReason
            )
            requests.upsert(
                request.copy(
                    bloodGroup = bloodGroup ?: request.bloodGroup,
                    urgency = urgency?.name ?: request.urgency,
                    units = units ?: request.units,
                    updatedAt = now,
                    syncStatus = "PENDING"
                )
            )
            activities.insertIfAbsent(activity(localId, "UPDATED", "HEALTH_WORKER", now))
            events.insertIfAbsent(event)
        }
    }

    suspend fun confirmDonation(requestId: String, donorRef: String): String {
        val request = requireNotNull(requests.get(requestId)) { "Emergency request not found: $requestId" }
        val response = responses.find(requestId, donorRef)
        val now = LocalEventFactory.now()
        val stamp = nowStamp()
        val receiptId = "SM-R-${requestId.removePrefix("REQ-").takeLast(8)}"
        val receiptHash = sha256("$requestId|$donorRef|$stamp")
        val profile = profiles.get()
        val deviceId = requireNotNull(devices.get()) { "Device identity has not been initialized." }.clientDeviceId.toString()
        val payload = JSONObject().put("receiptHash", receiptHash).put("consentedAt", now)
        response?.donorProfileId?.let { payload.put("donorProfileId", it) }
        val blockedReason = when {
            response?.donorProfileId == null -> "MISSING_DONOR_PROFILE_ID" to "A verified donor profile ID is required before this confirmation can sync."
            request.serverId == null -> "WAITING_FOR_SERVER_ENTITY_ID" to "This confirmation is waiting for the request's server ID."
            else -> null
        }
        database.withTransaction {
            val event = LocalEventFactory.event(
                eventType = "donation.confirm",
                localEntityId = requestId,
                entityId = request.serverId,
                originDeviceId = deviceId,
                localCreatedAt = now,
                payload = payload.toString(),
                dependsOnEventId = events.latestForEntity(requestId)?.eventId,
                blockedReason = blockedReason
            )
            requests.upsert(
                request.copy(status = RequestStatus.COMPLETED.name, updatedAt = now, syncStatus = "PENDING")
            )
            if (response != null) {
                responses.upsert(response.copy(status = ResponseStatus.CONFIRMED.name, syncStatus = "PENDING"))
            }
            confirmations.upsert(
                DonationConfirmationEntity(
                    id = receiptId,
                    requestLocalId = requestId,
                    donorProfileId = response?.donorProfileId,
                    donorRef = donorRef,
                    confirmedBy = profile?.role ?: "HEALTH_WORKER",
                    confirmedAt = now,
                    status = "Confirmed by healthcare worker",
                    receiptHash = receiptHash,
                    syncStatus = "PENDING"
                )
            )
            activities.insertIfAbsent(activity(requestId, "DONATION_CONFIRMED", "HEALTH_WORKER", now))
            events.insertIfAbsent(event)
        }
        return receiptId
    }

    suspend fun simulateFacilityConfirmation(localId: String): String {
        val request = requireNotNull(requests.get(localId)) { "Incoming request not found: $localId" }
        val profile = profiles.get()
        val donorRef = profile?.toDomain()?.donorRef ?: "0000"
        val stamp = nowStamp()
        val now = LocalEventFactory.now()
        val receiptId = "SM-R-${localId.removePrefix("REQ-").takeLast(8)}"
        database.withTransaction {
            requests.upsert(request.copy(confirmed = true, updatedAt = now))
            confirmations.upsert(
                DonationConfirmationEntity(
                    id = receiptId,
                    requestLocalId = localId,
                    donorRef = donorRef,
                    confirmedBy = "DEMO_FACILITY",
                    confirmedAt = now,
                    status = "Confirmed by healthcare worker",
                    receiptHash = sha256("$localId|$donorRef|$stamp"),
                    isDemo = true
                )
            )
        }
        return receiptId
    }

    suspend fun simulateIncoming(profile: Profile?): String {
        val groups = listOf("O-", "A+", "B-", "O+")
        val now = nowStamp()
        val localId = "REQ-${UUID.randomUUID().toString().take(8).uppercase()}"
        requests.upsert(
            EmergencyRequestEntity(
                localId = localId,
                kind = "INCOMING",
                bloodGroup = profile?.bloodGroup?.ifBlank { groups.random() } ?: groups.random(),
                urgency = Urgency.URGENT.name,
                units = 1,
                facility = "Port Harcourt Women's Hospital",
            createdAt = LocalEventFactory.now(),
                status = RequestStatus.SEARCHING.name,
                distance = "About 0.8 km away",
                receivedAt = "Just now",
                expiresIn = "Expires in 30 min",
                isDemo = true
            )
        )
        return localId
    }

    suspend fun updateSimulatedRequest(localId: String, transform: (BloodRequest) -> BloodRequest) {
        val current = requests.getWithResponses(localId) ?: return
        val updated = transform(current.toDomain())
        database.withTransaction {
            requests.upsert(
                current.request.copy(
                    status = updated.status.name,
                    devicesFound = updated.devicesFound,
                    delivered = updated.delivered,
                    updatedAt = LocalEventFactory.now()
                )
            )
            responses.deleteForRequest(localId)
            updated.responses.forEach { response ->
                responses.upsert(
                    com.sistermesh.app.data.local.DonorResponseEntity(
                        id = UUID.nameUUIDFromBytes("$localId:${response.ref}".toByteArray()).toString(),
                        requestLocalId = localId,
                        ref = response.ref,
                        area = response.area,
                        distance = response.distance,
                        status = response.status.name,
                        createdAt = LocalEventFactory.now(),
                        syncStatus = "DEMO"
                    )
                )
            }
        }
    }

    suspend fun attachServerEntityId(localId: String, serverId: String, updatedAt: String?) {
        database.withTransaction {
            requests.attachServerId(localId, serverId, updatedAt)
            events.attachEntityId(localId, serverId)
            events.unblockForEntity(localId)
        }
    }

    suspend fun recordEventResult(
        eventId: String,
        status: String,
        attemptCount: Int,
        attemptedAt: String?,
        serverStatus: String?,
        serverEntityId: String?,
        errorCode: String?,
        explanation: String?
    ) {
        events.recordResult(
            eventId,
            status,
            attemptCount,
            attemptedAt,
            serverStatus,
            serverEntityId,
            errorCode,
            explanation
        )
    }

    private suspend fun seedDemoData() {
        MockData.requests().forEach { sample ->
            val entity = sample.toEntity(isDemo = true)
            requests.insertIfAbsent(entity)
            sample.responses.forEach { response ->
                responses.upsert(
                    com.sistermesh.app.data.local.DonorResponseEntity(
                        id = UUID.nameUUIDFromBytes("${sample.id}:${response.ref}".toByteArray()).toString(),
                        requestLocalId = sample.id,
                        ref = response.ref,
                        area = response.area,
                        distance = response.distance,
                        status = response.status.name,
                        createdAt = sample.createdAt,
                        syncStatus = "DEMO"
                    )
                )
            }
        }
        MockData.incoming().forEach { sample ->
            requests.insertIfAbsent(
                EmergencyRequestEntity(
                    localId = sample.id,
                    kind = "INCOMING",
                    bloodGroup = sample.bloodGroup,
                    urgency = sample.urgency.name,
                    units = sample.units,
                    facility = sample.facility,
                    createdAt = LocalEventFactory.toIsoTimestamp(sample.receivedAt),
                    status = RequestStatus.SEARCHING.name,
                    distance = sample.distance,
                    receivedAt = sample.receivedAt,
                    expiresIn = sample.expiresIn,
                    decision = sample.decision.name,
                    confirmed = sample.confirmed,
                    isDemo = true
                )
            )
        }
        MockData.receipts().forEach { receipt ->
            confirmations.insertIfAbsent(
                DonationConfirmationEntity(
                    id = receipt.id,
                    requestLocalId = receipt.requestId,
                    donorRef = receipt.donorRef,
                    confirmedBy = "DEMO_FACILITY",
                    confirmedAt = LocalEventFactory.toIsoTimestamp(receipt.timestamp),
                    status = receipt.status,
                    receiptHash = receipt.hash,
                    isDemo = true
                )
            )
        }
    }

    private fun BloodRequest.toEntity(isDemo: Boolean = false) = EmergencyRequestEntity(
        localId = id,
        kind = "OUTGOING",
        bloodGroup = bloodGroup,
        urgency = urgency.name,
        units = units,
        note = note,
        facility = facility,
            createdAt = LocalEventFactory.toIsoTimestamp(createdAt),
        status = status.name,
        devicesFound = devicesFound,
        delivered = delivered,
        isDemo = isDemo,
        syncStatus = if (isDemo) "DEMO" else "LOCAL"
    )

    private fun activity(
        requestId: String,
        eventType: String,
        actorReference: String?,
        occurredAt: String,
        details: String = "{}"
    ) = RequestActivityEntity(
        id = UUID.randomUUID().toString(),
        requestLocalId = requestId,
        eventType = eventType,
        actorReference = actorReference,
        occurredAt = occurredAt,
        detailsJson = details
    )
}
