package com.sistermesh.app.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRepositoryTest {
    @Test
    fun expiredAccessTokenRefreshesOnceAndRetriesTheRequest() = runBlocking {
        val api = FakeApi()
        val user = donorUser()
        api.getMeHandler = { token ->
            if (token == "expired-access") throw BackendHttpException(401, null, "expired")
            user
        }
        api.refreshHandler = {
            BackendSession("fresh-access", "rotated-refresh", user)
        }
        val tokenStore = MemoryTokenStore(StoredTokens("expired-access", "old-refresh"))
        val auth = AuthRepository(api, tokenStore)

        val returned = auth.withAccessToken { api.getMe(it) }

        assertEquals(user, returned)
        assertEquals("fresh-access", tokenStore.value?.accessToken)
        assertEquals("rotated-refresh", tokenStore.value?.refreshCookie)
        assertEquals(1, api.refreshCalls)
    }

    @Test
    fun adminSessionIsRejectedAndNeverStored() = runBlocking {
        val api = FakeApi().apply {
            loginSession = BackendSession("admin-token", "admin-cookie", donorUser(role = "ADMIN"))
        }
        val tokenStore = MemoryTokenStore()
        val auth = AuthRepository(api, tokenStore)

        assertThrows(AccountRoleMismatchException::class.java) {
            runBlocking { auth.login("+2348012345678", "not-a-real-password", "DONOR") }
        }

        assertNull(tokenStore.value)
        assertEquals("admin-cookie", api.loggedOutCookie)
    }

    @Test
    fun rejectedRefreshClearsTheStoredSession() = runBlocking {
        val api = FakeApi().apply {
            getMeHandler = { throw BackendHttpException(401, null, "expired") }
            refreshHandler = { throw BackendHttpException(401, null, "refresh expired") }
        }
        val tokenStore = MemoryTokenStore(StoredTokens("expired-access", "expired-refresh"))
        val auth = AuthRepository(api, tokenStore)

        assertThrows(AuthenticationRequiredException::class.java) {
            runBlocking { auth.withAccessToken { api.getMe(it) } }
        }

        assertNull(tokenStore.value)
        assertTrue(api.refreshCalls > 0)
    }

    private class MemoryTokenStore(var value: StoredTokens? = null) : TokenStore {
        override fun read(): StoredTokens? = value
        override fun write(tokens: StoredTokens) { value = tokens }
        override fun clear() { value = null }
    }

    private class FakeApi : SyncBackendApi {
        var loginSession = BackendSession("access", "refresh", donorUser())
        var refreshCalls = 0
        var loggedOutCookie: String? = null
        var refreshHandler: suspend (String) -> BackendSession = { loginSession }
        var getMeHandler: suspend (String) -> BackendUser = { loginSession.user }

        override suspend fun login(phoneNumber: String, password: String) = loginSession
        override suspend fun registerDonor(
            displayName: String,
            phoneNumber: String,
            password: String,
            bloodGroup: String,
            locality: String
        ) = loginSession
        override suspend fun registerHealthWorker(
            displayName: String,
            phoneNumber: String,
            password: String,
            designation: String,
            professionalId: String
        ) = loginSession
        override suspend fun refresh(refreshCookie: String): BackendSession {
            refreshCalls++
            return refreshHandler(refreshCookie)
        }
        override suspend fun logout(refreshCookie: String) {
            loggedOutCookie = refreshCookie
        }
        override suspend fun getMe(accessToken: String) = getMeHandler(accessToken)
        override suspend fun registerDevice(accessToken: String, clientDeviceId: String, deviceName: String) =
            error("Not used in this test.")
        override suspend fun uploadBatch(
            accessToken: String,
            deviceId: String,
            clientBatchId: String,
            events: org.json.JSONArray
        ) = error("Not used in this test.")
        override suspend fun getChanges(accessToken: String, deviceId: String, limit: Int) =
            error("Not used in this test.")
        override suspend fun acknowledgeCheckpoint(accessToken: String, deviceId: String, cursor: String) =
            error("Not used in this test.")
        override suspend fun getConflicts(accessToken: String, deviceId: String, limit: Int) =
            error("Not used in this test.")
    }
}

private fun donorUser(role: String = "DONOR") = BackendUser(
    id = "user-1",
    role = role,
    status = "ACTIVE",
    displayName = "Donor",
    phoneNumber = "+2348012345678",
    donorProfileId = "donor-1",
    donorBloodGroup = "O_NEGATIVE",
    donorLocality = "Central",
    donorProfileComplete = true,
    healthWorkerVerificationStatus = null,
    healthWorkerFacilityId = null
)
