package com.sistermesh.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sistermesh.app.data.local.SyncEventEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Compose-facing state holder. Room-backed repositories are the source of truth;
 * the discovery and incoming-request simulators remain demo-only.
 */
class AppViewModel(private val repository: SisterMeshRepository) : ViewModel() {

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

    val incoming = mutableStateListOf<IncomingRequest>()
    val requests = mutableStateListOf<BloodRequest>()
    val receipts = mutableStateListOf<Receipt>()
    val messages = mutableStateListOf<ChatMessage>()
    val pendingEvents = mutableStateListOf<SyncEventEntity>()
    val eventsForReview = mutableStateListOf<SyncEventEntity>()

    private val started = mutableSetOf<String>()

    init {
        viewModelScope.launch {
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

    /** Demo-only discovery simulation. It persists its local state but queues no mesh event. */
    fun startDiscovery(id: String) {
        if (!started.add(id)) return
        viewModelScope.launch {
            delay(1600)
            if (request(id)?.status != RequestStatus.SEARCHING) return@launch
            repository.updateSimulatedRequest(id) { it.copy(devicesFound = 2) }
            delay(1600)
            if (request(id)?.status != RequestStatus.SEARCHING) return@launch
            repository.updateSimulatedRequest(id) {
                it.copy(
                    delivered = 2, status = RequestStatus.WAITING,
                    responses = listOf(
                        DonorResponse("A3F2", "Rumuola", "About 1.4 km", ResponseStatus.DELIVERED),
                        DonorResponse("7C1D", "Trans-Amadi", "About 2.1 km", ResponseStatus.DELIVERED)
                    )
                )
            }
            delay(2600)
            if (request(id)?.status != RequestStatus.WAITING) return@launch
            repository.updateSimulatedRequest(id) {
                it.copy(
                    status = RequestStatus.ACCEPTED,
                    responses = it.responses.map { r -> if (r.ref == "A3F2") r.copy(status = ResponseStatus.ACCEPTED) else r }
                )
            }
            delay(2000)
            repository.updateSimulatedRequest(id) {
                it.copy(responses = it.responses.map { r -> if (r.ref == "7C1D") r.copy(status = ResponseStatus.DECLINED) else r })
            }
        }
    }

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

    fun simulateIncoming() {
        viewModelScope.launch { repository.simulateIncoming(profile) }
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
            started.clear()
            onDone()
        }
    }
}
