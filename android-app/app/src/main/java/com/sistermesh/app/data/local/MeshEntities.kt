package com.sistermesh.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "trusted_mesh_peers",
    indices = [Index(value = ["trustLevel"]), Index(value = ["lastSeenAt"])]
)
data class TrustedMeshPeerEntity(
    @PrimaryKey val deviceId: String,
    val publicKeyBase64: String,
    val trustLevel: String,
    val trustedViaDeviceId: String? = null,
    val displayName: String = "SisterMesh device",
    val lastSeenAt: String,
    val lastConnectedAt: String? = null
)

@Entity(
    tableName = "mesh_messages",
    indices = [
        Index(value = ["deliveryState", "expiresAt"]),
        Index(value = ["originDeviceId"]),
        Index(value = ["targetDeviceId"])
    ]
)
data class MeshMessageEntity(
    @PrimaryKey val messageId: String,
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
    val maxHops: Int = 4,
    val previousHopDeviceId: String? = null,
    val receivedFromDeviceId: String? = null,
    val deliveryState: String = "CREATED",
    val lastError: String? = null
)

@Entity(
    tableName = "mesh_forwarding_history",
    primaryKeys = ["messageId", "peerDeviceId"],
    foreignKeys = [
        ForeignKey(
            entity = MeshMessageEntity::class,
            parentColumns = ["messageId"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["peerDeviceId"]),
        Index(value = ["messageId", "forwardedAt"])
    ]
)
data class MeshForwardingHistoryEntity(
    val messageId: String,
    val peerDeviceId: String,
    val hopCount: Int,
    val forwardedAt: String,
    val status: String,
    val error: String? = null
)

@Entity(
    tableName = "mesh_acknowledgements",
    foreignKeys = [
        ForeignKey(
            entity = MeshMessageEntity::class,
            parentColumns = ["messageId"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["messageId", "acknowledgingDeviceId", "ackStatus"], unique = true),
        Index(value = ["targetDeviceId", "deliveryState"])
    ]
)
data class MeshAcknowledgementEntity(
    @PrimaryKey val acknowledgementId: String,
    val messageId: String,
    val acknowledgingDeviceId: String,
    val acknowledgingPublicKey: String,
    val acknowledgingIdentitySignature: String,
    val targetDeviceId: String,
    val ackStatus: String,
    val signature: String,
    val hopCount: Int = 0,
    val maxHops: Int = 4,
    val nextHopDeviceId: String? = null,
    val receivedFromDeviceId: String? = null,
    val relayAttestorDeviceId: String? = null,
    val relayAttestationSignature: String? = null,
    val createdAt: String,
    val expiresAt: String,
    val deliveryState: String = "QUEUED"
)

@Entity(
    tableName = "mesh_request_routes",
    foreignKeys = [
        ForeignKey(
            entity = EmergencyRequestEntity::class,
            parentColumns = ["localId"],
            childColumns = ["requestLocalId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["originDeviceId", "originRequestLocalId"], unique = true),
        Index(value = ["expiresAt"])
    ]
)
data class MeshRequestRouteEntity(
    @PrimaryKey val requestLocalId: String,
    val originEventId: String,
    val originDeviceId: String,
    val originRequestLocalId: String,
    val previousHopDeviceId: String?,
    val expiresAt: String
)
