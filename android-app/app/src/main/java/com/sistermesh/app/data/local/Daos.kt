package com.sistermesh.app.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class RequestWithResponses(
    @Embedded val request: EmergencyRequestEntity,
    @Relation(parentColumn = "localId", entityColumn = "requestLocalId")
    val responses: List<DonorResponseEntity>
)

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles WHERE id = 1")
    fun observe(): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles WHERE id = 1")
    suspend fun get(): ProfileEntity?

    @Upsert
    suspend fun upsert(profile: ProfileEntity)

    @Query("DELETE FROM profiles WHERE id = 1")
    suspend fun clear()

    @Query("UPDATE profiles SET isAvailable = :available WHERE id = 1")
    suspend fun setAvailability(available: Boolean)

    @Query("UPDATE profiles SET language = :language WHERE id = 1")
    suspend fun setLanguage(language: String)
}

@Dao
interface FacilityDao {
    @Query("SELECT * FROM facilities ORDER BY state, name")
    fun observe(): Flow<List<FacilityEntity>>

    @Query("SELECT * FROM facilities ORDER BY state, name")
    suspend fun getAll(): List<FacilityEntity>

    @Upsert
    suspend fun upsertAll(facilities: List<FacilityEntity>)

    @Query("DELETE FROM facilities")
    suspend fun clear()
}

@Dao
interface EmergencyRequestDao {
    @Transaction
    @Query("SELECT * FROM emergency_requests WHERE kind = 'OUTGOING' AND isDemo = 0 ORDER BY createdAt DESC")
    fun observeOutgoingWithResponses(): Flow<List<RequestWithResponses>>

    @Transaction
    @Query("""
        SELECT request.* FROM emergency_requests AS request
        WHERE request.kind = 'OUTGOING' AND request.isDemo = 0
          AND (
            (
              request.localId NOT LIKE 'SYNC-%'
              AND NOT EXISTS (
                SELECT 1 FROM sync_events AS event
                WHERE event.localEntityId = request.localId
                  AND event.ownerAccountId IS NOT NULL
                  AND (:accountId IS NULL OR event.ownerAccountId != :accountId)
              )
            )
            OR (
              :accountId IS NOT NULL
              AND request.localId LIKE 'SYNC-%'
              AND request.serverId IN (:ownedServerIds)
            )
          )
        ORDER BY request.createdAt DESC
    """)
    fun observeOutgoingWithResponsesForAccount(
        accountId: String?,
        ownedServerIds: List<String>
    ): Flow<List<RequestWithResponses>>

    @Query("SELECT * FROM emergency_requests WHERE kind = 'INCOMING' AND isDemo = 0 ORDER BY createdAt DESC")
    fun observeIncoming(): Flow<List<EmergencyRequestEntity>>

    @Query("""
        SELECT request.* FROM emergency_requests AS request
        WHERE request.kind = 'INCOMING' AND request.isDemo = 0
          AND NOT EXISTS (
            SELECT 1 FROM sync_events AS event
            WHERE event.localEntityId = request.localId
              AND event.ownerAccountId IS NOT NULL
              AND (:accountId IS NULL OR event.ownerAccountId != :accountId)
          )
        ORDER BY request.createdAt DESC
    """)
    fun observeIncomingForAccount(accountId: String?): Flow<List<EmergencyRequestEntity>>

    @Transaction
    @Query("SELECT * FROM emergency_requests WHERE kind = 'FACILITY' AND isDemo = 0 ORDER BY createdAt DESC")
    fun observeFacilityWithResponses(): Flow<List<RequestWithResponses>>

    @Transaction
    @Query("SELECT * FROM emergency_requests WHERE localId = :localId LIMIT 1")
    suspend fun getWithResponses(localId: String): RequestWithResponses?

    @Query("SELECT * FROM emergency_requests WHERE localId = :localId LIMIT 1")
    suspend fun get(localId: String): EmergencyRequestEntity?

    @Query("SELECT * FROM emergency_requests WHERE serverId = :serverId LIMIT 1")
    suspend fun getByServerId(serverId: String): EmergencyRequestEntity?

