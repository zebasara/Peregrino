package com.zebass.peregrino.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.zebass.peregrino.MainActivity
import com.zebass.peregrino.R
import com.zebass.peregrino.SecondFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ✅ SERVICIO DE SEGURIDAD EN SEGUNDO PLANO
 *
 * Este servicio:
 * - Funciona 24/7 en segundo plano
 * - Monitorea la zona segura
 * - Activa alarma crítica si el vehículo sale de la zona
 * - Sobrevive a reinicios del sistema
 * - Usa muy poca batería
 */
@RequiresApi(Build.VERSION_CODES.O)
class BackgroundSecurityService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 3001
        private const val CRITICAL_ALERT_ID = 9998
        private const val CHANNEL_ID = "security_tracking_channel"
        private const val ALERT_CHANNEL_ID = "critical_security_alerts"
        private const val TAG = "BackgroundSecurityService"

        // ✅ ACCIONES DEL SERVICIO
        const val ACTION_START_SECURITY = "com.peregrino.START_SECURITY"
        const val ACTION_STOP_SECURITY = "com.peregrino.STOP_SECURITY"
        const val ACTION_SILENT_MODE = "com.peregrino.SILENT_MODE"
        const val ACTION_CHECK_STATUS = "com.peregrino.CHECK_STATUS"

        // ✅ EXTRAS
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_JWT_TOKEN = "jwt_token"
        const val EXTRA_SAFE_ZONE_LAT = "safe_zone_lat"
        const val EXTRA_SAFE_ZONE_LON = "safe_zone_lon"
        const val EXTRA_SAFE_ZONE_RADIUS = "safe_zone_radius"

        // ✅ CONFIGURACIÓN DE SEGURIDAD
        private const val DEFAULT_SAFE_ZONE_RADIUS = 15.0 // metros
        private const val POSITION_CHECK_INTERVAL = 10000L // 10 segundos
        private const val WEBSOCKET_RECONNECT_DELAY = 5000L // 5 segundos
        private const val MAX_RECONNECT_ATTEMPTS = 20
    }

    // ============ VARIABLES DE ESTADO ============
    private var deviceId: String? = null
    private var jwtToken: String? = null
    private var safeZoneLat: Double = 0.0
    private var safeZoneLon: Double = 0.0
    private var safeZoneRadius: Double = DEFAULT_SAFE_ZONE_RADIUS
    private var isInSilentMode = false
    private var isSecurityActive = false

    // ============ WEBSOCKET Y NETWORKING ============
    private var webSocket: WebSocket? = null
    private var isWebSocketConnected = false
    private var reconnectAttempts = 0
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectJob: Job? = null
    private var positionCheckJob: Job? = null

    // ✅ HTTP CLIENT OPTIMIZADO PARA BACKGROUND
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    // ============ COMPONENTES DE ALARMA ============
    private var wakeLock: PowerManager.WakeLock? = null
    private var isAlarmActive = false
    private var alarmStartTime = 0L
    private val handler = Handler(Looper.getMainLooper())

    // ============ BROADCAST RECEIVERS ============
    private val securityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.peregrino.STOP_ALARM_BROADCAST" -> {
                    Log.d(TAG, "🔇 Recibido comando para detener alarma")
                    stopCriticalAlarm()
                }
                "com.peregrino.DISABLE_SAFEZONE_BROADCAST" -> {
                    Log.d(TAG, "🛡️ Recibido comando para desactivar zona segura")
                    disableSafeZoneTemporarily()
                }
                "com.peregrino.EMERGENCY_STOP_ALL" -> {
                    Log.d(TAG, "🚨 PARADA DE EMERGENCIA TOTAL")
                    emergencyStopAll()
                }
            }
        }
    }

    // ============ LIFECYCLE DEL SERVICIO ============

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        registerSecurityReceiver()
        Log.d(TAG, "🛡️ BackgroundSecurityService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SECURITY -> {
                deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
                jwtToken = intent.getStringExtra(EXTRA_JWT_TOKEN)
                safeZoneLat = intent.getDoubleExtra(EXTRA_SAFE_ZONE_LAT, 0.0)
                safeZoneLon = intent.getDoubleExtra(EXTRA_SAFE_ZONE_LON, 0.0)
                safeZoneRadius = intent.getDoubleExtra(EXTRA_SAFE_ZONE_RADIUS, DEFAULT_SAFE_ZONE_RADIUS)

                startSecurityMonitoring()
            }
            ACTION_SILENT_MODE -> {
                enableSilentMode()
            }
            ACTION_STOP_SECURITY -> {
                stopSecurityMonitoring()
            }
            ACTION_CHECK_STATUS -> {
                updateSecurityNotification()
            }
        }

        // ✅ CRÍTICO: Reiniciar automáticamente si el sistema mata el servicio
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        Log.d(TAG, "🗑️ BackgroundSecurityService destroying...")

        // ✅ CLEANUP COMPLETO
        stopCriticalAlarm()
        disconnectWebSocket()
        serviceScope.cancel()
        unregisterSecurityReceiver()
        releaseWakeLock()

        Log.d(TAG, "✅ BackgroundSecurityService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ============ CONFIGURACIÓN INICIAL ============

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NotificationManager::class.java)

        // ✅ CANAL PARA NOTIFICACIONES NORMALES
        val normalChannel = NotificationChannel(
            CHANNEL_ID,
            "Seguridad GPS",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Monitoreo de seguridad GPS en segundo plano"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }

        // ✅ CANAL PARA ALERTAS CRÍTICAS
        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "⚠️ ALERTAS CRÍTICAS DE SEGURIDAD",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alertas críticas cuando el vehículo sale de la zona segura"
            enableLights(true)
            lightColor = Color.RED
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 1000, 300, 1000, 300, 1000)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setFlags(android.media.AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                    .build()
            )
            setBypassDnd(true) // ✅ IGNORAR NO MOLESTAR
        }

        notificationManager.createNotificationChannel(normalChannel)
        notificationManager.createNotificationChannel(alertChannel)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerSecurityReceiver() {
        val filter = IntentFilter().apply {
            addAction("com.peregrino.STOP_ALARM_BROADCAST")
            addAction("com.peregrino.DISABLE_SAFEZONE_BROADCAST")
            addAction("com.peregrino.EMERGENCY_STOP_ALL")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(
                this,
                securityReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(securityReceiver, filter)
        }

        Log.d(TAG, "✅ Security receiver registered")
    }

    private fun unregisterSecurityReceiver() {
        try {
            unregisterReceiver(securityReceiver)
            Log.d(TAG, "✅ Security receiver unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Security receiver already unregistered: ${e.message}")
        }
    }

    // ============ INICIO DEL MONITOREO DE SEGURIDAD ============

    private fun startSecurityMonitoring() {
        if (deviceId.isNullOrEmpty() || jwtToken.isNullOrEmpty()) {
            Log.e(TAG, "❌ Missing security data - deviceId: $deviceId, hasToken: ${!jwtToken.isNullOrEmpty()}")
            stopSelf()
            return
        }

        if (safeZoneLat == 0.0 || safeZoneLon == 0.0) {
            Log.e(TAG, "❌ Invalid safe zone coordinates")
            stopSelf()
            return
        }

        Log.d(TAG, "🛡️ INICIANDO MONITOREO DE SEGURIDAD")
        Log.d(TAG, "   Device: $deviceId")
        Log.d(TAG, "   Safe Zone: $safeZoneLat, $safeZoneLon (radio: ${safeZoneRadius}m)")

        isSecurityActive = true

        // ✅ GUARDAR CONFIGURACIÓN EN PREFERENCES
        saveSecurityConfiguration()

        // ✅ INICIAR FOREGROUND SERVICE
        val notification = createSecurityNotification()
        startForeground(NOTIFICATION_ID, notification)

        // ✅ CONECTAR WEBSOCKET PARA MONITOREO EN TIEMPO REAL
        connectSecurityWebSocket()

        // ✅ INICIAR VERIFICACIÓN PERIÓDICA (FALLBACK)
        startPeriodicPositionCheck()

        Log.d(TAG, "✅ Security monitoring started successfully")
    }

    private fun saveSecurityConfiguration() {
        getSharedPreferences("security_prefs", MODE_PRIVATE).edit {
            putString("device_id", deviceId)
            putString("jwt_token", jwtToken)
            putString("safe_zone_lat", safeZoneLat.toString())
            putString("safe_zone_lon", safeZoneLon.toString())
            putString("safe_zone_radius", safeZoneRadius.toString())
            putBoolean("security_active", true)
            putLong("security_start_time", System.currentTimeMillis())
        }
    }

    private fun clearSecurityConfiguration() {
        getSharedPreferences("security_prefs", MODE_PRIVATE).edit {
            clear()
        }
    }

    // ============ WEBSOCKET PARA MONITOREO EN TIEMPO REAL ============

    private fun connectSecurityWebSocket() {
        if (jwtToken.isNullOrEmpty() || deviceId.isNullOrEmpty()) {
            Log.e(TAG, "❌ Cannot connect WebSocket - missing credentials")
            return
        }

        // ✅ CERRAR CONEXIÓN ANTERIOR
        disconnectWebSocket()

        val wsUrl = "wss://app.socialengeneering.work/ws?token=$jwtToken"
        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("User-Agent", "PeregrinoGPS-Security-Background/3.0")
            .addHeader("X-Security-Mode", "true")
            .addHeader("X-Background-Service", "true")
            .build()

        Log.d(TAG, "🔌 Connecting SECURITY WebSocket...")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "✅ SECURITY WebSocket connected")
                isWebSocketConnected = true
                reconnectAttempts = 0

                // ✅ SUSCRIPCIÓN ESPECÍFICA PARA SEGURIDAD
                val subscribeMessage = JSONObject().apply {
                    put("type", "SUBSCRIBE_SECURITY")
                    put("deviceId", deviceId)
                    put("safeZone", JSONObject().apply {
                        put("latitude", safeZoneLat)
                        put("longitude", safeZoneLon)
                        put("radius", safeZoneRadius)
                    })
                    put("backgroundMode", true)
                    put("securityMode", true)
                    put("timestamp", System.currentTimeMillis())
                }

                try {
                    webSocket.send(subscribeMessage.toString())
                    Log.d(TAG, "📡 SECURITY subscription sent successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error sending subscription: ${e.message}")
                }

                updateSecurityNotification()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "📨 SECURITY message: ${text.take(100)}...")

                try {
                    handleSecurityWebSocketMessage(text)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error handling SECURITY message: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "❌ SECURITY WebSocket error: ${t.message}")
                isWebSocketConnected = false
                updateSecurityNotification()

                // ✅ RECONEXIÓN AUTOMÁTICA PARA SEGURIDAD CRÍTICA
                scheduleSecurityReconnection()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "🔌 SECURITY WebSocket closed: $code - $reason")
                isWebSocketConnected = false
                updateSecurityNotification()

                // ✅ RECONECTAR SI NO FUE CIERRE INTENCIONAL
                if (code != 1000 && isSecurityActive) {
                    scheduleSecurityReconnection()
                }
            }
        })
    }

    private fun disconnectWebSocket() {
        try {
            webSocket?.close(1000, "Security stopped")
            webSocket = null
            isWebSocketConnected = false
        } catch (e: Exception) {
            Log.w(TAG, "Error closing WebSocket: ${e.message}")
        }
    }

    // ============ MANEJO DE MENSAJES WEBSOCKET ============

    private fun handleSecurityWebSocketMessage(message: String) {
        val json = JSONObject(message)
        val type = json.getString("type")

        when (type) {
            "POSITION_UPDATE" -> {
                val data = json.getJSONObject("data")
                val deviceIdFromMessage = data.getString("deviceId")

                if (deviceIdFromMessage == deviceId) {
                    val latitude = data.getDouble("latitude")
                    val longitude = data.getDouble("longitude")
                    val timestamp = data.optLong("timestamp", System.currentTimeMillis())

                    Log.d(TAG, "🎯 SECURITY position update: lat=$latitude, lon=$longitude")

                    // ✅ VERIFICAR ZONA SEGURA INMEDIATAMENTE
                    checkSafeZoneViolation(latitude, longitude, timestamp)
                }
            }

            "SAFE_ZONE_ALERT" -> {
                val distance = json.getDouble("distance")
                val timestamp = json.optLong("timestamp", System.currentTimeMillis())

                Log.w(TAG, "🚨 SECURITY ALERT from server: Vehicle outside safe zone by ${distance}m")
                triggerCriticalSecurityAlert(distance, timestamp)
            }

            "CONNECTION_CONFIRMED" -> {
                Log.d(TAG, "✅ SECURITY WebSocket subscription confirmed")
                isWebSocketConnected = true
                updateSecurityNotification()
            }

            "PING" -> {
                // ✅ RESPONDER A PINGS PARA MANTENER CONEXIÓN
                val pongMessage = JSONObject().apply {
                    put("type", "PONG")
                    put("timestamp", System.currentTimeMillis())
                }
                webSocket?.send(pongMessage.toString())
            }

            "ERROR" -> {
                val errorMsg = json.optString("message", "Error desconocido")
                Log.e(TAG, "❌ SECURITY WebSocket server error: $errorMsg")
            }
        }
    }

    // ============ VERIFICACIÓN DE ZONA SEGURA ============

    private fun checkSafeZoneViolation(latitude: Double, longitude: Double, timestamp: Long) {
        if (safeZoneLat == 0.0 || safeZoneLon == 0.0) {
            Log.w(TAG, "⚠️ Safe zone not configured, skipping check")
            return
        }

        val distance = calculateDistance(safeZoneLat, safeZoneLon, latitude, longitude)

        Log.d(TAG, "📏 Distance to safe zone: ${String.format("%.1f", distance)}m (limit: ${safeZoneRadius}m)")

        if (distance > safeZoneRadius) {
            Log.w(TAG, "🚨 VEHICLE OUTSIDE SAFE ZONE! Distance: ${distance}m")
            triggerCriticalSecurityAlert(distance, timestamp)
        } else {
            Log.d(TAG, "✅ Vehicle within safe zone")

            // ✅ DETENER ALARMA SI ESTABA ACTIVA
            if (isAlarmActive) {
                Log.d(TAG, "🔇 Vehicle returned to safe zone - stopping alarm")
                stopCriticalAlarm()
            }
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0].toDouble()
    }

    // ============ SISTEMA DE ALARMA CRÍTICA ============

    private fun triggerCriticalSecurityAlert(distance: Double, timestamp: Long) {
        if (isAlarmActive) {
            Log.d(TAG, "⚠️ Alarm already active, updating distance")
            updateCriticalAlertNotification(distance)
            return
        }

        Log.e(TAG, "🚨🚨🚨 ACTIVANDO ALARMA CRÍTICA DE SEGURIDAD 🚨🚨🚨")
        Log.e(TAG, "   Device: $deviceId")
        Log.e(TAG, "   Distance: ${String.format("%.1f", distance)}m")
        Log.e(TAG, "   Safe Zone: $safeZoneLat, $safeZoneLon")
        Log.e(TAG, "   Time: ${java.util.Date(timestamp)}")

        isAlarmActive = true
        alarmStartTime = System.currentTimeMillis()

        // ✅ ADQUIRIR WAKE LOCK PARA MANTENER EL DISPOSITIVO DESPIERTO
        acquireWakeLock()

        // ✅ MÚLTIPLES MÉTODOS DE ALERTA
        serviceScope.launch {
            // 1. Notificación crítica
            showCriticalSecurityNotification(distance)

            // 2. Vibración intensa
            triggerAlertVibration()

            // 3. Enviar broadcast a la app (si está abierta)
            sendSecurityBroadcast(distance, timestamp)

            // 4. Guardar evento de seguridad
            logSecurityEvent(distance, timestamp)

            // 5. Auto-detener después de tiempo límite
            scheduleAlarmAutoStop()
        }
    }

    private fun showCriticalSecurityNotification(distance: Double) {
        // ✅ INTENT PARA ABRIR LA APP
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("SECURITY_ALERT", true)
            putExtra("ALERT_DISTANCE", distance)
            putExtra("ALERT_TIME", System.currentTimeMillis())
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 99, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // ✅ INTENT PARA SILENCIAR ALARMA
        val stopAlarmIntent = Intent(this, AlertStopReceiver::class.java)
        val stopAlarmPendingIntent = PendingIntent.getBroadcast(
            this, 98, stopAlarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // ✅ INTENT PARA DESACTIVAR ZONA SEGURA
        val disableZoneIntent = Intent(this, AlertDisableSafeZoneReceiver::class.java)
        val disableZonePendingIntent = PendingIntent.getBroadcast(
            this, 97, disableZoneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val criticalNotification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle("🚨 ALERTA CRÍTICA DE SEGURIDAD")
            .setContentText("¡VEHÍCULO FUERA DE ZONA SEGURA! (${String.format("%.0f", distance)}m)")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("""
                    🚨 ALERTA CRÍTICA DE SEGURIDAD 🚨
                    
                    Su vehículo ha salido de la zona segura
                    
                    📍 Distancia: ${String.format("%.0f", distance)} metros
                    ⏰ Hora: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}
                    🛡️ Dispositivo: $deviceId
                    
                    Toque para ver detalles o usar las acciones rápidas
                """.trimIndent()))
            .setSmallIcon(R.drawable.ic_security_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setOngoing(true)
            .setColorized(true)
            .setColor(Color.RED)
            .setLights(Color.RED, 1000, 1000)
            .setVibrate(longArrayOf(0, 1000, 500, 1000, 500, 1000))
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            .setContentIntent(openAppPendingIntent)
            .setFullScreenIntent(openAppPendingIntent, true) // ✅ MOSTRAR SOBRE LOCKSCREEN
            .addAction(
                R.drawable.ic_volume_off,
                "🔇 SILENCIAR",
                stopAlarmPendingIntent
            )
            .addAction(
                R.drawable.ic_shield_off,
                "🛡️ DESACTIVAR ZONA",
                disableZonePendingIntent
            )
            .build()

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(this).notify(CRITICAL_ALERT_ID, criticalNotification)
            Log.d(TAG, "📱 Critical security notification shown")
        } else {
            Log.w(TAG, "⚠️ No notification permission for critical alert")
        }
    }

    private fun updateCriticalAlertNotification(distance: Double) {
        // ✅ ACTUALIZAR NOTIFICACIÓN EXISTENTE
        showCriticalSecurityNotification(distance)
    }

    private fun triggerAlertVibration() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator?.hasVibrator() == true) {
                val pattern = longArrayOf(0, 1000, 200, 1000, 200, 1000, 200, 1000)
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
                Log.d(TAG, "📳 Alert vibration triggered")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error with vibration: ${e.message}")
        }
    }

    private fun sendSecurityBroadcast(distance: Double, timestamp: Long) {
        val intent = Intent("com.peregrino.SECURITY_ALERT").apply {
            putExtra("distance", distance)
            putExtra("deviceId", deviceId)
            putExtra("timestamp", timestamp)
            putExtra("safeZoneLat", safeZoneLat)
            putExtra("safeZoneLon", safeZoneLon)
            putExtra("alertType", "VEHICLE_OUTSIDE_SAFE_ZONE")
        }
        sendBroadcast(intent)
        Log.d(TAG, "📡 Security broadcast sent to app")
    }

    private fun logSecurityEvent(distance: Double, timestamp: Long) {
        val prefs = getSharedPreferences("security_events", MODE_PRIVATE)
        val eventJson = JSONObject().apply {
            put("type", "SAFE_ZONE_VIOLATION")
            put("deviceId", deviceId)
            put("distance", distance)
            put("timestamp", timestamp)
            put("safeZoneLat", safeZoneLat)
            put("safeZoneLon", safeZoneLon)
            put("alertStartTime", alarmStartTime)
        }

        prefs.edit {
            putString("last_security_event", eventJson.toString())
            putLong("last_event_time", timestamp)
        }

        Log.d(TAG, "📝 Security event logged")
    }

    private fun scheduleAlarmAutoStop() {
        // ✅ AUTO-DETENER ALARMA DESPUÉS DE 5 MINUTOS
        handler.postDelayed({
            if (isAlarmActive) {
                Log.d(TAG, "⏰ Auto-stopping alarm after timeout")
                stopCriticalAlarm()
            }
        }, 300000) // 5 minutos
    }

    // ============ DETENER ALARMA ============

    private fun stopCriticalAlarm() {
        if (!isAlarmActive) return

        Log.d(TAG, "🔇 DETENIENDO ALARMA CRÍTICA")

        isAlarmActive = false

        // ✅ CANCELAR NOTIFICACIÓN CRÍTICA
        NotificationManagerCompat.from(this).cancel(CRITICAL_ALERT_ID)

        // ✅ DETENER VIBRACIÓN
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        vibrator?.cancel()

        // ✅ LIBERAR WAKE LOCK
        releaseWakeLock()

        // ✅ LIMPIAR HANDLERS
        handler.removeCallbacksAndMessages(null)

        // ✅ ACTUALIZAR NOTIFICACIÓN NORMAL
        updateSecurityNotification()

        // ✅ LOG DEL EVENTO
        val duration = System.currentTimeMillis() - alarmStartTime
        Log.d(TAG, "✅ Alarm stopped after ${duration / 1000} seconds")

        getSharedPreferences("security_events", MODE_PRIVATE).edit {
            putLong("last_alarm_duration", duration)
            putLong("alarm_stopped_time", System.currentTimeMillis())
        }
    }

    private fun emergencyStopAll() {
        Log.e(TAG, "🚨 EMERGENCY STOP ALL - Stopping all alarms and security")

        stopCriticalAlarm()

        // ✅ CANCELAR TODAS LAS NOTIFICACIONES
        NotificationManagerCompat.from(this).cancelAll()

        // ✅ DETENER TODO EL SERVICIO
        stopSecurityMonitoring()
    }

    // ============ WAKE LOCK MANAGEMENT ============

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld != true) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "PeregrinoSecurity:CriticalAlert"
                ).apply {
                    acquire(300000) // 5 minutos máximo
                }
                Log.d(TAG, "🔓 Wake lock acquired for critical alert")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error acquiring wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { wl ->
                if (wl.isHeld) {
                    wl.release()
                    Log.d(TAG, "🔒 Wake lock released")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error releasing wake lock: ${e.message}")
        }
    }

    // ============ RECONEXIÓN AUTOMÁTICA ============

    private fun scheduleSecurityReconnection() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "❌ Max reconnection attempts reached - switching to periodic checks")
            // ✅ FALLBACK: Usar verificación periódica si WebSocket falla
            startPeriodicPositionCheck()
            return
        }

        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            val delayMs = WEBSOCKET_RECONNECT_DELAY * (reconnectAttempts + 1)
            Log.d(TAG, "🔄 Scheduling security reconnection in ${delayMs}ms (attempt ${reconnectAttempts + 1})")

            delay(delayMs)

            if (isSecurityActive && deviceId != null && jwtToken != null) {
                reconnectAttempts++
                Log.d(TAG, "🔄 Attempting security WebSocket reconnection...")
                connectSecurityWebSocket()
            }
        }
    }

    // ============ VERIFICACIÓN PERIÓDICA (FALLBACK) ============

    private fun startPeriodicPositionCheck() {
        if (deviceId.isNullOrEmpty() || jwtToken.isNullOrEmpty()) return

        positionCheckJob?.cancel()
        positionCheckJob = serviceScope.launch {
            Log.d(TAG, "⏰ Starting periodic position check (fallback mode)")

            while (isSecurityActive) {
                try {
                    checkPositionViaAPI()
                    delay(POSITION_CHECK_INTERVAL)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error in periodic check: ${e.message}")
                    delay(POSITION_CHECK_INTERVAL * 2) // Aumentar delay en caso de error
                }
            }
        }
    }

    private suspend fun checkPositionViaAPI() {
        try {
            val request = Request.Builder()
                .url("https://app.socialengeneering.work/api/last-position?deviceId=$deviceId&maxAge=60")
                .get()
                .addHeader("Authorization", "Bearer $jwtToken")
                .addHeader("User-Agent", "PeregrinoGPS-Security-Fallback/3.0")
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                val json = JSONObject(responseBody ?: "{}")

                val latitude = json.getDouble("latitude")
                val longitude = json.getDouble("longitude")
                val timestamp = System.currentTimeMillis()

                Log.d(TAG, "📍 Position via API: lat=$latitude, lon=$longitude")
                checkSafeZoneViolation(latitude, longitude, timestamp)

            } else {
                Log.w(TAG, "⚠️ API position check failed: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error checking position via API: ${e.message}")
        }
    }

    // ============ MODO SILENCIOSO ============

    private fun enableSilentMode() {
        isInSilentMode = true
        updateSecurityNotification()

        Log.d(TAG, "🔇 Silent mode enabled - notifications minimized")

        // ✅ GUARDAR PREFERENCIA
        getSharedPreferences("security_prefs", MODE_PRIVATE).edit {
            putBoolean("silent_mode", true)
        }
    }

    // ============ DESACTIVAR ZONA SEGURA TEMPORALMENTE ============

    private fun disableSafeZoneTemporarily() {
        Log.d(TAG, "🛡️ Disabling safe zone temporarily")

        // ✅ DETENER ALARMA SI ESTÁ ACTIVA
        stopCriticalAlarm()

        // ✅ MARCAR COMO TEMPORALMENTE DESACTIVADA
        getSharedPreferences("security_prefs", MODE_PRIVATE).edit {
            putBoolean("zone_temporarily_disabled", true)
            putLong("zone_disabled_time", System.currentTimeMillis())
        }

        // ✅ REACTIVAR AUTOMÁTICAMENTE DESPUÉS DE 1 HORA
        handler.postDelayed({
            Log.d(TAG, "🛡️ Auto-reactivating safe zone after temporary disable")
            getSharedPreferences("security_prefs", MODE_PRIVATE).edit {
                putBoolean("zone_temporarily_disabled", false)
            }
            updateSecurityNotification()
        }, 3600000) // 1 hora

        updateSecurityNotification()
    }

    // ============ NOTIFICACIONES DEL SERVICIO ============

    private fun createSecurityNotification(): android.app.Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val silentModeIntent = Intent(this, BackgroundSecurityService::class.java).apply {
            action = ACTION_SILENT_MODE
        }
        val silentModePendingIntent = PendingIntent.getService(
            this, 1, silentModeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopSecurityIntent = Intent(this, BackgroundSecurityService::class.java).apply {
            action = ACTION_STOP_SECURITY
        }
        val stopSecurityPendingIntent = PendingIntent.getService(
            this, 2, stopSecurityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(buildNotificationTitle())
            .setContentText(buildNotificationText())
            .setSmallIcon(R.drawable.ic_security_shield)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .apply {
                if (!isInSilentMode) {
                    addAction(
                        R.drawable.ic_silent,
                        "🔇 Silencioso",
                        silentModePendingIntent
                    )
                    addAction(
                        R.drawable.ic_stop,
                        "🛑 Detener",
                        stopSecurityPendingIntent
                    )
                }
            }
            .build()
    }

    private fun buildNotificationTitle(): String {
        return when {
            isInSilentMode -> "🔒 Seguridad Silenciosa"
            !isWebSocketConnected -> "🛡️ Seguridad GPS (Offline)"
            else -> "🛡️ Seguridad GPS Activa"
        }
    }

    private fun buildNotificationText(): String {
        val prefs = getSharedPreferences("security_prefs", MODE_PRIVATE)
        val isZoneDisabled = prefs.getBoolean("zone_temporarily_disabled", false)

        return when {
            isZoneDisabled -> "Zona segura temporalmente desactivada"
            isInSilentMode -> "Monitoreo silencioso de zona segura"
            isWebSocketConnected -> "Monitoreando zona segura en tiempo real"
            else -> "Verificando posición cada ${POSITION_CHECK_INTERVAL / 1000}s"
        }
    }

    private fun updateSecurityNotification() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    val notification = createSecurityNotification()
                    NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
                } else {
                    Log.w(TAG, "⚠️ No permission to show notification")
                }
            } else {
                val notification = createSecurityNotification()
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ SecurityException updating notification: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error updating notification: ${e.message}")
        }
    }

    // ============ DETENER MONITOREO DE SEGURIDAD ============

    private fun stopSecurityMonitoring() {
        Log.d(TAG, "🛑 Stopping security monitoring")

        isSecurityActive = false

        // ✅ DETENER ALARMA SI ESTÁ ACTIVA
        stopCriticalAlarm()

        // ✅ DESCONECTAR WEBSOCKET
        disconnectWebSocket()

        // ✅ CANCELAR JOBS
        reconnectJob?.cancel()
        positionCheckJob?.cancel()

        // ✅ LIMPIAR CONFIGURACIÓN
        clearSecurityConfiguration()

        // ✅ DETENER SERVICIO
        stopSelf()

        Log.d(TAG, "✅ Security monitoring stopped completely")
    }

    // ============ MÉTODOS AUXILIARES ============

    fun getSecurityStatus(): SecurityStatus {
        val prefs = getSharedPreferences("security_prefs", MODE_PRIVATE)
        val lastEventTime = prefs.getLong("last_event_time", 0)
        val isZoneDisabled = prefs.getBoolean("zone_temporarily_disabled", false)

        return SecurityStatus(
            isActive = isSecurityActive,
            isWebSocketConnected = isWebSocketConnected,
            isInSilentMode = isInSilentMode,
            isAlarmActive = isAlarmActive,
            isZoneTemporarilyDisabled = isZoneDisabled,
            deviceId = deviceId,
            safeZoneLatitude = safeZoneLat,
            safeZoneLongitude = safeZoneLon,
            safeZoneRadius = safeZoneRadius,
            lastEventTime = lastEventTime,
            reconnectAttempts = reconnectAttempts
        )
    }

    data class SecurityStatus(
        val isActive: Boolean,
        val isWebSocketConnected: Boolean,
        val isInSilentMode: Boolean,
        val isAlarmActive: Boolean,
        val isZoneTemporarilyDisabled: Boolean,
        val deviceId: String?,
        val safeZoneLatitude: Double,
        val safeZoneLongitude: Double,
        val safeZoneRadius: Double,
        val lastEventTime: Long,
        val reconnectAttempts: Int
    )
}