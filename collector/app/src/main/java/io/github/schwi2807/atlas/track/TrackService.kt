package io.github.schwi2807.atlas.track

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import io.github.schwi2807.atlas.AtlasApp
import io.github.schwi2807.atlas.MainActivity
import io.github.schwi2807.atlas.R

/**
 * Foreground service that keeps GNSS running while the screen is off, so the breadcrumb track and coverage layer
 * record everywhere you walk. The recording itself lives in SurveyController; this only keeps the process alive.
 */
class TrackService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            (application as AtlasApp).survey.setRecording(false)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, 7, notification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        val survey = (application as AtlasApp).survey
        survey.gnss.start(hasPermission = true)
        survey.setRecording(true)
        return START_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.track_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TrackService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle(getString(R.string.track_running))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "track"
        private const val ACTION_STOP = "stop"

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, TrackService::class.java))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, TrackService::class.java).setAction(ACTION_STOP))
    }
}
