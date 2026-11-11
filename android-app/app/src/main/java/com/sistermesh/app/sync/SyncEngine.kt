package com.sistermesh.app.sync

import androidx.room.withTransaction
import com.sistermesh.app.data.local.DeviceInfoEntity
import com.sistermesh.app.data.local.EmergencyRequestEntity
import com.sistermesh.app.data.local.RequestActivityEntity
import com.sistermesh.app.data.local.SisterMeshDatabase
import com.sistermesh.app.data.local.SyncConflictEntity
import com.sistermesh.app.data.local.SyncEventEntity
import com.sistermesh.app.data.local.SyncStateEntity
import com.sistermesh.app.data.local.LocalEventFactory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class SyncOutcome {
    SUCCESS,
    ALREADY_RUNNING,
    NEEDS_SIGN_IN,
    ACCOUNT_LINK_REQUIRED,
    ACCOUNT_MISMATCH,
    PROFILE_INCOMPLETE,
    DEVICE_REVOKED,
    RETRY,
    FAILED
}

class SyncEngine(
    private val database: SisterMeshDatabase,
    private val api: SyncBackendApi,
    private val auth: AuthRepository
) {
    private val runMutex = Mutex()

    suspend fun run(): SyncOutcome {
        if (!runMutex.tryLock()) return SyncOutcome.ALREADY_RUNNING
        try {
            updateState("RUNNING", null)
            val profile = database.profileDao().get()
                ?: return finish(SyncOutcome.PROFILE_INCOMPLETE, "Create a local profile before syncing.")
            val user = auth.currentUser()
            val expectedRole = profile.role.toBackendRole()
            if (user.role != expectedRole) {
                return finish(SyncOutcome.ACCOUNT_MISMATCH, "The signed-in account role does not match this local profile.")
            }
            val device = database.deviceInfoDao().get()
                ?: return finish(SyncOutcome.FAILED, "This phone has no saved device identity.")
            if (device.accountUserId != user.id) {
                return finish(SyncOutcome.ACCOUNT_LINK_REQUIRED, "Confirm that this local profile and its queued records belong to this account.")
            }
            if (device.registrationStatus == "REVOKED") {
                return finish(SyncOutcome.DEVICE_REVOKED, "This phone was revoked. Its queued data remains local.")
            }
            if (user.status != "ACTIVE") {
                return finish(SyncOutcome.NEEDS_SIGN_IN, "This account is not active.")
            }
            if (user.role == "INDIVIDUAL") syncDonorProfile(profile, user)

            val clientDeviceId = device.clientDeviceId.toString()
            val registered = apiCall { api.registerDevice(it, clientDeviceId, device.name) }
            if (registered.clientDeviceId != clientDeviceId || registered.revokedAt != null) {
                markDeviceRevoked(device)
                return finish(SyncOutcome.DEVICE_REVOKED, "This phone's registered identity has been revoked. Queued data was not uploaded.")
            }
            database.deviceInfoDao().upsert(
                device.copy(
                    serverDeviceId = registered.deviceId,
                    registrationStatus = "ACTIVE",
                    registeredAt = device.registeredAt ?: LocalEventFactory.now(),
                    revokedAt = null
                )
            )

            val outgoing = uploadPending(clientDeviceId, user.id, registered.deviceId)
            if (outgoing == SyncOutcome.RETRY) {
                return finish(outgoing, "A server item is waiting for retry.")
            }
            if (outgoing == SyncOutcome.FAILED) {
                return finish(outgoing, "An outgoing item could not be processed.")
            }
            downloadChanges(registered.deviceId, user)
            if (user.donorProfileId != null || profile.bloodGroup.isNotBlank()) {
                refreshDonationActivity()
            }
            refreshConflicts(registered.deviceId)
            val completedAt = LocalEventFactory.now()
            database.deviceInfoDao().upsert(
                database.deviceInfoDao().get()!!.copy(lastSyncAt = completedAt)
            )
            return finish(SyncOutcome.SUCCESS, null)
        } catch (_: AuthenticationRequiredException) {
            return finish(SyncOutcome.NEEDS_SIGN_IN, "Sign in again to resume synchronization.")
        } catch (error: BackendHttpException) {
            if ((error.statusCode == 403 || error.statusCode == 409) &&
                error.message.contains("revoked", ignoreCase = true)
            ) {
                database.deviceInfoDao().get()?.let { markDeviceRevoked(it) }
                return finish(SyncOutcome.DEVICE_REVOKED, "This phone was revoked. Its queued data remains local.")
            }
            if (error.statusCode == 403) {
                return finish(SyncOutcome.PROFILE_INCOMPLETE, error.message)
            }
            if (error.isRetryable) return finish(SyncOutcome.RETRY, "A temporary server error occurred. Sync will retry.")
            if (error.statusCode == 404) {
                database.deviceInfoDao().get()?.let {
                    database.deviceInfoDao().upsert(it.copy(serverDeviceId = null, registrationStatus = "LOCAL_ONLY"))
                }
                return finish(SyncOutcome.RETRY, "The saved server device registration was not found; it will be registered again.")
            }
            return finish(SyncOutcome.FAILED, error.message)
        } catch (error: BackendProtocolException) {
            return finish(SyncOutcome.FAILED, error.message)
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            return finish(SyncOutcome.RETRY, error.message ?: "The connection was interrupted. Sync will retry.")
        } catch (error: Exception) {
            return finish(SyncOutcome.FAILED, error.message ?: "Synchronization could not be completed.")
        } finally {
            runMutex.unlock()
        }
    }

    suspend fun linkLocalDataToAccount(user: BackendUser): Int {
        val profile = database.profileDao().get() ?: throw IllegalStateException("Create a local profile first.")
        require(profile.role.toBackendRole() == user.role) { "The account role does not match this local profile." }
        val device = requireNotNull(database.deviceInfoDao().get()) { "This phone has no saved device identity." }
        val bound = database.withTransaction {
            database.deviceInfoDao().linkAccount(user.id)
            val newlyBound = database.syncEventDao().bindUnownedLocalEvents(user.id, device.clientDeviceId.toString())
            database.syncEventDao().unblockForAccount(user.id, device.clientDeviceId.toString())
            newlyBound
        }
        return bound
    }

    suspend fun pendingCount(): Int = database.syncEventDao().pendingCountNow()

    private suspend fun uploadPending(clientDeviceId: String, accountId: String, serverDeviceId: String): SyncOutcome {
        database.syncEventDao().unblockForAccount(accountId, clientDeviceId)
        database.syncEventDao().blockOtherAccountEvents(accountId, clientDeviceId)
        val candidates = database.syncEventDao().forSync()
        candidates.filter { it.status == "PENDING" || it.status == "RETRY" }.forEach { event ->
            when {
                event.originDeviceId != clientDeviceId -> database.syncEventDao().blockEvent(
                    event.eventId,
                    "RELAYED_ORIGIN_UNSUPPORTED",
                    "Bluetooth-relayed events stay on this phone and cannot be uploaded under another device's identity."
                )
                event.ownerAccountId == null -> database.syncEventDao().blockEvent(
                    event.eventId,
                    "ACCOUNT_LINK_REQUIRED",
                    "Link this phone's local records to the signed-in account before uploading them."
                )
                event.ownerAccountId != accountId -> database.syncEventDao().blockEvent(
                    event.eventId,
                    "ACCOUNT_MISMATCH",
                    "This event belongs to a different signed-in account."
                )
            }
        }

        var anotherPass = true
        while (anotherPass) {
            anotherPass = false
            for (candidate in database.syncEventDao().forSync()) {
            val event = database.syncEventDao().get(candidate.eventId) ?: continue
            if (event.status != "PENDING" && event.status != "RETRY") continue
            if (event.originDeviceId != clientDeviceId || event.ownerAccountId != accountId) continue
            val dependencyId = event.dependsOnEventId
            if (dependencyId != null) {
                val dependency = database.syncEventDao().get(dependencyId)
                if (dependency == null) {
                    database.syncEventDao().blockEvent(
                        event.eventId,
                        "DEPENDENCY_MISSING",
                        "A required earlier local event is missing."
                    )
                    continue
                }
                if (dependency.status == "REJECTED" || dependency.status == "CONFLICT" ||
                    dependency.status == "BLOCKED"
                ) {
                    database.syncEventDao().blockEvent(
                        event.eventId,
                        "DEPENDENCY_FAILED",
                        "A required earlier event was not accepted by the server."
                    )
                    continue
                }
                if (dependency.status != "PROCESSED") continue
            }
            if (event.eventType != "request.create" && event.entityId.isNullOrBlank()) {
                database.syncEventDao().blockEvent(
                    event.eventId,
                    "WAITING_FOR_SERVER_ENTITY_ID",
                    "This operation needs the server ID from its request creation event."
                )
                continue
            }

            val payload = try {
                JSONObject(event.payloadJson)
            } catch (_: Exception) {
                database.syncEventDao().recordResult(
                    event.eventId,
                    "REJECTED",
                    event.attemptCount,
                    event.lastAttemptedAt,
                    "REJECTED",
                    null,
                    "INVALID_LOCAL_PAYLOAD",
                    "The saved event payload is not valid JSON."
                )
                continue
            }
            val envelope = JSONObject()
                .put("eventId", event.eventId)
                .put("eventType", event.eventType)
                .put("entityId", event.entityId ?: JSONObject.NULL)
                .put("originDeviceId", event.originDeviceId)
                .put("localCreatedAt", event.localCreatedAt)
                .put("payloadSchemaVersion", event.payloadSchemaVersion)
                .put("payload", payload)
            event.correlationId?.let { envelope.put("correlationId", it) }
            val batchId = stableBatchId(event.eventId)
            val attemptedAt = LocalEventFactory.now()
            database.syncEventDao().markAttempted(event.eventId, attemptedAt)
            val result = try {
                apiCall {
                    api.uploadBatch(it, serverDeviceId, batchId, JSONArray().put(envelope))
                }
            } catch (error: BackendHttpException) {
                if (error.statusCode == 409) {
                    persistEventConflict(event, "EVENT_ID_REUSE", error.message)
                    database.syncEventDao().recordResult(
                        event.eventId,
                        "CONFLICT",
                        event.attemptCount + 1,
                        attemptedAt,
                        "CONFLICT",
                        null,
                        "EVENT_ID_REUSE",
                        error.message
                    )
                    continue
                }
                database.syncEventDao().recordResult(
                    event.eventId,
                    if (error.isRetryable) "RETRY" else "REJECTED",
                    event.attemptCount + 1,
                    attemptedAt,
                    "HTTP_${error.statusCode}",
                    null,
                    error.errorCode ?: "HTTP_${error.statusCode}",
                    error.message
                )
                if (error.isRetryable) return SyncOutcome.RETRY
                continue
            } catch (error: IOException) {
                database.syncEventDao().recordResult(
                    event.eventId,
                    "RETRY",
                    event.attemptCount + 1,
                    attemptedAt,
                    "NETWORK_ERROR",
                    null,
                    "NETWORK_ERROR",
                    "The upload did not receive a server result."
                )
                throw error
            }

            if (result.clientBatchId != batchId) throw BackendProtocolException("The server returned a different batch ID.")
            val item = result.events.singleOrNull()
                ?: throw BackendProtocolException("The server did not return exactly one result for the uploaded event.")
            if (item.eventId != event.eventId) throw BackendProtocolException("The server result did not match the uploaded event ID.")
            when (item.status) {
                "PROCESSED" -> {
                    val entityId = item.entityId ?: event.entityId
                    if (event.eventType == "request.create" && entityId.isNullOrBlank()) {
                        database.syncEventDao().recordResult(
                            event.eventId,
                            "RETRY",
                            event.attemptCount + 1,
                            attemptedAt,
                            "INVALID_RESULT",
                            null,
                            "MISSING_SERVER_ENTITY_ID",
                            "The server accepted the request but omitted its ID."
                        )
                        return SyncOutcome.RETRY
                    }
                    database.withTransaction {
                        database.syncEventDao().recordResult(
                            event.eventId,
                            "PROCESSED",
                            event.attemptCount + 1,
                            attemptedAt,
                            item.status,
                            entityId,
                            null,
                            null
                        )
                        if (event.eventType == "request.create" && !event.localEntityId.isNullOrBlank()) {
                            database.emergencyRequestDao().attachServerId(event.localEntityId, entityId!!, null)
                            database.syncEventDao().attachEntityId(event.localEntityId, entityId)
                            database.syncEventDao().unblockForEntity(event.localEntityId)
                        }
                        event.localEntityId?.let { refreshEntitySyncState(it) }
                    }
                    anotherPass = true
                }
                "RECEIVED" -> {
                    database.syncEventDao().recordResult(
                        event.eventId,
                        "RETRY",
                        event.attemptCount + 1,
                        attemptedAt,
                        item.status,
                        item.entityId,
                        item.errorCode ?: "RETRYABLE_PROCESSING_ERROR",
                        item.explanation ?: "The server has not finished processing this event."
                    )
                    return SyncOutcome.RETRY
                }
                "CONFLICT", "REJECTED" -> {
                    database.withTransaction {
                        database.syncEventDao().recordResult(
                            event.eventId,
                            item.status,
                            event.attemptCount + 1,
                            attemptedAt,
                            item.status,
                            item.entityId,
                            item.errorCode ?: item.status,
                            item.explanation ?: "The server did not accept this event."
                        )
                        if (item.status == "CONFLICT") {
                            persistEventConflict(event, item.errorCode ?: "SERVER_CONFLICT", item.explanation.orEmpty())
                        }
                        event.localEntityId?.let {
                            refreshEntitySyncState(it)
                        }
                    }
                }
                else -> {
                    database.syncEventDao().recordResult(
                        event.eventId,
                        "RETRY",
                        event.attemptCount + 1,
                        attemptedAt,
                        item.status,
                        item.entityId,
                        "UNKNOWN_SERVER_STATUS",
                        "The server returned an unsupported event status."
                    )
                    return SyncOutcome.RETRY
                }
            }
            }
        }
        return SyncOutcome.SUCCESS
    }

    private suspend fun downloadChanges(serverDeviceId: String, user: BackendUser) {
        var pagesProcessed = 0
        while (pagesProcessed < MAX_PAGES_PER_RUN) {
            val page = apiCall { api.getChanges(it, serverDeviceId, PAGE_LIMIT) }
            requireCursor(page.checkpoint)
            requireCursor(page.nextCursor)
            val checkpointDao = database.syncCheckpointDao()
            var localCheckpoint = requireNotNull(checkpointDao.get()) { "Local sync checkpoint is missing." }
            if (page.checkpoint != localCheckpoint.lastCursor) {
                if (page.checkpoint == localCheckpoint.pendingCursor) {
                    localCheckpoint = localCheckpoint.copy(lastCursor = page.checkpoint, pendingCursor = null)
                    checkpointDao.upsert(localCheckpoint)
                } else {
                    throw BackendProtocolException("Local and server checkpoints differ; refusing to skip downloaded changes.")
                }
            }
            if (page.changes.isEmpty() && page.nextCursor == page.checkpoint) {
                if (page.hasMore) throw BackendProtocolException("The server returned an empty page with more changes.")
                return
            }
            validatePage(page)
            database.withTransaction {
                page.changes.forEach { change -> applyServerChange(change, user) }
                checkpointDao.upsert(
                    localCheckpoint.copy(pendingCursor = page.nextCursor, updatedAt = LocalEventFactory.now())
                )
            }
            if (page.nextCursor != page.checkpoint) {
                apiCall { api.acknowledgeCheckpoint(it, serverDeviceId, page.nextCursor) }
                database.withTransaction {
                    val current = requireNotNull(checkpointDao.get()) { "Local sync checkpoint is missing." }
                    if (current.pendingCursor != page.nextCursor) {
                        throw BackendProtocolException("The local pending checkpoint changed before acknowledgement.")
                    }
                    checkpointDao.upsert(
                        current.copy(lastCursor = page.nextCursor, pendingCursor = null, updatedAt = LocalEventFactory.now())
                    )
                }
            }
            pagesProcessed++
            if (!page.hasMore) return
        }
        throw IOException("More server changes remain; another background sync will continue.")
    }

    private suspend fun applyServerChange(change: ServerChange, user: BackendUser) {
        val cursorValue = requireCursor(change.cursor)
        val serverRequest = change.request
        val requestId = serverRequest.id
        if (!runCatching { UUID.fromString(requestId) }.isSuccess) {
            throw BackendProtocolException("The server returned an invalid request ID.")
        }
        val requests = database.emergencyRequestDao()
        val existing = requests.getByServerId(requestId)
        val localId = existing?.localId ?: "SYNC-$requestId"
        val preserveLocalEdits = database.syncEventDao().hasUnresolvedForEntity(localId)
        val lastCursor = existing?.lastServerCursor?.let(::BigInteger) ?: BigInteger.ZERO
        if (cursorValue > lastCursor) {
            val status = mapRequestStatus(serverRequest.status)
            val bloodGroup = mapBloodGroup(serverRequest.requestedBloodGroup)
            val urgency = serverRequest.urgency.takeIf { it == "CRITICAL" || it == "URGENT" }
                ?: throw BackendProtocolException("The server returned an unsupported urgency.")
            val facility = serverRequest.facilityName
                .ifBlank { listOf(serverRequest.facilityName, serverRequest.facilityLocality, serverRequest.facilityState)
                .filter(String::isNotBlank)
                .distinct()
                .joinToString(", ")
                .ifBlank { "Health facility" } }
            val current = existing
            val canSeePrivateDetails = serverRequest.isMine
            val publishedToDonors = status == "OPEN" || status == "PARTIALLY_FILLED"
            val requestKind = when {
                serverRequest.isMine -> "OUTGOING"
                publishedToDonors -> "INCOMING"
                else -> "HIDDEN"
            }
            val updated = (current ?: EmergencyRequestEntity(
                localId = localId,
                serverId = requestId,
                kind = requestKind,
                bloodGroup = bloodGroup,
                urgency = urgency,
                units = serverRequest.units,
                facility = facility,
                createdAt = serverRequest.createdAt,
                updatedAt = serverRequest.updatedAt,
                status = status,
                receivedAt = displayTime(change.occurredAt),
                expiresIn = expiryText(serverRequest.expiresAt),
                syncStatus = "SYNCED"
            )).copy(
                serverId = requestId,
                kind = requestKind,
                bloodGroup = if (preserveLocalEdits) current?.bloodGroup ?: bloodGroup else bloodGroup,
                urgency = if (preserveLocalEdits) current?.urgency ?: urgency else urgency,
                units = if (preserveLocalEdits) current?.units ?: serverRequest.units else serverRequest.units,
                facility = facility,
                facilityLocation = serverRequest.facilityLocation.ifBlank { serverRequest.facilityLocality },
                facilityLatitude = serverRequest.facilityLatitude,
                facilityLongitude = serverRequest.facilityLongitude,
                facilityId = serverRequest.facilityId.ifBlank { current?.facilityId.orEmpty() }.takeIf { it.isNotBlank() },
                isForSelf = if (!canSeePrivateDetails) true else if (preserveLocalEdits) {
                    current?.isForSelf ?: serverRequest.isForSelf ?: true
                } else {
                    serverRequest.isForSelf ?: current?.isForSelf ?: true
                },
                beneficiaryName = if (!canSeePrivateDetails) null else if (preserveLocalEdits) {
                    current?.beneficiaryName ?: serverRequest.beneficiaryName
                } else {
                    serverRequest.beneficiaryName
                },
                beneficiaryRelationship = if (!canSeePrivateDetails) null else if (preserveLocalEdits) {
                    current?.beneficiaryRelationship ?: serverRequest.beneficiaryRelationship
                } else {
                    serverRequest.beneficiaryRelationship
                },
                additionalInfo = if (!canSeePrivateDetails) null else if (preserveLocalEdits) {
                    current?.additionalInfo ?: serverRequest.additionalInfo
                } else {
                    serverRequest.additionalInfo
                },
                note = if (!canSeePrivateDetails) "" else if (preserveLocalEdits) {
                    current?.note ?: serverRequest.additionalInfo.orEmpty()
                } else {
                    serverRequest.additionalInfo.orEmpty()
                },
                reviewReason = if (canSeePrivateDetails) serverRequest.reviewReason else null,
                reviewedAt = serverRequest.reviewedAt,
                createdAt = current?.createdAt ?: serverRequest.createdAt,
                updatedAt = if (preserveLocalEdits) current?.updatedAt ?: serverRequest.updatedAt else serverRequest.updatedAt,
                status = status,
                receivedAt = if (current?.receivedAt.isNullOrBlank()) displayTime(change.occurredAt) else current!!.receivedAt,
                expiresIn = expiryText(serverRequest.expiresAt),
                lastServerCursor = change.cursor,
                syncStatus = if (preserveLocalEdits) current?.syncStatus ?: "PENDING" else "SYNCED"
            )
            requests.upsert(updated)
            if (requestKind == "OUTGOING" && publishedToDonors) {
                database.syncEventDao().setRequestPublicationStatus(localId, status)
            }
        }
        val activityId = "SERVER-CURSOR-${change.cursor}"
        database.requestActivityDao().insertIfAbsent(
            RequestActivityEntity(
                id = activityId,
                requestLocalId = localId,
                eventType = change.eventType,
                actorReference = null,
                occurredAt = change.occurredAt,
                detailsJson = JSONObject()
                    .put("cursor", change.cursor)
                    .put("fromStatus", change.fromStatus ?: JSONObject.NULL)
                    .put("toStatus", change.toStatus ?: JSONObject.NULL)
                    .toString()
            )
        )
    }

    private suspend fun refreshConflicts(serverDeviceId: String) {
        val conflicts = apiCall { api.getConflicts(it, serverDeviceId, CONFLICT_LIMIT) }
        conflicts.forEach { conflict ->
            val conflictId = stableConflictId(
                "SERVER_LIST",
                conflict.eventId.orEmpty(),
                conflict.errorCode,
                conflict.occurredAt
            )
            database.syncConflictDao().insertIfAbsent(
                SyncConflictEntity(
                    conflictId = conflictId,
                    eventId = conflict.eventId,
                    eventType = conflict.eventType,
                    entityId = conflict.entityId,
                    errorCode = conflict.errorCode,
                    explanation = conflict.explanation,
                    payloadJson = conflict.payloadJson,
                    occurredAt = conflict.occurredAt.ifBlank { LocalEventFactory.now() },
                    source = "SERVER_LIST"
                )
            )
        }
    }

    private suspend fun persistEventConflict(event: SyncEventEntity, errorCode: String, explanation: String) {
        val now = LocalEventFactory.now()
        database.syncConflictDao().insertIfAbsent(
            SyncConflictEntity(
                conflictId = stableConflictId("UPLOAD", event.eventId, errorCode, explanation),
                eventId = event.eventId,
                eventType = event.eventType,
                entityId = event.entityId,
                errorCode = errorCode,
                explanation = explanation,
                payloadJson = JSONObject()
                    .put("eventType", event.eventType)
                    .put("entityId", event.entityId ?: JSONObject.NULL)
                    .put("payload", runCatching { JSONObject(event.payloadJson) }.getOrNull() ?: JSONObject())
                    .toString(),
                occurredAt = now,
                source = "UPLOAD"
            )
        )
    }

    private suspend fun refreshEntitySyncState(localEntityId: String) {
        val events = database.syncEventDao()
        val state = when {
            events.hasFailureForEntity(localEntityId) -> "CONFLICT"
            events.hasPendingForEntity(localEntityId) -> "PENDING"
            else -> "SYNCED"
        }
        database.emergencyRequestDao().setSyncStatus(localEntityId, state)
        database.donorResponseDao().setSyncStatusForRequest(localEntityId, state)
        database.donationConfirmationDao().setSyncStatusForRequest(localEntityId, state)
    }

    private suspend fun <T> apiCall(call: suspend (String) -> T): T = auth.withAccessToken(call)

    private suspend fun markDeviceRevoked(device: DeviceInfoEntity) {
        database.deviceInfoDao().upsert(
            device.copy(registrationStatus = "REVOKED", revokedAt = LocalEventFactory.now())
        )
    }

    private suspend fun updateState(status: String, error: String?) {
        val previous = database.syncStateDao().get() ?: SyncStateEntity()
        database.syncStateDao().upsert(
            previous.copy(status = status, lastAttemptAt = LocalEventFactory.now(), lastError = error)
        )
    }

    private suspend fun finish(outcome: SyncOutcome, message: String?): SyncOutcome {
        val status = when (outcome) {
            SyncOutcome.SUCCESS -> "SUCCESS"
            SyncOutcome.ALREADY_RUNNING -> "RUNNING"
            SyncOutcome.NEEDS_SIGN_IN -> "NEEDS_SIGN_IN"
            SyncOutcome.ACCOUNT_LINK_REQUIRED -> "ACCOUNT_LINK_REQUIRED"
            SyncOutcome.ACCOUNT_MISMATCH -> "ACCOUNT_MISMATCH"
            SyncOutcome.PROFILE_INCOMPLETE -> "PROFILE_INCOMPLETE"
            SyncOutcome.DEVICE_REVOKED -> "DEVICE_REVOKED"
            SyncOutcome.RETRY -> "RETRYING"
            SyncOutcome.FAILED -> "FAILED"
        }
        updateState(status, message)
        return outcome
    }

    private fun validatePage(page: ServerChangesPage) {
        if (page.changes.size > PAGE_LIMIT) throw BackendProtocolException("The server returned more changes than the requested page limit.")
        var previous = BigInteger(page.checkpoint)
        for (change in page.changes) {
            val current = requireCursor(change.cursor)
            if (current <= previous || current > BigInteger(page.nextCursor)) {
                throw BackendProtocolException("The server returned changes outside the requested cursor range.")
            }
            previous = current
        }
        if (BigInteger(page.nextCursor) < BigInteger(page.checkpoint)) {
            throw BackendProtocolException("The server returned a checkpoint that moved backwards.")
        }
    }

    private fun requireCursor(value: String): BigInteger {
        if (!value.matches(Regex("^\\d{1,20}$"))) throw BackendProtocolException("The server returned an invalid cursor.")
        return BigInteger(value)
    }

    private suspend fun syncDonorProfile(
        profile: com.sistermesh.app.data.local.ProfileEntity,
        user: BackendUser
    ) {
        val bloodGroup = profile.bloodGroup.takeIf(String::isNotBlank)
        if (bloodGroup != null && user.donorBloodGroup.isNullOrBlank()) {
            auth.createDonorProfile(bloodGroup.toApiBloodGroup(), profile.area)
        } else if (bloodGroup != null && !user.donorBloodGroup.isNullOrBlank()) {
            auth.updateDonorProfile(
                bloodGroup = bloodGroup.toApiBloodGroup(),
                locality = profile.area,
                isAvailable = profile.isAvailable
            )
        }
    }

    private suspend fun refreshDonationActivity() {
        val serverResponses = auth.getMyDonations()
        val requestDao = database.emergencyRequestDao()
        val responseDao = database.donorResponseDao()
        serverResponses.sortedBy { it.createdAt }.forEach { response ->
            val snapshot = response.request
            val existing = requestDao.getByServerId(response.requestId)
            val localId = existing?.localId ?: "SYNC-${response.requestId}"
            val group = mapBloodGroup(snapshot.requestedBloodGroup)
            val urgency = snapshot.urgency.takeIf { it == "CRITICAL" || it == "URGENT" }
                ?: throw BackendProtocolException("The server returned an unsupported urgency.")
            val facility = snapshot.facilityName.ifBlank { "Health facility" }
            val decision = when (response.status) {
                "ACCEPTED", "CONFIRMED" -> "ACCEPTED"
                "DECLINED" -> "DECLINED"
                "WITHDRAWN" -> "WITHDRAWN"
                else -> "NONE"
            }
            val status = mapRequestStatus(snapshot.status)
            val entity = (existing ?: EmergencyRequestEntity(
                localId = localId,
                kind = "INCOMING",
                bloodGroup = group,
                urgency = urgency,
                units = snapshot.units,
                facility = facility,
                facilityLocation = snapshot.facilityLocation.ifBlank { snapshot.facilityLocality },
                facilityLatitude = snapshot.facilityLatitude,
                facilityLongitude = snapshot.facilityLongitude,
                createdAt = snapshot.createdAt,
                status = status,
                receivedAt = displayTime(response.respondedAt ?: response.createdAt),
                syncStatus = "SYNCED"
            )).copy(
                serverId = response.requestId,
                kind = if (existing?.kind == "OUTGOING") "OUTGOING" else "INCOMING",
                bloodGroup = group,
                urgency = urgency,
                units = snapshot.units,
                facility = facility,
                facilityId = snapshot.facilityId.takeIf(String::isNotBlank),
                updatedAt = snapshot.updatedAt,
                status = status,
                decision = decision,
                confirmed = response.status == "CONFIRMED",
                receivedAt = existing?.receivedAt?.takeIf(String::isNotBlank)
                    ?: displayTime(response.respondedAt ?: response.createdAt),
                syncStatus = "SYNCED"
            )
            requestDao.upsert(entity)
            responseDao.upsert(
                com.sistermesh.app.data.local.DonorResponseEntity(
                    id = "SERVER-${response.id}",
                    requestLocalId = localId,
                    serverResponseId = response.id,
                    donorProfileId = response.donorProfileId,
                    ref = "You",
                    area = "",
                    distance = "",
                    status = when (response.status) {
                        "ACCEPTED" -> "ACCEPTED"
                        "DECLINED" -> "DECLINED"
                        "WITHDRAWN" -> "WITHDRAWN"
                        "CONFIRMED" -> "CONFIRMED"
                        else -> "ACCEPTED"
                    },
                    createdAt = response.respondedAt ?: response.createdAt,
                    syncStatus = "SYNCED"
                )
            )
        }
    }

    private fun String.toApiBloodGroup(): String = when (uppercase()) {
        "A+" -> "A_POSITIVE"
        "A-" -> "A_NEGATIVE"
        "B+" -> "B_POSITIVE"
        "B-" -> "B_NEGATIVE"
        "AB+" -> "AB_POSITIVE"
        "AB-" -> "AB_NEGATIVE"
        "O+" -> "O_POSITIVE"
        "O-" -> "O_NEGATIVE"
        else -> throw IllegalArgumentException("Choose a valid blood group before syncing donor settings.")
    }

    private fun mapRequestStatus(status: String): String = when (status) {
        "OPEN", "PARTIALLY_FILLED", "FULFILLED", "CANCELLED", "EXPIRED",
        "PENDING_FACILITY_VERIFICATION", "NEEDS_INFORMATION", "REJECTED" -> status
        "SEARCHING" -> "SEARCHING"
        "WAITING" -> "WAITING"
        "ACCEPTED", "LEGACY_ACCEPTED" -> "ACCEPTED"
        "COMPLETED", "LEGACY_COMPLETED" -> "COMPLETED"
        else -> throw BackendProtocolException("The server returned an unsupported request status.")
    }

    private fun mapBloodGroup(group: String): String = when (group) {
        "A_POSITIVE" -> "A+"
        "A_NEGATIVE" -> "A-"
        "B_POSITIVE" -> "B+"
        "B_NEGATIVE" -> "B-"
        "AB_POSITIVE" -> "AB+"
        "AB_NEGATIVE" -> "AB-"
        "O_POSITIVE" -> "O+"
        "O_NEGATIVE" -> "O-"
        else -> throw BackendProtocolException("The server returned an unsupported blood group.")
    }

    private fun displayTime(value: String): String = runCatching {
        Instant.parse(value).toString()
    }.getOrElse { value }

    private fun expiryText(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val minutes = runCatching { Duration.between(Instant.now(), Instant.parse(value)).toMinutes() }.getOrDefault(0L)
        if (minutes <= 0) return "Expired"
        return if (minutes < 60) "$minutes min" else "${minutes / 60} hr"
    }

    private fun stableBatchId(eventId: String): String =
        UUID.nameUUIDFromBytes("SisterMeshSyncBatch/v1:$eventId".toByteArray(StandardCharsets.UTF_8)).toString()

    private fun stableConflictId(source: String, eventId: String, code: String, detail: String): String =
        UUID.nameUUIDFromBytes("SisterMeshConflict/v1:$source:$eventId:$code:$detail".toByteArray(StandardCharsets.UTF_8)).toString()

    private fun String.toBackendRole(): String = when (this) {
        "INDIVIDUAL" -> "INDIVIDUAL"
        "CHW" -> "INDIVIDUAL"
        else -> throw IllegalArgumentException("Unsupported local profile role.")
    }

    companion object {
        private const val PAGE_LIMIT = 50
        private const val CONFLICT_LIMIT = 100
        private const val MAX_PAGES_PER_RUN = 50
    }
}
