package app.nouri.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.nouri.NouriApplication
import app.nouri.R
import app.nouri.core.pantry.PantryLogic
import app.nouri.data.prefs.ImageRetention
import app.nouri.core.util.SystemTime
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

object Maintenance {
    private const val WORK_NAME = "nouri-daily-maintenance"
    const val CHANNEL_EXPIRY = "pantry_expiry"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(1, TimeUnit.DAYS).build()
        // WorkManager is absent in some test environments; housekeeping is best-effort.
        runCatching { WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request) }
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_EXPIRY, context.getString(R.string.channel_expiry), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }
}

/**
 * Daily, battery-friendly housekeeping: deletes food photos per the retention
 * setting, purges diary rows soft-deleted over 30 days ago, and (only if the user
 * opted in) reminds about pantry items expiring tomorrow.
 */
class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? NouriApplication ?: return Result.success()
        val c = app.container
        val settings = c.settings.current()

        val maxAgeMs = when (settings.privacy.imageRetention) {
            ImageRetention.DELETE_AFTER_ANALYSIS -> 6 * 3_600_000L
            ImageRetention.KEEP_30_DAYS -> 30 * 86_400_000L
            ImageRetention.KEEP_UNTIL_DELETED -> null
        }
        if (maxAgeMs != null) {
            val old = c.scans.sessionsOlderThan(maxAgeMs)
            old.forEach { s -> c.images.delete(c.scans.imagePaths()[s.id].orEmpty()) }
            c.scans.clearImages(old.map { it.id })
        }
        c.diary.purgeDeletedOlderThan(30 * 86_400_000L)

        if (settings.expiryReminders) {
            val today = SystemTime.today()
            val expiring = PantryLogic.expiringSoon(c.pantry.pantry.first(), today, withinDays = 1)
            if (expiring.isNotEmpty()) notify(expiring.joinToString { it.name })
        }
        return Result.success()
    }

    private fun notify(names: String) {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        Maintenance.ensureChannel(ctx)
        val notification = NotificationCompat.Builder(ctx, Maintenance.CHANNEL_EXPIRY)
            .setSmallIcon(R.drawable.ic_stat_nouri)
            .setContentTitle(ctx.getString(R.string.expiry_title))
            .setContentText(names)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(1001, notification) }
    }
}
