package com.sistermesh.app.navigation

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sistermesh.app.data.AppViewModel
import com.sistermesh.app.ui.screens.donor.*
import com.sistermesh.app.ui.screens.onboarding.*
import com.sistermesh.app.ui.screens.shared.*

private fun NavBackStackEntry.arg(key: String) = arguments?.getString(key).orEmpty()

@Composable
fun AppNav(vm: AppViewModel) {
    val nav = rememberNavController()
    val pendingConnection = vm.meshState.pendingConnections.firstOrNull()

    NavHost(navController = nav, startDestination = "splash") {
        composable("splash") {
            SplashScreen(isReady = vm.isReady) {
                nav.navigate(if (vm.profile == null) "welcome" else "home") {
                    popUpTo("splash") { inclusive = true }
                }
            }
        }
        composable("welcome") { WelcomeScreen { nav.navigate("role") } }
        composable("role") {
            UseModeScreen(vm, onBack = { nav.popBackStack() }) { mode ->
                nav.navigate("register/${mode.name}")
            }
        }
        composable("register/{mode}") {
            val mode = runCatching { com.sistermesh.app.data.UseMode.valueOf(it.arg("mode")) }
                .getOrDefault(com.sistermesh.app.data.UseMode.FIND_BLOOD)
            DonorRegistrationScreen(vm, mode, { nav.popBackStack() }) { nav.navigate("permissions") }
        }
        composable("permissions") {
            PermissionsScreen { nav.navigate("home") { popUpTo(nav.graph.id) { inclusive = true } } }
        }

        // Tabs
        composable("home") {
            UnifiedHomeScreen(vm, nav)
        }
        composable("find_blood") { FindBloodScreen(vm, nav) }
        composable("donate") { DonateScreen(vm, nav) }
        composable("activity") { ActivityScreen(vm, nav) }
        composable("edit_request/{id}") { EditRequestSubmissionScreen(vm, nav, it.arg("id")) }
        composable("history") { ActivityScreen(vm, nav) }
        composable("profile") { ProfileScreen(vm, nav) }
        composable("sync_account") { SyncAccountScreen(vm, nav) }
        composable("sync_review") { SyncReviewScreen(vm, nav) }

        // Donor flow
        composable("incoming/{id}") { IncomingRequestScreen(vm, nav, it.arg("id")) }

        composable("create_request") { UnifiedCreateRequestScreen(vm, nav) }

        // Shared
        composable("coordinate/{id}/{ref}") { CoordinationScreen(vm, nav, it.arg("id"), it.arg("ref")) }
        composable("receipt/{id}") { ReceiptScreen(vm, nav, it.arg("id")) }
    }

    pendingConnection?.let { connection ->
        AlertDialog(
            onDismissRequest = { vm.rejectNearbyConnection(connection.endpointId) },
            title = { Text("Verify nearby device") },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text("Only accept if both phones show the same security numbers.")
                    Text(
                        connection.authenticationDigits,
                        style = androidx.compose.material3.MaterialTheme.typography.headlineMedium
                    )
                    Text("Device: ${connection.endpointName}")
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.approveNearbyConnection(connection.endpointId) }) {
                    Text("Numbers match")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.rejectNearbyConnection(connection.endpointId) }) {
                    Text("Reject")
                }
            }
        )
    }
}
