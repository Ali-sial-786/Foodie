package app.nouri.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.nouri.MainActivity
import app.nouri.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps Nouri running while the browser shows the ChatGPT sign-in pages.
 *
 * With the browser in front, Android treats Nouri as a cached background app and
 * freezes it (Samsung phones within seconds). The redirect to 127.0.0.1 then hangs
 * and ends in "connection refused". A foreground service for the length of the
 * sign-in, with its own notification, keeps the loopback listener answering and lets
 * the code exchange use the network from the background (Android 15+ blocks it for
 * cached apps).
 */
class SignInService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nouri)
            .setContentTitle(getString(R.string.sign_in_title))
            .setContentText(getString(R.string.sign_in_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        running.value = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running.value = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "sign_in"
        private const val NOTIFICATION_ID = 2001
        private val running = MutableStateFlow(false)

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CHANNEL, context.getString(R.string.channel_sign_in), NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }

    /** Starts the service from the foreground; stops it once it is really in the foreground. */
    class KeepAlive(private val context: Context) : SignInKeepAlive {
        private val intent get() = Intent(context, SignInService::class.java)
        @Volatile private var started = false

        override fun start(): Boolean {
            // Throws (ForegroundServiceStartNotAllowedException) if the app is no longer in the foreground.
            started = runCatching { ContextCompat.startForegroundService(context, intent) }.isSuccess
            return started
        }

        override suspend fun stop() {
            if (!started) return
            started = false
            // Stopping before startForeground() has run crashes on some Android versions.
            withTimeoutOrNull(10_000) { running.first { it } }
            runCatching { context.stopService(intent) }
        }
    }
}
