package com.sistermesh.app

import android.app.Application
import androidx.room.Room
import com.sistermesh.app.data.SisterMeshRepository
import com.sistermesh.app.data.local.SisterMeshDatabase

class SisterMeshApplication : Application() {
    private val database by lazy {
        Room.databaseBuilder(
            applicationContext,
            SisterMeshDatabase::class.java,
            "sistermesh-local.db"
        ).build()
    }

    val repository by lazy { SisterMeshRepository(database) }
}
