package com.sistermesh.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        ProfileEntity::class,
        FacilityEntity::class,
        EmergencyRequestEntity::class,
        DonorResponseEntity::class,
        DonationConfirmationEntity::class,
        RequestActivityEntity::class,
        SyncEventEntity::class,
        DeviceInfoEntity::class,
        SyncCheckpointEntity::class,
        AppMetadataEntity::class,
        TrustedMeshPeerEntity::class,
        MeshMessageEntity::class,
        MeshForwardingHistoryEntity::class,
        MeshAcknowledgementEntity::class,
        MeshRequestRouteEntity::class,
        SyncConflictEntity::class,
        SyncStateEntity::class
    ],
    version = 5,
    exportSchema = true
)
@TypeConverters(RoomTypeConverters::class)
abstract class SisterMeshDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun facilityDao(): FacilityDao
    abstract fun emergencyRequestDao(): EmergencyRequestDao
    abstract fun donorResponseDao(): DonorResponseDao
    abstract fun donationConfirmationDao(): DonationConfirmationDao
    abstract fun requestActivityDao(): RequestActivityDao
    abstract fun syncEventDao(): SyncEventDao
    abstract fun deviceInfoDao(): DeviceInfoDao
    abstract fun syncCheckpointDao(): SyncCheckpointDao
    abstract fun syncConflictDao(): SyncConflictDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun appMetadataDao(): AppMetadataDao
    abstract fun trustedMeshPeerDao(): TrustedMeshPeerDao
    abstract fun meshMessageDao(): MeshMessageDao
    abstract fun meshForwardingHistoryDao(): MeshForwardingHistoryDao
    abstract fun meshAcknowledgementDao(): MeshAcknowledgementDao
    abstract fun meshRequestRouteDao(): MeshRequestRouteDao
}
