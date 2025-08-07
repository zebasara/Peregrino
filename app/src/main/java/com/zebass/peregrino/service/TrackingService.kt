package com.zebass.peregrino.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import com.zebass.peregrino.R
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.zebass.peregrino.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.util.concurrent.TimeUnit

class TrackingService : Service() {

    companion object {
        private const val TAG = "TrackingService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "tracking_channel"
        private const val LOCATION_UPDATE_INTERVAL = 5000L // 5 segundos
        private const val FASTEST_UPDATE_INTERVAL = 2000L // 2 segundos
        private const val MIN_DISTANCE_CHANGE = 5f // 5 metros

        // ✅ CONFIGURACIÓN DE GEOFENCE
        private const val GEOFENCE_RADIUS = 15.0 // 15 metros como en el Fragment

        // ✅ ACTIONS PARA CONTROL DE ALARMA
        const val ACTION_STOP_ALARM = "com.peregrino.STOP_ALARM"
        const val ACTION_DISABLE_SAFEZONE = "com.peregrino.DISABLE_SAFEZONE"
        const val ACTION_EMERGENCY_DISABLE = "com.peregrino.EMERGENCY_DISABLE"
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var webSocket: WebSocket? = null
    private var isServiceRunning = false
    private var alertManager: AlertManager? = null

    // ✅ DATOS DEL DISPOSITIVO
    private var deviceUniqueId: String? = null
    private var jwtToken: String? = null

    // ✅ ZONA SEGURA
    private var safeZoneCenter: GeoPoint? = null
    private var isSafeZoneActive = false
    private var isCurrentlyOutsideSafeZone = false
    private var lastAlertTime = 0L
    private val alertCooldownTime = 30000L // 30 segundos entre alertas

    // ✅ HTTP CLIENT OPTIMIZADO
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🚀 TrackingService creado")

        createNotificationChannel()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        alertManager = AlertManager(this)

        // ✅ REGISTRAR BROADCAST RECEIVER PARA ACCIONES DE ALARMA
        registerAlarmReceiver()

        // ✅ CARGAR ZONA SEGURA
        loadSafeZone()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "📱 TrackingService iniciado")

        // ✅ MANEJAR ACCIONES ESPECIALES DE ALARMA
        when (intent?.action) {
            ACTION_STOP_ALARM -> {
                Log.d(TAG, "🔇 Deteniendo alarma por acción del usuario")
                stopAlarm()
                return START_STICKY
            }
            ACTION_DISABLE_SAFEZONE -> {
                Log.d(TAG, "🛡️ Desactivando zona segura por acción del usuario")
                disableSafeZone()
                return START_STICKY
            }
            ACTION_EMERGENCY_DISABLE -> {
                Log.d(TAG, "🚨 Desactivación de emergencia - deteniendo todo")
                emergencyDisable()
                return START_STICKY
            }
        }

        // ✅ OBTENER DATOS DEL INTENT
        deviceUniqueId = intent?.getStringExtra("deviceUniqueId")
        jwtToken = intent?.getStringExtra("jwtToken")

        if (deviceUniqueId == null || jwtToken == null) {
            Log.e(TAG, "❌ Faltan datos críticos - deteniendo servicio")
            stopSelf()
            return START_NOT_STICKY
        }

        Log.d(TAG, "✅ Datos recibidos: deviceUniqueId=$deviceUniqueId")

        if (!isServiceRunning) {
            startForegroundService()
            startLocationTracking()
            setupWebSocket()
            isServiceRunning = true
        }

