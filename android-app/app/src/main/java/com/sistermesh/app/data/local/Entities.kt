package com.sistermesh.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: Int = 1,
    val role: String,
    val name: String,
    val bloodGroup: String,
    val area: String,
    val phone: String,
    val facility: String,
    val facilityId: String? = null,
    val facilityLocation: String,
    val designation: String,
    val isAvailable: Boolean = true,
    val language: String = "English"
)

@Entity(
    tableName = "emergency_requests",
    indices = [Index(value = ["kind", "status"]), Index(value = ["serverId"])]
)
data class EmergencyRequestEntity(
    @PrimaryKey val localId: String,
    val serverId: String? = null,
    val kind: String,
    val bloodGroup: String,
    val urgency: String,
    val units: Int,
    val note: String = "",
    val facility: String = "",
    val facilityId: String? = null,
    val isForSelf: Boolean = true,
    val beneficiaryName: String? = null,
    val beneficiaryRelationship: String? = null,
    val additionalInfo: String? = null,
    val reviewReason: String? = null,
    val reviewedAt: String? = null,
    val createdAt: String,
    val updatedAt: String? = null,
    val status: String,
    val devicesFound: Int = 0,
    val delivered: Int = 0,
    val distance: String = "",
    val receivedAt: String = "",
    val expiresIn: String = "",
    val decision: String = "NONE",
    val confirmed: Boolean = false,
    val isDemo: Boolean = false,
    val syncStatus: String = "LOCAL",
    val lastServerCursor: String? = null
)

@Entity(
    tableName = "facilities",
    indices = [Index(value = ["state", "locality"]), Index(value = ["name"])]
)
data class FacilityEntity(
    @PrimaryKey val id: String,
    val name: String,
    val locality: String,
    val state: String
)

@Entity(
    tableName = "donor_responses",
    foreignKeys = [
        ForeignKey(
            entity = EmergencyRequestEntity::class,
            parentColumns = ["localId"],
            childColumns = ["requestLocalId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["requestLocalId"]), Index(value = ["serverResponseId"])]
)
data class DonorResponseEntity(
    @PrimaryKey val id: String,
    val requestLocalId: String,
    val serverResponseId: String? = null,
    val donorProfileId: String? = null,
    val ref: String,
    val area: String,
    val distance: String,
    val status: String,
    val createdAt: String,
    val syncStatus: String = "LOCAL"
)

@Entity(
    tableName = "donation_confirmations",
    foreignKeys = [
        ForeignKey(
            entity = EmergencyRequestEntity::class,
            parentColumns = ["localId"],
            childColumns = ["requestLocalId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["requestLocalId"]), Index(value = ["serverConfirmationId"])]
)
data class DonationConfirmationEntity(
    @PrimaryKey val id: String,
    val requestLocalId: String,
    val serverConfirmationId: String? = null,
    val donorProfileId: String? = null,
    val donorRef: String,
    val confirmedBy: String,
    val confirmedAt: String,
    val status: String,
    val receiptHash: String,
    val isDemo: Boolean = false,
    val syncStatus: String = "LOCAL"
)

@Entity(
    tableName = "request_activity",
    foreignKeys = [
        ForeignKey(
            entity = EmergencyRequestEntity::class,
            parentColumns = ["localId"],
            childColumns = ["requestLocalId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["requestLocalId", "occurredAt"])]
)
data class RequestActivityEntity(
    @PrimaryKey val id: String,
    val requestLocalId: String,
    val eventType: String,
    val actorReference: String?,
    val occurredAt: String,
    val detailsJson: String = "{}"
)

@Entity(
    tableName = "sync_events",
    indices = [
        Index(value = ["status", "localCreatedAt"]),
        Index(value = ["localEntityId"]),
        Index(value = ["dependsOnEventId"])
    ]
)
data class SyncEventEntity(
    @PrimaryKey val eventId: String,
    val eventType: String,
    val localEntityId: String?,
    val entityId: String?,
    val originDeviceId: String,
    val localCreatedAt: String,
    val payloadSchemaVersion: Int = 1,
    val correlationId: String? = null,
    val payloadJson: String,
    val status: String = "PENDING",
    val dependsOnEventId: String? = null,
    val ownerAccountId: String? = null,
    val attemptCount: Int = 0,
    val lastAttemptedAt: String? = null,
    val serverStatus: String? = null,
    val serverEntityId: String? = null,
    val serverErrorCode: String? = null,
    val serverExplanation: String? = null
)

@Entity(tableName = "device_info")
data class DeviceInfoEntity(
    @PrimaryKey val id: Int = 1,
    val clientDeviceId: UUID,
    val serverDeviceId: String? = null,
    val name: String,
    val registrationStatus: String = "LOCAL_ONLY",
    val registeredAt: String? = null,
    val lastSyncAt: String? = null,
    val revokedAt: String? = null,
    val accountUserId: String? = null
)

@Entity(tableName = "sync_checkpoints")
data class SyncCheckpointEntity(
    @PrimaryKey val id: Int = 1,
    val lastCursor: String = "0",
    val pendingCursor: String? = null,
    val updatedAt: String
)

@Entity(
    tableName = "sync_conflicts",
    indices = [Index(value = ["eventId"]), Index(value = ["occurredAt"])]
)
data class SyncConflictEntity(
    @PrimaryKey val conflictId: String,
    val eventId: String?,
    val eventType: String?,
    val entityId: String?,
    val errorCode: String,
    val explanation: String,
    val payloadJson: String,
    val occurredAt: String,
    val source: String
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 1,
    val status: String = "IDLE",
    val lastAttemptAt: String? = null,
    val lastError: String? = null
)

@Entity(tableName = "app_metadata")
data class AppMetadataEntity(
    @PrimaryKey val id: Int = 1,
    val demoDataSeeded: Boolean = false
)
