package com.sistermesh.app.communication

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.Manifest
import com.sistermesh.app.data.local.TrustedMeshPeerEntity
import com.sistermesh.app.data.local.SyncEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

data class MeshNetworkUiState(
    val scanning: Boolean = false,
    val permissionsGranted: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val wifiEnabled: Boolean = false,
    val connectionStatus: NearbyConnectionStatus = NearbyConnectionStatus.NOT_CONFIGURED,
    val nearbyDeviceCount: Int = 0,
    val connectedDeviceIds: Set<String> = emptySet(),
    val pendingConnections: List<PendingNearbyConnection> = emptyList(),
    val trustedPeers: List<TrustedMeshPeerEntity> = emptyList(),
    val pendingMessages: Int = 0,
    val requestDeliveryStates: Map<String, String> = emptyMap(),
    val messageDeliveryStates: Map<String, String> = emptyMap(),
    val statusText: String = "Nearby connections are paused.",
    val lastError: String? = null
)

enum class NearbyConnectionStatus {
    NOT_CONFIGURED,
    CONNECTING,
    CONNECTED,
    UNAVAILABLE,
    PAUSED
}

internal fun nearbySetupStatus(
    foreground: Boolean,
    permissionsGranted: Boolean,
    bluetoothEnabled: Boolean,
    wifiEnabled: Boolean
): NearbyConnectionStatus = when {
    !foreground -> NearbyConnectionStatus.PAUSED
    !permissionsGranted -> NearbyConnectionStatus.NOT_CONFIGURED
    !bluetoothEnabled && !wifiEnabled -> NearbyConnectionStatus.UNAVAILABLE
    else -> NearbyConnectionStatus.CONNECTING
}

