// LocationSharingService.kt
package com.zebass.peregrino.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.zebass.peregrino.MainActivity
import com.zebass.peregrino.R
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LocationSharingService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "location_sharing_channel"
        private const val TAG = "LocationSharingService"

        const val ACTION_START_SHARING = "com.peregrino.START_SHARING"
        const val ACTION_STOP_SHARING = "com.peregrino.STOP_SHARING"

        const val EXTRA_SHARE_ID = "share_id"
        const val EXTRA_DURATION_MINUTES = "duration_minutes"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_JWT_TOKEN = "jwt_token"
    }

    private var shareId: String? = null
    private var deviceId: String? = null
    private var jwtToken: String? = null
    private var durationMinutes: Int = -1
    private var startTime: Long = 0

    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "📍 LocationSharingService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SHARING -> {
                shareId = intent.getStringExtra(EXTRA_SHARE_ID)
                deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
                jwtToken = intent.getStringExtra(EXTRA_JWT_TOKEN)
                durationMinutes = intent.getIntExtra(EXTRA_DURATION_MINUTES, -1)
                startTime = System.currentTimeMillis()

                startLocationSharing()
            }
            ACTION_STOP_SHARING -> {
                stopLocationSharing()
            }
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Compartir Ubicación",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificaciones para compartir ubicación en tiempo real"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startLocationSharing() {
        if (shareId == null || deviceId == null) {
            Log.e(TAG, "❌ Missing required data for location sharing")
            stopSelf()
            return
        }

        Log.d(TAG, "🌍 Starting location sharing: shareId=$shareId, deviceId=$deviceId, duration=${durationMinutes}min")

        val notification = createSharingNotification()
        startForeground(NOTIFICATION_ID, notification)

        // ✅ PROGRAMAR ACTUALIZACIONES DE NOTIFICACIÓN
        scheduleNotificationUpdates()

        // ✅ PROGRAMAR DETENCIÓN AUTOMÁTICA SI HAY DURACIÓN
        if (durationMinutes > 0) {
            handler.postDelayed({
                stopLocationSharing()
            }, durationMinutes * 60 * 1000L)
        }
    }

    private fun createSharingNotification(): Notification {
        val stopIntent = Intent(this, LocationSharingService::class.java).apply {
            action = ACTION_STOP_SHARING
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🌍 Ubicación Compartida")
            .setContentText(buildNotificationText())
            .setSmallIcon(R.drawable.ic_location_sharing)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                R.drawable.ic_stop,
                "Detener",
                stopPendingIntent
            )
            .build()
    }

    private fun buildNotificationText(): String {
        val elapsed = System.currentTimeMillis() - startTime
        val elapsedMinutes = elapsed / 60000

        return when {
            durationMinutes <= 0 -> "Compartiendo indefinidamente (${elapsedMinutes}min)"
            durationMinutes < 60 -> "Compartiendo por ${durationMinutes}min (${elapsedMinutes}min transcurridos)"
            else -> {
                val hours = durationMinutes / 60
                "Compartiendo por ${hours}h (${elapsedMinutes}min transcurridos)"
            }
        }
    }

    private fun scheduleNotificationUpdates() {
        updateRunnable = object : Runnable {
            override fun run() {
                try {
                    val notification = createSharingNotification()
                    val notificationManager = getSystemService(NotificationManager::class.java)
                    notificationManager.notify(NOTIFICATION_ID, notification)

                    // ✅ VERIFICAR SI DEBE CONTINUAR
                    if (durationMinutes > 0) {
                        val elapsed = System.currentTimeMillis() - startTime
                        val elapsedMinutes = elapsed / 60000

                        if (elapsedMinutes >= durationMinutes) {
                            stopLocationSharing()
                            return
                        }
                    }

                    // ✅ PROGRAMAR SIGUIENTE ACTUALIZACIÓN
                    handler.postDelayed(this, 60000) // Cada minuto
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating notification: ${e.message}")
                }
            }
        }

        handler.post(updateRunnable!!)
    }

    private fun stopLocationSharing() {
        Log.d(TAG, "🛑 Stopping location sharing: shareId=$shareId")

        // ✅ CANCELAR ACTUALIZACIONES
        updateRunnable?.let { handler.removeCallbacks(it) }
        updateRunnable = null

        // ✅ ENVIAR BROADCAST PARA NOTIFICAR A LA APP
        val intent = Intent("com.peregrino.LOCATION_SHARING_STOPPED").apply {
            putExtra("shareId", shareId)
            putExtra("deviceId", deviceId)
        }
        sendBroadcast(intent)

        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()

        updateRunnable?.let { handler.removeCallbacks(it) }

        Log.d(TAG, "🗑️ LocationSharingService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}