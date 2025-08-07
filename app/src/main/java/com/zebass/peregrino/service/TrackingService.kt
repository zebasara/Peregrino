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
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
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
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.LatLng
import com.zebass.peregrino.SecondFragment
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

    class TrackingService : Service() {
    
        // ============ COROUTINES Y LIFECYCLE ============
        private val serviceScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO.limitedParallelism(4)
        )
        private val locationProcessingChannel = Channel<Location>(Channel.UNLIMITED)
    
        // ============ LOCATION COMPONENTS ============
        private lateinit var fusedLocationClient: FusedLocationProviderClient
        private lateinit var locationCallback: LocationCallback
        private var wakeLock: PowerManager.WakeLock? = null
    
        // ============ CONFIGURACIÓN Y ESTADO ============
        private var deviceId: Int = -1
        private var deviceUniqueId: String? = null
        private var jwtToken: String? = null
        private val safeZone = AtomicReference<LatLng?>(null)
        private val lastLocationSent = AtomicReference<Location?>(null)
        private val lastLocationTime = AtomicLong(0)
        private val consecutiveErrors = AtomicLong(0)
        private val failedLocations = mutableListOf<LocationData>()
        private val failedLock = Any()
        private lateinit var gpsReceiver: BroadcastReceiver
    
        // ============ CACHE Y BATCHING ============
        private val locationBatch = mutableListOf<LocationData>()
        private val batchLock = Any()
        private var batchJob: Job? = null
    
        // ============ HTTP CLIENT OPTIMIZADO ============
        private val httpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .writeTimeout(3, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer $jwtToken")
                        .build()
                    chain.proceed(request)
                }
                .build()
        }
    
        private lateinit var sharedPreferences: SharedPreferences
    
        companion object {
            const val TAG = "TrackingService"
            const val NOTIFICATION_ID = 1001
            const val ALERT_NOTIFICATION_ID = 1002
            const val CHANNEL_ID = "tracking_channel"
            const val GEOFENCE_RADIUS = 15.0
            const val LOCATION_INTERVAL = 5000L
            const val FASTEST_INTERVAL = 2000L
            const val DISPLACEMENT_THRESHOLD = 5f
            const val LOCATION_SEND_THROTTLE = 3000L
            const val BATCH_SEND_INTERVAL = 10000L
            const val MAX_BATCH_SIZE = 20
            const val MAX_CONSECUTIVE_ERRORS = 10
            const val WAKE_LOCK_TIMEOUT = 60000L
        }
    
        data class LocationData(
            val latitude: Double,
            val longitude: Double,
            val timestamp: Long,
            val speed: Float = 0f,
            val accuracy: Float = 0f,
            val bearing: Float = 0f
        )
    
        // ============ LIFECYCLE METHODS ============
    
        @RequiresApi(Build.VERSION_CODES.M)
        override fun onCreate() {
            super.onCreate()
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            sharedPreferences = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            createNotificationChannel()
            acquireWakeLock()
            startLocationProcessing()
            registerGpsReceiver() // Nuevo
            Log.d(TAG, "TrackingService creado")
        }
        @RequiresApi(Build.VERSION_CODES.M)
        private fun registerGpsReceiver() {
            gpsReceiver = object : BroadcastReceiver() {
                @SuppressLint("ServiceCast")
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                        if (isGpsEnabled && hasLocationPermission()) {
                            Log.d(TAG, "✅ GPS enabled - restarting location updates")
                            startLocationUpdates()
                        } else {
                            Log.w(TAG, "⚠️ GPS disabled - stopping location updates")
                            updateNotification("⚠️ GPS desactivado - verifica tu configuración")
                        }
                    }
                }
            }
            val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
            registerReceiver(gpsReceiver, filter)
        }
        // REEMPLAZAR la función onStartCommand completa
        @RequiresApi(Build.VERSION_CODES.M)
        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            // ✅ OBTENER AMBOS IDS
            deviceId = intent?.getIntExtra("deviceId", -1) ?: -1
            deviceUniqueId = intent?.getStringExtra("deviceUniqueId")
                ?: sharedPreferences.getString(SecondFragment.DEVICE_UNIQUE_ID_PREF, null)
            jwtToken = intent?.getStringExtra("jwtToken") ?: SecondFragment.JWT_TOKEN

            Log.d(TAG, "🚀 TrackingService onStartCommand:")
            Log.d(TAG, "   deviceId: $deviceId")
            Log.d(TAG, "   deviceUniqueId: $deviceUniqueId")
            Log.d(TAG, "   hasJwtToken: ${!jwtToken.isNullOrEmpty()}")

            // ✅ VALIDACIÓN CORREGIDA - SOLO NECESITAMOS UNIQUEID Y TOKEN
            if (deviceUniqueId.isNullOrEmpty() || jwtToken.isNullOrEmpty()) {
                Log.e(TAG, "❌ Missing deviceUniqueId or jwtToken:")
                Log.e(TAG, "   deviceUniqueId: ${deviceUniqueId ?: "NULL"}")
                Log.e(TAG, "   jwtToken: ${if (jwtToken.isNullOrEmpty()) "NULL/EMPTY" else "OK"}")

                // ✅ IMPORTANTE: LLAMAR startForeground ANTES DE stopSelf
                try {
                    startForeground(NOTIFICATION_ID, createNotification("❌ Error: Configuración inválida"))
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting foreground notification: ${e.message}")
                }

                stopSelf()
                return START_NOT_STICKY
            }

            Log.d(TAG, "✅ TrackingService configuration valid, starting...")

            // ✅ INICIAR FOREGROUND INMEDIATAMENTE
            try {
                startForeground(NOTIFICATION_ID, createNotification("🔄 Iniciando servicio de rastreo..."))
                Log.d(TAG, "✅ Foreground service started")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error starting foreground: ${e.message}")
                stopSelf()
                return START_NOT_STICKY
            }

            // ✅ CARGAR ZONA SEGURA
            loadSafeZone()

            // ✅ INICIAR BATCH PROCESSOR
            startBatchProcessor()

            // ✅ VERIFICAR PERMISOS E INICIAR LOCATION UPDATES
            if (hasLocationPermission()) {
                Log.d(TAG, "✅ Location permissions granted, starting location updates")
                startLocationUpdates()
            } else {
                Log.e(TAG, "❌ Missing location permissions")
                updateNotification("❌ Sin permisos de ubicación - verifica configuración")
                // No detener el servicio, solo mostrar error
            }

            Log.d(TAG, "✅ TrackingService started successfully")
            return START_STICKY
        }
    
        // ============ NOTIFICATION MANAGEMENT ============
    
        private fun createNotificationChannel() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Rastreo de Vehículo",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Rastreo de ubicación de vehículo en tiempo real"
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                }
    
                getSystemService(NotificationManager::class.java)
                    ?.createNotificationChannel(channel)
            }
        }
    
        private fun createNotification(contentText: String = "Monitoreando ubicación del vehículo"): Notification {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
    
            return NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Rastreo Peregrino Activo")
                .setContentText(contentText)
                .setSmallIcon(com.zebass.peregrino.R.drawable.ic_vehicle)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
        }
    
        // ============ LOCATION TRACKING OPTIMIZADO ============
    
        @RequiresApi(Build.VERSION_CODES.M)
        private fun startLocationUpdates() {
            val locationRequest = LocationRequest.create().apply {
                interval = LOCATION_INTERVAL
                fastestInterval = FASTEST_INTERVAL
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
                smallestDisplacement = DISPLACEMENT_THRESHOLD
                maxWaitTime = LOCATION_INTERVAL * 2
            }

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    locationResult.lastLocation?.let { location ->
                        serviceScope.launch {
                            locationProcessingChannel.send(location)
                        }
                    }
                }

                override fun onLocationAvailability(availability: LocationAvailability) {
                    if (!availability.isLocationAvailable) {
                        Log.w(TAG, "Ubicación no disponible")
                        updateNotification("Ubicación no disponible - verifica GPS")
                    }
                }
            }

            try {
                if (hasLocationPermission()) {
                    val executor = Dispatchers.IO.asExecutor()
                    fusedLocationClient.requestLocationUpdates(
                        locationRequest,
                        executor,
                        locationCallback
                    )
                    Log.d(TAG, "Iniciadas actualizaciones de ubicación con configuración optimizada")
                } else {
                    Log.e(TAG, "Permiso de ubicación denegado")
                    stopSelf()
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException en startLocationUpdates", e)
                stopSelf()
            }
        }

        // ============ LOCATION PROCESSING PIPELINE ============
    
        @RequiresApi(Build.VERSION_CODES.M)
        private fun startLocationProcessing() {
            serviceScope.launch {
                locationProcessingChannel.consumeAsFlow()
                    .buffer(Channel.UNLIMITED)
                    .collect { location ->
                        processLocation(location)
                    }
            }
        }
        // REEMPLAZAR la función processLocation completa
        @RequiresApi(Build.VERSION_CODES.M)
        private suspend fun processLocation(location: Location) = withContext(Dispatchers.Default) {
            try {
                Log.d(TAG, "🔄 Processing location: lat=${location.latitude}, lon=${location.longitude}")
                Log.d(TAG, "   Accuracy: ${location.accuracy}m, Speed: ${location.speed}m/s")

                // ✅ VALIDACIÓN MÁS PERMISIVA
                if (!isValidLocation(location)) {
                    Log.w(TAG, "❌ Invalid location - skipping")
                    return@withContext
                }

                if (!shouldSendLocation(location)) {
                    Log.d(TAG, "⏳ Location sending throttled")
                    return@withContext
                }

                // ✅ USAR TIMESTAMP DEL SISTEMA GPS
                val timestamp = location.time

                // ✅ VERIFICAR EDAD MÁXIMA (15 minutos en lugar de 10)
                val ageMinutes = (System.currentTimeMillis() - timestamp) / 60000L
                if (ageMinutes > 15) {
                    Log.w(TAG, "❌ Location too old: ${ageMinutes} minutes")
                    return@withContext
                }

                Log.d(TAG, "✅ Location age: ${ageMinutes} minutes - acceptable")

                // Verificar zona segura
                checkSafeZone(location)

                val locationData = LocationData(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    timestamp = timestamp, // ✅ Usar timestamp original
                    speed = location.speed,
                    accuracy = location.accuracy,
                    bearing = location.bearing
                )

                addToBatch(locationData)

                if (isSignificantLocationChange(location)) {
                    Log.d(TAG, "📤 Significant change - sending batch immediately")
                    sendBatchNow()
                }

                lastLocationSent.set(location)
                lastLocationTime.set(System.currentTimeMillis())
                updateNotification("✅ Activo - ${getCurrentTime()}")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error processing location: ${e.message}", e)
                handleLocationError()
            }
        }
        // ============ BATCH PROCESSING ============
    
        @RequiresApi(Build.VERSION_CODES.M)
        private fun startBatchProcessor() {
            batchJob = serviceScope.launch {
                while (isActive) {
                    delay(BATCH_SEND_INTERVAL)
                    sendBatchNow()
                    retryFailedLocations()
                }
            }
        }

        @RequiresApi(Build.VERSION_CODES.M)
        private suspend fun retryFailedLocations() = withContext(Dispatchers.IO) {
            val toRetry = synchronized(failedLock) {
                if (failedLocations.isEmpty()) return@withContext
                val data = failedLocations.toList()
                failedLocations.clear()
                data
            }

            toRetry.forEach { locationData ->
                sendLocationToServer(locationData)
            }
        }
        @RequiresApi(Build.VERSION_CODES.M)
        private fun addToBatch(locationData: LocationData) {
            synchronized(batchLock) {
                locationBatch.add(locationData)
                if (locationBatch.size >= MAX_BATCH_SIZE) {
                    serviceScope.launch { sendBatchNow() }
                }
            }
        }
    
        @RequiresApi(Build.VERSION_CODES.M)
        private suspend fun sendBatchNow() = withContext(Dispatchers.IO) {
            val dataToSend = synchronized(batchLock) {
                if (locationBatch.isEmpty()) return@withContext
                val data = locationBatch.toList()
                locationBatch.clear()
                data
            }
    
            dataToSend.lastOrNull()?.let { mostRecent ->
                sendLocationToServer(mostRecent)
            }
    
            if (dataToSend.size > 1) {
                sendBatchToServer(dataToSend)
            }
        }
    
        // ============ SERVER COMMUNICATION ============
        // REEMPLAZAR la función sendLocationToServer completa
        @RequiresApi(Build.VERSION_CODES.M)
        private suspend fun sendLocationToServer(locationData: LocationData) = withContext(Dispatchers.IO) {
            if (deviceUniqueId == null || jwtToken.isNullOrEmpty()) {
                Log.e(TAG, "❌ Missing deviceUniqueId or jwtToken")
                return@withContext
            }

            try {
                // ✅ TIMESTAMP EN SEGUNDOS UNIX
                val unixTimestamp = locationData.timestamp / 1000

                val url = "https://carefully-arriving-shepherd.ngrok-free.app/gps/osmand?" +
                        "id=$deviceUniqueId" +
                        "&lat=${String.format("%.6f", locationData.latitude)}" +
                        "&lon=${String.format("%.6f", locationData.longitude)}" +
                        "&timestamp=$unixTimestamp" +
                        "&speed=${String.format("%.2f", locationData.speed)}" +
                        "&course=${String.format("%.1f", locationData.bearing)}" +
                        "&accuracy=${String.format("%.1f", locationData.accuracy)}"

                Log.d(TAG, "📤 Sending to server:")
                Log.d(TAG, "   Device: $deviceUniqueId")
                Log.d(TAG, "   Position: ${String.format("%.6f", locationData.latitude)}, ${String.format("%.6f", locationData.longitude)}")
                Log.d(TAG, "   Timestamp: $unixTimestamp (${java.util.Date(locationData.timestamp)})")

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val response = httpClient.newCall(request).execute()

                if (response.isSuccessful) {
                    consecutiveErrors.set(0)
                    Log.d(TAG, "✅ Location sent successfully")
                } else {
                    throw Exception("Server error: ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to send location: ${e.message}", e)
                synchronized(failedLock) {
                    failedLocations.add(locationData)
                }
                handleLocationError()
            }
        }
        private suspend fun sendBatchToServer(batch: List<LocationData>) = withContext(Dispatchers.IO) {
            if (deviceUniqueId == null || batch.isEmpty() || jwtToken.isNullOrEmpty()) {
                Log.e(TAG, "deviceUniqueId, jwtToken nulo o batch vacío, omitiendo envío")
                return@withContext
            }
    
            try {
                val jsonArray = org.json.JSONArray()
                batch.forEach { location ->
                    jsonArray.put(JSONObject().apply {
                        put("id", deviceUniqueId)
                        put("latitude", location.latitude)
                        put("longitude", location.longitude)
                        put("timestamp", location.timestamp)
                        put("speed", location.speed)
                        put("course", location.bearing)
                        put("accuracy", location.accuracy)
                    })
                }
    
                val requestBody = jsonArray.toString()
                    .toRequestBody("application/json".toMediaType())
    
                val request = Request.Builder()
                    .url("https://carefully-arriving-shepherd.ngrok-free.app/gps/batch")
                    .post(requestBody)
                    .tag("batch_update")
                    .build()
    
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    Log.d(TAG, "Batch de ${batch.size} ubicaciones enviado")
                } else {
                    Log.e(TAG, "Fallo al enviar batch: ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fallo al enviar batch", e)
            }
        }
    
        // ============ SAFE ZONE MONITORING ============
        private fun loadSafeZone() {
            val lat = sharedPreferences.getString(SecondFragment.PREF_SAFEZONE_LAT, null)?.toDoubleOrNull()
            val lon = sharedPreferences.getString(SecondFragment.PREF_SAFEZONE_LON, null)?.toDoubleOrNull()

            if (lat != null && lon != null) {
                safeZone.set(LatLng(lat, lon))
                Log.d(TAG, "✅ Safe zone loaded from preferences:")
                Log.d(TAG, "   Center: (${String.format("%.6f", lat)}, ${String.format("%.6f", lon)})")
                Log.d(TAG, "   Radius: ${GEOFENCE_RADIUS} meters")
            } else {
                safeZone.set(null)
                Log.d(TAG, "ℹ️ No safe zone found in preferences")
            }
        }

        private fun checkSafeZone(location: Location) {
            safeZone.get()?.let { zone ->
                // Usar el método más preciso para calcular distancia
                val distance = calculateAccurateDistance(
                    zone.latitude, zone.longitude,
                    location.latitude, location.longitude
                )

                Log.d(TAG, "📏 Calculated distance to safe zone: ${String.format("%.2f", distance)} meters")
                Log.d(TAG, "🎯 Safe zone center: lat=${zone.latitude}, lon=${zone.longitude}")
                Log.d(TAG, "📍 Current position: lat=${location.latitude}, lon=${location.longitude}")
                Log.d(TAG, "⚖️ Geofence radius: $GEOFENCE_RADIUS meters")

                if (distance > GEOFENCE_RADIUS) {
                    Log.d(TAG, "🚨 Vehicle is OUTSIDE safe zone by ${String.format("%.1f", distance - GEOFENCE_RADIUS)} meters")

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        triggerGeofenceAlert(distance)
                    } else {
                        //UPS
                    }
                } else {
                    Log.d(TAG, "✅ Vehicle is INSIDE safe zone (${String.format("%.1f", GEOFENCE_RADIUS - distance)} meters from edge)")
                }
            } ?: run {
                Log.d(TAG, "ℹ️ No safe zone configured - skipping geofence check")
            }
        }
        // ============ CÁLCULO DE DISTANCIA MEJORADO Y MÁS PRECISO ============
        private fun calculateAccurateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            // Usar el método más preciso de Android para cálculo de distancias
            val results = FloatArray(1)
            Location.distanceBetween(lat1, lon1, lat2, lon2, results)
            val distanceInMeters = results[0].toDouble()

            Log.d(TAG, "🧮 Distance calculation:")
            Log.d(TAG, "   From: (${String.format("%.6f", lat1)}, ${String.format("%.6f", lon1)})")
            Log.d(TAG, "   To: (${String.format("%.6f", lat2)}, ${String.format("%.6f", lon2)})")
            Log.d(TAG, "   Result: ${String.format("%.2f", distanceInMeters)} meters")

            return distanceInMeters
        }
        @RequiresApi(Build.VERSION_CODES.O)
        private fun triggerGeofenceAlert(distance: Double) {
            // Solo disparar alerta si la distancia es significativamente mayor al radio
            val threshold = GEOFENCE_RADIUS + 5.0 // 5 metros de margen para evitar falsos positivos

            if (distance < threshold) {
                Log.d(TAG, "⚠️ Distance ${String.format("%.1f", distance)}m is within threshold ${String.format("%.1f", threshold)}m - no alert")
                return
            }

            Log.d(TAG, "🚨 Triggering geofence alert - distance: ${String.format("%.1f", distance)}m")

            // Vibración más suave para evitar molestias
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 500, 200, 500), // Patrón: silencio, vibrar, pausa, vibrar
                    -1 // No repetir
                )
            )

            // Calcular distancia excedente más precisa
            val excessDistance = distance - GEOFENCE_RADIUS
            val alertMessage = "El vehículo salió de la zona segura por ${String.format("%.0f", excessDistance)} metros"

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("🚨 Alerta de Zona Segura")
                .setContentText(alertMessage)
                .setSmallIcon(com.zebass.peregrino.R.drawable.ic_vehicle_alert)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setVibrate(longArrayOf(0, 500, 200, 500)) // Patrón de vibración en la notificación también
                .build()

            getSystemService(NotificationManager::class.java)
                ?.notify(ALERT_NOTIFICATION_ID, notification)

            Log.d(TAG, "✅ Geofence alert triggered successfully: excess distance = ${String.format("%.1f", excessDistance)}m")
        }

        // ============ UTILITY FUNCTIONS ============
        // REEMPLAZAR la función isValidLocation
        private fun isValidLocation(location: Location): Boolean {
            val isValid = location.latitude != 0.0 &&
                    location.longitude != 0.0 &&
                    location.accuracy > 0f &&
                    location.accuracy <= 100f && // ✅ MÁS PERMISIVO: 100m en lugar de 50m
                    location.latitude >= -90.0 &&
                    location.latitude <= 90.0 &&
                    location.longitude >= -180.0 &&
                    location.longitude <= 180.0

            if (!isValid) {
                Log.d(TAG, "❌ Invalid location: lat=${location.latitude}, lon=${location.longitude}, accuracy=${location.accuracy}")
            } else {
                Log.d(TAG, "✅ Valid location: lat=${location.latitude}, lon=${location.longitude}, accuracy=${location.accuracy}m")
            }

            return isValid
        }

        private fun shouldSendLocation(location: Location): Boolean {
            val now = System.currentTimeMillis()
            val timeSinceLastSend = now - lastLocationTime.get()

            // Throttling más inteligente
            if (timeSinceLastSend < LOCATION_SEND_THROTTLE) {
                Log.d(TAG, "⏳ Send throttled: ${timeSinceLastSend}ms < ${LOCATION_SEND_THROTTLE}ms")
                return false
            }

            val lastLocation = lastLocationSent.get()
            if (lastLocation == null) {
                Log.d(TAG, "📤 First location - sending")
                return true
            }

            val distance = lastLocation.distanceTo(location)
            val shouldSend = distance >= DISPLACEMENT_THRESHOLD || timeSinceLastSend > LOCATION_INTERVAL * 2

            Log.d(TAG, "📊 Send decision:")
            Log.d(TAG, "   Distance: ${String.format("%.1f", distance)}m (threshold: ${DISPLACEMENT_THRESHOLD}m)")
            Log.d(TAG, "   Time since last: ${timeSinceLastSend}ms (max: ${LOCATION_INTERVAL * 2}ms)")
            Log.d(TAG, "   Will send: $shouldSend")

            return shouldSend
        }

        private fun isSignificantLocationChange(location: Location): Boolean {
            val lastLocation = lastLocationSent.get() ?: return true

            val distance = lastLocation.distanceTo(location)
            val speedChange = abs(location.speed - lastLocation.speed)
            val bearingChange = abs(location.bearing - lastLocation.bearing)
            val timeDifference = location.time - lastLocation.time

            val isSignificant = distance > 25f || // Reducido de 50f a 25f para mayor precisión
                    speedChange > 3f || // Reducido de 5f a 3f
                    bearingChange > 30f || // Reducido de 45f a 30f
                    timeDifference > (LOCATION_INTERVAL * 2) // Más frecuente si pasa mucho tiempo

            if (isSignificant) {
                Log.d(TAG, "📍 Significant change detected:")
                Log.d(TAG, "   Distance: ${String.format("%.1f", distance)}m (threshold: 25m)")
                Log.d(TAG, "   Speed change: ${String.format("%.1f", speedChange)} m/s (threshold: 3 m/s)")
                Log.d(TAG, "   Bearing change: ${String.format("%.1f", bearingChange)}° (threshold: 30°)")
                Log.d(TAG, "   Time difference: ${timeDifference}ms")
            }

            return isSignificant
        }
        // VERIFICAR que esta función esté correcta
        private fun hasLocationPermission(): Boolean {
            val fineLocation = ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            val coarseLocation = ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            val backgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true // No requerido en versiones anteriores a Android 10
            }

            val hasPermissions = (fineLocation || coarseLocation) // Solo necesitamos uno de estos

            Log.d(TAG, "📍 Location permissions check:")
            Log.d(TAG, "   Fine location: $fineLocation")
            Log.d(TAG, "   Coarse location: $coarseLocation")
            Log.d(TAG, "   Background location: $backgroundLocation")
            Log.d(TAG, "   Has required permissions: $hasPermissions")

            return hasPermissions
        }
        private fun getCurrentTime(): String {
            return java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
        }

        @RequiresApi(Build.VERSION_CODES.M)
        private fun updateNotification(text: String) {
            // Agregar información de zona segura si existe
            val enhancedText = if (safeZone.get() != null) {
                "$text • Zona Segura: Activa"
            } else {
                "$text • Sin Zona Segura"
            }

            val notification = createNotification(enhancedText)
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, notification)
        }
        @RequiresApi(Build.VERSION_CODES.M)
        private fun handleLocationError() {
            val errors = consecutiveErrors.incrementAndGet()

            Log.w(TAG, "⚠️ Location error #$errors")

            if (errors >= MAX_CONSECUTIVE_ERRORS) {
                Log.e(TAG, "❌ Too many consecutive errors ($errors), stopping service")
                updateNotification("⚠️ Servicio detenido - demasiados errores de ubicación")

                // Dar tiempo para que se vea la notificación antes de parar
                Handler(Looper.getMainLooper()).postDelayed({
                    stopSelf()
                }, 3000)
            } else {
                // Error temporal - continuar pero informar
                val remainingAttempts = MAX_CONSECUTIVE_ERRORS - errors
                updateNotification("⚠️ Error de ubicación - $remainingAttempts intentos restantes")
            }
        }
        // ============ WAKE LOCK MANAGEMENT ============
    
        private fun acquireWakeLock() {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Peregrino::TrackingWakeLock"
            ).apply {
                acquire(WAKE_LOCK_TIMEOUT)
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
    
        // ============ SERVICE LIFECYCLE ============
    
        override fun onBind(intent: Intent?): IBinder? = null

        @RequiresApi(Build.VERSION_CODES.M)
        override fun onDestroy() {
            // Limpiar cualquier caché local o datos pendientes
            getSharedPreferences("tracking_prefs", MODE_PRIVATE).edit().clear().apply()
            Log.d(TAG, "TrackingService destruido y preferencias limpiadas")
            Log.d(TAG, "Destruyendo TrackingService")

            // Cancelar el job de procesamiento de batch
            batchJob?.cancel()

            // Cancelar el scope de coroutines
            serviceScope.cancel()

            // Enviar cualquier batch pendiente
            runBlocking {
                withTimeoutOrNull(2000L) {
                    sendBatchNow()
                }
            }

            // Detener actualizaciones de ubicación
            if (::locationCallback.isInitialized) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
                Log.d(TAG, "✅ Location updates stopped")
            }

            // Liberar wake lock
            releaseWakeLock()

            // Cerrar canal de procesamiento de ubicaciones
            locationProcessingChannel.close()

            // Cancelar todas las solicitudes HTTP pendientes
            httpClient.dispatcher.cancelAll()

            // Desregistrar el BroadcastReceiver para el estado del GPS
            if (::gpsReceiver.isInitialized) {
                unregisterReceiver(gpsReceiver)
                Log.d(TAG, "✅ GPS receiver unregistered")
            }

            super.onDestroy()
            Log.d(TAG, "TrackingService destruido")
        }
    }