    @Query("UPDATE emergency_requests SET kind = 'HIDDEN' WHERE serverId = :serverId AND kind = 'INCOMING' AND localId != :keepLocalId")
    suspend fun hideOtherIncomingForServerId(serverId: String, keepLocalId: String)

    @Upsert
    suspend fun upsert(request: EmergencyRequestEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(request: EmergencyRequestEntity): Long

    @Query("DELETE FROM emergency_requests WHERE isDemo = 1")
    suspend fun deleteDemoRequests()

    @Query("SELECT COUNT(*) FROM emergency_requests")
    suspend fun count(): Int

    @Query("UPDATE emergency_requests SET serverId = :serverId, updatedAt = :updatedAt WHERE localId = :localId")
    suspend fun attachServerId(localId: String, serverId: String, updatedAt: String?)

    @Query("UPDATE emergency_requests SET syncStatus = :status WHERE localId = :localId")
    suspend fun setSyncStatus(localId: String, status: String)
}

@Dao
interface DonorResponseDao {
    @Query("SELECT * FROM donor_responses WHERE requestLocalId = :requestLocalId ORDER BY createdAt")
    suspend fun forRequest(requestLocalId: String): List<DonorResponseEntity>

    @Query("SELECT * FROM donor_responses WHERE requestLocalId = :requestLocalId AND ref = :ref LIMIT 1")
    suspend fun find(requestLocalId: String, ref: String): DonorResponseEntity?

    @Upsert
    suspend fun upsert(response: DonorResponseEntity)

    @Query("UPDATE donor_responses SET syncStatus = :status WHERE requestLocalId = :requestLocalId")
    suspend fun setSyncStatusForRequest(requestLocalId: String, status: String)

    @Query("DELETE FROM donor_responses WHERE requestLocalId = :requestLocalId")
    suspend fun deleteForRequest(requestLocalId: String)
}

@Dao
interface DonationConfirmationDao {
    @Query("SELECT * FROM donation_confirmations WHERE isDemo = 0 ORDER BY confirmedAt DESC")
    fun observeAll(): Flow<List<DonationConfirmationEntity>>

    @Query("""
        SELECT confirmation.* FROM donation_confirmations AS confirmation
        WHERE confirmation.isDemo = 0
          AND NOT EXISTS (
            SELECT 1 FROM sync_events AS event
            WHERE event.localEntityId = confirmation.requestLocalId
              AND event.ownerAccountId IS NOT NULL
              AND (:accountId IS NULL OR event.ownerAccountId != :accountId)
          )
        ORDER BY confirmation.confirmedAt DESC
    """)
    fun observeForAccount(accountId: String?): Flow<List<DonationConfirmationEntity>>

    @Upsert
    suspend fun upsert(confirmation: DonationConfirmationEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(confirmation: DonationConfirmationEntity): Long

    @Query("UPDATE donation_confirmations SET syncStatus = :status WHERE requestLocalId = :requestLocalId")
    suspend fun setSyncStatusForRequest(requestLocalId: String, status: String)
}

@Dao
interface RequestActivityDao {
    @Query("SELECT * FROM request_activity WHERE requestLocalId = :requestLocalId ORDER BY occurredAt, id")
    fun observeForRequest(requestLocalId: String): Flow<List<RequestActivityEntity>>

    @Query("SELECT * FROM request_activity WHERE requestLocalId = :requestLocalId ORDER BY occurredAt, id")
    suspend fun forRequest(requestLocalId: String): List<RequestActivityEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(activity: RequestActivityEntity): Long
}

@Dao
interface SyncEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(event: SyncEventEntity): Long

    @Query("SELECT * FROM sync_events WHERE eventId = :eventId LIMIT 1")
    suspend fun get(eventId: String): SyncEventEntity?

    @Query("""
        SELECT * FROM sync_events
        WHERE status IN ('PENDING', 'RETRY')
          AND (ownerAccountId IS NULL OR ownerAccountId = :accountId)
        ORDER BY localCreatedAt, eventId
    """)
    fun observePending(accountId: String?): Flow<List<SyncEventEntity>>

