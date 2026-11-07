package com.sistermesh.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sistermesh.app.data.AppViewModel
import com.sistermesh.app.data.AppViewModelFactory
import com.sistermesh.app.navigation.AppNav
import com.sistermesh.app.ui.theme.SisterMeshTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SisterMeshTheme {
                val app = application as SisterMeshApplication
                val vm: AppViewModel = viewModel(
                    factory = AppViewModelFactory(
                        app.repository,
                        app.nearbyManager,
                        app.authRepository,
                        app.syncEngine,
                        app.syncWorkScheduler,
                        app.connectivityStatus
                    )
                )
                AppNav(vm)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as SisterMeshApplication).nearbyManager.onForeground()
    }

    override fun onResume() {
        super.onResume()
        (application as SisterMeshApplication).nearbyManager.refreshEnvironment()
    }

    override fun onStop() {
        (application as SisterMeshApplication).nearbyManager.onBackground()
        super.onStop()
    }
}
