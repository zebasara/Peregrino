package com.zebass.peregrino.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.telecom.TelecomManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.zebass.peregrino.R
import com.zebass.peregrino.SecondFragment
import kotlinx.coroutines.*

class AlertManager(private val context: Context) {

    companion object {
        private const val TAG = "AlertManager"
        private const val ALERT_CHANNEL_ID = "geofence_alert_channel"
        private const val ALERT_NOTIFICATION_ID = 9999
        private const val WAKE_LOCK_TAG = "Peregrino:AlertWakeLock"

        // Configuración de alerta
        const val ALERT_DURATION = 60000L // 1 minuto de alerta continua
        const val VIBRATION_PATTERN_DURATION = 1000L
        const val SOUND_LOOP_DELAY = 2000L
    }

    private var alertJob: Job? = null
    private var ringtone: Ringtone? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isAlerting = false

    init {
        createAlertChannel()
    }

    private fun createAlertChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Geofence Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical alerts when vehicle leaves safe zone"
                enableLights(true)
                lightColor = android.graphics.Color.RED
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 500, 1000)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }

            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    /**
     * Inicia la alerta crítica cuando el vehículo sale de la zona segura
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun startCriticalAlert(vehicleId: Int, distance: Double) {
        if (isAlerting) {
            Log.d(TAG, "Alert already active, ignoring new alert")
            return
        }

        isAlerting = true
        acquireWakeLock()

        // Iniciar alerta con múltiples componentes
        alertJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                // 1. Mostrar notificación crítica
                showCriticalNotification(vehicleId, distance)

                // 2. Iniciar vibración continua
                startContinuousVibration()

                // 3. Iniciar sonido de alarma
                startAlarmSound()

                // 4. Intentar hacer llamada de emergencia (si está configurado)
                attemptEmergencyCall()

                // Mantener alerta activa por ALERT_DURATION
                delay(ALERT_DURATION)

            } catch (e: CancellationException) {
                Log.d(TAG, "Alert cancelled")
            } finally {
                stopAlert()
            }
        }
    }

    /**
     * Detiene todas las alertas activas
     */
    fun stopAlert() {
        isAlerting = false

        // Cancelar job
        alertJob?.cancel()
        alertJob = null

        // Detener sonido
        ringtone?.stop()
        ringtone = null

        // Detener vibración
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        vibrator?.cancel()

        // Liberar wake lock
        releaseWakeLock()

        // Cancelar notificación
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(ALERT_NOTIFICATION_ID)

        Log.d(TAG, "Alert stopped")
    }

    private fun showCriticalNotification(vehicleId: Int, distance: Double) {
        val stopIntent = Intent(context, AlertStopReceiver::class.java)
        val stopPendingIntent = PendingIntent.getBroadcast(
            context, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vehicle_alert)
            .setContentTitle("⚠️ VEHICLE OUTSIDE SAFE ZONE!")
            .setContentText("Vehicle $vehicleId is ${distance.toInt()}m away from safe zone")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(createFullScreenIntent(), true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColorized(true)
            .setColor(android.graphics.Color.RED)
            .addAction(R.drawable.ic_close, "STOP ALERT", stopPendingIntent)
            .setVibrate(longArrayOf(0, 1000, 500, 1000))
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Para Android 8.0+ necesitamos permisos especiales para notificaciones críticas
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification.flags = notification.flags or NotificationCompat.FLAG_INSISTENT
        }

        notificationManager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun createFullScreenIntent(): PendingIntent {
        val fullScreenIntent = Intent(context, AlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        return PendingIntent.getActivity(
            context, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun startContinuousVibration() = withContext(Dispatchers.IO) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (vibrator?.hasVibrator() == true) {
            while (isAlerting) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        VIBRATION_PATTERN_DURATION,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
                delay(VIBRATION_PATTERN_DURATION + 500)
            }
        }
    }

    private suspend fun startAlarmSound() = withContext(Dispatchers.IO) {
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            ringtone = RingtoneManager.getRingtone(context, alarmUri).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    isLooping = true
                }

                // Configurar volumen máximo
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()

                play()
            }

            // Para versiones anteriores, loop manual
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                while (isAlerting) {
                    if (ringtone?.isPlaying == false) {
                        ringtone?.play()
                    }
                    delay(SOUND_LOOP_DELAY)
                }
            } else {

            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing alarm sound", e)
        }
    }

    private fun attemptEmergencyCall() {
        // Verificar si tenemos permiso para hacer llamadas
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.CALL_PHONE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "No permission to make phone calls")
            return
        }

        // Obtener número de emergencia configurado
        val sharedPrefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val emergencyNumber = sharedPrefs.getString("emergency_number", null)

        if (emergencyNumber.isNullOrEmpty()) {
            Log.d(TAG, "No emergency number configured")
            return
        }

        try {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$emergencyNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(callIntent)
            Log.d(TAG, "Emergency call initiated to $emergencyNumber")
        } catch (e: Exception) {
            Log.e(TAG, "Error making emergency call", e)
        }
    }

    private fun acquireWakeLock() {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
            WAKE_LOCK_TAG
        ).apply {
            acquire(ALERT_DURATION + 5000) // Un poco más que la duración de la alerta
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }
}