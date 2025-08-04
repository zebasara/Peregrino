package com.zebass.peregrino.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
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
    private lateinit var alertManager: AlertManager

    // ============ CONFIGURACIÓN Y ESTADO ============
    private var deviceId: Int = -1
    private var deviceUniqueId: String? = null
    private var jwtToken: String? = null
    private val safeZone = AtomicReference<LatLng?>(null)
    private val lastLocationSent = AtomicReference<Location?>(null)
    private val lastLocationTime = AtomicLong(0)
    private val isProcessingLocation = AtomicBoolean(false)
    private val consecutiveErrors = AtomicLong(0)

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

        // Configuración optimizada
        const val LOCATION_INTERVAL = 5000L           // 5 segundos
        const val FASTEST_INTERVAL = 2000L            // 2 segundos mínimo
        const val DISPLACEMENT_THRESHOLD = 5f         // 5 metros mínimo
        const val LOCATION_SEND_THROTTLE = 3000L     // Máximo 1 envío cada 3s
        const val BATCH_SEND_INTERVAL = 10000L       // Enviar batch cada 10s
        const val MAX_BATCH_SIZE = 20                // Máximo 20 ubicaciones por batch
        const val MAX_CONSECUTIVE_ERRORS = 10        // Reintentos máximos
        const val WAKE_LOCK_TIMEOUT = 60000L         // 1 minuto wake lock
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
        alertManager = AlertManager(this)
        createNotificationChannel()
        acquireWakeLock()
        startLocationProcessing()
        Log.d(TAG, "TrackingService created")
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Extraer parámetros
        deviceId = intent?.getIntExtra("deviceId", -1) ?: -1
        deviceUniqueId = sharedPreferences.getString(SecondFragment.DEVICE_UNIQUE_ID_PREF, null)
        jwtToken = intent?.getStringExtra("jwtToken") ?: SecondFragment.JWT_TOKEN

        Log.d(TAG, "onStartCommand: deviceId=$deviceId, uniqueId=$deviceUniqueId")

        if (deviceId == -1 || deviceUniqueId.isNullOrEmpty()) {
            Log.e(TAG, "Invalid device configuration, stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        // Iniciar foreground service
        startForeground(NOTIFICATION_ID, createNotification())

        // Cargar zona segura y comenzar tracking
        loadSafeZone()
        startBatchProcessor()

        if (hasLocationPermission()) {
            startLocationUpdates()
        } else {
            Log.e(TAG, "No location permission, stopping service")
            stopSelf()
        }

        return START_STICKY
    }

    // ============ NOTIFICATION MANAGEMENT ============

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Vehicle Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Real-time vehicle location tracking"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }

            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String = "Monitoring vehicle location"): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Peregrino Tracking Active")
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
            maxWaitTime = LOCATION_INTERVAL * 2 // Batching for battery saving
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    // Send to channel for async processing
                    serviceScope.launch {
                        locationProcessingChannel.send(location)
                    }
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!availability.isLocationAvailable) {
                    Log.w(TAG, "Location not available")
                    updateNotification("Location unavailable - check GPS")
                }
            }
        }

        try {
            val executor = Dispatchers.IO.asExecutor()
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                executor,
                locationCallback
            )
            Log.d(TAG, "Started location updates with optimized settings")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in startLocationUpdates", e)
            stopSelf()
        }
    }
    // ============ LOCATION PROCESSING PIPELINE ============

    @RequiresApi(Build.VERSION_CODES.M)
    private fun startLocationProcessing() {
        serviceScope.launch {
            locationProcessingChannel.consumeAsFlow()
                .buffer(Channel.UNLIMITED) // Buffer ilimitado para no perder ubicaciones
                .collect { location ->
                    processLocation(location)
                }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private suspend fun processLocation(location: Location) = withContext(Dispatchers.Default) {
        try {
            // 1. Validar ubicación
            if (!isValidLocation(location)) {
                Log.d(TAG, "Invalid location, skipping")
                return@withContext
            }

            // 2. Throttling inteligente
            if (!shouldSendLocation(location)) {
                Log.d(TAG, "Location throttled")
                return@withContext
            }

            // 3. Verificar zona segura
            checkSafeZone(location)

            // 4. Preparar datos
            val locationData = LocationData(
                latitude = location.latitude,
                longitude = location.longitude,
                timestamp = location.time,
                speed = location.speed,
                accuracy = location.accuracy,
                bearing = location.bearing
            )

            // 5. Añadir a batch
            addToBatch(locationData)

            // 6. Enviar inmediatamente si es significativo
            if (isSignificantLocationChange(location)) {
                sendBatchNow()
            }

            // Actualizar estado
            lastLocationSent.set(location)
            lastLocationTime.set(System.currentTimeMillis())
            updateNotification("Active - Last update: ${getCurrentTime()}")

        } catch (e: Exception) {
            Log.e(TAG, "Error processing location", e)
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
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun addToBatch(locationData: LocationData) {
        synchronized(batchLock) {
            locationBatch.add(locationData)

            // Si el batch está lleno, enviarlo inmediatamente
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

        // Enviar solo la ubicación más reciente para tiempo real
        dataToSend.lastOrNull()?.let { mostRecent ->
            sendLocationToServer(mostRecent)
        }

        // Si hay múltiples ubicaciones, enviar batch completo
        if (dataToSend.size > 1) {
            sendBatchToServer(dataToSend)
        }
    }

    // ============ SERVER COMMUNICATION ============

    @RequiresApi(Build.VERSION_CODES.M)
    private suspend fun sendLocationToServer(locationData: LocationData) = withContext(Dispatchers.IO) {
        if (deviceUniqueId == null) return@withContext

        try {
            val url = "https://carefully-arriving-shepherd.ngrok-free.app/gps/osmand?" +
                    "id=$deviceUniqueId" +
                    "&lat=${locationData.latitude}" +
                    "&lon=${locationData.longitude}" +
                    "&timestamp=${locationData.timestamp / 1000}" +
                    "&speed=${locationData.speed}" +
                    "&bearing=${locationData.bearing}" +
                    "&accuracy=${locationData.accuracy}"

            val request = Request.Builder()
                .url(url)
                .get()
                .tag("location_update")
                .build()

            val response = httpClient.newCall(request).execute()

            if (response.isSuccessful) {
                consecutiveErrors.set(0)
                Log.d(TAG, "Location sent successfully")
            } else {
                throw Exception("Server returned ${response.code}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send location", e)
            handleLocationError()
        }
    }

    private suspend fun sendBatchToServer(batch: List<LocationData>) = withContext(Dispatchers.IO) {
        if (deviceUniqueId == null || batch.isEmpty()) return@withContext

        try {
            val jsonArray = org.json.JSONArray()
            batch.forEach { location ->
                jsonArray.put(JSONObject().apply {
                    put("id", deviceUniqueId)
                    put("lat", location.latitude)
                    put("lon", location.longitude)
                    put("timestamp", location.timestamp)
                    put("speed", location.speed)
                    put("bearing", location.bearing)
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

            httpClient.newCall(request).execute()
            Log.d(TAG, "Batch of ${batch.size} locations sent")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send batch", e)
        }
    }

    // ============ SAFE ZONE MONITORING ============

    private fun loadSafeZone() {
        val lat = sharedPreferences.getString(SecondFragment.PREF_SAFEZONE_LAT, null)?.toDoubleOrNull()
        val lon = sharedPreferences.getString(SecondFragment.PREF_SAFEZONE_LON, null)?.toDoubleOrNull()

        if (lat != null && lon != null) {
            safeZone.set(LatLng(lat, lon))
            Log.d(TAG, "Loaded safe zone: lat=$lat, lon=$lon")
        }
    }

    private fun checkSafeZone(location: Location) {
        safeZone.get()?.let { zone ->
            val distance = calculateDistance(
                zone.latitude, zone.longitude,
                location.latitude, location.longitude
            )

            if (distance > GEOFENCE_RADIUS) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    triggerGeofenceAlert(distance)
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun triggerGeofenceAlert(distance: Double) {
        // Vibración
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(
            VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE)
        )

        // Notificación
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⚠️ Geofence Alert")
            .setContentText("Vehicle is ${"%.1f".format(distance)} meters outside safe zone")
            .setSmallIcon(com.zebass.peregrino.R.drawable.ic_vehicle_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()

        getSystemService(NotificationManager::class.java)
            ?.notify(ALERT_NOTIFICATION_ID, notification)

        Log.d(TAG, "Geofence alert triggered: distance=$distance")
    }

    // ============ UTILITY FUNCTIONS ============

    private fun isValidLocation(location: Location): Boolean {
        return location.latitude != 0.0 &&
                location.longitude != 0.0 &&
                location.accuracy < 100f // Ignorar ubicaciones muy imprecisas
    }

    private fun shouldSendLocation(location: Location): Boolean {
        val now = System.currentTimeMillis()
        val timeSinceLastSend = now - lastLocationTime.get()

        // Throttling básico
        if (timeSinceLastSend < LOCATION_SEND_THROTTLE) {
            return false
        }

        // Si es la primera ubicación
        val lastLocation = lastLocationSent.get() ?: return true

        // Calcular distancia desde última ubicación enviada
        val distance = lastLocation.distanceTo(location)

        // Enviar si se movió más del threshold o pasó mucho tiempo
        return distance >= DISPLACEMENT_THRESHOLD || timeSinceLastSend > LOCATION_INTERVAL * 3
    }

    private fun isSignificantLocationChange(location: Location): Boolean {
        val lastLocation = lastLocationSent.get() ?: return true

        val distance = lastLocation.distanceTo(location)
        val speedChange = abs(location.speed - lastLocation.speed)
        val bearingChange = abs(location.bearing - lastLocation.bearing)

        return distance > 50f || // Movimiento significativo
                speedChange > 5f || // Cambio de velocidad significativo
                bearingChange > 45f  // Cambio de dirección significativo
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0].toDouble()
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun getCurrentTime(): String {
        return java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, notification)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun handleLocationError() {
        val errors = consecutiveErrors.incrementAndGet()

        if (errors >= MAX_CONSECUTIVE_ERRORS) {
            Log.e(TAG, "Too many consecutive errors, stopping service")
            updateNotification("Service stopped - too many errors")
            stopSelf()
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
        Log.d(TAG, "TrackingService destroying")

        // Cancelar todas las operaciones
        batchJob?.cancel()
        serviceScope.cancel()

        // Enviar último batch si existe
        runBlocking {
            withTimeoutOrNull(2000L) {
                sendBatchNow()
            }
        }

        // Limpiar recursos
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }

        releaseWakeLock()
        locationProcessingChannel.close()

        // Cancelar requests HTTP pendientes
        httpClient.dispatcher.cancelAll()

        super.onDestroy()
        Log.d(TAG, "TrackingService destroyed")
    }
}