    @Query("SELECT * FROM sync_events WHERE status IN ('PENDING', 'RETRY', 'BLOCKED') ORDER BY localCreatedAt, eventId")
    suspend fun forSync(): List<SyncEventEntity>

    @Query("""
        SELECT COUNT(*) FROM sync_events
        WHERE status IN ('PENDING', 'RETRY')
          AND (ownerAccountId IS NULL OR ownerAccountId = :accountId)
    """)
    fun observePendingCount(accountId: String?): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_events WHERE status IN ('PENDING', 'RETRY')")
    suspend fun pendingCountNow(): Int

    @Query("""
        SELECT COUNT(*) FROM sync_events
        WHERE status IN ('REJECTED', 'CONFLICT', 'BLOCKED')
          AND (ownerAccountId IS NULL OR ownerAccountId = :accountId)
    """)
    fun observeFailedCount(accountId: String?): Flow<Int>

    @Query("SELECT * FROM sync_events ORDER BY localCreatedAt, eventId")
    fun observeAll(): Flow<List<SyncEventEntity>>

    @Query("""
        SELECT * FROM sync_events
        WHERE status IN ('REJECTED', 'CONFLICT', 'BLOCKED')
          AND (ownerAccountId IS NULL OR ownerAccountId = :accountId)
        ORDER BY localCreatedAt DESC
    """)
    fun observeNeedsReview(accountId: String?): Flow<List<SyncEventEntity>>

    @Query("SELECT * FROM sync_events WHERE localEntityId = :localEntityId ORDER BY localCreatedAt DESC LIMIT 1")
    suspend fun latestForEntity(localEntityId: String): SyncEventEntity?

    @Query("UPDATE sync_events SET serverStatus = :serverStatus WHERE localEntityId = :localEntityId AND eventType = 'request.create'")
    suspend fun setRequestPublicationStatus(localEntityId: String, serverStatus: String)

    @Query("SELECT EXISTS(SELECT 1 FROM sync_events WHERE localEntityId = :localEntityId AND status != 'PROCESSED')")
    suspend fun hasUnresolvedForEntity(localEntityId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM sync_events WHERE localEntityId = :localEntityId AND status IN ('REJECTED', 'CONFLICT'))")
    suspend fun hasFailureForEntity(localEntityId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM sync_events WHERE localEntityId = :localEntityId AND status IN ('PENDING', 'RETRY', 'BLOCKED'))")
    suspend fun hasPendingForEntity(localEntityId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM sync_events WHERE localEntityId = :localEntityId AND ownerAccountId IS NOT NULL AND ownerAccountId != :accountId)")
    suspend fun hasEventOwnedByAnotherAccount(localEntityId: String, accountId: String): Boolean

    @Query("UPDATE sync_events SET entityId = :entityId WHERE localEntityId = :localEntityId AND eventType != 'request.create' AND entityId IS NULL")
    suspend fun attachEntityId(localEntityId: String, entityId: String)

    @Query("UPDATE sync_events SET status = 'PENDING', serverErrorCode = NULL, serverExplanation = NULL WHERE localEntityId = :localEntityId AND status = 'BLOCKED' AND serverErrorCode = 'WAITING_FOR_SERVER_ENTITY_ID'")
    suspend fun unblockForEntity(localEntityId: String)

    @Query("UPDATE sync_events SET status = :status, attemptCount = :attemptCount, lastAttemptedAt = :lastAttemptedAt, serverStatus = :serverStatus, serverEntityId = :serverEntityId, serverErrorCode = :errorCode, serverExplanation = :explanation WHERE eventId = :eventId")
    suspend fun recordResult(
        eventId: String,
        status: String,
        attemptCount: Int,
        lastAttemptedAt: String?,
        serverStatus: String?,
        serverEntityId: String?,
        errorCode: String?,
        explanation: String?
    )

    @Query("UPDATE sync_events SET attemptCount = attemptCount + 1, lastAttemptedAt = :attemptedAt WHERE eventId = :eventId")
    suspend fun markAttempted(eventId: String, attemptedAt: String)