class NearbyConnectionsManager(
    context: Context,
    private val repository: MeshRepository,
    private val transport: NearbyTransport
) : NearbyTransportListener {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val flushMutex = Mutex()
    private val mutableState = MutableStateFlow(MeshNetworkUiState())
    val state: StateFlow<MeshNetworkUiState> = mutableState.asStateFlow()

    private val discovered = ConcurrentHashMap<String, String>()
    private val requestedEndpoints = ConcurrentHashMap.newKeySet<String>()
    private val connectedEndpoints = ConcurrentHashMap.newKeySet<String>()
    private val endpointDeviceIds = ConcurrentHashMap<String, String>()
    private var foreground = false
    private var starting = false
    private var running = false
    private var retryJob: Job? = null

    init {
        scope.launch {
            repository.observeTrustedPeers().collectLatest { peers ->
                mutableState.value = mutableState.value.copy(trustedPeers = peers)
            }
        }
        scope.launch {
            repository.observeMessages().collectLatest { rows ->
                val localId = runCatching { repository.localDeviceId() }.getOrNull()
                val statuses = rows.asSequence()
                    .filter { it.originDeviceId == localId && it.eventType == "request.create" }
                    .mapNotNull { row -> row.localEntityId?.let { it to row.deliveryState } }
                    .toMap()
                val eventStatuses = rows.asSequence()
                    .filter { it.originDeviceId == localId }
                    .mapNotNull { row ->
                        row.localEntityId?.let { "${row.eventType}:$it" to row.deliveryState }
                    }
                    .toMap()
                mutableState.value = mutableState.value.copy(
                    requestDeliveryStates = statuses,
                    messageDeliveryStates = eventStatuses
                )
            }
        }
    }

    suspend fun enqueue(event: SyncEventEntity) {
        runCatching { repository.ensureOutgoing(event) }
            .onFailure { updateState(lastError = it.message ?: "Could not queue this nearby message.") }
        refreshPendingCount()
        safelyFlushOutbox()
    }

    fun flushPending() {
        scope.launch { safelyFlushOutbox() }
    }

    fun onForeground() {
        foreground = true
        refreshEnvironment()
        scope.launch {
            repository.cleanupExpired()
            repository.recoverInterruptedTransfers()
            safelyFlushOutbox()
        }
        if (retryJob == null) {
            retryJob = scope.launch {
                while (foreground) {
                    delay(60_000)
                    if (foreground) safelyFlushOutbox()
                }
            }
        }
    }

    fun onBackground() {
        foreground = false
        retryJob?.cancel()
        retryJob = null
        stopTransport()
        updateState(
            scanning = false,
            nearbyDeviceCount = 0,
            connectedDeviceIds = emptySet(),
            pendingConnections = emptyList(),
            connectionStatus = NearbyConnectionStatus.PAUSED,
            statusText = "Nearby connections pause when SisterMesh is not in the foreground."
        )
    }

    fun refreshEnvironment() {
        val permissionGranted = NearbyPermissions.hasAll(appContext)
        val bluetoothEnabled = isBluetoothEnabled()
        val wifiEnabled = isWifiEnabled()
        updateState(
            permissionsGranted = permissionGranted,
            bluetoothEnabled = bluetoothEnabled,
            wifiEnabled = wifiEnabled
        )
        reconcile()
    }

    fun retry() {
        if (!foreground) {
            updateState(
                connectionStatus = NearbyConnectionStatus.PAUSED,
                statusText = "Open SisterMesh in the foreground to search for nearby phones."
            )
            return
        }
        stopTransport()
        updateState(
            scanning = false,
            nearbyDeviceCount = 0,
            connectedDeviceIds = emptySet(),
            pendingConnections = emptyList(),
            lastError = null
        )
        refreshEnvironment()
    }

    fun approveConnection(endpointId: String) {
        val pending = mutableState.value.pendingConnections.any { it.endpointId == endpointId }
        if (!pending) return
        transport.acceptConnection(endpointId)
        updateState(pendingConnections = mutableState.value.pendingConnections.filterNot { it.endpointId == endpointId })
    }

    fun rejectConnection(endpointId: String) {
        transport.rejectConnection(endpointId)
        requestedEndpoints.remove(endpointId)
        updateState(pendingConnections = mutableState.value.pendingConnections.filterNot { it.endpointId == endpointId })
    }

    override fun onStarted() {
        starting = false
        running = true
        updateState(
            scanning = true,
            connectionStatus = NearbyConnectionStatus.CONNECTING,
            lastError = null,
            statusText = "Searching for nearby SisterMesh phones. Internet access is not required."
        )
        scope.launch { safelyFlushOutbox() }
    }

    override fun onStartFailed(message: String) {
        runCatching { transport.stop() }
        starting = false
        running = false
        updateState(
            scanning = false,
            connectionStatus = NearbyConnectionStatus.UNAVAILABLE,
            lastError = message,
            statusText = "Nearby connection is unavailable. Check device settings and retry."
        )
    }

    override fun onEndpointFound(endpointId: String, endpointName: String) {
        discovered[endpointId] = endpointName
        updateState(
            nearbyDeviceCount = discovered.size,
            connectionStatus = NearbyConnectionStatus.CONNECTING,
            statusText = "A nearby phone was found. Establishing a verified connection."
        )
        if (requestedEndpoints.add(endpointId) && !connectedEndpoints.contains(endpointId)) {
            transport.requestConnection(endpointId)
        }
    }

    override fun onEndpointLost(endpointId: String) {
        discovered.remove(endpointId)
        if (!connectedEndpoints.contains(endpointId)) requestedEndpoints.remove(endpointId)
        updateState(nearbyDeviceCount = discovered.size)
    }

    override fun onConnectionInitiated(connection: PendingNearbyConnection) {
        val pending = mutableState.value.pendingConnections
            .filterNot { it.endpointId == connection.endpointId } + connection
        updateState(
            pendingConnections = pending,
            connectionStatus = NearbyConnectionStatus.CONNECTING,
            statusText = "Compare the security numbers on both phones before connecting."
        )
    }

    override fun onConnectionAccepted(endpointId: String) {
        requestedEndpoints.remove(endpointId)
        connectedEndpoints.add(endpointId)
        updateState(
            connectionStatus = NearbyConnectionStatus.CONNECTING,
            statusText = "Verifying the nearby device before sharing."
        )
        scope.launch {
            runCatching {
                val identity = repository.identityForLocalDevice()
                transport.send(endpointId, MeshCodec.encodeHello(identity)) { ok, error ->
                    if (!ok) updateState(lastError = error, statusText = "Device identity exchange failed.")
                }
            }.onFailure {
                updateState(lastError = it.message, statusText = "Could not verify this device.")
            }
        }
    }

    override fun onConnectionFailed(endpointId: String, message: String) {
        requestedEndpoints.remove(endpointId)
        connectedEndpoints.remove(endpointId)
        endpointDeviceIds.remove(endpointId)
        updateState(
            connectedDeviceIds = endpointDeviceIds.values.toSet(),
            connectionStatus = if (running) NearbyConnectionStatus.CONNECTING else NearbyConnectionStatus.UNAVAILABLE,
            pendingConnections = mutableState.value.pendingConnections.filterNot { it.endpointId == endpointId },
            lastError = message
        )
    }

    override fun onDisconnected(endpointId: String) {
        connectedEndpoints.remove(endpointId)
        endpointDeviceIds.remove(endpointId)
        requestedEndpoints.remove(endpointId)
        updateState(
            connectedDeviceIds = endpointDeviceIds.values.toSet(),
            connectionStatus = if (endpointDeviceIds.isEmpty()) {
                if (running) NearbyConnectionStatus.CONNECTING else NearbyConnectionStatus.PAUSED
            } else {
                NearbyConnectionStatus.CONNECTED
            },
            statusText = if (endpointDeviceIds.isEmpty()) "No trusted devices connected." else "A nearby device disconnected."
        )
    }

    override fun onPayload(endpointId: String, bytes: ByteArray) {
        scope.launch {
            val senderId = endpointDeviceIds[endpointId]
            val wirePacket = runCatching { MeshCodec.decode(bytes) }.getOrElse {
                updateState(lastError = it.message ?: "Invalid nearby packet.")
                return@launch
            }
            when (wirePacket) {
                is MeshWirePacket.Hello -> {
                    val trusted = repository.pinDirectPeer(wirePacket.identity)
                    if (!trusted) {
                        updateState(lastError = "The device identity does not match its trusted key.")
                        transport.disconnect(endpointId)
                        return@launch
                    }
                    endpointDeviceIds[endpointId] = wirePacket.identity.deviceId
                    updateState(
                        connectedDeviceIds = endpointDeviceIds.values.toSet(),
                        connectionStatus = NearbyConnectionStatus.CONNECTED,
                        lastError = null,
                        statusText = "Connected to a trusted nearby device."
                    )
                    safelyFlushOutbox()
                }
                is MeshWirePacket.Event -> {
                    if (senderId == null) return@launch
                    val result = runCatching { repository.receiveEvent(wirePacket.event, senderId) }.getOrElse {
                        updateState(lastError = it.message ?: "Could not store nearby message.")
                        return@launch
                    }
                    result.acknowledgement?.let { sendAcknowledgement(endpointId, it) }
                    if (result.error != null) updateState(lastError = result.error)
                    refreshPendingCount()
                    safelyFlushOutbox()
                }
                is MeshWirePacket.Acknowledgement -> {
                    if (senderId == null) return@launch
                    val forwarded = runCatching {
                        repository.receiveAcknowledgement(wirePacket.ack, senderId)
                    }.getOrElse {
                        updateState(lastError = it.message ?: "Could not process nearby acknowledgement.")
                        return@launch
                    }
                    forwarded?.let { ack ->
                        endpointForDevice(ack.nextHopDeviceId)?.let { nextEndpoint ->
                            sendAcknowledgement(nextEndpoint, ack)
                        }
                    }
                    refreshPendingCount()
                    safelyFlushOutbox()
                }
            }
        }
    }

    private fun reconcile() {
        when (nearbySetupStatus(
            foreground = foreground,
            permissionsGranted = mutableState.value.permissionsGranted,
            bluetoothEnabled = mutableState.value.bluetoothEnabled,
            wifiEnabled = mutableState.value.wifiEnabled
        )) {
            NearbyConnectionStatus.PAUSED -> {
            stopTransport()
            updateState(
                scanning = false,
                connectionStatus = NearbyConnectionStatus.PAUSED,
                statusText = "Nearby search starts while SisterMesh is in the foreground."
            )
            return
            }
            NearbyConnectionStatus.NOT_CONFIGURED -> {
            stopTransport()
            updateState(
                scanning = false,
                connectionStatus = NearbyConnectionStatus.NOT_CONFIGURED,
                lastError = null,
                statusText = "Allow nearby-device permissions to connect to phones."
            )
            return
            }
            NearbyConnectionStatus.UNAVAILABLE -> {
            stopTransport()
            val message = "Turn on Bluetooth or Wi-Fi to search for nearby phones. Internet is not required."
            updateState(
                scanning = false,
                connectionStatus = NearbyConnectionStatus.UNAVAILABLE,
                lastError = message,
                statusText = message
            )
            return
            }
            NearbyConnectionStatus.CONNECTED -> return
            NearbyConnectionStatus.CONNECTING -> Unit
        }
        if (running || starting) return
        starting = true
        updateState(
            connectionStatus = NearbyConnectionStatus.CONNECTING,
            lastError = null,
            statusText = "Starting nearby discovery."
        )
        runCatching { transport.start(this) }.onFailure {
            starting = false
            updateState(
                scanning = false,
                connectionStatus = NearbyConnectionStatus.UNAVAILABLE,
                lastError = it.message,
                statusText = "Nearby connection is unavailable. Check device settings and retry."
            )
        }
    }

    private fun stopTransport() {
        if (running || starting) transport.stop()
        starting = false
        running = false
        discovered.clear()
        requestedEndpoints.clear()
        connectedEndpoints.clear()
        endpointDeviceIds.clear()
    }

    private suspend fun flushOutbox() = flushMutex.withLock {
        refreshPendingCount()
        if (!running) return
        val connected = endpointDeviceIds.entries.toList()
        if (connected.isEmpty()) return

        for (message in repository.pendingMessages()) {
            for ((endpointId, peerId) in connected) {
                val bytes = repository.prepareEventForPeer(message.messageId, peerId) ?: continue
                repository.recordEventAttempt(message.messageId, peerId)
                transport.send(endpointId, bytes) { ok, error ->
                    scope.launch {
                        if (ok) repository.recordEventTransmitted(message.messageId, peerId)
                        else repository.recordEventSendFailure(message.messageId, peerId, error ?: "Nearby send failed.")
                        refreshPendingCount()
                    }
                }
            }
        }

        for ((endpointId, peerId) in connected) {
            for (ackRow in repository.pendingAcknowledgementsFor(peerId)) {
                val packet = repository.acknowledgementPacket(ackRow) ?: continue
                repository.recordAcknowledgementAttempt(packet.acknowledgementId)
                transport.send(endpointId, MeshCodec.encodeAck(packet)) { ok, _ ->
                    scope.launch {
                        if (ok) repository.recordAcknowledgementTransmitted(packet.acknowledgementId)
                        else repository.recordAcknowledgementFailure(packet.acknowledgementId)
                    }
                }
            }
        }
        refreshPendingCount()
    }

    private suspend fun safelyFlushOutbox() {
        runCatching { flushOutbox() }.onFailure {
            updateState(lastError = it.message ?: "Could not process the nearby queue.")
        }
    }

    private fun sendAcknowledgement(endpointId: String, packet: MeshAckPacket) {
        scope.launch {
            repository.recordAcknowledgementAttempt(packet.acknowledgementId)
            transport.send(endpointId, MeshCodec.encodeAck(packet)) { ok, _ ->
                scope.launch {
                    if (ok) repository.recordAcknowledgementTransmitted(packet.acknowledgementId)
                    else repository.recordAcknowledgementFailure(packet.acknowledgementId)
                }
            }
        }
    }

    private fun endpointForDevice(deviceId: String?): String? =
        deviceId?.let { id -> endpointDeviceIds.entries.firstOrNull { it.value == id }?.key }

    private suspend fun refreshPendingCount() {
        updateState(pendingMessages = repository.pendingMessages().size)
    }

    private fun updateState(
        scanning: Boolean? = null,
        permissionsGranted: Boolean? = null,
        bluetoothEnabled: Boolean? = null,
        wifiEnabled: Boolean? = null,
        connectionStatus: NearbyConnectionStatus? = null,
        nearbyDeviceCount: Int? = null,
        connectedDeviceIds: Set<String>? = null,
        pendingConnections: List<PendingNearbyConnection>? = null,
        pendingMessages: Int? = null,
        statusText: String? = null,
        lastError: String? = mutableState.value.lastError
    ) {
        val current = mutableState.value
        mutableState.value = current.copy(
            scanning = scanning ?: current.scanning,
            permissionsGranted = permissionsGranted ?: current.permissionsGranted,
            bluetoothEnabled = bluetoothEnabled ?: current.bluetoothEnabled,
            wifiEnabled = wifiEnabled ?: current.wifiEnabled,
            connectionStatus = connectionStatus ?: current.connectionStatus,
            nearbyDeviceCount = nearbyDeviceCount ?: current.nearbyDeviceCount,
            connectedDeviceIds = connectedDeviceIds ?: current.connectedDeviceIds,
            pendingConnections = pendingConnections ?: current.pendingConnections,
            pendingMessages = pendingMessages ?: current.pendingMessages,
            statusText = statusText ?: current.statusText,
            lastError = lastError
        )
    }

    private fun isBluetoothEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return false
        return runCatching { BluetoothAdapter.getDefaultAdapter()?.isEnabled ?: false }.getOrDefault(false)
    }

    private fun isWifiEnabled(): Boolean = runCatching {
        (appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled == true
    }.getOrDefault(false)
}
