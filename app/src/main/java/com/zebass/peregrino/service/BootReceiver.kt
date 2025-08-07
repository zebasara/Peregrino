package com.zebass.peregrino.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.annotation.RequiresPermission
import com.zebass.peregrino.R
import com.zebass.peregrino.SyncWorker

class BootReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "BootReceiver"
        private const val RESTART_NOTIFICATION_ID = 12345
        private const val RESTART_CHANNEL_ID = "restart_channel"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "🔄 BootReceiver triggered: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "📱 Device booted - checking for service restart")
                restartTrackingServiceIfNeeded(context, "boot completed")
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d(TAG, "📦 Package updated - checking for service restart")
                restartTrackingServiceIfNeeded(context, "package updated")
            }
            else -> {
                Log.d(TAG, "❓ Unknown action received: $action")
            }
        }
    }

    private fun restartTrackingServiceIfNeeded(context: Context, reason: String) {
        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val jwtToken = prefs.getString("jwt_token", null)
        val deviceUniqueId = prefs.getString("associated_device_unique_id", null)

        if (jwtToken.isNullOrEmpty() || deviceUniqueId.isNullOrEmpty()) {
            Log.d(TAG, "❌ No hay datos de sesión guardados")
            return
        }

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                startTrackingService(
                    context,
                    jwtToken,
                    deviceUniqueId,
                    prefs.getInt("associated_device_id", -1),
                    prefs.getString("associated_device_name", "Unknown Device") ?: "Unknown Device",
                    reason
                )
            } catch (e: SecurityException) {
                Log.e(TAG, "❌ SecurityException: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error: ${e.message}")
            }
        }, 3000)
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun startTrackingService(
        context: Context,
        jwtToken: String,
        deviceUniqueId: String,
        deviceId: Int,
        deviceName: String,
        reason: String
    ) {
        try {
            val canNotify = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

            val serviceIntent = Intent(context, TrackingService::class.java).apply {
                putExtra("jwtToken", jwtToken)
                putExtra("deviceUniqueId", deviceUniqueId)
                putExtra("deviceId", deviceId)
                putExtra("auto_restart", true)
                putExtra("restart_reason", reason)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            SyncWorker.schedulePeriodicSync(context, 15)

            if (canNotify) {
                showRestartNotification(context, deviceName, reason)
            } else {
                Log.w(TAG, "⚠️ No se mostró notificación por falta de permisos")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error iniciando TrackingService", e)
        }
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun showRestartNotification(context: Context, deviceName: String, reason: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "⚠️ No tiene permiso para mostrar notificaciones")
                return
            }

            createRestartNotificationChannel(context)

            val notification = NotificationCompat.Builder(context, RESTART_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_gps_tracking)
                .setContentTitle(context.getString(R.string.notification_restart_title))
                .setContentText(context.getString(R.string.notification_restart_text, deviceName))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .build()

            NotificationManagerCompat.from(context).notify(RESTART_NOTIFICATION_ID, notification)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error mostrando notificación: ${e.message}")
        }
    }

    private fun createRestartNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                NotificationChannel(
                    RESTART_CHANNEL_ID,
                    context.getString(R.string.notification_channel_restart),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notification_channel_restart_desc)
                    enableVibration(false)
                }.also { channel ->
                    context.getSystemService(NotificationManager::class.java)
                        ?.createNotificationChannel(channel)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error creando canal: ${e.message}")
            }
        }
    }
}