package com.sistermesh.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sistermesh.app.communication.MeshNetworkUiState
import com.sistermesh.app.communication.NearbyConnectionsManager
import com.sistermesh.app.data.local.SyncEventEntity
import kotlinx.coroutines.launch

/**
 * Compose-facing state holder. Room-backed repositories are the source of truth;
 * the discovery and incoming-request simulators remain demo-only.
 */
class AppViewModel(
    private val repository: SisterMeshRepository,
    private val nearbyManager: NearbyConnectionsManager
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

    val incoming = mutableStateListOf<IncomingRequest>()
    val requests = mutableStateListOf<BloodRequest>()
    val receipts = mutableStateListOf<Receipt>()
    val messages = mutableStateListOf<ChatMessage>()
    val pendingEvents = mutableStateListOf<SyncEventEntity>()
    val eventsForReview = mutableStateListOf<SyncEventEntity>()

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
            launch { repository.observeIncoming().collect { replace(incoming, it) } }
            launch { repository.observeReceipts().collect { replace(receipts, it) } }
            launch { repository.observePendingEvents().collect { replace(pendingEvents, it) } }
            launch { repository.observeEventsForReview().collect { replace(eventsForReview, it) } }
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
            onSaved()
        }
    }

    fun updateAvailability(v: Boolean) {
        available = v
        viewModelScope.launch { repository.setAvailability(v) }
    }

    fun request(id: String) = requests.firstOrNull { it.id == id }
    fun incomingById(id: String) = incoming.firstOrNull { it.id == id }
    fun receipt(id: String) = receipts.firstOrNull { it.id == id }

    // ---------- CHW ----------

    fun createRequest(
        group: String,
        urgency: Urgency,
        units: Int,
        note: String,
        onSaved: (String) -> Unit
    ) {
        viewModelScope.launch {
            val created = repository.createRequest(group, urgency, units, note)
            requests.removeAll { it.id == created.id }
            requests.add(0, created)
            onSaved(created.id)
        }
    }

    fun startDiscovery(@Suppress("UNUSED_PARAMETER") id: String) = nearbyManager.flushPending()

    fun approveNearbyConnection(endpointId: String) = nearbyManager.approveConnection(endpointId)
    fun rejectNearbyConnection(endpointId: String) = nearbyManager.rejectConnection(endpointId)

    fun cancelRequest(id: String) {
        viewModelScope.launch { repository.cancelRequest(id) }
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
            repository.updateRequest(requestId, bloodGroup, urgency, units, expiresAt, expectedUpdatedAt)
        }
    }

    fun confirmDonation(requestId: String, donorRef: String, onSaved: (String) -> Unit) {
        viewModelScope.launch {
            onSaved(repository.confirmDonation(requestId, donorRef))
        }
    }

    // ---------- Donor ----------

    fun respond(id: String, accept: Boolean, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            repository.respond(id, accept)
            onSaved()
        }
    }

    fun withdrawResponse(id: String) {
        viewModelScope.launch { repository.withdrawResponse(id) }
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
