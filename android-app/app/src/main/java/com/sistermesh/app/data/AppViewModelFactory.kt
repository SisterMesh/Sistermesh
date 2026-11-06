package com.sistermesh.app.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.sistermesh.app.communication.NearbyConnectionsManager

class AppViewModelFactory(
    private val repository: SisterMeshRepository,
    private val nearbyManager: NearbyConnectionsManager
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (!modelClass.isAssignableFrom(AppViewModel::class.java)) {
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
        return AppViewModel(repository, nearbyManager) as T
    }
}
