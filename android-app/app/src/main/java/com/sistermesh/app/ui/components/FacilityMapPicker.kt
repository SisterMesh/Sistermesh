package com.sistermesh.app.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

@Composable
fun FacilityMapPicker(
    latitude: Double?,
    longitude: Double?,
    onPinSelected: (Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val latestPick = rememberUpdatedState(onPinSelected)
    val mapView = remember(context) {
        createFacilityMap(context) { lat, lon -> latestPick.value(lat, lon) }
    }
    DisposableEffect(mapView) {
        onDispose { mapView.onDetach() }
    }
    Column(modifier) {
        Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 1.dp) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxWidth().height(230.dp),
                update = { map ->
                    val hasPin = latitude != null && longitude != null
                    val currentMarker = map.overlays.filterIsInstance<Marker>()
                    map.overlays.removeAll(currentMarker.toSet())
                    if (hasPin) {
                        map.overlays.add(Marker(map).apply {
                            position = GeoPoint(latitude!!, longitude!!)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            title = "Donation point"
                        })
                        map.controller.setCenter(GeoPoint(latitude!!, longitude!!))
                    }
                    map.invalidate()
                }
            )
        }
        Text(
            "Long-press the map to set a pin. Cached map tiles work offline; address and coordinates can also be entered manually.",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Map data © OpenStreetMap contributors",
            style = MaterialTheme.typography.labelSmall
        )
    }
}

private fun createFacilityMap(context: Context, onPinSelected: (Double, Double) -> Unit): MapView {
    Configuration.getInstance().userAgentValue = context.packageName
    return MapView(context).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        controller.setZoom(5.5)
        controller.setCenter(GeoPoint(9.0820, 8.6753))
        overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(point: GeoPoint?): Boolean = false

            override fun longPressHelper(point: GeoPoint?): Boolean {
                point ?: return false
                onPinSelected(point.latitude, point.longitude)
                return true
            }
        }))
    }
}
