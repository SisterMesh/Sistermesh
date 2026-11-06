package com.sistermesh.app.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.sistermesh.app.data.local.MIGRATION_1_2
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
                    facilityLocation = "Riverside",
                    designation = "Midwife"
                )
            )

            val created = repository.createRequest("O-", Urgency.CRITICAL, 2, "Local-only note")
            val stored = database.emergencyRequestDao().getWithResponses(created.id)
            val pending = repository.observePendingEvents().first()
            val createEvent = pending.single()
            val payload = JSONObject(createEvent.payloadJson)
            val device = database.deviceInfoDao().get()

            assertNotNull(stored)
            assertEquals(RequestStatus.SEARCHING, created.status)
            assertEquals("Community Clinic", stored?.request?.facility)
            assertEquals("O_NEGATIVE", payload.getString("requestedBloodGroup"))
            assertEquals("CRITICAL", payload.getString("urgency"))
            assertEquals(2, payload.getInt("units"))
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
                    facilityLocation = "Riverside",
                    designation = "Midwife"
                )
            )
            val request = repository.createRequest("O-", Urgency.URGENT, 1, "")
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
                    role = Role.DONOR,
                    name = "Donor",
                    bloodGroup = "O-",
                    area = "Riverside"
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
                    facilityLocation = "North",
                    designation = "Nurse"
                )
            )
            val request = firstRepository.createRequest("A+", Urgency.URGENT, 1, "")
            val eventId = firstRepository.observePendingEvents().first().single().eventId
            val deviceId = firstDatabase.deviceInfoDao().get()!!.clientDeviceId.toString()
            firstDatabase.close()

            reopened = fileDatabase(databaseName)
            val secondRepository = SisterMeshRepository(reopened!!)
            secondRepository.initialize()
            val persistedRequest = secondRepository.observeRequests().first().single { it.id == request.id }
            assertEquals("Persistent Worker", secondRepository.getProfile()?.name)
            assertEquals("A+", persistedRequest.bloodGroup)
            assertEquals(eventId, secondRepository.observePendingEvents().first().single().eventId)
            assertEquals(deviceId, reopened!!.deviceInfoDao().get()?.clientDeviceId?.toString())
            assertEquals(2, reopened!!.openHelper.writableDatabase.version)
            assertEquals(request.id, persistedRequest.id)
        } finally {
            if (reopened?.isOpen == true) reopened?.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun versionOneDataSurvivesMeshMigrationWithoutDestructiveFallback() {
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
            val profile = migrated.query("SELECT name FROM profiles WHERE id = 1")
            assertTrue(profile.moveToFirst())
            assertEquals("Legacy Worker", profile.getString(0))
            profile.close()
            val request = migrated.query("SELECT note FROM emergency_requests WHERE localId = 'legacy-request'")
            assertTrue(request.moveToFirst())
            assertEquals("local note", request.getString(0))
            request.close()
            val meshTables = migrated.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name IN ('mesh_messages', 'mesh_acknowledgements')"
            )
            assertTrue(meshTables.moveToFirst())
            assertEquals(2, meshTables.getInt(0))
            meshTables.close()
        } finally {
            helper.close()
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
