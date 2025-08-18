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
import android.content.SharedPreferences
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import com.zebass.peregrino.R
import com.zebass.peregrino.SyncWorker

class BootReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "BootReceiver"
        private const val RESTART_NOTIFICATION_ID = 12345
        private const val RESTART_CHANNEL_ID = "restart_channel"
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "🔄 BootReceiver triggered: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "📱 Device booted - checking services")
                restartServicesIfNeeded(context, "boot completed")
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d(TAG, "📦 Package updated - checking services")
                restartServicesIfNeeded(context, "package updated")
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun restartServicesIfNeeded(context: Context, reason: String) {
        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val securityPrefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)

        val jwtToken = prefs.getString("jwt_token", null)
        val deviceUniqueId = prefs.getString("associated_device_unique_id", null)
        val isSecurityActive = securityPrefs.getBoolean("security_active", false)

        if (jwtToken.isNullOrEmpty() || deviceUniqueId.isNullOrEmpty()) {
            Log.d(TAG, "❌ No session data - skipping restart")
            return
        }

        // Verificar el permiso POST_NOTIFICATIONS
        val canNotify = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                // 2. Reiniciar SecurityService si está activo
                if (isSecurityActive && canNotify) {
                    restartSecurityService(context, securityPrefs, reason)
                } else if (isSecurityActive) {
                    Log.w(TAG, "⚠️ No se reinició SecurityService por falta de permisos de notificación")
                }

            } catch (e: SecurityException) {
                Log.e(TAG, "❌ SecurityException al reiniciar servicios: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error reiniciando servicios: ${e.message}")
            }
        }, 5000) // 5 segundos de delay
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    @RequiresApi(Build.VERSION_CODES.O)
    private fun restartSecurityService(context: Context, securityPrefs: SharedPreferences, reason: String) {
        try {
            val deviceId = securityPrefs.getString("device_id", null)
            val jwtToken = securityPrefs.getString("jwt_token", null)
            val safeZoneLat = securityPrefs.getString("safe_zone_lat", null)?.toDoubleOrNull()
            val safeZoneLon = securityPrefs.getString("safe_zone_lon", null)?.toDoubleOrNull()

            if (deviceId != null && jwtToken != null && safeZoneLat != null && safeZoneLon != null) {
                val securityIntent = Intent(context, BackgroundSecurityService::class.java).apply {
                    action = BackgroundSecurityService.ACTION_START_SECURITY
                    putExtra(BackgroundSecurityService.EXTRA_DEVICE_ID, deviceId)
                    putExtra(BackgroundSecurityService.EXTRA_JWT_TOKEN, jwtToken)
                    putExtra(BackgroundSecurityService.EXTRA_SAFE_ZONE_LAT, safeZoneLat)
                    putExtra(BackgroundSecurityService.EXTRA_SAFE_ZONE_LON, safeZoneLon)
                }

                context.startForegroundService(securityIntent)
                Log.d(TAG, "✅ Security service restarted: $reason")

                showRestartNotification(context, "Seguridad GPS reactivada", reason)

            } else {
                Log.w(TAG, "⚠️ Incomplete security data - cannot restart security")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error restarting security service: ${e.message}")
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