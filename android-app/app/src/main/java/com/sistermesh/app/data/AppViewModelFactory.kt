package com.sistermesh.app.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.sistermesh.app.communication.NearbyConnectionsManager
import com.sistermesh.app.sync.AuthRepository
import com.sistermesh.app.sync.ConnectivityStatus
import com.sistermesh.app.sync.SyncEngine
import com.sistermesh.app.sync.SyncWorkScheduler

class AppViewModelFactory(
    private val repository: SisterMeshRepository,
    private val nearbyManager: NearbyConnectionsManager,
    private val authRepository: AuthRepository,
    private val syncEngine: SyncEngine,
    private val syncWorkScheduler: SyncWorkScheduler,
    private val connectivityStatus: ConnectivityStatus
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (!modelClass.isAssignableFrom(AppViewModel::class.java)) {
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
        return AppViewModel(
            repository,
            nearbyManager,
            authRepository,
            syncEngine,
            syncWorkScheduler,
            connectivityStatus
        ) as T
    }
}
