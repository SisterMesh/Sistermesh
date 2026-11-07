package com.sistermesh.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sistermesh.app.SisterMeshApplication

class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as SisterMeshApplication
        val outcome = app.syncEngine.run()
        if (outcome in setOf(
                SyncOutcome.NEEDS_SIGN_IN,
                SyncOutcome.ACCOUNT_LINK_REQUIRED,
                SyncOutcome.ACCOUNT_MISMATCH,
                SyncOutcome.DEVICE_REVOKED
            )
        ) {
            app.syncWorkScheduler.cancelPeriodic()
        }
        return when (outcome) {
            SyncOutcome.SUCCESS,
            SyncOutcome.ALREADY_RUNNING,
            SyncOutcome.NEEDS_SIGN_IN,
            SyncOutcome.ACCOUNT_LINK_REQUIRED,
            SyncOutcome.ACCOUNT_MISMATCH,
            SyncOutcome.PROFILE_INCOMPLETE,
            SyncOutcome.VERIFICATION_PENDING,
            SyncOutcome.DEVICE_REVOKED,
            SyncOutcome.FAILED -> Result.success()
            SyncOutcome.RETRY -> Result.retry()
        }
    }
}
