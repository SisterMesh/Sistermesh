package com.sistermesh.app.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.sistermesh.app.data.local.MIGRATION_1_2
import com.sistermesh.app.data.local.MIGRATION_2_3
import com.sistermesh.app.data.local.MIGRATION_3_4
import com.sistermesh.app.data.local.MIGRATION_4_5
import com.sistermesh.app.data.local.MIGRATION_5_6
import com.sistermesh.app.data.local.EmergencyRequestEntity
import com.sistermesh.app.data.local.ProfileEntity
import com.sistermesh.app.data.local.SisterMeshDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class SisterMeshRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun offlineRequestAndVersionOneEventAreSavedTogether() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val repository = SisterMeshRepository(database)
            repository.initialize()
            repository.registerProfile(
                Profile(
                    role = Role.CHW,
                    name = "Worker",
                    facility = "Community Clinic",
                    facilityId = "facility-1",
                    facilityLocation = "Riverside",
                    designation = "Midwife"
                )
            )

            val created = repository.createRequest(
                "O-", Urgency.CRITICAL, 2, "facility-1", false, "Patient A", "Sibling", "Local-only note"
            )
            val stored = database.emergencyRequestDao().getWithResponses(created.id)
            val pending = repository.observePendingEvents().first()
            val createEvent = pending.single()
            val payload = JSONObject(createEvent.payloadJson)
            val device = database.deviceInfoDao().get()

            assertNotNull(stored)
            assertEquals(RequestStatus.PENDING_FACILITY_VERIFICATION, created.status)
            assertEquals("Community Clinic", stored?.request?.facility)
            assertEquals("O_NEGATIVE", payload.getString("requestedBloodGroup"))
            assertEquals("CRITICAL", payload.getString("urgency"))
            assertEquals(2, payload.getInt("units"))
            assertEquals("facility-1", payload.getString("facilityId"))
            assertFalse(payload.getBoolean("isForSelf"))
            assertEquals("Patient A", payload.getString("beneficiaryName"))
            assertFalse(payload.has("note"))
            assertNull(createEvent.entityId)
            assertEquals(1, createEvent.payloadSchemaVersion)
            assertEquals(device?.clientDeviceId?.toString(), createEvent.originDeviceId)
            assertNotNull(UUID.fromString(createEvent.eventId))
            assertEquals(1, database.requestActivityDao().forRequest(created.id).size)

            repository.cancelRequest(created.id)
            val cancelEvent = repository.observeEventsForReview().first().single()
            assertEquals("request.cancel", cancelEvent.eventType)
            assertEquals("BLOCKED", cancelEvent.status)
            assertEquals(createEvent.eventId, cancelEvent.dependsOnEventId)

            val duplicate = database.syncEventDao().insertIfAbsent(
                createEvent.copy(payloadJson = """{"units":99}""")
            )
            assertEquals(-1L, duplicate)
            assertEquals(createEvent.eventId, repository.observePendingEvents().first().single().eventId)
        } finally {
            database.close()
        }
    }

    @Test
    fun confirmationRemainsBlockedWithoutVerifiedDonorIdAfterRequestIdAttachment() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val repository = SisterMeshRepository(database)
            repository.initialize()
            repository.registerProfile(
                Profile(
                    role = Role.CHW,
                    name = "Worker",
                    facility = "Community Clinic",
                    facilityId = "facility-1",
                    facilityLocation = "Riverside",
                    designation = "Midwife"
                )
            )
            val request = repository.createRequest("O-", Urgency.URGENT, 1, "facility-1", false, "Patient B", null, null)
            repository.confirmDonation(request.id, "ME")

            val blocked = repository.observeEventsForReview().first()
                .single { it.eventType == "donation.confirm" }
            assertEquals("MISSING_DONOR_PROFILE_ID", blocked.serverErrorCode)
            assertEquals(request.id, blocked.localEntityId)

            val serverId = UUID.randomUUID().toString()
            repository.attachServerEntityId(request.id, serverId, null)
            val stillBlocked = repository.observeEventsForReview().first()
                .single { it.eventType == "donation.confirm" }
            assertEquals("BLOCKED", stillBlocked.status)
            assertEquals(serverId, stillBlocked.entityId)
        } finally {
            database.close()
        }
    }

    @Test
    fun donorResponsePersistsLocallyAndStaysBlockedUntilServerRequestIdExists() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val repository = SisterMeshRepository(database)
            repository.initialize()
            repository.registerProfile(
                Profile(
                    role = Role.INDIVIDUAL,
                    name = "Donor",
                    bloodGroup = "O-",
                    area = "Riverside"
                )
            )
            database.emergencyRequestDao().upsert(
                EmergencyRequestEntity(
                    localId = "REQ-4821",
                    kind = "INCOMING",
                    bloodGroup = "O-",
                    urgency = Urgency.URGENT.name,
                    units = 1,
                    facility = "Community Clinic",
                    createdAt = "2026-11-07T12:00:00Z",
                    status = RequestStatus.OPEN.name,
                    receivedAt = "Just now"
                )
            )
            val deviceId = database.deviceInfoDao().get()!!.clientDeviceId.toString()

            repository.respond("REQ-4821", accept = true)

            val incoming = repository.observeIncoming().first().single { it.id == "REQ-4821" }
            val response = database.donorResponseDao().forRequest("REQ-4821").single()
            val queued = repository.observeEventsForReview().first().single()
            assertEquals(Decision.ACCEPTED, incoming.decision)
            assertEquals("ACCEPTED", response.status)
            assertEquals("donor-response.accept", queued.eventType)
            assertEquals("BLOCKED", queued.status)
            assertEquals("WAITING_FOR_SERVER_ENTITY_ID", queued.serverErrorCode)
            assertEquals("{}", queued.payloadJson)
            assertEquals(deviceId, queued.originDeviceId)

            repository.attachServerEntityId(
                "REQ-4821",
                UUID.randomUUID().toString(),
                null
            )
            val ready = repository.observePendingEvents().first().single()
            assertEquals("PENDING", ready.status)
            assertNotNull(ready.entityId)
        } finally {
            database.close()
        }
    }

    @Test
    fun profileRequestsAndStableEventIdsSurviveDatabaseReopen() = runBlocking {
        val databaseName = "sistermesh-test-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        val firstDatabase = fileDatabase(databaseName)
        var reopened: SisterMeshDatabase? = null
        try {
            val firstRepository = SisterMeshRepository(firstDatabase)
            firstRepository.initialize()
            firstRepository.registerProfile(
                Profile(
                    role = Role.CHW,
                    name = "Persistent Worker",
                    facility = "North Clinic",
                    facilityId = "facility-2",
                    facilityLocation = "North",
                    designation = "Nurse"
                )
            )
            val request = firstRepository.createRequest("A+", Urgency.URGENT, 1, "facility-2", false, "Patient C", null, null)
            val eventId = firstRepository.observePendingEvents().first().single().eventId
            val deviceId = firstDatabase.deviceInfoDao().get()!!.clientDeviceId.toString()
            firstDatabase.close()

            reopened = fileDatabase(databaseName)
            val secondRepository = SisterMeshRepository(reopened!!)
            secondRepository.initialize()
            val persistedRequest = secondRepository.observeFacilityRequests().first().single { it.id == request.id }
            assertEquals("Persistent Worker", secondRepository.getProfile()?.name)
            assertEquals("A+", persistedRequest.bloodGroup)
            assertEquals(eventId, secondRepository.observePendingEvents().first().single().eventId)
            assertEquals(deviceId, reopened!!.deviceInfoDao().get()?.clientDeviceId?.toString())
            assertEquals(5, reopened!!.openHelper.writableDatabase.version)
            assertEquals(request.id, persistedRequest.id)
        } finally {
            if (reopened?.isOpen == true) reopened?.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun roomOpensExistingVersionFourDatabaseAndPreservesAllProfileAndRequestData() = runBlocking {
        val name = "sistermesh-v4-to-v5-${UUID.randomUUID()}.db"
        context.deleteDatabase(name)

        val existingProfile = ProfileEntity(
            role = "INDIVIDUAL",
            name = "Legacy Member",
            bloodGroup = "A+",
            area = "Ikeja",
            phone = "0000000000",
            facility = "",
            facilityLocation = "",
            designation = "",
            isAvailable = false,
            language = "Yoruba"
        )
        val existingRequest = EmergencyRequestEntity(
            localId = "v4-request",
            serverId = "server-v4-request",
            kind = "OUTGOING",
            bloodGroup = "O-",
            urgency = "CRITICAL",
            units = 2,
            note = "Preserve this local note",
            facility = "Lagos Central",
            facilityId = "facility-lagos-central",
            isForSelf = false,
            beneficiaryName = "Request Beneficiary",
            beneficiaryRelationship = "Sibling",
            additionalInfo = "Available after 4pm",
            reviewReason = "Awaiting facility verification",
            createdAt = "2026-01-12T10:15:00Z",
            updatedAt = "2026-01-12T10:20:00Z",
            status = "PENDING_FACILITY_VERIFICATION",
            devicesFound = 3,
            delivered = 1,
            distance = "2 km",
            receivedAt = "2026-01-12T10:16:00Z",
            expiresIn = "3 hours",
            decision = "NONE",
            confirmed = false,
            isDemo = false,
            syncStatus = "PENDING",
            lastServerCursor = "42"
        )

        var upgradedDatabase: SisterMeshDatabase? = null
        try {
            val roomV5Database = fileDatabase(name)
            try {
                roomV5Database.profileDao().upsert(existingProfile)
                roomV5Database.emergencyRequestDao().upsert(existingRequest)
            } finally {
                roomV5Database.close()
            }

            // Room v4 differs from v5 only by the reviewedAt column added in MIGRATION_4_5.
            val legacyDatabase = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
            try {
                legacyDatabase.execSQL("PRAGMA foreign_keys = OFF")
                legacyDatabase.execSQL(
                    """
                    CREATE TABLE emergency_requests_v4 (
                        `localId` TEXT NOT NULL,
                        `serverId` TEXT,
                        `kind` TEXT NOT NULL,
                        `bloodGroup` TEXT NOT NULL,
                        `urgency` TEXT NOT NULL,
                        `units` INTEGER NOT NULL,
                        `note` TEXT NOT NULL,
                        `facility` TEXT NOT NULL,
                        `facilityId` TEXT,
                        `isForSelf` INTEGER NOT NULL,
                        `beneficiaryName` TEXT,
                        `beneficiaryRelationship` TEXT,
                        `additionalInfo` TEXT,
                        `reviewReason` TEXT,
                        `createdAt` TEXT NOT NULL,
                        `updatedAt` TEXT,
                        `status` TEXT NOT NULL,
                        `devicesFound` INTEGER NOT NULL,
                        `delivered` INTEGER NOT NULL,
                        `distance` TEXT NOT NULL,
                        `receivedAt` TEXT NOT NULL,
                        `expiresIn` TEXT NOT NULL,
                        `decision` TEXT NOT NULL,
                        `confirmed` INTEGER NOT NULL,
                        `isDemo` INTEGER NOT NULL,
                        `syncStatus` TEXT NOT NULL,
                        `lastServerCursor` TEXT,
                        PRIMARY KEY(`localId`)
                    )
                    """.trimIndent()
                )
                legacyDatabase.execSQL(
                    """
                    INSERT INTO emergency_requests_v4 (
                        localId, serverId, kind, bloodGroup, urgency, units, note, facility, facilityId,
                        isForSelf, beneficiaryName, beneficiaryRelationship, additionalInfo, reviewReason,
                        createdAt, updatedAt, status, devicesFound, delivered, distance, receivedAt, expiresIn,
                        decision, confirmed, isDemo, syncStatus, lastServerCursor
                    )
                    SELECT
                        localId, serverId, kind, bloodGroup, urgency, units, note, facility, facilityId,
                        isForSelf, beneficiaryName, beneficiaryRelationship, additionalInfo, reviewReason,
                        createdAt, updatedAt, status, devicesFound, delivered, distance, receivedAt, expiresIn,
                        decision, confirmed, isDemo, syncStatus, lastServerCursor
                    FROM emergency_requests
                    """.trimIndent()
                )
                legacyDatabase.execSQL("DROP TABLE emergency_requests")
                legacyDatabase.execSQL("ALTER TABLE emergency_requests_v4 RENAME TO emergency_requests")
                legacyDatabase.execSQL(
                    "CREATE INDEX `index_emergency_requests_kind_status` ON `emergency_requests` (`kind`, `status`)"
                )
                legacyDatabase.execSQL(
                    "CREATE INDEX `index_emergency_requests_serverId` ON `emergency_requests` (`serverId`)"
                )
                legacyDatabase.version = 4

                assertEquals(4, legacyDatabase.version)
                legacyDatabase.rawQuery("PRAGMA table_info('emergency_requests')", null).use { cursor ->
                    val columns = buildSet {
                        while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                    }
                    assertFalse(columns.contains("reviewedAt"))
                }
                legacyDatabase.rawQuery("SELECT COUNT(*) FROM profiles", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                }
                legacyDatabase.rawQuery("SELECT COUNT(*) FROM emergency_requests", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                }
            } finally {
                legacyDatabase.close()
            }

            upgradedDatabase = Room.databaseBuilder(context, SisterMeshDatabase::class.java, name)
                .addMigrations(MIGRATION_4_5)
                .allowMainThreadQueries()
                .build()

            assertEquals(5, upgradedDatabase.openHelper.writableDatabase.version)
            assertEquals(existingProfile, upgradedDatabase.profileDao().get())
            val migratedRequest = upgradedDatabase.emergencyRequestDao().get("v4-request")
            assertEquals(existingRequest, migratedRequest)
            assertNull(migratedRequest?.reviewedAt)
            assertEquals(1, upgradedDatabase.emergencyRequestDao().count())
            upgradedDatabase.openHelper.writableDatabase
                .query("PRAGMA table_info('emergency_requests')").use { cursor ->
                    val columns = buildSet {
                        while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                    }
                    assertTrue(columns.contains("reviewedAt"))
                }
        } finally {
            upgradedDatabase?.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun versionOneDataSurvivesMeshAndSyncMigrationsWithoutDestructiveFallback() {
        val name = "sistermesh-migration-${UUID.randomUUID()}.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE profiles (
                                id INTEGER NOT NULL PRIMARY KEY,
                                role TEXT NOT NULL, name TEXT NOT NULL, bloodGroup TEXT NOT NULL,
                                area TEXT NOT NULL, phone TEXT NOT NULL, facility TEXT NOT NULL,
                                facilityLocation TEXT NOT NULL, designation TEXT NOT NULL,
                                isAvailable INTEGER NOT NULL, language TEXT NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE emergency_requests (
                                localId TEXT NOT NULL PRIMARY KEY, serverId TEXT, kind TEXT NOT NULL,
                                bloodGroup TEXT NOT NULL, urgency TEXT NOT NULL, units INTEGER NOT NULL,
                                note TEXT NOT NULL, facility TEXT NOT NULL, createdAt TEXT NOT NULL,
                                updatedAt TEXT, status TEXT NOT NULL, devicesFound INTEGER NOT NULL,
                                delivered INTEGER NOT NULL, distance TEXT NOT NULL, receivedAt TEXT NOT NULL,
                                expiresIn TEXT NOT NULL, decision TEXT NOT NULL, confirmed INTEGER NOT NULL,
                                isDemo INTEGER NOT NULL, syncStatus TEXT NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE device_info (
                                id INTEGER NOT NULL PRIMARY KEY,
                                clientDeviceId TEXT NOT NULL,
                                name TEXT NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE sync_events (
                                eventId TEXT NOT NULL PRIMARY KEY,
                                originDeviceId TEXT NOT NULL,
                                payloadJson TEXT NOT NULL,
                                status TEXT NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            INSERT INTO profiles
                            (id, role, name, bloodGroup, area, phone, facility, facilityLocation, designation, isAvailable, language)
                            VALUES (1, 'CHW', 'Legacy Worker', '', '', '', 'Legacy Clinic', 'Riverside', 'Nurse', 1, 'English')
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            INSERT INTO emergency_requests
                            (localId, serverId, kind, bloodGroup, urgency, units, note, facility, createdAt, updatedAt,
                             status, devicesFound, delivered, distance, receivedAt, expiresIn, decision, confirmed, isDemo, syncStatus)
                            VALUES ('legacy-request', NULL, 'OUTGOING', 'O-', 'CRITICAL', 2, 'local note', 'Legacy Clinic',
                             '2026-01-01T00:00:00Z', NULL, 'SEARCHING', 0, 0, '', '', '', 'NONE', 0, 0, 'PENDING')
                            """.trimIndent()
                        )
                        db.execSQL(
                            "INSERT INTO device_info (id, clientDeviceId, name) VALUES (1, 'stable-client-id', 'Legacy phone')"
                        )
                        db.execSQL(
                            "INSERT INTO sync_events (eventId, originDeviceId, payloadJson, status) VALUES ('legacy-event', 'stable-client-id', '{}', 'PENDING')"
                        )
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        error("Unexpected test database upgrade.")
                    }
                })
                .build()
        )
        val migrated = helper.writableDatabase
        try {
            MIGRATION_1_2.migrate(migrated)
            MIGRATION_2_3.migrate(migrated)
            MIGRATION_3_4.migrate(migrated)
            val requestsBeforeV5 = migrated.query("SELECT COUNT(*) FROM emergency_requests")
            assertTrue(requestsBeforeV5.moveToFirst())
            val requestCountBeforeV5 = requestsBeforeV5.getInt(0)
            requestsBeforeV5.close()
            MIGRATION_4_5.migrate(migrated)
            val profile = migrated.query("SELECT name FROM profiles WHERE id = 1")
            assertTrue(profile.moveToFirst())
            assertEquals("Legacy Worker", profile.getString(0))
            profile.close()
            val request = migrated.query("SELECT note FROM emergency_requests WHERE localId = 'legacy-request'")
            assertTrue(request.moveToFirst())
            assertEquals("local note", request.getString(0))
            request.close()
            val migratedReviewTimestamp = migrated.query(
                "SELECT reviewedAt FROM emergency_requests WHERE localId = 'legacy-request'"
            )
            assertTrue(migratedReviewTimestamp.moveToFirst())
            assertTrue(migratedReviewTimestamp.isNull(0))
            migratedReviewTimestamp.close()
            val requestsAfterV5 = migrated.query("SELECT COUNT(*) FROM emergency_requests")
            assertTrue(requestsAfterV5.moveToFirst())
            assertEquals(requestCountBeforeV5, requestsAfterV5.getInt(0))
            requestsAfterV5.close()
            val meshTables = migrated.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name IN ('mesh_messages', 'mesh_acknowledgements')"
            )
            assertTrue(meshTables.moveToFirst())
            assertEquals(2, meshTables.getInt(0))
            meshTables.close()
            val event = migrated.query("SELECT eventId, ownerAccountId FROM sync_events WHERE eventId = 'legacy-event'")
            assertTrue(event.moveToFirst())
            assertEquals("legacy-event", event.getString(0))
            assertTrue(event.isNull(1))
            event.close()
            val device = migrated.query("SELECT clientDeviceId, accountUserId FROM device_info WHERE id = 1")
            assertTrue(device.moveToFirst())
            assertEquals("stable-client-id", device.getString(0))
            assertTrue(device.isNull(1))
            device.close()
            val syncTables = migrated.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name IN ('sync_conflicts', 'sync_state')"
            )
            assertTrue(syncTables.moveToFirst())
            assertEquals(2, syncTables.getInt(0))
            syncTables.close()
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun requestMigrationFromV5PreservesRowsAndRemovesLegacyWorkerFlow() = runBlocking {
        val name = "room-v5-to-v6-${UUID.randomUUID()}"
        val profile = ProfileEntity(
            role = "CHW",
            name = "Legacy account",
            bloodGroup = "O-",
            area = "Lagos",
            phone = "+2348012345678",
            facility = "Old clinic",
            facilityLocation = "",
            designation = "Nurse"
        )
        val request = EmergencyRequestEntity(
            localId = "legacy-facility-request",
            kind = "FACILITY",
            bloodGroup = "O-",
            urgency = "URGENT",
            units = 2,
            note = "Preserve this note",
            facility = "Old clinic, Lagos",
            facilityId = "legacy-facility-id",
            beneficiaryName = "Private person",
            additionalInfo = "Keep this data",
            reviewedAt = null,
            createdAt = "2026-11-10T09:00:00Z",
            status = "PENDING_FACILITY_VERIFICATION",
            syncStatus = "PENDING",
            lastServerCursor = "17"
        )
        var upgraded: SisterMeshDatabase? = null
        try {
            fileDatabase(name).use { database ->
                database.profileDao().upsert(profile)
                database.emergencyRequestDao().upsert(request)
            }

            // Recreate the prior v5 schema, then run Room's real 5→6 migration.
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { legacy ->
                legacy.execSQL("ALTER TABLE `profiles` DROP COLUMN `useMode`")
                legacy.execSQL("ALTER TABLE `emergency_requests` DROP COLUMN `facilityLocation`")
                legacy.execSQL("ALTER TABLE `emergency_requests` DROP COLUMN `facilityLatitude`")
                legacy.execSQL("ALTER TABLE `emergency_requests` DROP COLUMN `facilityLongitude`")
                legacy.version = 5
            }

            upgraded = Room.databaseBuilder(context, SisterMeshDatabase::class.java, name)
                .addMigrations(MIGRATION_5_6)
                .allowMainThreadQueries()
                .build()

            val migratedProfile = upgraded.profileDao().get()
            val migratedRequest = upgraded.emergencyRequestDao().get(request.localId)
            assertEquals(6, upgraded.openHelper.writableDatabase.version)
            assertEquals(profile.copy(role = "INDIVIDUAL"), migratedProfile)
            assertEquals("OUTGOING", migratedRequest?.kind)
            assertEquals("OPEN", migratedRequest?.status)
            assertEquals(request.facility, migratedRequest?.facility)
            assertEquals(request.note, migratedRequest?.note)
            assertEquals(request.beneficiaryName, migratedRequest?.beneficiaryName)
            assertEquals(request.additionalInfo, migratedRequest?.additionalInfo)
            assertEquals(request.lastServerCursor, migratedRequest?.lastServerCursor)
            assertEquals("", migratedRequest?.facilityLocation)
            assertNull(migratedRequest?.facilityLatitude)
            assertNull(migratedRequest?.facilityLongitude)
            assertNull(migratedRequest?.reviewedAt)
            assertEquals(1, upgraded.profileDao().count())
            assertEquals(1, upgraded.emergencyRequestDao().count())
        } finally {
            upgraded?.close()
            context.deleteDatabase(name)
        }
    }

    private fun inMemoryDatabase() =
        Room.inMemoryDatabaseBuilder(context, SisterMeshDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    private fun fileDatabase(name: String) =
        Room.databaseBuilder(context, SisterMeshDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
}
