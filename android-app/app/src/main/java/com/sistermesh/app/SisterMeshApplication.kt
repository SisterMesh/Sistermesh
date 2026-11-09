package com.sistermesh.app

import android.app.Application
import androidx.room.Room
import com.sistermesh.app.data.SisterMeshRepository
import com.sistermesh.app.data.local.MIGRATION_1_2
import com.sistermesh.app.data.local.MIGRATION_2_3
import com.sistermesh.app.data.local.MIGRATION_3_4
import com.sistermesh.app.data.local.MIGRATION_4_5
import com.sistermesh.app.data.local.SisterMeshDatabase
import com.sistermesh.app.communication.AndroidKeystoreMeshSigner
import com.sistermesh.app.communication.GoogleNearbyTransport
import com.sistermesh.app.communication.MeshRepository
import com.sistermesh.app.communication.NearbyConnectionsManager
import com.sistermesh.app.sync.AndroidKeystoreTokenStore
import com.sistermesh.app.sync.AuthRepository
import com.sistermesh.app.sync.BackendApi
import com.sistermesh.app.sync.ConnectivityStatus
import com.sistermesh.app.sync.SyncEngine
import com.sistermesh.app.sync.SyncWorkScheduler

class SisterMeshApplication : Application() {
    private val database by lazy {
        Room.databaseBuilder(
            applicationContext,
            SisterMeshDatabase::class.java,
            "sistermesh-local.db"
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
    }

    val repository by lazy { SisterMeshRepository(database) }
    val meshRepository by lazy { MeshRepository(database, AndroidKeystoreMeshSigner()) }
    val tokenStore by lazy { AndroidKeystoreTokenStore(applicationContext) }
    val backendApi by lazy { BackendApi(BuildConfig.SYNC_API_BASE_URL) }
    val authRepository by lazy { AuthRepository(backendApi, tokenStore) }
    val syncEngine by lazy { SyncEngine(database, backendApi, authRepository) }
    val syncWorkScheduler by lazy { SyncWorkScheduler(applicationContext) }
    val connectivityStatus by lazy { ConnectivityStatus(applicationContext) }
    val nearbyManager by lazy {
        NearbyConnectionsManager(
            applicationContext,
            meshRepository,
            GoogleNearbyTransport(applicationContext)
        )
    }
}