    @Query("UPDATE sync_events SET ownerAccountId = :accountId, status = CASE WHEN status = 'BLOCKED' AND serverErrorCode = 'ACCOUNT_LINK_REQUIRED' THEN 'PENDING' ELSE status END, serverErrorCode = CASE WHEN serverErrorCode = 'ACCOUNT_LINK_REQUIRED' THEN NULL ELSE serverErrorCode END, serverExplanation = CASE WHEN serverErrorCode = 'ACCOUNT_LINK_REQUIRED' THEN NULL ELSE serverExplanation END WHERE originDeviceId = :clientDeviceId AND ownerAccountId IS NULL AND status IN ('PENDING', 'RETRY', 'BLOCKED')")
    suspend fun bindUnownedLocalEvents(accountId: String, clientDeviceId: String): Int

    @Query("UPDATE sync_events SET status = 'BLOCKED', serverErrorCode = 'ACCOUNT_MISMATCH', serverExplanation = 'This local event belongs to a different signed-in account.' WHERE ownerAccountId IS NOT NULL AND ownerAccountId != :accountId AND originDeviceId = :clientDeviceId AND status IN ('PENDING', 'RETRY')")
    suspend fun blockOtherAccountEvents(accountId: String, clientDeviceId: String): Int

    @Query("UPDATE sync_events SET status = 'PENDING', serverErrorCode = NULL, serverExplanation = NULL WHERE ownerAccountId = :accountId AND originDeviceId = :clientDeviceId AND status = 'BLOCKED' AND serverErrorCode = 'ACCOUNT_MISMATCH'")
    suspend fun unblockForAccount(accountId: String, clientDeviceId: String): Int

    @Query("UPDATE sync_events SET status = 'BLOCKED', serverErrorCode = :errorCode, serverExplanation = :explanation WHERE eventId = :eventId")
    suspend fun blockEvent(eventId: String, errorCode: String, explanation: String)
}

@Dao
interface DeviceInfoDao {
    @Query("SELECT * FROM device_info WHERE id = 1")
    fun observe(): Flow<DeviceInfoEntity?>

    @Query("SELECT * FROM device_info WHERE id = 1")
    suspend fun get(): DeviceInfoEntity?

    @Upsert
    suspend fun upsert(device: DeviceInfoEntity)

    @Query("UPDATE device_info SET accountUserId = :accountUserId WHERE id = 1")
    suspend fun linkAccount(accountUserId: String?)
}

@Dao
interface SyncCheckpointDao {
    @Query("SELECT * FROM sync_checkpoints WHERE id = 1")
    suspend fun get(): SyncCheckpointEntity?

    @Upsert
    suspend fun upsert(checkpoint: SyncCheckpointEntity)
}

@Dao
interface SyncConflictDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(conflict: SyncConflictEntity): Long

    @Query("""
        SELECT sc.* FROM sync_conflicts AS sc
        LEFT JOIN sync_events AS event ON event.eventId = sc.eventId
        WHERE event.ownerAccountId IS NULL OR event.ownerAccountId = :accountId
        ORDER BY sc.occurredAt DESC, sc.conflictId DESC
    """)
    fun observeAll(accountId: String?): Flow<List<SyncConflictEntity>>

    @Query("""
        SELECT COUNT(*) FROM sync_conflicts AS sc
        LEFT JOIN sync_events AS event ON event.eventId = sc.eventId
        WHERE event.ownerAccountId IS NULL OR event.ownerAccountId = :accountId
    """)
    fun observeCount(accountId: String?): Flow<Int>

    @Query("SELECT * FROM sync_conflicts WHERE conflictId = :conflictId LIMIT 1")
    suspend fun get(conflictId: String): SyncConflictEntity?
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE id = 1")
    fun observe(): Flow<SyncStateEntity?>

    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun get(): SyncStateEntity?

    @Upsert
    suspend fun upsert(state: SyncStateEntity)
}

@Dao
interface AppMetadataDao {
    @Query("SELECT * FROM app_metadata WHERE id = 1")
    suspend fun get(): AppMetadataEntity?

    @Upsert
    suspend fun upsert(metadata: AppMetadataEntity)
}
