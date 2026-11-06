package com.sistermesh.app.navigation

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
                nav.navigate(if (role == Role.DONOR) "register_donor" else "register_chw")
            }
        }
        composable("register_donor") { DonorRegistrationScreen(vm, { nav.popBackStack() }) { nav.navigate("permissions") } }
        composable("register_chw") { ChwRegistrationScreen(vm, { nav.popBackStack() }) { nav.navigate("permissions") } }
        composable("permissions") {
            PermissionsScreen { nav.navigate("home") { popUpTo(nav.graph.id) { inclusive = true } } }
        }

        // Tabs
        composable("home") {
            if (vm.profile?.role == Role.CHW) ChwHomeScreen(vm, nav) else DonorHomeScreen(vm, nav)
        }
        composable("history") { HistoryScreen(vm, nav) }
        composable("profile") { ProfileScreen(vm, nav) }

        // Donor flow
        composable("incoming/{id}") { IncomingRequestScreen(vm, nav, it.arg("id")) }

        // CHW flow
        composable("create_request") { CreateRequestScreen(vm, nav) }
        composable("broadcast/{id}") { BroadcastScreen(vm, nav, it.arg("id")) }
        composable("tracking/{id}") { TrackingScreen(vm, nav, it.arg("id")) }
        composable("confirm/{id}/{ref}") { DonationConfirmationScreen(vm, nav, it.arg("id"), it.arg("ref")) }

        // Shared
        composable("coordinate/{id}/{ref}") { CoordinationScreen(vm, nav, it.arg("id"), it.arg("ref")) }
        composable("receipt/{id}") { ReceiptScreen(vm, nav, it.arg("id")) }
    }
}
