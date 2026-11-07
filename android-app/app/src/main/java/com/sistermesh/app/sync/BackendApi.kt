package com.sistermesh.app.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class BackendUser(
    val id: String,
    val role: String,
    val status: String,
    val displayName: String,
    val phoneNumber: String,
    val donorProfileId: String?,
    val donorBloodGroup: String?,
    val donorLocality: String?,
    val donorProfileComplete: Boolean,
    val healthWorkerVerificationStatus: String?,
    val healthWorkerFacilityId: String?
)

data class BackendSession(val accessToken: String, val refreshCookie: String, val user: BackendUser)

data class RegisteredDevice(
    val deviceId: String,
    val clientDeviceId: String,
    val revokedAt: String?
)

data class SyncEventResult(
    val eventId: String?,
    val status: String,
    val entityId: String?,
    val errorCode: String?,
    val explanation: String?
)

data class SyncBatchResult(val clientBatchId: String, val events: List<SyncEventResult>)

data class ServerRequestSnapshot(
    val id: String,
    val requestedBloodGroup: String,
    val urgency: String,
    val units: Int,
    val status: String,
    val expiresAt: String?,
    val createdAt: String,
    val updatedAt: String,
    val facilityName: String,
    val facilityLocality: String,
    val facilityState: String
)

data class ServerChange(
    val cursor: String,
    val eventType: String,
    val fromStatus: String?,
    val toStatus: String?,
    val occurredAt: String,
    val request: ServerRequestSnapshot
)

data class ServerChangesPage(
    val checkpoint: String,
    val nextCursor: String,
    val hasMore: Boolean,
    val changes: List<ServerChange>
)

data class ServerConflict(
    val eventId: String?,
    val eventType: String?,
    val entityId: String?,
    val errorCode: String,
    val explanation: String,
    val payloadJson: String,
    val occurredAt: String
)

class BackendHttpException(
    val statusCode: Int,
    val errorCode: String?,
    override val message: String
) : IOException(message) {
    val isRetryable: Boolean get() = statusCode == 408 || statusCode == 429 || statusCode >= 500
}

class BackendProtocolException(message: String) : IOException(message)

interface SyncBackendApi {
    suspend fun login(phoneNumber: String, password: String): BackendSession
    suspend fun registerDonor(
        displayName: String,
        phoneNumber: String,
        password: String,
        bloodGroup: String,
        locality: String
    ): BackendSession
    suspend fun registerHealthWorker(
        displayName: String,
        phoneNumber: String,
        password: String,
        designation: String,
        professionalId: String
    ): BackendSession
    suspend fun refresh(refreshCookie: String): BackendSession
    suspend fun logout(refreshCookie: String)
    suspend fun getMe(accessToken: String): BackendUser
    suspend fun registerDevice(accessToken: String, clientDeviceId: String, deviceName: String): RegisteredDevice
    suspend fun uploadBatch(
        accessToken: String,
        deviceId: String,
        clientBatchId: String,
        events: JSONArray
    ): SyncBatchResult
    suspend fun getChanges(accessToken: String, deviceId: String, limit: Int = 50): ServerChangesPage
    suspend fun acknowledgeCheckpoint(accessToken: String, deviceId: String, cursor: String)
    suspend fun getConflicts(accessToken: String, deviceId: String, limit: Int = 50): List<ServerConflict>
}

