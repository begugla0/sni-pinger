package com.begugla.snipinger

import android.app.Notification
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

/**
 * Holds the app in a foreground state with a persistent notification while
 * checks run, so the OS does not kill the process when the UI goes into
 * background. The actual work is still driven from MainActivity; this service
 * only provides the foreground context.
 */
class CheckForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "snipinger_check_channel"
        const val NOTIFICATION_ID = 101
        const val ACTION_START = "com.begugla.snipinger.action.START"
        const val ACTION_UPDATE = "com.begugla.snipinger.action.UPDATE"
        const val ACTION_STOP = "com.begugla.snipinger.action.STOP"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_SUBTEXT = "subtext"
        const val EXTRA_BIGTEXT = "bigtext"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_MAX = "max"

        fun start(ctx: Context, title: String, text: String) {
            val i = Intent(ctx, CheckForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
            }
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun update(
            ctx: Context,
            title: String,
            text: String,
            subText: String?,
            bigText: String?,
            progress: Int,
            max: Int
        ) {
            val i = Intent(ctx, CheckForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_SUBTEXT, subText)
                putExtra(EXTRA_BIGTEXT, bigText)
                putExtra(EXTRA_PROGRESS, progress)
                putExtra(EXTRA_MAX, max)
            }
            ctx.startService(i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, CheckForegroundService::class.java).apply { action = ACTION_STOP }
            ctx.startService(i)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.bg_service_title)
                val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
                val subText = intent.getStringExtra(EXTRA_SUBTEXT)
                val bigText = intent.getStringExtra(EXTRA_BIGTEXT)
                val progress = intent.getIntExtra(EXTRA_PROGRESS, 0)
                val max = intent.getIntExtra(EXTRA_MAX, 0)
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildNotification(title, text, subText, bigText, progress, max))
            }
            else -> {
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.bg_service_title)
                val text = intent?.getStringExtra(EXTRA_TEXT) ?: getString(R.string.bg_service_text)
                ensureChannel()
                val notification = buildNotification(title, text, null, null, 0, 0)
                if (Build.VERSION.SDK_INT >= 30) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        }
        return START_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.bg_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.bg_channel_desc)
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(
        title: String,
        text: String,
        subText: String?,
        bigText: String?,
        progress: Int,
        max: Int
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val piFlags = if (Build.VERSION.SDK_INT >= 23)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getActivity(this, 0, openIntent, piFlags)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (!subText.isNullOrBlank()) builder.setSubText(subText)
        if (!bigText.isNullOrBlank())
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(bigText))

        if (max > 0) builder.setProgress(max, progress, false)
        return builder.build()
    }
}
