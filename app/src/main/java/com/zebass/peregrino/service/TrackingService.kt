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
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.IOException
import java.util.concurrent.TimeUnit

class TrackingService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "tracking_channel"
        private const val LOCATION_UPDATE_INTERVAL = 2000L // ✅ MÁS FRECUENTE PARA TRACKING SUAVE
        private const val FASTEST_UPDATE_INTERVAL = 1000L
        private const val MIN_DISTANCE_CHANGE = 2f // ✅ MÁS SENSIBLE
        private const val GEOFENCE_RADIUS = 15.0
        private const val TAG = "TrackingService"

        // ✅ URLs CLOUDFLARE DNS
        private const val BASE_URL = "https://app.socialengeneering.work"
        private const val WS_URL = "wss://app.socialengeneering.work"
        private const val TRACCAR_URL = "https://traccar.socialengeneering.work"

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
    private var alarmReceiver: BroadcastReceiver? = null

    // ✅ DATOS DEL DISPOSITIVO
    private var deviceUniqueId: String? = null
    private var jwtToken: String? = null

    // ✅ ZONA SEGURA
    private var safeZoneCenter: GeoPoint? = null
    private var isSafeZoneActive = false
    private var isCurrentlyOutsideSafeZone = false
    private var lastAlertTime = 0L
    private val alertCooldownTime = 30000L

    // ✅ NUEVO: TRACKING SUAVE Y FILTRADO
    private var kalmanFilter: LocationKalmanFilter? = null
    private var lastSentPosition: Location? = null
    private var lastSentTime = 0L
    private val sendThrottle = 3000L // Enviar máximo cada 3 segundos al servidor
    private val broadcastThrottle = 500L // Broadcast local más frecuente
    private var lastBroadcastTime = 0L

    // ✅ HTTP CLIENT OPTIMIZADO PARA TIEMPO REAL
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS) // ✅ MÁS FRECUENTE PARA TIEMPO REAL
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("User-Agent", "PeregrinoGPS-Service-RealTime/2.0")
                    .addHeader("Accept", "application/json")
                    .addHeader("Connection", "keep-alive")
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    private val handler = Handler(Looper.getMainLooper())

    // ✅ FILTRO KALMAN SIMPLIFICADO PARA EL SERVICIO
    private class LocationKalmanFilter {
        private var lat: Double = 0.0
        private var lon: Double = 0.0
        private var variance: Double = 1000.0
        private var lastTimestamp: Long = 0L

        fun update(location: Location): Location {
            val currentTime = location.time
            val deltaTime = if (lastTimestamp == 0L) 1.0 else (currentTime - lastTimestamp) / 1000.0
            lastTimestamp = currentTime

            if (deltaTime > 60.0) { // Reset si gap muy grande
                lat = location.latitude
                lon = location.longitude
                variance = location.accuracy.toDouble()
                return location
            }

            // Proceso de predicción
            variance += 0.1 * deltaTime // Incrementar incertidumbre con el tiempo

            // Actualización con medición
            val measurementVariance = location.accuracy.toDouble()
            val gain = variance / (variance + measurementVariance)

            lat += gain * (location.latitude - lat)
            lon += gain * (location.longitude - lon)
            variance *= (1 - gain)

            // Crear nueva ubicación filtrada
            val filteredLocation = Location(location.provider)
            filteredLocation.latitude = lat
            filteredLocation.longitude = lon
            filteredLocation.accuracy = variance.toFloat().coerceAtMost(location.accuracy)
            filteredLocation.time = currentTime
            filteredLocation.speed = location.speed
            filteredLocation.bearing = location.bearing

            return filteredLocation
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🚀 Enhanced TrackingService created")

        createNotificationChannel()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        alertManager = AlertManager(this)

        // ✅ INICIALIZAR KALMAN FILTER
        kalmanFilter = LocationKalmanFilter()

        // ✅ REGISTRAR BROADCAST RECEIVER
        registerAlarmReceiver()

        // ✅ CARGAR ZONA SEGURA
        loadSafeZone()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GPS Tracking en Tiempo Real",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Rastreo GPS suave y en tiempo real"
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
        Log.d(TAG, "✅ Enhanced foreground service started")
    }

    private fun createTrackingNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disableSafeZoneIntent = Intent(this, TrackingService::class.java).apply {
            action = ACTION_DISABLE_SAFEZONE
        }
        val disableSafeZonePendingIntent = PendingIntent.getService(
            this, 1, disableSafeZoneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🛡️ GPS Tracking en Tiempo Real")
            .setContentText(buildNotificationText())
            .setSmallIcon(R.drawable.ic_gps_tracking)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
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
            !isSafeZoneActive -> "Tracking suave activo - Sin zona segura"
            isCurrentlyOutsideSafeZone -> "⚠️ FUERA DE ZONA SEGURA"
            else -> "✅ Dentro de zona segura - Tracking activo"
        }
    }

    // ✅ CONFIGURACIÓN MEJORADA DE TRACKING DE UBICACIÓN
    @SuppressLint("MissingPermission")
    private fun startLocationTracking() {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            LOCATION_UPDATE_INTERVAL
        ).apply {
            setMinUpdateIntervalMillis(FASTEST_UPDATE_INTERVAL)
            setMinUpdateDistanceMeters(MIN_DISTANCE_CHANGE)
            setMaxUpdateDelayMillis(LOCATION_UPDATE_INTERVAL * 2)
            setWaitForAccurateLocation(false) // No esperar GPS perfecto
        }.build()

        locationCallback = object : LocationCallback() {
            @RequiresApi(Build.VERSION_CODES.O)
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    handleLocationUpdateEnhanced(location)
                }
            }
        }

        if (hasLocationPermission()) {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.d(TAG, "✅ Enhanced location tracking started")
        } else {
            Log.e(TAG, "❌ Sin permisos de ubicación")
            stopSelf()
        }
    }

    // ✅ MANEJO MEJORADO DE ACTUALIZACIONES DE UBICACIÓN
    @RequiresApi(Build.VERSION_CODES.O)
    private fun handleLocationUpdateEnhanced(location: Location) {
        try {
            // ✅ APLICAR FILTRO KALMAN
            val filteredLocation = kalmanFilter?.update(location) ?: location

            val currentPosition = GeoPoint(filteredLocation.latitude, filteredLocation.longitude)
            val currentTime = System.currentTimeMillis()

            Log.d(TAG, "📍 Enhanced location: ${filteredLocation.latitude}, ${filteredLocation.longitude}" +
                    " (accuracy: ${filteredLocation.accuracy}m, speed: ${filteredLocation.speed} m/s)")

            // ✅ VERIFICAR ZONA SEGURA SI ESTÁ ACTIVA
            if (isSafeZoneActive && safeZoneCenter != null) {
                checkSafeZoneStatus(currentPosition)
            }

            // ✅ BROADCAST LOCAL MÁS FRECUENTE PARA LA APP
            if (currentTime - lastBroadcastTime > broadcastThrottle) {
                broadcastLocationUpdate(filteredLocation)
                lastBroadcastTime = currentTime
            }

            // ✅ ENVÍO AL SERVIDOR CON THROTTLE
            if (shouldSendToServer(filteredLocation, currentTime)) {
                sendLocationToServer(currentPosition, filteredLocation)
                lastSentPosition = filteredLocation
                lastSentTime = currentTime
            }

            // ✅ ACTUALIZAR NOTIFICACIÓN
            updateNotification()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in enhanced location handling: ${e.message}")
        }
    }

    // ✅ DECIDIR SI ENVIAR AL SERVIDOR (OPTIMIZACIÓN DE BATERÍA Y RED)
    // ✅ MEJORAR shouldSendToServer en TrackingService
    private fun shouldSendToServer(location: Location, currentTime: Long): Boolean {
        // Enviar si es la primera posición
        if (lastSentPosition == null) return true

        // ✅ TIEMPO MÍNIMO RESPETADO
        if (currentTime - lastSentTime < sendThrottle) return false

        val distance = lastSentPosition!!.distanceTo(location)
        val speedDiff = Math.abs(location.speed - (lastSentPosition?.speed ?: 0f))
        val accuracyImprovement = (lastSentPosition?.accuracy ?: 100f) - location.accuracy

        // ✅ CRITERIOS OPTIMIZADOS
        return when {
            distance > MIN_DISTANCE_CHANGE * 3 -> true // 6 metros de movimiento
            speedDiff > 2.0 -> true // Cambio de velocidad > 7 km/h
            accuracyImprovement > 10 -> true // Mejora significativa de precisión
            location.speed > 5 && distance > 1 -> true // Movimiento rápido
            currentTime - lastSentTime > 10000 -> true // Máximo 10 segundos sin envío
            else -> false
        }
    }

    // ✅ BROADCAST PARA LA APP (TIEMPO REAL LOCAL)
    private fun broadcastLocationUpdate(location: Location) {
        val intent = Intent("com.peregrino.LOCATION_UPDATE").apply {
            putExtra("latitude", location.latitude)
            putExtra("longitude", location.longitude)
            putExtra("speed", location.speed)
            putExtra("bearing", location.bearing)
            putExtra("accuracy", location.accuracy)
            putExtra("timestamp", location.time)
            putExtra("deviceId", deviceUniqueId)
        }
        sendBroadcast(intent)

        Log.d(TAG, "📡 Location broadcast sent to app")
    }

    // ✅ VERIFICACIÓN MEJORADA DE ZONA SEGURA
    @RequiresApi(Build.VERSION_CODES.O)
    private fun checkSafeZoneStatus(currentPosition: GeoPoint) {
        val safeZone = safeZoneCenter ?: return

        val distance = calculateDistance(safeZone, currentPosition)

        Log.d(TAG, "📏 Distancia a zona segura: ${String.format("%.1f", distance)}m")

        val wasOutside = isCurrentlyOutsideSafeZone
        val isNowOutside = distance > GEOFENCE_RADIUS

        if (!wasOutside && isNowOutside) {
            Log.w(TAG, "🚨 SALIÓ DE LA ZONA SEGURA - Distancia: ${String.format("%.1f", distance)}m")
            isCurrentlyOutsideSafeZone = true
            triggerSafeZoneAlert(distance)

        } else if (wasOutside && !isNowOutside) {
            Log.d(TAG, "✅ REGRESÓ A LA ZONA SEGURA - Distancia: ${String.format("%.1f", distance)}m")
            isCurrentlyOutsideSafeZone = false
            stopAlarm()

        } else if (isNowOutside) {
            val timeSinceLastAlert = System.currentTimeMillis() - lastAlertTime
            if (timeSinceLastAlert > alertCooldownTime) {
                Log.w(TAG, "🔄 CONTINÚA FUERA - Nueva alerta - Distancia: ${String.format("%.1f", distance)}m")
                triggerSafeZoneAlert(distance)
            }
        }
    }

    // ✅ DISPARAR ALARMA CRÍTICA (SIN CAMBIOS)
    @RequiresApi(Build.VERSION_CODES.O)
    private fun triggerSafeZoneAlert(distance: Double) {
        val now = System.currentTimeMillis()

        if (now - lastAlertTime < alertCooldownTime) {
            Log.d(TAG, "⏳ Alerta en cooldown, ignorando")
            return
        }

        lastAlertTime = now

        Log.e(TAG, "🚨🚨🚨 ALERTA CRÍTICA - VEHÍCULO FUERA DE ZONA SEGURA 🚨🚨🚨")
        Log.e(TAG, "📏 Distancia: ${String.format("%.1f", distance)}m")

        alertManager?.startCriticalAlert(deviceUniqueId.hashCode(), distance)
        showEmergencyNotification(distance)
        sendAlertBroadcast(distance)
    }

    private fun showEmergencyNotification(distance: Double) {
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

    // ✅ FUNCIONES DE CONTROL DE ALARMA (SIN CAMBIOS PRINCIPALES)
    private fun stopAlarm() {
        alertManager?.stopAlert()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(9999)

        updateNotification()
        Log.d(TAG, "🔇 Alarma detenida")
    }

    private fun disableSafeZone() {
        isSafeZoneActive = false
        safeZoneCenter = null
        isCurrentlyOutsideSafeZone = false

        stopAlarm()

        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            remove("safezone_lat")
            remove("safezone_lon")
            apply()
        }

        updateNotification()

        val intent = Intent("com.peregrino.SAFEZONE_DISABLED")
        sendBroadcast(intent)

        Log.d(TAG, "🛡️ Zona segura desactivada")

        handler.post {
            Toast.makeText(this, "✅ Zona segura desactivada", Toast.LENGTH_LONG).show()
        }
    }

    private fun emergencyDisable() {
        Log.d(TAG, "🚨 DESACTIVACIÓN DE EMERGENCIA")

        stopAlarm()
        disableSafeZone()

        handler.post {
            Toast.makeText(this, "🚨 ZONA SEGURA DESACTIVADA - ALARMA DETENIDA", Toast.LENGTH_LONG).show()
        }
    }

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

    // ✅ WEBSOCKET MEJORADO PARA RECIBIR ACTUALIZACIONES EN TIEMPO REAL
    private fun setupWebSocket() {
        if (jwtToken.isNullOrEmpty()) {
            Log.w(TAG, "⚠️ No JWT token for WebSocket")
            return
        }

        val wsUrl = "$WS_URL/ws?token=$jwtToken"
        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("User-Agent", "PeregrinoGPS-Service-Enhanced/2.0")
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "✅ Service WebSocket connected")

                // Suscribirse a actualizaciones del dispositivo
                if (!deviceUniqueId.isNullOrEmpty()) {
                    val subscribeMessage = JSONObject().apply {
                        put("type", "SUBSCRIBE_SERVICE")
                        put("deviceId", deviceUniqueId)
                        put("service", "tracking")
                    }
                    webSocket.send(subscribeMessage.toString())
                    Log.d(TAG, "📡 Service subscribed to device: $deviceUniqueId")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServiceWebSocketMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "❌ Service WebSocket error: ${t.message}")

                // Reconectar después de 10 segundos
                handler.postDelayed({
                    if (isServiceRunning) {
                        setupWebSocket()
                    }
                }, 10000)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "🔌 Service WebSocket closed: $code - $reason")
            }
        })
    }

    private fun handleServiceWebSocketMessage(message: String) {
        try {
            val json = JSONObject(message)
            val type = json.getString("type")

            when (type) {
                "SAFEZONE_UPDATED" -> {
                    Log.d(TAG, "🛡️ Safe zone updated from server")
                    loadSafeZone()
                }

                "SAFEZONE_DELETED" -> {
                    Log.d(TAG, "🗑️ Safe zone deleted from server")
                    disableSafeZone()
                }

                "CONFIG_UPDATE" -> {
                    val config = json.getJSONObject("config")
                    val newInterval = config.optLong("updateInterval", LOCATION_UPDATE_INTERVAL)
                    Log.d(TAG, "⚙️ Config update received - interval: ${newInterval}ms")
                    // Aquí podrías ajustar dinámicamente los intervalos
                }

                "EMERGENCY_STOP" -> {
                    Log.w(TAG, "🚨 Emergency stop received from server")
                    emergencyDisable()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error processing service WebSocket message: ${e.message}")
        }
    }

    // ✅ ENVÍO OPTIMIZADO AL SERVIDOR CON CLOUDFLARE
    private fun sendLocationToServer(position: GeoPoint, location: Location) {
        if (deviceUniqueId.isNullOrEmpty()) {
            Log.w(TAG, "⚠️ No device ID for server update")
            return
        }

        val locationData = JSONObject().apply {
            put("id", deviceUniqueId)
            put("lat", position.latitude)
            put("lon", position.longitude)
            put("timestamp", System.currentTimeMillis() / 1000)
            put("speed", location.speed * 3.6) // Convertir m/s a km/h
            put("course", location.bearing)
            put("accuracy", location.accuracy)
            put("source", "android_service_enhanced")
            put("filtered", true) // Indicar que está filtrado con Kalman
        }

        val requestBody = locationData.toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$BASE_URL/gps/osmand")
            .post(requestBody)
            .addHeader("Authorization", "Bearer $jwtToken")
            .addHeader("User-Agent", "PeregrinoGPS-Service-Enhanced/2.0")
            .addHeader("X-Real-Time", "true")
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "❌ Failed to send location to server: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    Log.d(TAG, "✅ Location sent to server successfully")
                } else {
                    Log.e(TAG, "❌ Server error: ${response.code} - ${response.message}")
                }
                response.close()
            }
        })
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

    // ✅ BROADCAST RECEIVER MEJORADO
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerAlarmReceiver() {
        if (alarmReceiver != null) {
            Log.w(TAG, "Alarm receiver already registered")
            return
        }

        val filter = IntentFilter().apply {
            addAction("com.peregrino.STOP_ALARM_BROADCAST")
            addAction("com.peregrino.DISABLE_SAFEZONE_BROADCAST")
            addAction("com.peregrino.EMERGENCY_DISABLE_BROADCAST")
        }

        alarmReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.d(TAG, "📨 Enhanced broadcast received: ${intent?.action}")
                when (intent?.action) {
                    "com.peregrino.STOP_ALARM_BROADCAST" -> {
                        Log.d(TAG, "🔇 Stopping alarm via broadcast")
                        stopAlarm()
                    }
                    "com.peregrino.DISABLE_SAFEZONE_BROADCAST" -> {
                        Log.d(TAG, "🛡️ Disabling safezone via broadcast")
                        disableSafeZone()
                    }
                    "com.peregrino.EMERGENCY_DISABLE_BROADCAST" -> {
                        Log.d(TAG, "🚨 Emergency disable via broadcast")
                        emergencyDisable()
                    }
                }
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(alarmReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(alarmReceiver, filter)
            }
            Log.d(TAG, "✅ Enhanced alarm receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error registering alarm receiver: ${e.message}")
            alarmReceiver = null
        }
    }

    private fun unregisterAlarmReceiver() {
        alarmReceiver?.let { receiver ->
            try {
                unregisterReceiver(receiver)
                Log.d(TAG, "✅ Alarm receiver unregistered")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Receiver already unregistered: ${e.message}")
            } finally {
                alarmReceiver = null
            }
        }
    }

    // ✅ CICLO DE VIDA MEJORADO
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "📱 Enhanced TrackingService onStartCommand")

        // Manejar acciones especiales
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
                Log.d(TAG, "🚨 Desactivación de emergencia")
                emergencyDisable()
                return START_STICKY
            }
        }

        // Obtener datos del intent
        deviceUniqueId = intent?.getStringExtra("deviceUniqueId")
        jwtToken = intent?.getStringExtra("jwtToken")

        if (deviceUniqueId == null || jwtToken == null) {
            Log.e(TAG, "❌ Faltan datos críticos - deteniendo servicio")
            stopSelf()
            return START_NOT_STICKY
        }

        Log.d(TAG, "✅ Enhanced service data: deviceUniqueId=$deviceUniqueId")

        if (!isServiceRunning) {
            startForegroundService()
            startLocationTracking()
            setupWebSocket()
            registerAlarmReceiver()
            loadSafeZone()

            isServiceRunning = true
            Log.d(TAG, "✅ Enhanced TrackingService started successfully")
        } else {
            Log.d(TAG, "ℹ️ Enhanced TrackingService already running")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "🛑 Enhanced TrackingService destroyed")

        isServiceRunning = false

        // Limpiar recursos
        fusedLocationClient.removeLocationUpdates(locationCallback)
        webSocket?.close(1000, "Service destroyed")
        alertManager?.stopAlert()
        unregisterAlarmReceiver()

        // Limpiar filtro Kalman
        kalmanFilter = null

        Log.d(TAG, "✅ Enhanced service cleanup completed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}