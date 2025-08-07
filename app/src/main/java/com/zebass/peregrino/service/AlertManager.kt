package com.zebass.peregrino.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.telecom.TelecomManager
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zebass.peregrino.R
import com.zebass.peregrino.SecondFragment
import kotlinx.coroutines.*

class AlertManager(private val context: Context) {

    companion object {
        private const val TAG = "AlertManager"
        private const val ALERT_CHANNEL_ID = "geofence_alert_channel"
        private const val ALERT_NOTIFICATION_ID = 9999
        private const val WAKE_LOCK_TAG = "Peregrino:AlertWakeLock"

        // ✅ CONFIGURACIÓN DE ALARMA MÁS INTENSA
        const val ALERT_DURATION = 120000L // 2 minutos de alarma continua
        const val VIBRATION_PATTERN_DURATION = 1500L // Vibración más larga
        const val SOUND_LOOP_DELAY = 1000L // Sonido más frecuente
        const val MAX_ALARM_VOLUME = 1.0f

        // ✅ CONFIGURACIÓN DE ESCALADO
        const val ALARM_ESCALATION_TIME = 30000L // 30 segundos para escalar
        const val FLASHLIGHT_BLINK_INTERVAL = 500L // Parpadeo cada 500ms
    }

    private var alertJob: Job? = null
    private var ringtone: Ringtone? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isAlerting = false
    private var alertStartTime = 0L

    // ✅ COMPONENTES PARA ALARMA INTENSA
    private var originalVolume = 0
    private var audioManager: AudioManager? = null
    private var cameraManager: CameraManager? = null
    private var cameraId: String? = null

    init {
        createAlertChannel()
        setupAudioManager()
        setupCameraManager()
    }

