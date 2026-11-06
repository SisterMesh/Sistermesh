package com.sistermesh.app.communication

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy

data class PendingNearbyConnection(
    val endpointId: String,
    val endpointName: String,
    val authenticationDigits: String,
    val incoming: Boolean
)

interface NearbyTransportListener {
    fun onStarted()
    fun onStartFailed(message: String)
    fun onEndpointFound(endpointId: String, endpointName: String)
    fun onEndpointLost(endpointId: String)
    fun onConnectionInitiated(connection: PendingNearbyConnection)
    fun onConnectionAccepted(endpointId: String)
    fun onConnectionFailed(endpointId: String, message: String)
    fun onDisconnected(endpointId: String)
    fun onPayload(endpointId: String, bytes: ByteArray)
}

interface NearbyTransport {
    fun start(listener: NearbyTransportListener)
    fun requestConnection(endpointId: String)
    fun acceptConnection(endpointId: String)
    fun rejectConnection(endpointId: String)
    fun send(endpointId: String, bytes: ByteArray, result: (Boolean, String?) -> Unit)
    fun disconnect(endpointId: String)
    fun stop()
}

class GoogleNearbyTransport(context: Context) : NearbyTransport {
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext)
    private val serviceId = context.applicationContext.packageName
    private var listener: NearbyTransportListener? = null

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            listener?.onPayload(endpointId, bytes)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            listener?.onConnectionInitiated(
                PendingNearbyConnection(
                    endpointId = endpointId,
                    endpointName = connectionInfo.endpointName.orEmpty().take(40),
                    authenticationDigits = connectionInfo.authenticationDigits.orEmpty(),
                    incoming = connectionInfo.isIncomingConnection
                )
            )
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                listener?.onConnectionAccepted(endpointId)
            } else {
                listener?.onConnectionFailed(endpointId, "Nearby connection was not accepted.")
            }
        }

        override fun onDisconnected(endpointId: String) {
            listener?.onDisconnected(endpointId)
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: com.google.android.gms.nearby.connection.DiscoveredEndpointInfo) {
            listener?.onEndpointFound(endpointId, info.endpointName.orEmpty().take(40))
        }

        override fun onEndpointLost(endpointId: String) {
            listener?.onEndpointLost(endpointId)
        }
    }

    override fun start(listener: NearbyTransportListener) {
        this.listener = listener
        val advertising = client.startAdvertising(
            "SisterMesh",
            serviceId,
            lifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        )
        val discovery = client.startDiscovery(
            serviceId,
            discoveryCallback,
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        )
        advertising.addOnFailureListener { listener.onStartFailed("Advertising could not start: ${it.message.orEmpty()}") }
        discovery.addOnFailureListener { listener.onStartFailed("Nearby discovery could not start: ${it.message.orEmpty()}") }
        advertising.addOnSuccessListener {
            discovery.addOnSuccessListener { listener.onStarted() }
        }
    }

    override fun requestConnection(endpointId: String) {
        client.requestConnection("SisterMesh", endpointId, lifecycleCallback)
            .addOnFailureListener { listener?.onConnectionFailed(endpointId, "Connection request failed: ${it.message.orEmpty()}") }
    }

    override fun acceptConnection(endpointId: String) {
        client.acceptConnection(endpointId, payloadCallback)
            .addOnFailureListener { listener?.onConnectionFailed(endpointId, "Could not accept connection: ${it.message.orEmpty()}") }
    }

    override fun rejectConnection(endpointId: String) {
        client.rejectConnection(endpointId)
    }

    override fun send(endpointId: String, bytes: ByteArray, result: (Boolean, String?) -> Unit) {
        if (bytes.size > MESH_MAX_FRAME_BYTES) {
            result(false, "Message exceeds the supported size.")
            return
        }
        client.sendPayload(endpointId, Payload.fromBytes(bytes))
            .addOnSuccessListener { result(true, null) }
            .addOnFailureListener { result(false, it.message ?: "Nearby could not queue the payload.") }
    }

    override fun disconnect(endpointId: String) {
        client.disconnectFromEndpoint(endpointId)
    }

    override fun stop() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        listener = null
    }
}
