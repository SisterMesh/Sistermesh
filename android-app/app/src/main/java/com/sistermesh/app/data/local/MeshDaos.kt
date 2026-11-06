package com.sistermesh.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrustedMeshPeerDao {
    @Query("SELECT * FROM trusted_mesh_peers ORDER BY lastSeenAt DESC")
    fun observeAll(): Flow<List<TrustedMeshPeerEntity>>

    @Query("SELECT * FROM trusted_mesh_peers WHERE deviceId = :deviceId LIMIT 1")
    suspend fun get(deviceId: String): TrustedMeshPeerEntity?

    @Upsert
    suspend fun upsert(peer: TrustedMeshPeerEntity)

    @Query("DELETE FROM trusted_mesh_peers WHERE deviceId = :deviceId")
    suspend fun remove(deviceId: String)
}

@Dao
interface MeshMessageDao {
    @Query("SELECT * FROM mesh_messages ORDER BY originCreatedAt DESC")
    fun observeAll(): Flow<List<MeshMessageEntity>>

    @Query("SELECT * FROM mesh_messages WHERE messageId = :messageId LIMIT 1")
    suspend fun get(messageId: String): MeshMessageEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(message: MeshMessageEntity): Long

    @Upsert
    suspend fun upsert(message: MeshMessageEntity)

    @Query("SELECT * FROM mesh_messages WHERE deliveryState NOT IN ('DELIVERED', 'EXPIRED', 'REJECTED', 'FAILED') AND expiresAt > :now ORDER BY originCreatedAt, messageId")
    suspend fun pending(now: String): List<MeshMessageEntity>

    @Query("SELECT COUNT(*) FROM mesh_messages WHERE messageId = :messageId")
    suspend fun count(messageId: String): Int

    @Query("UPDATE mesh_messages SET deliveryState = :state, lastError = :error WHERE messageId = :messageId")
    suspend fun setState(messageId: String, state: String, error: String?)

    @Query("UPDATE mesh_messages SET hopCount = :hopCount, previousHopDeviceId = :previousHopDeviceId, deliveryState = :state WHERE messageId = :messageId")
    suspend fun markReceived(
        messageId: String,
        hopCount: Int,
        previousHopDeviceId: String,
        state: String
    )

    @Query("UPDATE mesh_messages SET deliveryState = 'EXPIRED' WHERE expiresAt <= :now AND deliveryState NOT IN ('DELIVERED', 'EXPIRED', 'REJECTED')")
    suspend fun expire(now: String)

    @Query("DELETE FROM mesh_messages WHERE expiresAt <= :retentionCutoff AND deliveryState IN ('DELIVERED', 'EXPIRED', 'REJECTED')")
    suspend fun deleteExpired(retentionCutoff: String)
}

@Dao
interface MeshForwardingHistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(record: MeshForwardingHistoryEntity): Long

    @Upsert
    suspend fun upsert(record: MeshForwardingHistoryEntity)

    @Query("SELECT * FROM mesh_forwarding_history WHERE messageId = :messageId")
    suspend fun forMessage(messageId: String): List<MeshForwardingHistoryEntity>

    @Query("SELECT * FROM mesh_forwarding_history WHERE messageId = :messageId AND peerDeviceId = :peerDeviceId LIMIT 1")
    suspend fun get(messageId: String, peerDeviceId: String): MeshForwardingHistoryEntity?

    @Query("UPDATE mesh_forwarding_history SET status = 'FAILED', error = 'Interrupted before send confirmation' WHERE status = 'SENDING'")
    suspend fun recoverInterruptedSends()
}

@Dao
interface MeshAcknowledgementDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(ack: MeshAcknowledgementEntity): Long

    @Upsert
    suspend fun upsert(ack: MeshAcknowledgementEntity)

    @Query("SELECT * FROM mesh_acknowledgements WHERE acknowledgementId = :acknowledgementId LIMIT 1")
    suspend fun get(acknowledgementId: String): MeshAcknowledgementEntity?

    @Query("SELECT * FROM mesh_acknowledgements WHERE deliveryState IN ('QUEUED', 'RETRY') AND expiresAt > :now ORDER BY createdAt")
    suspend fun pending(now: String): List<MeshAcknowledgementEntity>

    @Query("UPDATE mesh_acknowledgements SET deliveryState = :state WHERE acknowledgementId = :acknowledgementId")
    suspend fun setState(acknowledgementId: String, state: String)

    @Query("UPDATE mesh_acknowledgements SET deliveryState = 'RETRY' WHERE deliveryState = 'SENDING'")
    suspend fun recoverInterruptedSends()
}

@Dao
interface MeshRequestRouteDao {
    @Query("SELECT * FROM mesh_request_routes WHERE requestLocalId = :requestLocalId LIMIT 1")
    suspend fun byLocalRequest(requestLocalId: String): MeshRequestRouteEntity?

    @Query("SELECT * FROM mesh_request_routes WHERE originDeviceId = :originDeviceId AND originRequestLocalId = :originRequestLocalId LIMIT 1")
    suspend fun byOriginRequest(originDeviceId: String, originRequestLocalId: String): MeshRequestRouteEntity?

    @Upsert
    suspend fun upsert(route: MeshRequestRouteEntity)

    @Query("DELETE FROM mesh_request_routes WHERE expiresAt <= :now")
    suspend fun deleteExpired(now: String)
}
