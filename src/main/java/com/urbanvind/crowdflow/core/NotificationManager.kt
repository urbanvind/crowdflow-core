package com.urbanvind.crowdflow.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

class NotificationManager(private val context: Context) {

    private val channelId = "ForegroundServiceChannel"
    private val logTag = "NotificationHelper"

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                channelId,
                "Foreground Service Channel",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
            Log.d(logTag, "Notification channel created")
        }
    }

    fun createNotification(pendingIntent: PendingIntent): Notification {
        return NotificationCompat.Builder(context, channelId)
            .setContentTitle("CrowdFlow is active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }


    fun getPendingIntent(): PendingIntent? {
        // Obtain the launch intent for the current package (the main activity that would be opened)
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)

        if (launchIntent == null) {
            Log.e(logTag, "No launch intent found for package: ${context.packageName}")
            return null
        }

        return PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
    }

}
