package com.mywallet.data.backup

import android.content.Context
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mywallet.data.settings.BackupFrequency
import com.mywallet.data.settings.SettingsStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes a backup on a schedule.
 *
 * Retries rather than fails on a transient problem — a backup that silently
 * gave up is worse than no backup, because the user believes they are covered.
 */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val backupManager: BackupManager,
    private val settings: SettingsStore,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val current = settings.settings.first()
        if (current.backupFrequency == BackupFrequency.OFF) return Result.success()
        val folder = current.backupFolderUri ?: return Result.success()

        return backupManager.writeAutomaticBackup(folder.toUri())
            .fold(
                onSuccess = { Result.success() },
                // The folder may be on removable or cloud storage that is
                // momentarily unavailable; WorkManager will back off and retry.
                onFailure = { if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure() },
            )
    }

    companion object {
        const val WORK_NAME = "automatic-backup"
        private const val MAX_ATTEMPTS = 3
    }
}

@Singleton
class BackupScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Applies [frequency] to WorkManager. Called whenever the setting or the
     * chosen folder changes, and on app start so a reinstall re-registers it.
     */
    fun apply(frequency: BackupFrequency, hasFolder: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (frequency == BackupFrequency.OFF || !hasFolder) {
            workManager.cancelUniqueWork(BackupWorker.WORK_NAME)
            return
        }

        val request = PeriodicWorkRequestBuilder<BackupWorker>(
            frequency.intervalHours(), TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    // Not while the battery is critical: a backup is never worth
                    // being the thing that killed someone's phone.
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        workManager.enqueueUniquePeriodicWork(
            BackupWorker.WORK_NAME,
            // KEEP would ignore a changed interval; UPDATE re-applies it without
            // losing the existing schedule's next run.
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun BackupFrequency.intervalHours(): Long = when (this) {
        BackupFrequency.OFF -> 24L
        BackupFrequency.DAILY -> 24L
        BackupFrequency.WEEKLY -> 24L * 7
        BackupFrequency.MONTHLY -> 24L * 30
    }
}
