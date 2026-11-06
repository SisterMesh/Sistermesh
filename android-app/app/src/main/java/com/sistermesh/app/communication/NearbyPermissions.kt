package com.sistermesh.app.communication

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object NearbyPermissions {
    fun required(): List<String> = when {
        Build.VERSION.SDK_INT >= 33 -> listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES
        )
        Build.VERSION.SDK_INT >= 31 -> listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        else -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun missing(context: Context): List<String> =
        required().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    fun hasAll(context: Context) = missing(context).isEmpty()
}
