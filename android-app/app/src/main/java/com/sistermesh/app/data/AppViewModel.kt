package com.sistermesh.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sistermesh.app.communication.MeshNetworkUiState
import com.sistermesh.app.communication.NearbyConnectionsManager
import com.sistermesh.app.data.local.DeviceInfoEntity
import com.sistermesh.app.data.local.SyncConflictEntity
import com.sistermesh.app.data.local.SyncStateEntity
import com.sistermesh.app.data.local.SyncEventEntity
import com.sistermesh.app.sync.AccountRoleMismatchException
import com.sistermesh.app.sync.AccountState
import com.sistermesh.app.sync.AuthRepository
import com.sistermesh.app.sync.BackendHttpException
import com.sistermesh.app.sync.ConnectivityStatus
import com.sistermesh.app.sync.SyncEngine
import com.sistermesh.app.sync.SyncWorkScheduler
import kotlinx.coroutines.launch

/**
 * Compose-facing state holder. Room-backed repositories are the source of truth;
 * the discovery and incoming-request simulators remain demo-only.
 */
class AppViewModel(
    private val repository: SisterMeshRepository,
    private val nearbyManager: NearbyConnectionsManager,
    private val authRepository: AuthRepository,
    private val syncEngine: SyncEngine,
    private val syncWorkScheduler: SyncWorkScheduler,
    private val connectivityStatus: ConnectivityStatus
) : ViewModel() {

    var profile by mutableStateOf<Profile?>(null); private set
    var pendingRole by mutableStateOf<Role?>(null)
    var available by mutableStateOf(true); private set
    private val languageState = mutableStateOf("English")
    var language: String
        get() = languageState.value
        set(value) {
            languageState.value = value
            if (isReady) viewModelScope.launch { repository.setLanguage(value) }
        }
    var isReady by mutableStateOf(false); private set
    var meshState by mutableStateOf(MeshNetworkUiState()); private set
    var accountState by mutableStateOf<AccountState>(AccountState.SignedOut); private set
    var deviceInfo by mutableStateOf<DeviceInfoEntity?>(null); private set
    var syncState by mutableStateOf<SyncStateEntity?>(null); private set
    var pendingSyncCount by mutableStateOf(0); private set
    var failedSyncCount by mutableStateOf(0); private set
    var conflictCount by mutableStateOf(0); private set
    var isOnline by mutableStateOf(false); private set
    var accountBusy by mutableStateOf(false); private set
    var accountError by mutableStateOf<String?>(null); private set
    var accountMessage by mutableStateOf<String?>(null)
    var facilityCatalogError by mutableStateOf<String?>(null)

    val incoming = mutableStateListOf<IncomingRequest>()
    val requests = mutableStateListOf<BloodRequest>()
    val facilityRequests = mutableStateListOf<BloodRequest>()
    val facilities = mutableStateListOf<Facility>()
    val receipts = mutableStateListOf<Receipt>()
    val messages = mutableStateListOf<ChatMessage>()
    val pendingEvents = mutableStateListOf<SyncEventEntity>()
    val eventsForReview = mutableStateListOf<SyncEventEntity>()
    val conflicts = mutableStateListOf<SyncConflictEntity>()

    init {
        viewModelScope.launch {
            launch { nearbyManager.state.collect { meshState = it } }
            repository.initialize()
            profile = repository.getProfile()
            val preferences = repository.getPreferences()
            available = preferences.first
            languageState.value = preferences.second
            isReady = true

            launch { repository.observeProfile().collect { profile = it } }
            launch { repository.observeRequests().collect { replace(requests, it) } }
            launch { repository.observeFacilityRequests().collect { replace(facilityRequests, it) } }
            launch { repository.observeFacilities().collect { replace(facilities, it) } }
            launch { repository.observeIncoming().collect { replace(incoming, it) } }
            launch { repository.observeReceipts().collect { replace(receipts, it) } }
            launch { repository.observePendingEvents().collect { replace(pendingEvents, it) } }
            launch { repository.observeEventsForReview().collect { replace(eventsForReview, it) } }
            launch { repository.observeDeviceInfo().collect { deviceInfo = it; updateSyncScheduling() } }
            launch { repository.observeSyncState().collect { syncState = it } }
            launch { repository.observePendingSyncCount().collect { pendingSyncCount = it } }
            launch { repository.observeFailedSyncCount().collect { failedSyncCount = it } }
            launch { repository.observeConflicts().collect { replace(conflicts, it) } }
            launch { repository.observeConflictCount().collect { conflictCount = it } }
            launch {
                authRepository.state.collect { state ->
                    accountState = state
                    updateSyncScheduling()
                    val user = (state as? AccountState.SignedIn)?.user ?: return@collect
                    val local = profile ?: return@collect
                    val updated = when {
                        local.role == Role.CHW &&
                            !user.healthWorkerFacilityId.isNullOrBlank() &&
                            local.facilityId != user.healthWorkerFacilityId ->
                            local.copy(facilityId = user.healthWorkerFacilityId)
                        local.role == Role.INDIVIDUAL &&
                            local.bloodGroup.isBlank() &&
                            !user.donorBloodGroup.isNullOrBlank() ->
                            local.copy(
                                bloodGroup = user.donorBloodGroup.toLocalBloodGroup(),
                                area = user.donorLocality.orEmpty()
                            )
                        else -> local
                    }
                    if (updated != local) {
                        repository.registerProfile(updated)
                        profile = updated
                    }
                    if (local.role == Role.INDIVIDUAL && local.bloodGroup.isBlank() && user.donorAvailable != null) {
                        available = user.donorAvailable
                        repository.setAvailability(available)
                    }
                }
            }
            launch {
                connectivityStatus.online.collect { online ->
                    isOnline = online
                    val expectedRole = profile?.role?.toBackendRole()
                    if (online) {
                        authRepository.restore(expectedRole)
                        runCatching {
                            repository.storeFacilities(authRepository.listFacilities().map {
                                Facility(it.id, it.name, it.locality, it.state)
                            })
                        }.onSuccess { facilityCatalogError = null }
                            .onFailure {
                                if (facilities.isEmpty()) {
                                    facilityCatalogError = "Facility list could not be refreshed. Connect to the internet to load facilities."
                                }
                            }
                    } else authRepository.restoreOffline()
                }
            }
            launch {
                repository.observeAllEvents().collect { events ->
                    events.forEach { nearbyManager.enqueue(it) }
                }
            }
        }
    }

    private fun <T> replace(destination: MutableList<T>, items: List<T>) {
        destination.clear()
        destination.addAll(items)
    }

    fun register(p: Profile, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            repository.registerProfile(p)
            profile = p
            updateSyncScheduling()
            onSaved()
        }
    }

    fun loginAccount(phoneNumber: String, password: String) {
        val expectedRole = profile?.role?.toBackendRole() ?: run {
            accountError = "Create a local individual or health-worker profile first."
            return
        }
        accountBusy = true
        accountError = null
        accountMessage = null
        viewModelScope.launch {
            try {
                authRepository.login(phoneNumber.trim(), password, expectedRole)
                accountMessage = "Account connected. Confirm the local profile link before syncing."
            } catch (error: Exception) {
                accountError = accountErrorMessage(error)
            } finally {
                accountBusy = false
            }
        }
    }

    fun registerAccount(phoneNumber: String, password: String, professionalId: String = "") {
        val localProfile = profile ?: run {
            accountError = "Create a local individual or health-worker profile first."
            return
        }
        accountBusy = true
        accountError = null
        accountMessage = null
        viewModelScope.launch {
            try {
                when (localProfile.role) {
                    Role.INDIVIDUAL -> {
                        authRepository.registerIndividual(
                        displayName = localProfile.name,
                        phoneNumber = phoneNumber.trim(),
                        password = password
                        )
                        if (localProfile.bloodGroup.isNotBlank()) {
                            authRepository.createDonorProfile(
                                bloodGroup = localProfile.bloodGroup.toBackendBloodGroup(),
                                locality = localProfile.area
                            )
                        }
                    }
                    Role.CHW -> authRepository.registerHealthWorker(
                        displayName = localProfile.name,
                        phoneNumber = phoneNumber.trim(),
                        password = password,
                        designation = localProfile.designation,
                        professionalId = professionalId.trim()
                    )
                }
                accountMessage = "Account created. Confirm the local profile link before syncing."
            } catch (error: Exception) {
                accountError = accountErrorMessage(error)
            } finally {
                accountBusy = false
            }
        }
    }

    fun linkLocalAccount() {
        val user = when (val state = accountState) {
            is AccountState.SignedIn -> state.user
            is AccountState.RoleMismatch -> state.user
            else -> {
                accountError = "Sign in before linking local records."
                return
            }
        }
        accountBusy = true
        accountError = null
        viewModelScope.launch {
            try {
                val bound = syncEngine.linkLocalDataToAccount(user)
                deviceInfo = deviceInfo?.copy(accountUserId = user.id)
                accountMessage = if (bound == 0) {
                    "This phone is linked. New local activity will be associated with this account."
                } else {
                    "$bound queued local event${if (bound == 1) "" else "s"} linked to this account."
                }
                updateSyncScheduling()
                syncWorkScheduler.syncNow()
            } catch (error: Exception) {
                accountError = accountErrorMessage(error)
            } finally {
                accountBusy = false
            }
        }
    }

    fun disconnectAccount() {
        accountBusy = true
        accountError = null
        viewModelScope.launch {
            try {
                syncWorkScheduler.cancelAll()
                authRepository.logout()
                accountMessage = "Signed out. Local records remain on this phone."
            } catch (error: Exception) {
                accountError = accountErrorMessage(error)
            } finally {
                accountBusy = false
            }
        }
    }

    fun syncNow() {
        if (accountState !is AccountState.SignedIn || !isAccountLinked) {
            accountMessage = "Connect and link an account before syncing."
            return
        }
        syncWorkScheduler.schedulePeriodic()
        syncWorkScheduler.syncNow()
        accountMessage = if (isOnline) "Sync queued." else "Sync will start when internet is available."
    }

    fun clearAccountFeedback() {
        accountError = null
        accountMessage = null
    }

    val isAccountLinked: Boolean
        get() {
            val user = (accountState as? AccountState.SignedIn)?.user ?: return false
            return profile?.role?.toBackendRole() == user.role && deviceInfo?.accountUserId == user.id
        }

    private fun eventOwnerAccountId(): String? =
        (accountState as? AccountState.SignedIn)?.user?.id?.takeIf { it == deviceInfo?.accountUserId }

    private fun scheduleSyncAfterLocalChange() {
        if (eventOwnerAccountId() != null) {
            syncWorkScheduler.schedulePeriodic()
            syncWorkScheduler.syncNow()
        }
    }

    private fun updateSyncScheduling() {
        when (val state = accountState) {
            is AccountState.SignedIn -> {
                val localProfile = profile
                if (localProfile != null && localProfile.role.toBackendRole() == state.user.role &&
                    deviceInfo?.accountUserId == state.user.id
                ) {
                    syncWorkScheduler.schedulePeriodic()
                } else {
                    syncWorkScheduler.cancelPeriodic()
                }
            }
            is AccountState.RoleMismatch -> syncWorkScheduler.cancelPeriodic()
            else -> Unit
        }
    }

    private fun accountErrorMessage(error: Exception): String = when (error) {
        is AccountRoleMismatchException -> error.message ?: "The account role does not match."
        is BackendHttpException -> error.message
        else -> error.message ?: "Could not connect to the account."
    }

    private fun Role.toBackendRole(): String = when (this) {
        Role.INDIVIDUAL -> "INDIVIDUAL"
        Role.CHW -> "HEALTH_WORKER"
    }

    private fun String.toBackendBloodGroup(): String = when (uppercase()) {
        "A+" -> "A_POSITIVE"
        "A-" -> "A_NEGATIVE"
        "B+" -> "B_POSITIVE"
        "B-" -> "B_NEGATIVE"
        "AB+" -> "AB_POSITIVE"
        "AB-" -> "AB_NEGATIVE"
        "O+" -> "O_POSITIVE"
        "O-" -> "O_NEGATIVE"
        else -> throw IllegalArgumentException("Choose a valid blood group before creating an account.")
    }

    private fun String.toLocalBloodGroup(): String = when (this) {
        "A_POSITIVE" -> "A+"
        "A_NEGATIVE" -> "A-"
        "B_POSITIVE" -> "B+"
        "B_NEGATIVE" -> "B-"
        "AB_POSITIVE" -> "AB+"
        "AB_NEGATIVE" -> "AB-"
        "O_POSITIVE" -> "O+"
        "O_NEGATIVE" -> "O-"
        else -> ""
    }

    fun updateAvailability(v: Boolean) {
        available = v
        viewModelScope.launch {
            repository.setAvailability(v)
            scheduleSyncAfterLocalChange()
        }
    }

    fun registerAsDonor(group: String, area: String, onSaved: () -> Unit = {}) {
        val current = profile ?: return
        if (current.role != Role.INDIVIDUAL) {
            accountError = "Donor participation requires an individual account."
            return
        }
        viewModelScope.launch {
            val updated = current.copy(bloodGroup = group, area = area.trim())
            repository.registerProfile(updated)
            profile = updated
            val user = (accountState as? AccountState.SignedIn)?.user
            if (user?.role == "INDIVIDUAL" && isOnline) {
                runCatching {
                    if (user.donorBloodGroup.isNullOrBlank()) {
                        authRepository.createDonorProfile(group.toBackendBloodGroup(), area.trim())
                    } else {
                        authRepository.updateDonorProfile(
                            bloodGroup = group.toBackendBloodGroup(),
                            locality = area.trim(),
                            isAvailable = available
                        )
                    }
                }.onFailure { accountError = accountErrorMessage(it as? Exception ?: Exception(it)) }
            }
            scheduleSyncAfterLocalChange()
            onSaved()
        }
    }

    fun request(id: String) = requests.firstOrNull { it.id == id }
        ?: facilityRequests.firstOrNull { it.id == id }
    fun incomingById(id: String) = incoming.firstOrNull { it.id == id }
    fun receipt(id: String) = receipts.firstOrNull { it.id == id }

    // ---------- CHW ----------

    fun createRequest(
        group: String,
        urgency: Urgency,
        units: Int,
        facilityId: String,
        isForSelf: Boolean,
        beneficiaryName: String?,
        beneficiaryRelationship: String?,
        additionalInfo: String?,
        onSaved: (String) -> Unit
    ) {
        viewModelScope.launch {
            val created = repository.createRequest(
                group,
                urgency,
                units,
                facilityId,
                isForSelf,
                beneficiaryName,
                beneficiaryRelationship,
                additionalInfo,
                eventOwnerAccountId()
            )
            scheduleSyncAfterLocalChange()
            if (profile?.role == Role.CHW) {
                facilityRequests.removeAll { it.id == created.id }
                facilityRequests.add(0, created)
            } else {
                requests.removeAll { it.id == created.id }
                requests.add(0, created)
            }
            onSaved(created.id)
        }
    }

    @Deprecated("Use the unified request form with an active facility and beneficiary details.")
    fun createRequest(
        group: String,
        urgency: Urgency,
        units: Int,
        note: String,
        onSaved: (String) -> Unit
    ) {
        val facilityId = profile?.facilityId.orEmpty()
        createRequest(group, urgency, units, facilityId, false, null, null, note, onSaved)
    }

    fun updateSubmission(
        request: BloodRequest,
        group: String,
        urgency: Urgency,
        units: Int,
        isForSelf: Boolean,
        beneficiaryName: String?,
        beneficiaryRelationship: String?,
        additionalInfo: String?
    ) {
        viewModelScope.launch {
            val expectedUpdatedAt = request.serverUpdatedAt ?: run {
                accountError = "Sync this request before sending an update."
                return@launch
            }
            repository.updateSubmission(
                request.id,
                group,
                urgency,
                units,
                isForSelf,
                beneficiaryName,
                beneficiaryRelationship,
                additionalInfo,
                expectedUpdatedAt,
                eventOwnerAccountId()
            )
            scheduleSyncAfterLocalChange()
        }
    }

    fun reviewRequest(requestId: String, action: String, reason: String?, expectedUpdatedAt: String) {
        viewModelScope.launch {
            repository.reviewRequest(requestId, action, reason, expectedUpdatedAt, eventOwnerAccountId())
            scheduleSyncAfterLocalChange()
        }
    }

    fun startDiscovery(@Suppress("UNUSED_PARAMETER") id: String) = nearbyManager.flushPending()

    fun approveNearbyConnection(endpointId: String) = nearbyManager.approveConnection(endpointId)
    fun rejectNearbyConnection(endpointId: String) = nearbyManager.rejectConnection(endpointId)

    fun cancelRequest(id: String) {
        viewModelScope.launch {
            repository.cancelRequest(id, eventOwnerAccountId())
            scheduleSyncAfterLocalChange()
        }
    }

    fun updateRequest(
        requestId: String,
        bloodGroup: String?,
        urgency: Urgency?,
        units: Int?,
        expiresAt: String?,
        expectedUpdatedAt: String?
    ) {
        viewModelScope.launch {
            repository.updateRequest(
                requestId,
                bloodGroup,
                urgency,
                units,
                expiresAt,
                expectedUpdatedAt,
                eventOwnerAccountId()
            )
            scheduleSyncAfterLocalChange()
        }
    }

    fun confirmDonation(requestId: String, donorRef: String, onSaved: (String) -> Unit) {
        viewModelScope.launch {
            onSaved(repository.confirmDonation(requestId, donorRef, eventOwnerAccountId()))
            scheduleSyncAfterLocalChange()
        }
    }

    // ---------- Donor ----------

    fun respond(id: String, accept: Boolean, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            repository.respond(id, accept, eventOwnerAccountId())
            scheduleSyncAfterLocalChange()
            onSaved()
        }
    }

    fun withdrawResponse(id: String) {
        viewModelScope.launch {
            repository.withdrawResponse(id, eventOwnerAccountId())
            scheduleSyncAfterLocalChange()
        }
    }

    /** Demo only: stands in for a server/facility confirmation on the donor device. */
    fun simulateFacilityConfirmation(incomingId: String, onSaved: (String) -> Unit) {
        viewModelScope.launch {
            onSaved(repository.simulateFacilityConfirmation(incomingId))
        }
    }

    // ---------- Coordination chat ----------

    fun send(text: String, mine: Boolean = true) { messages.add(ChatMessage(text, mine)) }
    fun clearMessages() = messages.clear()

    fun reset(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repository.resetDemoProfile()
            profile = null
            pendingRole = null
            available = true
            languageState.value = "English"
            messages.clear()
            onDone()
        }
    }
}
