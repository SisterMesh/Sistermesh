package com.sistermesh.app

import android.app.Application
import androidx.room.Room
import com.sistermesh.app.data.SisterMeshRepository
import com.sistermesh.app.data.local.MIGRATION_1_2
import com.sistermesh.app.data.local.SisterMeshDatabase
import com.sistermesh.app.communication.AndroidKeystoreMeshSigner
import com.sistermesh.app.communication.GoogleNearbyTransport
import com.sistermesh.app.communication.MeshRepository
import com.sistermesh.app.communication.NearbyConnectionsManager

class SisterMeshApplication : Application() {
    private val database by lazy {
        Room.databaseBuilder(
            applicationContext,
            SisterMeshDatabase::class.java,
            "sistermesh-local.db"
        ).addMigrations(MIGRATION_1_2).build()
    }

    val repository by lazy { SisterMeshRepository(database) }
    val meshRepository by lazy { MeshRepository(database, AndroidKeystoreMeshSigner()) }
    val nearbyManager by lazy {
        NearbyConnectionsManager(
            applicationContext,
            meshRepository,
            GoogleNearbyTransport(applicationContext)
        )
    }
}