    private fun createAlertChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "⚠️ ALERTAS CRÍTICAS",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alertas críticas cuando el vehículo sale de la zona segura"
                enableLights(true)
                lightColor = android.graphics.Color.RED
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 300, 1000, 300, 1000)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                        .build()
                )
                setBypassDnd(true) // ✅ IGNORAR NO MOLESTAR
                canShowBadge()
            }

            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun setupAudioManager() {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        originalVolume = audioManager?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 0
    }

    private fun setupCameraManager() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                cameraId = cameraManager?.cameraIdList?.firstOrNull { id ->
                    val characteristics = cameraManager?.getCameraCharacteristics(id)
                    characteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Flash not available: ${e.message}")
            }
        }
    }

    /**
     * ✅ INICIA LA ALARMA CRÍTICA ULTRA-INTENSA
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun startCriticalAlert(vehicleId: Int, distance: Double) {
        if (isAlerting) {
            Log.d(TAG, "⚠️ Alarma ya activa, actualizando distancia")
            updateAlertNotification(vehicleId, distance)
            return
        }

        Log.e(TAG, "🚨🚨🚨 INICIANDO ALARMA CRÍTICA 🚨🚨🚨")

        isAlerting = true
        alertStartTime = System.currentTimeMillis()
        acquireWakeLock()

        // ✅ INICIAR TODOS LOS COMPONENTES DE ALARMA
        alertJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                // ✅ CONFIGURACIÓN INICIAL
                setMaxVolume()

                // ✅ INICIAR COMPONENTES EN PARALELO
                supervisorScope {
                    launch { showCriticalNotification(vehicleId, distance) }
                    launch { startIntensiveVibration() }
                    launch { startLoudAlarmSound() }
                    launch { startFlashlightBlink() }
                    launch { attemptScreenWakeUp() }
                }

                // ✅ ESCALADO DE ALARMA DESPUÉS DE 30 SEGUNDOS
                delay(ALARM_ESCALATION_TIME)
                if (isAlerting) {
                    escalateAlarm(vehicleId, distance)
                }

                // ✅ MANTENER ALARMA POR TIEMPO COMPLETO
                delay(ALERT_DURATION - ALARM_ESCALATION_TIME)

            } catch (e: CancellationException) {
                Log.d(TAG, "🔇 Alarma cancelada por usuario")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error en alarma crítica", e)
            } finally {
                cleanupAlert()
            }
        }
    }

    /**
     * ✅ ESCALADO DE ALARMA - MÁS INTENSA DESPUÉS DE 30 SEGUNDOS
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun escalateAlarm(vehicleId: Int, distance: Double) {
        Log.e(TAG, "📈 ESCALANDO ALARMA - NIVEL CRÍTICO MÁXIMO")

        supervisorScope {
            // ✅ VIBRACIÓN MÁS INTENSA
            launch { startMaxVibration() }

            // ✅ INTENTAR LLAMADA DE EMERGENCIA
            launch { attemptEmergencyCall() }

            // ✅ NOTIFICACIÓN DE ESCALADO
            launch { showEscalatedNotification(vehicleId, distance) }
        }
    }

    /**
     * ✅ DETIENE TODA LA ALARMA INMEDIATAMENTE
     */
    fun stopAlert() {
        if (!isAlerting) return

        Log.d(TAG, "🔇 DETENIENDO ALARMA CRÍTICA")
        isAlerting = false

        // ✅ CANCELAR JOB PRINCIPAL
        alertJob?.cancel()
        alertJob = null

        cleanupAlert()
    }

    private fun cleanupAlert() {
        // ✅ DETENER SONIDOS
        ringtone?.stop()
        ringtone = null

        mediaPlayer?.let {
            try {
                it.stop()
                it.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping media player: ${e.message}")
            }
        }
        mediaPlayer = null

        // ✅ DETENER VIBRACIÓN
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        vibrator?.cancel()

        // ✅ DETENER FLASH
        stopFlashlight()

        // ✅ RESTAURAR VOLUMEN ORIGINAL
        restoreOriginalVolume()

        // ✅ LIBERAR WAKE LOCK
        releaseWakeLock()

        // ✅ CANCELAR NOTIFICACIONES
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(ALERT_NOTIFICATION_ID)
        notificationManager.cancel(ALERT_NOTIFICATION_ID + 1) // Escalated notification

        Log.d(TAG, "✅ Limpieza de alarma completada")
    }

    private fun showCriticalNotification(vehicleId: Int, distance: Double) {
        val stopIntent = Intent(context, AlertStopReceiver::class.java)
        val stopPendingIntent = PendingIntent.getBroadcast(
            context, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // ✅ INTENT PARA DESACTIVAR ZONA SEGURA RÁPIDAMENTE
        val disableSafeZoneIntent = Intent(context, AlertDisableSafeZoneReceiver::class.java)
        val disableSafeZonePendingIntent = PendingIntent.getBroadcast(
            context, 1, disableSafeZoneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vehicle_alert)
            .setContentTitle("🚨 ¡VEHÍCULO FUERA DE ZONA SEGURA!")
            .setContentText("Distancia: ${distance.toInt()}m de la zona segura")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("🚨 ALERTA CRÍTICA\n\n" +
                        "Vehículo $vehicleId está a ${distance.toInt()}m de la zona segura.\n\n" +
                        "• Toca SILENCIAR para detener la alarma\n" +
                        "• Toca DESACTIVAR ZONA si es un error\n\n" +
                        "⚠️ Esta alarma continuará por 2 minutos"))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(createFullScreenIntent(), true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColorized(true)
            .setColor(android.graphics.Color.RED)
            .addAction(
                R.drawable.ic_volume_off,
                "🔇 SILENCIAR",
                stopPendingIntent
            )
            .addAction(
                R.drawable.ic_shield_off,
                "🛡️ DESACTIVAR ZONA",
                disableSafeZonePendingIntent
            )
            .setVibrate(longArrayOf(0, 1000, 300, 1000, 300, 1000, 300, 1000))
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            .setTimeoutAfter(ALERT_DURATION)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(ALERT_NOTIFICATION_ID, notification)

        Log.d(TAG, "📱 Notificación crítica mostrada")
    }

    private fun updateAlertNotification(vehicleId: Int, distance: Double) {
        showCriticalNotification(vehicleId, distance)
    }

    private fun showEscalatedNotification(vehicleId: Int, distance: Double) {
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vehicle_alert)
            .setContentTitle("🚨🚨 ALERTA ESCALADA - ACCIÓN REQUERIDA")
            .setContentText("⚠️ Vehículo sigue fuera - ${distance.toInt()}m")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("🚨🚨 ALERTA ESCALADA 🚨🚨\n\n" +
                        "El vehículo $vehicleId continúa fuera de la zona segura.\n\n" +
                        "Distancia actual: ${distance.toInt()}m\n" +
                        "Tiempo transcurrido: ${(System.currentTimeMillis() - alertStartTime) / 1000}s\n\n" +
                        "⚠️ ACCIÓN REQUERIDA INMEDIATAMENTE"))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setColorized(true)
            .setColor(android.graphics.Color.RED)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(ALERT_NOTIFICATION_ID + 1, notification)
    }

    private fun createFullScreenIntent(): PendingIntent {
        val fullScreenIntent = Intent(context, AlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("alert_distance", 0.0) // Se actualizará dinámicamente
            putExtra("vehicle_id", 0) // Se actualizará dinámicamente
        }

        return PendingIntent.getActivity(
            context, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ✅ VIBRACIÓN ULTRA-INTENSIVA
    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun startIntensiveVibration() = withContext(Dispatchers.IO) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (vibrator?.hasVibrator() != true) return@withContext

        Log.d(TAG, "📳 Iniciando vibración intensiva")

        while (isAlerting) {
            try {
                // ✅ PATRÓN DE VIBRACIÓN INTENSO Y VARIABLE
                val patterns = listOf(
                    longArrayOf(0, 1000, 200, 1000, 200, 1000, 200, 1000),
                    longArrayOf(0, 500, 100, 500, 100, 500, 100, 2000),
                    longArrayOf(0, 2000, 500, 1000, 300, 1000, 300, 1000)
                )

                val randomPattern = patterns.random()
                val vibrationEffect = VibrationEffect.createWaveform(randomPattern, -1)
                vibrator.vibrate(vibrationEffect)

                delay(randomPattern.sum() + 500)

            } catch (e: Exception) {
                Log.e(TAG, "Error en vibración: ${e.message}")
                delay(2000)
            }
        }
    }

    // ✅ VIBRACIÓN MÁXIMA PARA ESCALADO
    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun startMaxVibration() = withContext(Dispatchers.IO) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (vibrator?.hasVibrator() != true) return@withContext

        Log.d(TAG, "📳📳 VIBRACIÓN MÁXIMA ACTIVADA")

        while (isAlerting) {
            try {
                // ✅ VIBRACIÓN CONTINUA Y MUY INTENSA
                val maxVibration = VibrationEffect.createOneShot(3000, 255) // Máxima intensidad
                vibrator.vibrate(maxVibration)
                delay(3500)
            } catch (e: Exception) {
                delay(2000)
            }
        }
    }

    // ✅ SONIDO DE ALARMA ULTRA-FUERTE
    private suspend fun startLoudAlarmSound() = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔊 Iniciando sonido de alarma ultra-fuerte")

            // ✅ CONFIGURAR VOLUMEN AL MÁXIMO
            setMaxVolume()

            // ✅ USAR MÚLTIPLES FUENTES DE SONIDO
            supervisorScope {
                launch { playRingtoneAlarm() }
                launch { playMediaPlayerAlarm() }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error en sonido de alarma", e)
        }
    }

    private suspend fun playRingtoneAlarm() {
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            ringtone = RingtoneManager.getRingtone(context, alarmUri).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    isLooping = true
                    volume = MAX_ALARM_VOLUME
                }

                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                    .build()

                play()
            }

            // ✅ LOOP MANUAL PARA VERSIONES ANTERIORES
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                while (isAlerting) {
                    if (ringtone?.isPlaying == false) {
                        ringtone?.play()
                    }
                    delay(SOUND_LOOP_DELAY)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en ringtone: ${e.message}")
        }
    }

    private suspend fun playMediaPlayerAlarm() {
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, alarmUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                setVolume(MAX_ALARM_VOLUME, MAX_ALARM_VOLUME)
                prepare()
                start()
            }

            Log.d(TAG, "🎵 MediaPlayer alarm iniciado")

            // ✅ MONITOREAR ESTADO
            while (isAlerting && mediaPlayer?.isPlaying == true) {
                delay(1000)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error en MediaPlayer: ${e.message}")
        }
    }

    // ✅ FLASH PARPADEANTE
    @RequiresApi(Build.VERSION_CODES.M)
    private suspend fun startFlashlightBlink() = withContext(Dispatchers.IO) {
        if (cameraManager == null || cameraId == null) {
            Log.w(TAG, "Flash no disponible")
            return@withContext
        }

        Log.d(TAG, "💡 Iniciando flash parpadeante")

        try {
            var isFlashOn = false
            while (isAlerting) {
                try {
                    cameraManager?.setTorchMode(cameraId!!, isFlashOn)
                    isFlashOn = !isFlashOn
                    delay(FLASHLIGHT_BLINK_INTERVAL)
                } catch (e: Exception) {
                    Log.w(TAG, "Error controlando flash: ${e.message}")
                    delay(1000)
                }
            }
        } finally {
            stopFlashlight()
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun stopFlashlight() {
        try {
            if (cameraManager != null && cameraId != null) {
                cameraManager?.setTorchMode(cameraId!!, false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error deteniendo flash: ${e.message}")
        }
    }

    // ✅ DESPERTAR PANTALLA
    private suspend fun attemptScreenWakeUp() {
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val screenWakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "Peregrino:ScreenWakeLock"
            )

            screenWakeLock.acquire(5000) // 5 segundos

            // ✅ INTENTAR ABRIR ACTIVITY DE ALARMA
            val intent = Intent(context, AlertActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            context.startActivity(intent)

            delay(5000)
            if (screenWakeLock.isHeld) {
                screenWakeLock.release()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error despertando pantalla: ${e.message}")
        }
    }

    private fun setMaxVolume() {
        try {
            audioManager?.let { am ->
                originalVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
                val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                am.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
                Log.d(TAG, "🔊 Volumen configurado al máximo: $maxVolume")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error configurando volumen: ${e.message}")
        }
    }

    private fun restoreOriginalVolume() {
        try {
            audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0)
            Log.d(TAG, "🔊 Volumen restaurado: $originalVolume")
        } catch (e: Exception) {
            Log.e(TAG, "Error restaurando volumen: ${e.message}")
        }
    }

    private fun attemptEmergencyCall() {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.CALL_PHONE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "❌ Sin permisos para llamadas de emergencia")
            return
        }

        val sharedPrefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val emergencyNumber = sharedPrefs.getString("emergency_number", null)

        if (emergencyNumber.isNullOrEmpty()) {
            Log.d(TAG, "ℹ️ Número de emergencia no configurado")
            return
        }

        try {
            Log.w(TAG, "📞 Intentando llamada de emergencia a: $emergencyNumber")

            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$emergencyNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(callIntent)

            Log.d(TAG, "✅ Llamada de emergencia iniciada")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error en llamada de emergencia", e)
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                WAKE_LOCK_TAG
            ).apply {
                acquire(ALERT_DURATION + 10000) // Extra tiempo por seguridad
            }
            Log.d(TAG, "🔓 Wake lock adquirido")
        } catch (e: Exception) {
            Log.e(TAG, "Error adquiriendo wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            try {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "🔒 Wake lock liberado")
                } else {

                }
            } catch (e: Exception) {
                Log.e(TAG, "Error liberando wake lock: ${e.message}")
            }
        }
        wakeLock = null
    }
}