        return START_STICKY // ✅ REINICIO AUTOMÁTICO
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GPS Tracking",
                NotificationManager.IMPORTANCE_LOW // ✅ BAJA PARA NO MOLESTAR
            ).apply {
                description = "Rastreo GPS en segundo plano"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val notification = createTrackingNotification()
        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "✅ Servicio en primer plano iniciado")
    }

    private fun createTrackingNotification(): Notification {
        // ✅ INTENT PARA ABRIR LA APP
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // ✅ INTENT PARA DESACTIVAR ZONA SEGURA RÁPIDO
        val disableSafeZoneIntent = Intent(this, TrackingService::class.java).apply {
            action = ACTION_DISABLE_SAFEZONE
        }
        val disableSafeZonePendingIntent = PendingIntent.getService(
            this, 1, disableSafeZoneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🛡️ GPS Tracking Activo")
            .setContentText(buildNotificationText())
            .setSmallIcon(R.drawable.ic_gps_tracking) // Necesitarás este ícono
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                // ✅ BOTÓN DE DESACTIVACIÓN RÁPIDA SOLO SI HAY ZONA SEGURA
                if (isSafeZoneActive) {
                    addAction(
                        R.drawable.ic_shield_off,
                        "Desactivar Zona",
                        disableSafeZonePendingIntent
                    )
                }
            }
            .build()
    }

    private fun buildNotificationText(): String {
        return when {
            !isSafeZoneActive -> "Rastreando ubicación - Sin zona segura"
            isCurrentlyOutsideSafeZone -> "⚠️ FUERA DE ZONA SEGURA"
            else -> "✅ Dentro de zona segura"
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationTracking() {
        val locationRequest = LocationRequest.create().apply {
            interval = LOCATION_UPDATE_INTERVAL
            fastestInterval = FASTEST_UPDATE_INTERVAL
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            smallestDisplacement = MIN_DISTANCE_CHANGE
        }

        locationCallback = object : LocationCallback() {
            @RequiresApi(Build.VERSION_CODES.O)
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    handleLocationUpdate(location)
                }
            }
        }

        if (hasLocationPermission()) {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.d(TAG, "✅ Rastreo de ubicación iniciado")
        } else {
            Log.e(TAG, "❌ Sin permisos de ubicación")
            stopSelf()
        }
    }

    // ✅ FUNCIÓN PRINCIPAL PARA MANEJAR ACTUALIZACIONES DE UBICACIÓN
    @RequiresApi(Build.VERSION_CODES.O)
    private fun handleLocationUpdate(location: Location) {
        val currentPosition = GeoPoint(location.latitude, location.longitude)

        Log.d(TAG, "📍 Nueva ubicación: ${location.latitude}, ${location.longitude}")

        // ✅ VERIFICAR ZONA SEGURA SI ESTÁ ACTIVA
        if (isSafeZoneActive && safeZoneCenter != null) {
            checkSafeZoneStatus(currentPosition)
        }

        // ✅ ENVIAR UBICACIÓN AL SERVIDOR
        sendLocationToServer(currentPosition, location)

        // ✅ ACTUALIZAR NOTIFICACIÓN
        updateNotification()
    }

    // ✅ VERIFICACIÓN CRÍTICA DE ZONA SEGURA
    @RequiresApi(Build.VERSION_CODES.O)
    private fun checkSafeZoneStatus(currentPosition: GeoPoint) {
        val safeZone = safeZoneCenter ?: return

        // ✅ CALCULAR DISTANCIA PRECISA
        val distance = calculateDistance(safeZone, currentPosition)

        Log.d(TAG, "📏 Distancia a zona segura: ${String.format("%.1f", distance)}m")

        val wasOutside = isCurrentlyOutsideSafeZone
        val isNowOutside = distance > GEOFENCE_RADIUS

        if (!wasOutside && isNowOutside) {
            // ✅ ACABA DE SALIR DE LA ZONA SEGURA
            Log.w(TAG, "🚨 SALIÓ DE LA ZONA SEGURA - Distancia: ${String.format("%.1f", distance)}m")
            isCurrentlyOutsideSafeZone = true
            triggerSafeZoneAlert(distance)

        } else if (wasOutside && !isNowOutside) {
            // ✅ REGRESÓ A LA ZONA SEGURA
            Log.d(TAG, "✅ REGRESÓ A LA ZONA SEGURA - Distancia: ${String.format("%.1f", distance)}m")
            isCurrentlyOutsideSafeZone = false
            stopAlarm()

        } else if (isNowOutside) {
            // ✅ CONTINÚA FUERA - VERIFICAR SI NECESITA NUEVA ALERTA
            val timeSinceLastAlert = System.currentTimeMillis() - lastAlertTime
            if (timeSinceLastAlert > alertCooldownTime) {
                Log.w(TAG, "🔄 CONTINÚA FUERA - Nueva alerta - Distancia: ${String.format("%.1f", distance)}m")
                triggerSafeZoneAlert(distance)
            }
        }
    }

    // ✅ DISPARAR ALARMA CRÍTICA
    @RequiresApi(Build.VERSION_CODES.O)
    private fun triggerSafeZoneAlert(distance: Double) {
        val now = System.currentTimeMillis()

        // ✅ COOLDOWN PARA EVITAR SPAM
        if (now - lastAlertTime < alertCooldownTime) {
            Log.d(TAG, "⏳ Alerta en cooldown, ignorando")
            return
        }

        lastAlertTime = now

        Log.e(TAG, "🚨🚨🚨 ALERTA CRÍTICA - VEHÍCULO FUERA DE ZONA SEGURA 🚨🚨🚨")
        Log.e(TAG, "📏 Distancia: ${String.format("%.1f", distance)}m")

        // ✅ ACTIVAR ALARMA COMPLETA
        alertManager?.startCriticalAlert(deviceUniqueId.hashCode(), distance)

        // ✅ NOTIFICACIÓN DE EMERGENCIA
        showEmergencyNotification(distance)

        // ✅ BROADCAST PARA LA APP (SI ESTÁ ABIERTA)
        sendAlertBroadcast(distance)
    }

    private fun showEmergencyNotification(distance: Double) {
        // ✅ INTENTS PARA ACCIONES RÁPIDAS
        val stopAlarmIntent = Intent(this, TrackingService::class.java).apply {
            action = ACTION_STOP_ALARM
        }
        val stopAlarmPendingIntent = PendingIntent.getService(
            this, 2, stopAlarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disableSafeZoneIntent = Intent(this, TrackingService::class.java).apply {
            action = ACTION_EMERGENCY_DISABLE
        }
        val disableSafeZonePendingIntent = PendingIntent.getService(
            this, 3, disableSafeZoneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val emergencyNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🚨 VEHÍCULO FUERA DE ZONA SEGURA")
            .setContentText("Distancia: ${String.format("%.0f", distance)}m - Toca para abrir")
            .setSmallIcon(R.drawable.ic_alert_critical)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setOngoing(true)
            .setColorized(true)
            .setColor(android.graphics.Color.RED)
            .setVibrate(longArrayOf(0, 1000, 500, 1000, 500, 1000))
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            .addAction(
                R.drawable.ic_volume_off,
                "SILENCIAR",
                stopAlarmPendingIntent
            )
            .addAction(
                R.drawable.ic_shield_off,
                "DESACTIVAR ZONA",
                disableSafeZonePendingIntent
            )
            .build()

        // ✅ MOSTRAR CON ID DIFERENTE PARA QUE SEA MUY VISIBLE
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(9999, emergencyNotification)
    }

    private fun sendAlertBroadcast(distance: Double) {
        val intent = Intent("com.peregrino.SAFEZONE_ALERT").apply {
            putExtra("distance", distance)
            putExtra("deviceId", deviceUniqueId)
            putExtra("timestamp", System.currentTimeMillis())
        }
        sendBroadcast(intent)
    }

    // ✅ FUNCIONES DE CONTROL DE ALARMA
    private fun stopAlarm() {
        alertManager?.stopAlert()

        // ✅ CANCELAR NOTIFICACIÓN DE EMERGENCIA
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(9999)

        // ✅ ACTUALIZAR NOTIFICACIÓN PRINCIPAL
        updateNotification()

        Log.d(TAG, "🔇 Alarma detenida")
    }

    private fun disableSafeZone() {
        // ✅ DESACTIVAR ZONA SEGURA LOCALMENTE
        isSafeZoneActive = false
        safeZoneCenter = null
        isCurrentlyOutsideSafeZone = false

        // ✅ DETENER ALARMA
        stopAlarm()

        // ✅ LIMPIAR PREFERENCIAS
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            remove("safezone_lat")
            remove("safezone_lon")
            apply()
        }

        // ✅ ACTUALIZAR NOTIFICACIÓN
        updateNotification()

        // ✅ NOTIFICAR A LA APP
        val intent = Intent("com.peregrino.SAFEZONE_DISABLED")
        sendBroadcast(intent)

        Log.d(TAG, "🛡️ Zona segura desactivada")

        // ✅ TOAST PARA CONFIRMAR
        handler.post {
            Toast.makeText(this, "✅ Zona segura desactivada", Toast.LENGTH_LONG).show()
        }
    }

    private fun emergencyDisable() {
        Log.d(TAG, "🚨 DESACTIVACIÓN DE EMERGENCIA")

        // ✅ DETENER TODO
        stopAlarm()
        disableSafeZone()

        // ✅ NOTIFICACIÓN DE CONFIRMACIÓN
        handler.post {
            Toast.makeText(this, "🚨 ZONA SEGURA DESACTIVADA - ALARMA DETENIDA", Toast.LENGTH_LONG).show()
        }
    }

    // ✅ CARGAR ZONA SEGURA AL INICIO
    private fun loadSafeZone() {
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val lat = prefs.getString("safezone_lat", null)?.toDoubleOrNull()
        val lon = prefs.getString("safezone_lon", null)?.toDoubleOrNull()

        if (lat != null && lon != null) {
            safeZoneCenter = GeoPoint(lat, lon)
            isSafeZoneActive = true
            Log.d(TAG, "✅ Zona segura cargada: $lat, $lon")
        } else {
            Log.d(TAG, "ℹ️ No hay zona segura configurada")
        }
    }

    private fun updateNotification() {
        val notification = createTrackingNotification()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    // ✅ WEBSOCKET PARA RECIBIR ACTUALIZACIONES
    private fun setupWebSocket() {
        if (jwtToken.isNullOrEmpty()) return

        val wsUrl = "wss://carefully-arriving-shepherd.ngrok-free.app/ws?token=$jwtToken"
        val request = Request.Builder().url(wsUrl).build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.getString("type")) {
                        "SAFEZONE_UPDATED" -> {
                            Log.d(TAG, "🛡️ Zona segura actualizada desde servidor")
                            loadSafeZone()
                        }
                        "SAFEZONE_DELETED" -> {
                            Log.d(TAG, "🗑️ Zona segura eliminada desde servidor")
                            disableSafeZone()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error procesando WebSocket: ${e.message}")
                }
            }
        })
    }

    private fun sendLocationToServer(position: GeoPoint, location: Location) {
        // Implementación simplificada - puedes expandirla
        Log.d(TAG, "📤 Enviando ubicación al servidor: $position")
    }

    // ✅ REGISTRO DE BROADCAST RECEIVER
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerAlarmReceiver() {
        val filter = IntentFilter().apply {
            addAction("com.peregrino.STOP_ALARM_BROADCAST")
            addAction("com.peregrino.DISABLE_SAFEZONE_BROADCAST")
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    "com.peregrino.STOP_ALARM_BROADCAST" -> stopAlarm()
                    "com.peregrino.DISABLE_SAFEZONE_BROADCAST" -> disableSafeZone()
                }
            }
        }

        registerReceiver(receiver, filter)
    }

    // ✅ UTILIDADES
    private fun calculateDistance(point1: GeoPoint, point2: GeoPoint): Double {
        val results = FloatArray(1)
        Location.distanceBetween(
            point1.latitude, point1.longitude,
            point2.latitude, point2.longitude,
            results
        )
        return results[0].toDouble()
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        fusedLocationClient.removeLocationUpdates(locationCallback)
        webSocket?.close(1000, "Service destroyed")
        alertManager?.stopAlert()
        Log.d(TAG, "🛑 TrackingService destruido")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}