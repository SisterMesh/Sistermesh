package com.sistermesh.app.communication

import org.junit.Assert.assertEquals
import org.junit.Test

class NearbyStatusPolicyTest {
    @Test
    fun bluetoothRadioAloneAllowsNearbyAttemptWhenPermissionsAreGranted() {
        assertEquals(
            NearbyConnectionStatus.CONNECTING,
            nearbySetupStatus(
                foreground = true,
                permissionsGranted = true,
                bluetoothEnabled = true,
                wifiEnabled = false
            )
        )
    }

    @Test
    fun wifiCanAttemptNearbyWithoutBluetooth() {
        assertEquals(
            NearbyConnectionStatus.CONNECTING,
            nearbySetupStatus(
                foreground = true,
                permissionsGranted = true,
                bluetoothEnabled = false,
                wifiEnabled = true
            )
        )
    }

    @Test
    fun missingPermissionIsNotConfiguredAndNoEnabledRadioIsUnavailable() {
        assertEquals(
            NearbyConnectionStatus.NOT_CONFIGURED,
            nearbySetupStatus(true, false, true, true)
        )
        assertEquals(
            NearbyConnectionStatus.UNAVAILABLE,
            nearbySetupStatus(true, true, false, false)
        )
    }

    @Test
    fun nearbySearchIsPausedWhenAppIsInBackground() {
        assertEquals(
            NearbyConnectionStatus.PAUSED,
            nearbySetupStatus(false, true, true, false)
        )
    }
}
