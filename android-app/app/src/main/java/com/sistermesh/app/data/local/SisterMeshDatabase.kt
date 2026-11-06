package com.sistermesh.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        ProfileEntity::class,
        EmergencyRequestEntity::class,
        DonorResponseEntity::class,
        DonationConfirmationEntity::class,
        RequestActivityEntity::class,
        SyncEventEntity::class,
        DeviceInfoEntity::class,
        SyncCheckpointEntity::class,
        AppMetadataEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(RoomTypeConverters::class)
abstract class SisterMeshDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun emergencyRequestDao(): EmergencyRequestDao
    abstract fun donorResponseDao(): DonorResponseDao
    abstract fun donationConfirmationDao(): DonationConfirmationDao
    abstract fun requestActivityDao(): RequestActivityDao
    abstract fun syncEventDao(): SyncEventDao
    abstract fun deviceInfoDao(): DeviceInfoDao
    abstract fun syncCheckpointDao(): SyncCheckpointDao
    abstract fun appMetadataDao(): AppMetadataDao
}
