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
    fun donorRegistrationUsesTheAtomicIndividualRegistrationEndpoint() = runBlocking {
        server.enqueue(
            MockResponse()
                .addHeader("Set-Cookie", "sistermesh_refresh=refresh-value; Path=/api/auth; HttpOnly; Secure")
                .setBody(sessionJson("INDIVIDUAL"))
        )

        api.registerDonor("Local user", "+2348012345678", "correct horse battery 24", "O_NEGATIVE", "Central")

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals("/api/auth/register/donor", request.path)
        assertEquals("O_NEGATIVE", body.getString("bloodGroup"))
        assertEquals("Central", body.getString("locality"))
        assertEquals("+2348012345678", body.getString("phoneNumber"))
        assertEquals("correct horse battery 24", body.getString("password"))
        assertTrue(!body.has("role"))
    }

    @Test
    fun individualRegistrationUsesTheBackendRoleContract() = runBlocking {
        server.enqueue(
            MockResponse()
                .addHeader("Set-Cookie", "sistermesh_refresh=refresh-value; Path=/api/auth; HttpOnly; Secure")
                .setBody(sessionJson("INDIVIDUAL"))
        )

        api.registerIndividual("Local requester", "+2348012345678", "correct horse battery 24")

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals("/api/auth/register", request.path)
        assertEquals("INDIVIDUAL", body.getString("role"))
        assertEquals("+2348012345678", body.getString("phoneNumber"))
    }

    @Test
    fun requestFeedEndpointsUseTheSignedInIdentityAndSeparatePublicFromPrivateFields() = runBlocking {
        val requestBody = """
            [{
              "id":"00000000-0000-4000-8000-000000000001",
              "clientRequestId":"00000000-0000-4000-8000-000000000099",
              "requestedBloodGroup":"O_NEGATIVE",
              "urgency":"URGENT","units":1,"status":"OPEN",
              "createdAt":"2026-11-07T11:00:00.000Z",
              "facilityName":"Community Clinic","facilityLocation":"Central",
              "isMine":true,"beneficiaryName":"Private name"
            }]
        """.trimIndent()
        server.enqueue(MockResponse().setBody(requestBody))
        server.enqueue(MockResponse().setBody(requestBody))

        val eligible = api.getEligibleRequests("access-test").single()
        val eligibleRequest = server.takeRequest()
        val mine = api.getMyRequests("access-test").single()
        val mineRequest = server.takeRequest()

        assertEquals("/api/emergency-requests/eligible", eligibleRequest.path)
        assertEquals("Bearer access-test", eligibleRequest.getHeader("Authorization"))
        assertEquals("/api/emergency-requests/mine", mineRequest.path)
        assertEquals("Bearer access-test", mineRequest.getHeader("Authorization"))
        assertEquals(false, eligible.isMine)
        assertEquals(true, mine.isMine)
        assertEquals("Private name", mine.beneficiaryName)
        assertEquals("00000000-0000-4000-8000-000000000099", eligible.clientRequestId)
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
    fun changesParserAcceptsFreeformDestinationWithoutFacilityRecord() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "checkpoint":"0","nextCursor":"1","hasMore":false,
                  "changes":[{
                    "cursor":"1","eventType":"CREATED","fromStatus":null,
                    "toStatus":"OPEN","occurredAt":"2026-11-07T12:00:00.000Z",
                    "request":{
                      "id":"00000000-0000-4000-8000-000000000001",
                      "requestedBloodGroup":"O_NEGATIVE","urgency":"URGENT","units":1,
                      "status":"OPEN","expiresAt":null,
                      "createdAt":"2026-11-07T11:00:00.000Z",
                      "facilityId":null,"facility":null,
                      "facilityName":"Community Clinic",
                      "facilityLocation":"Ikeja, Lagos",
                      "facilityLatitude":6.6018,"facilityLongitude":3.3515,
                      "isMine":false
                    }
                  }]
                }
                """.trimIndent()
            )
        )

        val request = api.getChanges("access-test", "server-device").changes.single().request

        assertEquals("Community Clinic", request.facilityName)
        assertEquals("Ikeja, Lagos", request.facilityLocation)
        assertEquals(6.6018, request.facilityLatitude!!, 0.0001)
        assertEquals(3.3515, request.facilityLongitude!!, 0.0001)
        assertEquals("", request.facilityId)
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
