package com.sistermesh.app.data

import androidx.room.withTransaction
import com.sistermesh.app.data.local.AppMetadataEntity
import com.sistermesh.app.data.local.DeviceInfoEntity
import com.sistermesh.app.data.local.DonationConfirmationEntity
import com.sistermesh.app.data.local.EmergencyRequestEntity
import com.sistermesh.app.data.local.FacilityEntity
import com.sistermesh.app.data.local.LocalEventFactory
import com.sistermesh.app.data.local.ProfileEntity
import com.sistermesh.app.data.local.RequestActivityEntity
import com.sistermesh.app.data.local.SisterMeshDatabase
import com.sistermesh.app.data.local.SyncCheckpointEntity
import com.sistermesh.app.data.local.SyncEventEntity
import com.sistermesh.app.data.local.SyncStateEntity
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
    private val conflicts = database.syncConflictDao()
    private val syncStates = database.syncStateDao()
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
            if (syncStates.get() == null) {
                syncStates.upsert(SyncStateEntity())
            }
            val appMetadata = metadata.get()
            if (appMetadata == null) {
                metadata.upsert(AppMetadataEntity(demoDataSeeded = true))
            } else if (!appMetadata.demoDataSeeded) {
                metadata.upsert(appMetadata.copy(demoDataSeeded = true))
            }
        }
    }

    fun observeProfile() = profiles.observe().map { it?.toDomain() }
    fun observeRequests() = requests.observeOutgoingWithResponses().map { rows -> rows.map { it.toDomain() } }
    fun observeIncoming() = requests.observeIncoming().map { rows -> rows.map { it.toIncomingDomain() } }
    fun observeFacilityRequests() = requests.observeFacilityWithResponses().map { rows -> rows.map { it.toDomain() } }
    fun observeFacilities() = database.facilityDao().observe().map { rows ->
        rows.map { Facility(it.id, it.name, it.locality, it.state) }
    }
    fun observeReceipts() = confirmations.observeAll().map { rows -> rows.map { it.toReceipt() } }
    fun observePendingEvents() = events.observePending()
    fun observeEventsForReview() = events.observeNeedsReview()
    fun observeAllEvents() = events.observeAll()
    fun observeDeviceInfo() = devices.observe()
    fun observeSyncState() = syncStates.observe()
    fun observePendingSyncCount() = events.observePendingCount()
    fun observeFailedSyncCount() = events.observeFailedCount()
    fun observeConflicts() = conflicts.observeAll()
    fun observeConflictCount() = conflicts.observeCount()

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
                facilityId = profile.facilityId,
                facilityLocation = profile.facilityLocation,
                designation = profile.designation,
                isAvailable = previous?.isAvailable ?: true,
                language = previous?.language ?: "English"
            )
        )
    }

    suspend fun setAvailability(available: Boolean) = profiles.setAvailability(available)
    suspend fun setLanguage(language: String) = profiles.setLanguage(language)

    suspend fun storeFacilities(facilities: List<Facility>) {
        database.withTransaction {
            database.facilityDao().clear()
            database.facilityDao().upsertAll(facilities.map {
                FacilityEntity(it.id, it.name, it.locality, it.state)
            })
        }
    }

    suspend fun resetDemoProfile() {
        database.withTransaction {
            profiles.clear()
            requests.deleteDemoRequests()
        }
    }

    suspend fun createRequest(
        group: String,
        urgency: Urgency,
        units: Int,
        facilityId: String,
        isForSelf: Boolean,
        beneficiaryName: String?,
        beneficiaryRelationship: String?,
        additionalInfo: String?,
        ownerAccountId: String? = null
    ): BloodRequest {
        val now = LocalEventFactory.now()
        val localId = "REQ-${UUID.randomUUID().toString().take(8).uppercase()}"
        val profile = profiles.get()
        val deviceId = requireNotNull(devices.get()) { "Device identity has not been initialized." }.clientDeviceId.toString()
        val facility = database.facilityDao().getAll().firstOrNull { it.id == facilityId }
        val facilityName = facility?.let {
            listOf(it.name, it.locality, it.state).filter(String::isNotBlank).joinToString(", ")
        } ?: profile?.facility.orEmpty()
        val request = EmergencyRequestEntity(
            localId = localId,
            kind = if (profile?.role == "CHW") "FACILITY" else "OUTGOING",
            bloodGroup = group,
            urgency = urgency.name,
            units = units,
            note = additionalInfo.orEmpty(),
            additionalInfo = additionalInfo,
            facility = facilityName.ifBlank { "Health facility" },
            facilityId = facilityId,
            isForSelf = isForSelf,
            beneficiaryName = beneficiaryName,
            beneficiaryRelationship = beneficiaryRelationship,
            createdAt = now,
            updatedAt = now,
            status = RequestStatus.PENDING_FACILITY_VERIFICATION.name,
            syncStatus = "PENDING"
        )
        val event = LocalEventFactory.requestCreate(
            localId = localId,
            bloodGroup = group,
            urgency = urgency.name,
            units = units,
            facilityId = facilityId,
            isForSelf = isForSelf,
            beneficiaryName = beneficiaryName,
            beneficiaryRelationship = beneficiaryRelationship,
            additionalInfo = additionalInfo,
            originDeviceId = deviceId,
            localCreatedAt = now,
            ownerAccountId = ownerAccountId
        )

        database.withTransaction {
            requests.upsert(request)
            activities.insertIfAbsent(activity(localId, "CREATED", profile?.role, now))
            events.insertIfAbsent(event)
        }
        return request.toDomain(emptyList())
    }

    suspend fun cancelRequest(localId: String, ownerAccountId: String? = null) {
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
                } else null,
                ownerAccountId = ownerAccountId
            )
            requests.upsert(request.copy(status = RequestStatus.CANCELLED.name, updatedAt = now, syncStatus = "PENDING"))
            activities.insertIfAbsent(activity(localId, "CANCELLED", profiles.get()?.role, now))
            events.insertIfAbsent(event)
        }
    }

    suspend fun updateSubmission(
        localId: String,
        group: String,
        urgency: Urgency,
        units: Int,
        isForSelf: Boolean,
        beneficiaryName: String?,
        beneficiaryRelationship: String?,
        additionalInfo: String?,
        expectedUpdatedAt: String,
        ownerAccountId: String? = null
    ) {
        val request = requests.get(localId) ?: return
        val now = LocalEventFactory.now()
        val patch = JSONObject()
            .put("requestedBloodGroup", LocalEventFactory.apiBloodGroup(group))
            .put("urgency", urgency.name)
            .put("units", units)
            .put("isForSelf", isForSelf)
            .put("beneficiaryName", beneficiaryName)
            .put("beneficiaryRelationship", beneficiaryRelationship)
            .put("additionalInfo", additionalInfo)
            .put("expectedUpdatedAt", expectedUpdatedAt)
        val deviceId = requireNotNull(devices.get()) { "Device identity has not been initialized." }
            .clientDeviceId.toString()
        val event = LocalEventFactory.event(
            eventType = "request.update",
            localEntityId = localId,
            entityId = request.serverId,
            originDeviceId = deviceId,
            localCreatedAt = now,
            payload = patch.toString(),
            dependsOnEventId = events.latestForEntity(localId)?.eventId,
            blockedReason = when {
                request.serverId == null -> "WAITING_FOR_SERVER_ENTITY_ID" to "This update is waiting for the request's server ID."
                request.lastServerCursor == null -> "MISSING_SERVER_VERSION" to "Download the latest server version before this update can sync."
                else -> null
            },
            ownerAccountId = ownerAccountId
        )
        database.withTransaction {
            requests.upsert(
                request.copy(
                    bloodGroup = group,
                    urgency = urgency.name,
                    units = units,
                    isForSelf = isForSelf,
                    beneficiaryName = beneficiaryName,
                    beneficiaryRelationship = beneficiaryRelationship,
                    additionalInfo = additionalInfo,
                    note = additionalInfo.orEmpty(),
                    updatedAt = now,
                    status = RequestStatus.PENDING_FACILITY_VERIFICATION.name,
                    syncStatus = "PENDING"
                )
            )
            activities.insertIfAbsent(activity(localId, "UPDATED", "INDIVIDUAL", now))
            events.insertIfAbsent(event)
        }
    }

    suspend fun reviewRequest(
        localId: String,
        action: String,
        reason: String?,
        expectedUpdatedAt: String,
        ownerAccountId: String? = null
    ) {
        val request = requests.get(localId) ?: return
        val now = LocalEventFactory.now()
        val payload = JSONObject()
            .put("action", action)
            .put("expectedUpdatedAt", expectedUpdatedAt)
        reason?.takeIf(String::isNotBlank)?.let { payload.put("reason", it) }
        val deviceId = requireNotNull(devices.get()) { "Device identity has not been initialized." }
            .clientDeviceId.toString()
        val event = LocalEventFactory.event(
            eventType = "request.review",
            localEntityId = localId,
            entityId = request.serverId,
            originDeviceId = deviceId,
            localCreatedAt = now,
            payload = payload.toString(),
            dependsOnEventId = events.latestForEntity(localId)?.eventId,
            blockedReason = if (request.serverId == null || request.lastServerCursor == null) {
                "MISSING_SERVER_VERSION" to "Download the latest server request before reviewing it."
            } else null,
            ownerAccountId = ownerAccountId
        )
        database.withTransaction {
            events.insertIfAbsent(event)
            activities.insertIfAbsent(activity(localId, "REVIEW_QUEUED", "HEALTH_WORKER", now))
        }
    }

    suspend fun respond(localId: String, accept: Boolean, ownerAccountId: String? = null) {
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
            } else null,
            ownerAccountId = ownerAccountId
        )
        val responseStatus = if (accept) ResponseStatus.ACCEPTED else ResponseStatus.DECLINED
        val donorRef = deviceId.takeLast(4).uppercase()
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

    suspend fun withdrawResponse(localId: String, ownerAccountId: String? = null) {
        val request = requests.get(localId) ?: return
        val profile = profiles.get()
        val deviceId = requireNotNull(devices.get()) { "Device identity has not been initialized." }.clientDeviceId.toString()
        val donorRef = deviceId.takeLast(4).uppercase()
        val response = responses.find(localId, donorRef) ?: return
        if (response.status == ResponseStatus.WITHDRAWN.name) return
        val now = LocalEventFactory.now()
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
                } else null,
                ownerAccountId = ownerAccountId
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
        expectedUpdatedAt: String?,
        ownerAccountId: String? = null
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
            expectedUpdatedAt == null || request.lastServerCursor == null ->
                "MISSING_SERVER_VERSION" to "Download the latest server version before this update can sync."
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
                blockedReason = blockedReason,
                ownerAccountId = ownerAccountId
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

    suspend fun confirmDonation(requestId: String, donorRef: String, ownerAccountId: String? = null): String {
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
                blockedReason = blockedReason,
                ownerAccountId = ownerAccountId
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