class BackendApi(
    baseUrl: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
) : SyncBackendApi {
    private val base = baseUrl.trim().let { configured ->
        if (configured.isBlank()) null
        else runCatching { (configured.trimEnd('/') + "/").toHttpUrl() }.getOrNull()
    }

    override suspend fun login(phoneNumber: String, password: String): BackendSession =
        authSession("auth/login", JSONObject().put("phoneNumber", phoneNumber).put("password", password))

    override suspend fun registerDonor(
        displayName: String,
        phoneNumber: String,
        password: String,
        bloodGroup: String,
        locality: String
    ): BackendSession {
        val body = JSONObject()
            .put("displayName", displayName)
            .put("phoneNumber", phoneNumber)
            .put("password", password)
            .put("bloodGroup", bloodGroup)
        if (locality.isNotBlank()) body.put("locality", locality)
        return authSession("auth/register/donor", body)
    }

    override suspend fun registerHealthWorker(
        displayName: String,
        phoneNumber: String,
        password: String,
        designation: String,
        professionalId: String
    ): BackendSession {
        val body = JSONObject()
            .put("displayName", displayName)
            .put("phoneNumber", phoneNumber)
            .put("password", password)
        if (designation.isNotBlank()) body.put("designation", designation)
        if (professionalId.isNotBlank()) body.put("professionalId", professionalId)
        return authSession("auth/register/health-worker", body)
    }

    override suspend fun refresh(refreshCookie: String): BackendSession {
        val result = execute(
            method = "POST",
            path = "auth/refresh",
            body = JSONObject(),
            cookie = refreshCookie
        )
        return parseSession(result)
    }

    override suspend fun logout(refreshCookie: String) {
        execute(method = "POST", path = "auth/logout", body = JSONObject(), cookie = refreshCookie)
    }

    override suspend fun getMe(accessToken: String): BackendUser =
        parseUser(execute("GET", "auth/me", token = accessToken).json)

    override suspend fun registerDevice(
        accessToken: String,
        clientDeviceId: String,
        deviceName: String
    ): RegisteredDevice {
        val json = execute(
            "POST",
            "sync/devices",
            JSONObject().put("clientDeviceId", clientDeviceId).put("name", deviceName),
            token = accessToken
        ).json
        return RegisteredDevice(
            deviceId = json.requiredString("deviceId"),
            clientDeviceId = json.requiredString("clientDeviceId"),
            revokedAt = json.optNullableString("revokedAt")
        )
    }

    override suspend fun uploadBatch(
        accessToken: String,
        deviceId: String,
        clientBatchId: String,
        events: JSONArray
    ): SyncBatchResult {
        val json = execute(
            "POST",
            "sync/devices/$deviceId/batches",
            JSONObject().put("clientBatchId", clientBatchId).put("events", events),
            token = accessToken
        ).json
        val returnedBatchId = json.requiredString("clientBatchId")
        val results = json.optJSONArray("events") ?: throw BackendProtocolException("The server response omitted batch event results.")
        return SyncBatchResult(returnedBatchId, (0 until results.length()).map { index ->
            val item = results.optJSONObject(index)
                ?: throw BackendProtocolException("The server returned an invalid event result.")
            SyncEventResult(
                eventId = item.optNullableString("eventId"),
                status = item.requiredString("status"),
                entityId = item.optNullableString("entityId"),
                errorCode = item.optNullableString("errorCode"),
                explanation = item.optNullableString("explanation")
            )
        })
    }

    override suspend fun getChanges(accessToken: String, deviceId: String, limit: Int): ServerChangesPage {
        val json = execute(
            "GET",
            "sync/devices/$deviceId/changes?limit=$limit",
            token = accessToken
        ).json
        val changes = json.optJSONArray("changes")
            ?: throw BackendProtocolException("The server response omitted its changes page.")
        return ServerChangesPage(
            checkpoint = json.requiredString("checkpoint"),
            nextCursor = json.requiredString("nextCursor"),
            hasMore = json.optBoolean("hasMore"),
            changes = (0 until changes.length()).map { index ->
                parseChange(changes.optJSONObject(index)
                    ?: throw BackendProtocolException("The server returned an invalid change record."))
            }
        )
    }

    override suspend fun acknowledgeCheckpoint(accessToken: String, deviceId: String, cursor: String) {
        execute(
            "POST",
            "sync/devices/$deviceId/checkpoint",
            JSONObject().put("cursor", cursor),
            token = accessToken
        )
    }

    override suspend fun getConflicts(accessToken: String, deviceId: String, limit: Int): List<ServerConflict> {
        val json = execute(
            "GET",
            "sync/devices/$deviceId/conflicts?limit=$limit",
            token = accessToken
        ).json
        val conflicts = json.optJSONArray("conflicts")
            ?: throw BackendProtocolException("The server response omitted its conflicts list.")
        return (0 until conflicts.length()).map { index ->
            val item = conflicts.optJSONObject(index)
                ?: throw BackendProtocolException("The server returned an invalid conflict.")
            val payload = item.opt("payload")
            ServerConflict(
                eventId = item.optNullableString("eventId"),
                eventType = item.optNullableString("eventType"),
                entityId = item.optNullableString("entityId"),
                errorCode = item.optNullableString("errorCode") ?: "UNKNOWN_CONFLICT",
                explanation = item.optNullableString("explanation") ?: "The server retained a conflict for review.",
                payloadJson = payload?.toString() ?: "{}",
                occurredAt = item.optNullableString("occurredAt") ?: ""
            )
        }
    }

    private suspend fun authSession(path: String, body: JSONObject): BackendSession =
        parseSession(execute("POST", path, body))

    private suspend fun execute(
        method: String,
        path: String,
        body: JSONObject? = null,
        token: String? = null,
        cookie: String? = null
    ): HttpResult = withContext(Dispatchers.IO) {
        val root = base ?: throw BackendProtocolException("Sync server URL is not configured for this build.")
        val url = root.resolve(path) ?: throw BackendProtocolException("Invalid sync server path.")
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply {
                token?.let { header("Authorization", "Bearer $it") }
                cookie?.let { header("Cookie", "sistermesh_refresh=$it") }
                if (body != null) {
                    method(method, body.toString().toRequestBody(JSON_MEDIA_TYPE))
                } else {
                    method(method, null)
                }
            }
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw httpException(response.code, raw)
            val json = if (raw.isBlank()) JSONObject() else try {
                JSONObject(raw)
            } catch (_: Exception) {
                throw BackendProtocolException("The server returned malformed JSON.")
            }
            HttpResult(json, response.headers.values("Set-Cookie"))
        }
    }

    private fun parseSession(result: HttpResult): BackendSession {
        val accessToken = result.json.optNullableString("accessToken")
            ?: throw BackendProtocolException("The authentication response omitted its access token.")
        val cookie = result.setCookies.firstNotNullOfOrNull(::refreshCookieValue)
            ?: throw BackendProtocolException("The authentication response omitted its secure refresh cookie.")
        val user = result.json.optJSONObject("user")
            ?: throw BackendProtocolException("The authentication response omitted the user profile.")
        return BackendSession(accessToken, cookie, parseUser(user))
    }

    private fun parseUser(json: JSONObject): BackendUser {
        val donor = json.optJSONObject("donorProfile")
        val worker = json.optJSONObject("healthWorkerProfile")
        return BackendUser(
            id = json.requiredString("id"),
            role = json.requiredString("role"),
            status = json.requiredString("status"),
            displayName = json.optNullableString("displayName").orEmpty(),
            phoneNumber = json.optNullableString("phoneNumber").orEmpty(),
            donorProfileId = donor?.optNullableString("id"),
            donorBloodGroup = donor?.optNullableString("bloodGroup"),
            donorLocality = donor?.optNullableString("locality"),
            donorProfileComplete = donor?.optBoolean("isProfileComplete") ?: false,
            healthWorkerVerificationStatus = worker?.optNullableString("verificationStatus"),
            healthWorkerFacilityId = worker?.optNullableString("facilityId")
        )
    }

    private fun parseChange(json: JSONObject): ServerChange {
        val request = json.optJSONObject("request")
            ?: throw BackendProtocolException("The server returned a change without a request snapshot.")
        val facility = request.optJSONObject("facility")
            ?: throw BackendProtocolException("The server returned a request without facility data.")
        return ServerChange(
            cursor = json.requiredString("cursor"),
            eventType = json.requiredString("eventType"),
            fromStatus = json.optNullableString("fromStatus"),
            toStatus = json.optNullableString("toStatus"),
            occurredAt = json.requiredString("occurredAt"),
            request = ServerRequestSnapshot(
                id = request.requiredString("id"),
                requestedBloodGroup = request.requiredString("requestedBloodGroup"),
                urgency = request.requiredString("urgency"),
                units = request.optInt("units", -1).takeIf { it >= 0 }
                    ?: throw BackendProtocolException("The server returned an invalid request unit count."),
                status = request.requiredString("status"),
                expiresAt = request.optNullableString("expiresAt"),
                createdAt = request.requiredString("createdAt"),
                updatedAt = request.requiredString("updatedAt"),
                facilityName = facility.optNullableString("name").orEmpty(),
                facilityLocality = facility.optNullableString("locality").orEmpty(),
                facilityState = facility.optNullableString("state").orEmpty()
            )
        )
    }

    private fun httpException(status: Int, raw: String): BackendHttpException {
        val json = runCatching { JSONObject(raw) }.getOrNull()
        val messageValue = json?.opt("message")
        val message = when (messageValue) {
            is JSONArray -> (0 until messageValue.length()).joinToString("; ") { messageValue.optString(it) }
            is String -> messageValue
            else -> "The server rejected the request."
        }
        return BackendHttpException(status, json?.optNullableString("errorCode"), message)
    }

    private fun refreshCookieValue(header: String): String? {
        val firstPair = header.substringBefore(';')
        val separator = firstPair.indexOf('=')
        if (separator <= 0 || firstPair.substring(0, separator).trim() != "sistermesh_refresh") return null
        return runCatching {
            URLDecoder.decode(firstPair.substring(separator + 1).trim(), StandardCharsets.UTF_8.name())
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private data class HttpResult(val json: JSONObject, val setCookies: List<String>)

    private fun JSONObject.requiredString(name: String): String =
        optNullableString(name) ?: throw BackendProtocolException("The server response omitted $name.")

    private fun JSONObject.optNullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
