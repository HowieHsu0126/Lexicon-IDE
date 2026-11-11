package dev.lexicon.ide

import android.app.Application
import androidx.work.*
import dev.lexicon.data.LexiconRepository
import dev.lexicon.domain.AppRepository
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class LexiconApplication : Application() {
    val repository: AppRepository by lazy { LexiconRepository(this) }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            repository.initialize()
            scheduleSync()
        }
    }
    private fun scheduleSync() {
        val work = WorkManager.getInstance(this)
        work.enqueueUniquePeriodicWork("lexicon-feed-sync", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<FeedSyncWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresCharging(true).build())
                .setInputData(workDataOf("requireWifi" to true))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build())
        val prefs = getSharedPreferences("startup", MODE_PRIVATE)
        if (!prefs.getBoolean("initialSyncScheduled", false)) {
            work.enqueueUniqueWork("lexicon-first-sync", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<FeedSyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
            prefs.edit().putBoolean("initialSyncScheduled", true).apply()
        }
    }
}
