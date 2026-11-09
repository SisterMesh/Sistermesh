package com.sistermesh.app.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface AccountState {
    data object SignedOut : AccountState
    data object Checking : AccountState
    data object SavedOffline : AccountState
    data class SignedIn(val user: BackendUser) : AccountState
    data class RoleMismatch(val user: BackendUser) : AccountState
}

class AuthenticationRequiredException(message: String = "Sign in again to resume synchronization.") :
    IOException(message)

class AccountRoleMismatchException(message: String) : IOException(message)

class AuthRepository(
    private val api: SyncBackendApi,
    private val tokenStore: TokenStore
) {
    private val refreshMutex = Mutex()
    private var expectedRole: String? = null
    private val mutableState = MutableStateFlow<AccountState>(AccountState.SignedOut)
    val state: StateFlow<AccountState> = mutableState.asStateFlow()

    suspend fun restore(expectedRole: String?) {
        this.expectedRole = expectedRole
        mutableState.value = AccountState.Checking
        val stored = readTokens()
        if (stored == null) {
            mutableState.value = AccountState.SignedOut
            return
        }
        try {
            val user = withAccessToken { api.getMe(it) }
            if (user.role !in setOf("INDIVIDUAL", "HEALTH_WORKER")) {
                clearLocalSession()
                return
            }
            mutableState.value = if (expectedRole == null || user.role == expectedRole) {
                AccountState.SignedIn(user)
            } else {
                AccountState.RoleMismatch(user)
            }
        } catch (_: AuthenticationRequiredException) {
            mutableState.value = AccountState.SignedOut
        } catch (_: IOException) {
            mutableState.value = AccountState.SavedOffline
        }
    }

    suspend fun restoreOffline() {
        mutableState.value = if (hasStoredSession()) AccountState.SavedOffline else AccountState.SignedOut
    }

    suspend fun login(phoneNumber: String, password: String, expectedRole: String): BackendUser {
        this.expectedRole = expectedRole
        val session = api.login(phoneNumber, password)
        return acceptSession(session, expectedRole)
    }

    suspend fun registerDonor(
        displayName: String,
        phoneNumber: String,
        password: String,
        bloodGroup: String,
        locality: String
    ): BackendUser = acceptSession(
        api.registerDonor(displayName, phoneNumber, password, bloodGroup, locality),
        expectedRole = "INDIVIDUAL"
    )

    suspend fun registerIndividual(
        displayName: String,
        phoneNumber: String,
        password: String
    ): BackendUser = acceptSession(
        api.registerIndividual(displayName, phoneNumber, password),
        expectedRole = "INDIVIDUAL"
    )

    suspend fun createDonorProfile(bloodGroup: String, locality: String): BackendDonorProfile =
        withAccessToken { api.createDonorProfile(it, bloodGroup, locality) }

    suspend fun updateDonorProfile(
        bloodGroup: String? = null,
        locality: String? = null,
        isAvailable: Boolean? = null
    ): BackendDonorProfile = withAccessToken {
        api.updateDonorProfile(it, bloodGroup, locality, isAvailable)
    }

    suspend fun listFacilities(): List<BackendFacility> = api.listFacilities()

    suspend fun getMyDonations(): List<BackendDonorResponse> =
        withAccessToken { api.getMyDonations(it) }

    suspend fun registerHealthWorker(
        displayName: String,
        phoneNumber: String,
        password: String,
        designation: String,
        professionalId: String
    ): BackendUser = acceptSession(
        api.registerHealthWorker(displayName, phoneNumber, password, designation, professionalId),
        expectedRole = "HEALTH_WORKER"
    )

    suspend fun currentUser(): BackendUser = withAccessToken { token ->
        api.getMe(token).also { user ->
            setAccountState(user)
        }
    }

    suspend fun <T> withAccessToken(request: suspend (String) -> T): T {
        val original = readTokens() ?: throw AuthenticationRequiredException()
        return try {
            request(original.accessToken)
        } catch (error: BackendHttpException) {
            if (error.statusCode != 401) throw error
            val refreshed = refreshAccessToken(original.accessToken)
            try {
                request(refreshed)
            } catch (retryError: BackendHttpException) {
                if (retryError.statusCode != 401) throw retryError
                clearLocalSession()
                throw AuthenticationRequiredException()
            }
        }
    }

    suspend fun hasStoredSession(): Boolean = readTokens() != null

    suspend fun logout() {
        val tokens = readTokens()
        if (tokens != null) runCatching { api.logout(tokens.refreshCookie) }
        clearLocalSession()
    }

    suspend fun clearLocalSession() {
        withContext(Dispatchers.IO) { tokenStore.clear() }
        mutableState.value = AccountState.SignedOut
    }

    private suspend fun acceptSession(session: BackendSession, expectedRole: String): BackendUser {
        val user = session.user
        if (user.role != expectedRole || user.role !in setOf("INDIVIDUAL", "HEALTH_WORKER")) {
            runCatching { api.logout(session.refreshCookie) }
            throw AccountRoleMismatchException("This account does not match the role selected for this phone.")
        }
        if (user.status != "ACTIVE") {
            runCatching { api.logout(session.refreshCookie) }
            throw AccountRoleMismatchException("This account is not active yet.")
        }
        withContext(Dispatchers.IO) {
            tokenStore.write(StoredTokens(session.accessToken, session.refreshCookie))
        }
        this.expectedRole = expectedRole
        setAccountState(user)
        return user
    }

    private suspend fun refreshAccessToken(observedAccessToken: String): String =
        refreshMutex.withLock {
            val current = readTokens() ?: throw AuthenticationRequiredException()
            if (current.accessToken != observedAccessToken) return@withLock current.accessToken
            try {
                val refreshed = api.refresh(current.refreshCookie)
                withContext(Dispatchers.IO) {
                    tokenStore.write(StoredTokens(refreshed.accessToken, refreshed.refreshCookie))
                }
                setAccountState(refreshed.user)
                refreshed.accessToken
            } catch (error: BackendHttpException) {
                if (error.statusCode == 401) {
                    clearLocalSession()
                    throw AuthenticationRequiredException()
                }
                throw error
            }
        }

    private suspend fun readTokens(): StoredTokens? =
        withContext(Dispatchers.IO) { tokenStore.read() }

    private fun setAccountState(user: BackendUser) {
        mutableState.value = if (expectedRole != null && user.role != expectedRole) {
            AccountState.RoleMismatch(user)
        } else {
            AccountState.SignedIn(user)
        }
    }
}
