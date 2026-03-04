package com.example.megameshapp.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.megameshapp.MainActivity
import com.example.megameshapp.R

/**
 * Foreground service that keeps the BLE connection alive in the background
 * and posts notifications for incoming mesh messages.
 */
class MeshBleService : Service() {

    companion object {
        const val CHANNEL_ID_FOREGROUND = "mesh_foreground"
        const val CHANNEL_ID_MESSAGES = "mesh_messages"
        const val FOREGROUND_NOTIFICATION_ID = 1001
        const val ACTION_NEW_MESSAGE = "com.example.megameshapp.NEW_MESSAGE"
        const val EXTRA_SENDER = "sender"
        const val EXTRA_TEXT = "text"
        private var messageNotificationId = 2000
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_NEW_MESSAGE -> {
                val sender = intent.getStringExtra(EXTRA_SENDER) ?: "Unknown"
                val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
                showMessageNotification(sender, text)
            }
            else -> {
                // Start foreground
                val notification = buildForegroundNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        FOREGROUND_NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    )
                } else {
                    startForeground(FOREGROUND_NOTIFICATION_ID, notification)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val foregroundChannel = NotificationChannel(
                CHANNEL_ID_FOREGROUND,
                "MegaMesh Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the BLE connection alive in the background"
                setShowBadge(false)
            }

            val messageChannel = NotificationChannel(
                CHANNEL_ID_MESSAGES,
                "Mesh Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming mesh messages"
                enableVibration(true)
                enableLights(true)
                lightColor = 0xFF1E88FF.toInt()
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(foregroundChannel)
            notificationManager.createNotificationChannel(messageChannel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID_FOREGROUND)
            .setContentTitle("MegaMesh Active")
            .setContentText("Listening for mesh messages in the background")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun showMessageNotification(sender: String, text: String) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_MESSAGES)
            .setContentTitle("Message from $sender")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(messageNotificationId++, notification)
        } catch (e: SecurityException) {
            // Missing POST_NOTIFICATIONS permission
        }
    }
}

