package dev.lexicon.ide

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

class FeedSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (inputData.getBoolean("requireWifi", false)) {
            val connectivity = applicationContext.getSystemService(ConnectivityManager::class.java)
            val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return Result.retry()
        }
        val repo = (applicationContext as LexiconApplication).repository
        return try {
            repo.initialize()
            val result = repo.sync()
            repo.classifyPending()
            if (result.sourceCount > 0 && result.failures.size == result.sourceCount) Result.retry() else Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
}
