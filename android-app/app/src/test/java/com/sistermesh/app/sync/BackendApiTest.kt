package com.sistermesh.app.sync

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackendApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: BackendApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = BackendApi(server.url("/api/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun loginCapturesTheSecureRefreshCookieWithoutReturningItInJson() = runBlocking {
        server.enqueue(
            MockResponse()
                .addHeader("Set-Cookie", "sistermesh_refresh=refresh%2Bvalue; Path=/api/auth; HttpOnly; Secure; SameSite=Strict")
                .setBody(sessionJson("INDIVIDUAL"))
        )

        val session = api.login("+2348012345678", "not-a-real-password")
        val request = server.takeRequest()

        assertEquals("/api/auth/login", request.path)
        assertEquals("+2348012345678", JSONObject(request.body.readUtf8()).getString("phoneNumber"))
        assertEquals("refresh+value", session.refreshCookie)
        assertEquals("access-test", session.accessToken)
        assertEquals("INDIVIDUAL", session.user.role)
    }

    @Test
    fun healthWorkerRegistrationDoesNotSendClientAssignedRoleOrFacility() = runBlocking {
        server.enqueue(
            MockResponse()
                .addHeader("Set-Cookie", "sistermesh_refresh=worker-cookie; Path=/api/auth; HttpOnly; Secure")
                .setBody(sessionJson("HEALTH_WORKER"))
        )

        api.registerHealthWorker(
            displayName = "Local worker",
            phoneNumber = "+2348012345678",
            password = "not-a-real-password",
            designation = "Nurse",
            professionalId = "PRO-1"
        )
        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())

        assertEquals("/api/auth/register/health-worker", request.path)
        assertTrue(!body.has("role"))
        assertTrue(!body.has("facilityId"))
        assertEquals("PRO-1", body.getString("professionalId"))
    }

    @Test
    fun uploadUsesBearerIdentityAndStableBatchEnvelope() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"clientBatchId":"stable-batch","events":[{"eventId":"event-1","status":"PROCESSED","entityId":"server-1"}]}"""
            )
        )
        val events = JSONArray().put(
            JSONObject()
                .put("eventId", "event-1")
                .put("eventType", "request.create")
                .put("originDeviceId", "client-device")
                .put("payload", JSONObject().put("units", 1))
        )

        val result = api.uploadBatch("access-test", "server-device", "stable-batch", events)
        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())

        assertEquals("/api/sync/devices/server-device/batches", request.path)
        assertEquals("Bearer access-test", request.getHeader("Authorization"))
        assertEquals("stable-batch", body.getString("clientBatchId"))
        assertEquals("event-1", body.getJSONArray("events").getJSONObject(0).getString("eventId"))
        assertEquals("server-1", result.events.single().entityId)
    }

    @Test
    fun changesParserPreservesCursorAndSanitizedFacilitySnapshot() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "checkpoint":"0",
                  "nextCursor":"1",
                  "hasMore":false,
                  "changes":[{
                    "cursor":"1",
                    "eventType":"CREATED",
                    "fromStatus":null,
                    "toStatus":"OPEN",
                    "occurredAt":"2026-11-07T12:00:00.000Z",
                    "request":{
                      "id":"00000000-0000-4000-8000-000000000001",
                      "requestedBloodGroup":"O_NEGATIVE",
                      "urgency":"URGENT",
                      "units":1,
                      "status":"OPEN",
                      "expiresAt":null,
                      "createdAt":"2026-11-07T11:00:00.000Z",
                      "updatedAt":"2026-11-07T11:00:00.000Z",
                      "facility":{"name":"Community Clinic","locality":"Central","state":"Rivers"}
                    }
                  }]
                }
                """.trimIndent()
            )
        )

        val page = api.getChanges("access-test", "server-device")
        val request = server.takeRequest()

        assertEquals("/api/sync/devices/server-device/changes?limit=50", request.path)
        assertEquals("0", page.checkpoint)
        assertEquals("1", page.nextCursor)
        assertEquals("Community Clinic", page.changes.single().request.facilityName)
    }

    @Test
    fun nonSuccessResponseRemainsAnExplicitHttpError() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"statusCode":401,"message":"A valid bearer access token is required."}""")
        )

        val error = runCatching { api.getMe("expired") }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error is BackendHttpException)
        assertEquals(401, (error as BackendHttpException).statusCode)
    }

    private fun sessionJson(role: String) = """
        {
          "accessToken":"access-test",
          "user":{
            "id":"user-1",
            "role":"$role",
            "status":"ACTIVE",
            "displayName":"Local user",
            "phoneNumber":"+2348012345678",
            "donorProfile":{
              "id":"donor-1",
              "bloodGroup":"O_NEGATIVE",
              "locality":"Central",
              "isProfileComplete":true
            },
            "healthWorkerProfile":{
              "verificationStatus":"PENDING",
              "facilityId":null
            }
          }
        }
    """.trimIndent()
}
