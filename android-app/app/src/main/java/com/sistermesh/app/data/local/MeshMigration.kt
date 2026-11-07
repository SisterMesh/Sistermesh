package com.sistermesh.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `trusted_mesh_peers` (
                `deviceId` TEXT NOT NULL,
                `publicKeyBase64` TEXT NOT NULL,
                `trustLevel` TEXT NOT NULL,
                `trustedViaDeviceId` TEXT,
                `displayName` TEXT NOT NULL,
                `lastSeenAt` TEXT NOT NULL,
                `lastConnectedAt` TEXT,
                PRIMARY KEY(`deviceId`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_trusted_mesh_peers_trustLevel` ON `trusted_mesh_peers` (`trustLevel`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_trusted_mesh_peers_lastSeenAt` ON `trusted_mesh_peers` (`lastSeenAt`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `mesh_messages` (
                `messageId` TEXT NOT NULL,
                `eventType` TEXT NOT NULL,
                `localEntityId` TEXT,
                `entityId` TEXT,
                `originDeviceId` TEXT NOT NULL,
                `originPublicKey` TEXT NOT NULL,
                `originIdentitySignature` TEXT NOT NULL,
                `originSignature` TEXT NOT NULL,
                `payloadSchemaVersion` INTEGER NOT NULL,
                `payloadJson` TEXT NOT NULL,
                `displayFacility` TEXT,
                `displayDonorRef` TEXT,
                `relatedRequestOriginId` TEXT,
                `targetDeviceId` TEXT,
                `originCreatedAt` TEXT NOT NULL,
                `expiresAt` TEXT NOT NULL,
                `hopCount` INTEGER NOT NULL,
                `maxHops` INTEGER NOT NULL,
                `previousHopDeviceId` TEXT,
                `receivedFromDeviceId` TEXT,
                `deliveryState` TEXT NOT NULL,
                `lastError` TEXT,
                PRIMARY KEY(`messageId`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_mesh_messages_deliveryState_expiresAt` ON `mesh_messages` (`deliveryState`, `expiresAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_mesh_messages_originDeviceId` ON `mesh_messages` (`originDeviceId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_mesh_messages_targetDeviceId` ON `mesh_messages` (`targetDeviceId`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `mesh_forwarding_history` (
                `messageId` TEXT NOT NULL,
                `peerDeviceId` TEXT NOT NULL,
                `hopCount` INTEGER NOT NULL,
                `forwardedAt` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `error` TEXT,
                PRIMARY KEY(`messageId`, `peerDeviceId`),
                FOREIGN KEY(`messageId`) REFERENCES `mesh_messages`(`messageId`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_mesh_forwarding_history_peerDeviceId` ON `mesh_forwarding_history` (`peerDeviceId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_mesh_forwarding_history_messageId_forwardedAt` ON `mesh_forwarding_history` (`messageId`, `forwardedAt`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `mesh_acknowledgements` (
                `acknowledgementId` TEXT NOT NULL,
                `messageId` TEXT NOT NULL,
                `acknowledgingDeviceId` TEXT NOT NULL,
                `acknowledgingPublicKey` TEXT NOT NULL,
                `acknowledgingIdentitySignature` TEXT NOT NULL,
                `targetDeviceId` TEXT NOT NULL,
                `ackStatus` TEXT NOT NULL,
                `signature` TEXT NOT NULL,
                `hopCount` INTEGER NOT NULL,
                `maxHops` INTEGER NOT NULL,
                `nextHopDeviceId` TEXT,
                `receivedFromDeviceId` TEXT,
                `relayAttestorDeviceId` TEXT,
                `relayAttestationSignature` TEXT,
                `createdAt` TEXT NOT NULL,
                `expiresAt` TEXT NOT NULL,
                `deliveryState` TEXT NOT NULL,
                PRIMARY KEY(`acknowledgementId`),
                FOREIGN KEY(`messageId`) REFERENCES `mesh_messages`(`messageId`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_mesh_acknowledgements_messageId_acknowledgingDeviceId_ackStatus` ON `mesh_acknowledgements` (`messageId`, `acknowledgingDeviceId`, `ackStatus`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_mesh_acknowledgements_targetDeviceId_deliveryState` ON `mesh_acknowledgements` (`targetDeviceId`, `deliveryState`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `mesh_request_routes` (
                `requestLocalId` TEXT NOT NULL,
                `originEventId` TEXT NOT NULL,
                `originDeviceId` TEXT NOT NULL,
                `originRequestLocalId` TEXT NOT NULL,
                `previousHopDeviceId` TEXT,
                `expiresAt` TEXT NOT NULL,
                PRIMARY KEY(`requestLocalId`),
                FOREIGN KEY(`requestLocalId`) REFERENCES `emergency_requests`(`localId`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_mesh_request_routes_originDeviceId_originRequestLocalId` ON `mesh_request_routes` (`originDeviceId`, `originRequestLocalId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_mesh_request_routes_expiresAt` ON `mesh_request_routes` (`expiresAt`)")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `emergency_requests` ADD COLUMN `lastServerCursor` TEXT")
        db.execSQL("ALTER TABLE `sync_events` ADD COLUMN `ownerAccountId` TEXT")
        db.execSQL("ALTER TABLE `device_info` ADD COLUMN `accountUserId` TEXT")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sync_conflicts` (
                `conflictId` TEXT NOT NULL,
                `eventId` TEXT,
                `eventType` TEXT,
                `entityId` TEXT,
                `errorCode` TEXT NOT NULL,
                `explanation` TEXT NOT NULL,
                `payloadJson` TEXT NOT NULL,
                `occurredAt` TEXT NOT NULL,
                `source` TEXT NOT NULL,
                PRIMARY KEY(`conflictId`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_conflicts_eventId` ON `sync_conflicts` (`eventId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_conflicts_occurredAt` ON `sync_conflicts` (`occurredAt`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sync_state` (
                `id` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `lastAttemptAt` TEXT,
                `lastError` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
    }
}
