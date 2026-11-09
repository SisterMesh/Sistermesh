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
import com.sistermesh.app.data.Role
import com.sistermesh.app.ui.screens.chw.*
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
            RoleScreen(vm, onBack = { nav.popBackStack() }) { role ->
                nav.navigate(if (role == Role.INDIVIDUAL) "register_individual" else "register_chw")
            }
        }
        composable("register_individual") { DonorRegistrationScreen(vm, { nav.popBackStack() }) { nav.navigate("permissions") } }
        composable("register_chw") { ChwRegistrationScreen(vm, { nav.popBackStack() }) { nav.navigate("permissions") } }
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
        composable("facility_requests") { FacilityReviewScreen(vm, nav) }
        composable("review_request/{id}") { RequestReviewScreen(vm, nav, it.arg("id")) }
        composable("edit_request/{id}") { EditRequestSubmissionScreen(vm, nav, it.arg("id")) }
        composable("history") { ActivityScreen(vm, nav) }
        composable("profile") { ProfileScreen(vm, nav) }
        composable("sync_account") { SyncAccountScreen(vm, nav) }
        composable("sync_review") { SyncReviewScreen(vm, nav) }

        // Donor flow
        composable("incoming/{id}") { IncomingRequestScreen(vm, nav, it.arg("id")) }

        // CHW flow
        composable("create_request") { UnifiedCreateRequestScreen(vm, nav) }
        composable("broadcast/{id}") { BroadcastScreen(vm, nav, it.arg("id")) }
        composable("tracking/{id}") { TrackingScreen(vm, nav, it.arg("id")) }
        composable("confirm/{id}/{ref}") { DonationConfirmationScreen(vm, nav, it.arg("id"), it.arg("ref")) }

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
