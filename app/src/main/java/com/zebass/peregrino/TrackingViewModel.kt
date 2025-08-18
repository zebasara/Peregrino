package com.zebass.peregrino

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.math.*
import com.google.android.gms.maps.model.LatLng
import com.zebass.peregrino.SecondFragment.Companion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TrackingViewModel : ViewModel() {

    // ============ ESTADO ULTRA-OPTIMIZADO CON CACHE ============
    private val _vehiclePosition = MutableStateFlow<VehiclePosition?>(null)
    val vehiclePosition: StateFlow<VehiclePosition?> = _vehiclePosition.asStateFlow()

    private val _safeZone = MutableStateFlow<LatLng?>(null)
    val safeZone: StateFlow<LatLng?> = _safeZone.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _deviceInfo = MutableStateFlow<String?>(null)
    val deviceInfo: StateFlow<String?> = _deviceInfo.asStateFlow()
    // ✅ Add Fragment lifecycle awareness
    private var isFragmentAttached = true

    // ✅ NUEVO: ESTADO DE CONEXIÓN WEBSOCKET
    private val _connectionStatus =
        MutableStateFlow<ConnectionStatus>(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()


    // ============ NUEVO: SISTEMA DE INTERPOLACIÓN Y KALMAN FILTER ============
    private var kalmanFilter: VehicleKalmanFilter? = null
    private var lastGPSPosition: VehiclePosition? = null
    private var interpolationJob: Job? = null
    private val interpolationScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ============ WEBSOCKET PERSISTENTE MEJORADO ============
    private var webSocket: WebSocket? = null
    private var isWebSocketConnected = false
    private val webSocketScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 10
    private var lastConnectionTime = 0L
    private val minReconnectDelay = 1000L

    enum class ConnectionStatus {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        ERROR
    }

    // ✅ CRÍTICO: WebSocket debe sobrevivir cambios de fragmento
    private var isViewModelActive = true
    private var keepWebSocketAlive = false

    // ============ VARIABLES DE TRACKING ============
    private var isTrackingActive = false
    private var lastUpdateTime = 0L
    private val trackingHistory = mutableListOf<VehiclePosition>()
    private val maxHistorySize = 100

    // ✅ CRITICAL FIX: Enhanced HTTP Client
    private val client by lazy {
        OkHttpClient.Builder().apply {
            connectTimeout(10, TimeUnit.SECONDS)
            readTimeout(15, TimeUnit.SECONDS)
            writeTimeout(10, TimeUnit.SECONDS)
            callTimeout(20, TimeUnit.SECONDS)
            retryOnConnectionFailure(true)
            connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))

            addInterceptor { chain ->
                val originalRequest = chain.request()
                val newRequest = originalRequest.newBuilder()
                    .addHeader("User-Agent", "PeregrinoGPS-Fixed/3.0")
                    .addHeader("Accept", "application/json")
                    .addHeader("Cache-Control", "no-cache")
                    .build()

                val startTime = System.currentTimeMillis()
                try {
                    val response = chain.proceed(newRequest)
                    val duration = System.currentTimeMillis() - startTime

                    Log.d(
                        TAG,
                        "🌐 Request: ${originalRequest.url} (${duration}ms) → ${response.code}"
                    )

                    if (response.code == 502) {
                        Log.e(TAG, "❌ 502 Bad Gateway detected")
                        response.close()
                        return@addInterceptor Response.Builder()
                            .request(newRequest)
                            .protocol(response.protocol)
                            .code(502)
                            .message("Servidor temporalmente no disponible")
                            .body(
                                "{\"error\":\"Servidor temporalmente no disponible\"}".toResponseBody(
                                    "application/json".toMediaType()
                                )
                            )
                            .build()
                    }

                    response
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Request failed: ${e.message}")
                    Response.Builder()
                        .request(newRequest)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(503)
                        .message("Error de red: ${e.message}")
                        .body("{\"error\":\"${e.message}\"}".toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
        }.build()
    }

    // ============ DATA CLASSES MEJORADAS ============
    data class VehiclePosition(
        val deviceId: String,
        val latitude: Double,
        val longitude: Double,
        val speed: Double = 0.0,
        val bearing: Double = 0.0,
        val timestamp: Long = System.currentTimeMillis(),
        val accuracy: Float = 10f,
        val isInterpolated: Boolean = false,
        val quality: String = "unknown"
    )

    // ============ KALMAN FILTER PARA SUAVIZADO GPS ============
    private class VehicleKalmanFilter {
        private var lat: Double = 0.0
        private var lon: Double = 0.0
        private var velLat: Double = 0.0
        private var velLon: Double = 0.0
        private var lastTimestamp: Long = 0L

        // Matrices de covarianza simplificadas
        private var pLat: Double = 100.0
        private var pVelLat: Double = 10.0
        private var pLon: Double = 100.0
        private var pVelLon: Double = 10.0

        // Parámetros del filtro
        private val processNoise = 0.5
        private val measurementNoise = 5.0

        fun update(position: VehiclePosition): VehiclePosition {
            val currentTime = position.timestamp
            val deltaTime =
                if (lastTimestamp == 0L) 1.0 else (currentTime - lastTimestamp) / 1000.0
            lastTimestamp = currentTime

            if (deltaTime > 30.0) {
                lat = position.latitude
                lon = position.longitude
                velLat = 0.0
                velLon = 0.0
                return position.copy(accuracy = 20f)
            }

            predictLatitude(deltaTime)
            predictLongitude(deltaTime)
            updateLatitude(position.latitude, position.accuracy)
            updateLongitude(position.longitude, position.accuracy)

            return VehiclePosition(
                deviceId = position.deviceId,
                latitude = lat,
                longitude = lon,
                speed = kotlin.math.sqrt(velLat * velLat + velLon * velLon) * 111000,
                bearing = kotlin.math.atan2(velLon, velLat) * 180.0 / kotlin.math.PI,
                timestamp = currentTime,
                accuracy = kotlin.math.min(position.accuracy, 15f),
                isInterpolated = false,
                quality = "kalman_filtered"
            )
        }

        private fun predictLatitude(deltaTime: Double) {
            lat += velLat * deltaTime
            pLat += pVelLat * deltaTime * deltaTime + processNoise * deltaTime
            pVelLat += processNoise * deltaTime
        }

        private fun predictLongitude(deltaTime: Double) {
            lon += velLon * deltaTime
            pLon += pVelLon * deltaTime * deltaTime + processNoise * deltaTime
            pVelLon += processNoise * deltaTime
        }

        private fun updateLatitude(measurement: Double, accuracy: Float) {
            val r = (accuracy / 1.0).coerceAtLeast(measurementNoise)
            val k = pLat / (pLat + r)
            lat += k * (measurement - lat)
            pLat *= (1 - k)

            if (kotlin.math.abs(measurement - lat) > 0.00001) {
                velLat = (measurement - lat) * k * 0.1
            }
        }

        private fun updateLongitude(measurement: Double, accuracy: Float) {
            val r = (accuracy / 1.0).coerceAtLeast(measurementNoise)
            val k = pLon / (pLon + r)
            lon += k * (measurement - lon)
            pLon *= (1 - k)

            if (kotlin.math.abs(measurement - lon) > 0.00001) {
                velLon = (measurement - lon) * k * 0.1
            }
        }

        fun interpolate(deltaTimeMs: Long): VehiclePosition? {
            if (lastTimestamp == 0L) return null

            val deltaTime = deltaTimeMs / 1000.0
            val interpLat = lat + velLat * deltaTime
            val interpLon = lon + velLon * deltaTime

            return VehiclePosition(
                deviceId = "",
                latitude = interpLat,
                longitude = interpLon,
                speed = kotlin.math.sqrt(velLat * velLat + velLon * velLon) * 111000,
                bearing = kotlin.math.atan2(velLon, velLat) * 180.0 / kotlin.math.PI,
                timestamp = System.currentTimeMillis(),
                accuracy = 10f,
                isInterpolated = true,
                quality = "interpolated"
            )
        }
    }

    // ============ CACHE ULTRA-RÁPIDO ============
    private data class CacheEntry<T>(
        val data: T,
        val timestamp: Long,
        val ttl: Long = 30_000L
    ) {
        fun isValid(): Boolean = System.currentTimeMillis() - timestamp < ttl
    }

    private val positionCache = ConcurrentHashMap<String, CacheEntry<LatLng>>()
    private val deviceStatusCache = ConcurrentHashMap<String, CacheEntry<DeviceStatus>>()
    private val safeZoneCache = ConcurrentHashMap<String, CacheEntry<LatLng>>()
    private val deviceListCache = AtomicReference<CacheEntry<List<DeviceInfo>>?>()

    // ============ COROUTINES OPTIMIZADAS ============
    private val ioDispatcher = Dispatchers.IO.limitedParallelism(8)
    private val networkScope = CoroutineScope(ioDispatcher + SupervisorJob())
    private val cacheCleanupJob = viewModelScope.launch {
        while (true) {
            delay(60_000L)
            cleanupCaches()
        }
    }

    // ✅ ACTUALIZAR LA DATA CLASS AssociateDeviceResponse
    data class AssociateDeviceResponse(
        val success: Boolean = true,
        val message: String = "",
        val device: DeviceDetails
    ) {
        data class DeviceDetails(
            val id: Int,
            val name: String,
            val uniqueId: String,
            val traccarDeviceId: Int,
            val status: String
        )

        val id: Int get() = device.id
        val name: String get() = device.name
        val uniqueId: String get() = device.uniqueId
        val status: String get() = device.status
    }

    // ============ MODELOS OPTIMIZADOS ============
    data class DeviceStatus(
        val isOnline: Boolean,
        val deviceName: String,
        val recentPositions: Int,
        val lastSeen: Long = System.currentTimeMillis()
    )

    data class DeviceInfo(
        val id: String,
        val name: String,
        val uniqueId: String,
        val status: String = "unknown"
    )

    data class DeviceInfoResponse(
        val id: String,
        val name: String,
        val uniqueId: String?,
        val status: String?
    )

    data class LastPositionResponse(
        val deviceId: String,
        val traccarDeviceId: Int,
        val latitude: Double,
        val longitude: Double,
        val speed: Double,
        val course: Double,
        val timestamp: String,
        val age: Int? = null,
        val quality: String? = null
    )

    companion object {
        const val DEVICE_ID_PREF = "associated_device_id"
        const val DEVICE_NAME_PREF = "associated_device_name"
        const val DEVICE_UNIQUE_ID_PREF = "associated_device_unique_id"

        // ✅ CRITICAL FIX: Updated URLs with proper fallbacks
        private const val BASE_URL = "https://app.socialengeneering.work"
        private const val TRACCAR_URL = "https://traccar.socialengeneering.work"
        private const val WS_URL = "wss://app.socialengeneering.work"

        private const val TAG = "TrackingViewModel"

        private const val POSITION_TTL = 5_000L
        private const val DEVICE_STATUS_TTL = 30_000L
        private const val SAFE_ZONE_TTL = 120_000L
        private const val DEVICE_LIST_TTL = 60_000L
    }

    @SuppressLint("StaticFieldLeak")
    private lateinit var context: Context

    // ============ WEBSOCKET MEJORADO PARA TIEMPO REAL ============
    @RequiresApi(Build.VERSION_CODES.O)
    fun startRealTimeTracking(loadInitialPosition: Boolean = true) {
        isViewModelActive = true
        keepWebSocketAlive = true
        isTrackingActive = true
        kalmanFilter = VehicleKalmanFilter()

        // ✅ NO cargar posición inicial - esperar GPS real
        connectWebSocketForRealTimeGPS()
        startInterpolation()

        _error.value = "🔄 Conectando GPS - Esperando posición real..."
    }
    // 2. NUEVA FUNCIÓN: WebSocket específico para GPS en tiempo real
    private fun connectWebSocketForRealTimeGPS(retryCount: Int = 0) {
        if (!isViewModelActive || !keepWebSocketAlive) {
            Log.d(TAG, "⏹️ GPS WebSocket connection cancelled - ViewModel inactive")
            return
        }

        reconnectJob?.cancel()

        val timeSinceLastConnection = System.currentTimeMillis() - lastConnectionTime
        if (timeSinceLastConnection < minReconnectDelay && retryCount > 0) {
            Log.d(TAG, "⏳ Delaying GPS reconnection to avoid spam")
            scheduleGPSReconnection(retryCount)
            return
        }

        lastConnectionTime = System.currentTimeMillis()

        // ✅ CERRAR CONEXIÓN ANTERIOR
        try {
            webSocket?.close(1000, "Reconectando GPS")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing previous GPS WebSocket: ${e.message}")
        }
        webSocket = null
        isWebSocketConnected = false

        _connectionStatus.value =
            if (retryCount == 0) ConnectionStatus.CONNECTING else ConnectionStatus.RECONNECTING

        val wsUrl = "$WS_URL/ws?token=${SecondFragment.JWT_TOKEN}"
        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("User-Agent", "PeregrinoGPS-RealTime-GPS/3.0")
            .addHeader("Origin", "https://app.socialengeneering.work")
            .addHeader("X-GPS-Client", "true") // Identificar como cliente GPS
            .build()

        Log.d(TAG, "🔌 Connecting GPS WebSocket (attempt ${retryCount + 1})")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "✅ GPS WebSocket CONNECTED successfully")
                isWebSocketConnected = true
                reconnectAttempts = 0
                _connectionStatus.value = ConnectionStatus.CONNECTED

                val deviceUniqueId = getDeviceUniqueId()
                if (deviceUniqueId != null) {
                    // ✅ SUSCRIPCIÓN ESPECÍFICA PARA GPS
                    val subscribeMessage = JSONObject().apply {
                        put("type", "SUBSCRIBE")
                        put("deviceId", deviceUniqueId)
                        put("timestamp", System.currentTimeMillis())
                        put("requestRealTime", true)
                        put("gpsRealTime", true) // ✅ MARCAR COMO GPS EN TIEMPO REAL
                        put("persistent", true)
                    }

                    try {
                        val success = webSocket.send(subscribeMessage.toString())
                        Log.d(TAG, "📡 GPS subscription sent: $success for device: $deviceUniqueId")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error sending GPS subscription: ${e.message}")
                    }

                    // ✅ SOLICITAR POSICIÓN ACTUAL DE GPS
                    webSocketScope.launch {
                        delay(1000)
                        try {
                            val requestCurrentPosition = JSONObject().apply {
                                put("type", "REQUEST_CURRENT_POSITION")
                                put("deviceId", deviceUniqueId)
                                put("gpsRealTime", true)
                            }
                            webSocket.send(requestCurrentPosition.toString())
                            Log.d(TAG, "📍 GPS current position requested")
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Error requesting GPS position: ${e.message}")
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "📨 GPS WebSocket message: ${text.take(150)}...")
                try {
                    handleGPSWebSocketMessage(text)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error handling GPS message: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "❌ GPS WebSocket error: ${t.message}")
                isWebSocketConnected = false
                _connectionStatus.value = ConnectionStatus.ERROR

                if (keepWebSocketAlive && isViewModelActive && reconnectAttempts < maxReconnectAttempts) {
                    reconnectAttempts++
                    val delayMs = calculateReconnectDelay(reconnectAttempts)
                    Log.d(
                        TAG,
                        "🔄 GPS WebSocket reconnecting in ${delayMs}ms (attempt $reconnectAttempts)"
                    )
                    scheduleGPSReconnection(reconnectAttempts, delayMs)
                } else {
                    Log.e(TAG, "❌ GPS WebSocket: Max reconnect attempts reached")
                    _connectionStatus.value = ConnectionStatus.DISCONNECTED
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "🔌 GPS WebSocket closed: $code - $reason")
                isWebSocketConnected = false

                if (code != 1000 && keepWebSocketAlive && isViewModelActive && reconnectAttempts < maxReconnectAttempts) {
                    reconnectAttempts++
                    Log.d(TAG, "🔄 GPS WebSocket auto-reconnecting after unexpected close...")
                    scheduleGPSReconnection(reconnectAttempts)
                } else {
                    _connectionStatus.value = ConnectionStatus.DISCONNECTED
                }
            }
        })
    }

    // 1. AGREGAR función handleGPSWebSocketMessage que falta:
    private fun handleGPSWebSocketMessage(message: String) {
        // Check if we should still process messages
        if (!isViewModelActive || !isFragmentAttached) {
            Log.d(TAG, "🛑 Ignoring GPS message - ViewModel/Fragment inactive")
            return
        }

        try {
            val json = JSONObject(message)
            val type = json.getString("type")
            val ourDeviceId = getDeviceUniqueId()

            when (type) {
                "POSITION_UPDATE" -> {
                    val data = json.getJSONObject("data")
                    val deviceId = data.getString("deviceId")

                    if (deviceId == ourDeviceId) {
                        val position = VehiclePosition(
                            deviceId = deviceId,
                            latitude = data.getDouble("latitude"),
                            longitude = data.getDouble("longitude"),
                            speed = data.optDouble("speed", 0.0),
                            bearing = data.optDouble("course", 0.0),
                            timestamp = System.currentTimeMillis(),
                            accuracy = 5f,
                            quality = "gps_live_real",
                            isInterpolated = false
                        )

                        // ✅ Only process if Fragment is still attached
                        if (isFragmentAttached) {
                            processNewGPSPositionPersistent(position)
                            _error.value = null
                        }
                    }
                }

                "CONNECTION_CONFIRMED" -> {
                    Log.d(TAG, "✅ GPS WebSocket subscription confirmed")
                    if (isFragmentAttached) {
                        _connectionStatus.value = ConnectionStatus.CONNECTED
                    }
                }

                "CURRENT_POSITION" -> {
                    val data = json.getJSONObject("data")
                    val position = VehiclePosition(
                        deviceId = data.getString("deviceId"),
                        latitude = data.getDouble("latitude"),
                        longitude = data.getDouble("longitude"),
                        speed = data.optDouble("speed", 0.0),
                        bearing = data.optDouble("course", 0.0),
                        timestamp = System.currentTimeMillis(),
                        accuracy = 10f,
                        quality = "gps_current_requested"
                    )

                    Log.d(TAG, "📍 GPS Current position received: ${position.latitude}, ${position.longitude}")
                    if (isFragmentAttached) {
                        processNewGPSPositionPersistent(position)
                    }
                }

                "ERROR" -> {
                    val errorMsg = json.optString("message", "Error GPS desconocido")
                    Log.e(TAG, "❌ GPS WebSocket error: $errorMsg")
                    if (isFragmentAttached) {
                        postError("Error GPS: $errorMsg")
                    }
                }

                "PING" -> {
                    val pongMessage = JSONObject().apply {
                        put("type", "PONG")
                        put("timestamp", System.currentTimeMillis())
                        put("gpsClient", true)
                    }
                    webSocket?.send(pongMessage.toString())
                    Log.d(TAG, "💓 GPS WebSocket ping responded")
                }

                "PONG" -> {
                    Log.d(TAG, "💓 GPS WebSocket pong received")
                }

                else -> {
                    Log.d(TAG, "ℹ️ GPS Unknown WebSocket message type: $type")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error processing GPS WebSocket message: ${e.message}")
        }
    }

    // ✅ Safe context access
    private fun getSafeContext(): Context? {
        return try {
            if (::context.isInitialized && isFragmentAttached) {
                context
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Context access failed: ${e.message}")
            null
        }
    }

    // ✅ Notify when Fragment detaches
    fun onFragmentDetached() {
        Log.d(TAG, "📱 Fragment detached, marking ViewModel")
        isFragmentAttached = false

        // Keep WebSocket alive but reduce activity
        if (keepWebSocketAlive) {
            pauseWebSocket()
        }
    }

    // ✅ Notify when Fragment reattaches
    fun onFragmentAttached() {
        Log.d(TAG, "📱 Fragment reattached, resuming ViewModel")
        isFragmentAttached = true

        if (keepWebSocketAlive) {
            resumeWebSocket()
        }
    }


    // ✅ NUEVA FUNCIÓN: WebSocket Persistente con Reconexión Automática
    private fun connectWebSocketPersistent(retryCount: Int = 0) {
        if (!isViewModelActive || !keepWebSocketAlive) {
            Log.d(TAG, "⏹️ WebSocket connection cancelled - ViewModel inactive")
            return
        }

        reconnectJob?.cancel()

        val timeSinceLastConnection = System.currentTimeMillis() - lastConnectionTime
        if (timeSinceLastConnection < minReconnectDelay && retryCount > 0) {
            Log.d(TAG, "⏳ Delaying reconnection to avoid spam")
            scheduleReconnection(retryCount)
            return
        }

        lastConnectionTime = System.currentTimeMillis()

        // ✅ CERRAR CONEXIÓN ANTERIOR CORRECTAMENTE
        try {
            webSocket?.close(1000, "Reconectando")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing previous WebSocket: ${e.message}")
        }
        webSocket = null
        isWebSocketConnected = false

        _connectionStatus.value =
            if (retryCount == 0) ConnectionStatus.CONNECTING else ConnectionStatus.RECONNECTING

        val wsUrl = "$WS_URL/ws?token=${SecondFragment.JWT_TOKEN}"
        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("User-Agent", "PeregrinoGPS-Persistent-WS/3.0")
            .addHeader("Origin", "https://app.socialengeneering.work")
            .build()

        Log.d(TAG, "🔌 Connecting PERSISTENT WebSocket (attempt ${retryCount + 1})")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "✅ PERSISTENT WebSocket CONNECTED successfully")
                isWebSocketConnected = true
                reconnectAttempts = 0
                _connectionStatus.value = ConnectionStatus.CONNECTED

                val deviceUniqueId = getDeviceUniqueId()
                if (deviceUniqueId != null) {
                    val subscribeMessage = JSONObject().apply {
                        put("type", "SUBSCRIBE")
                        put("deviceId", deviceUniqueId)
                        put("timestamp", System.currentTimeMillis())
                        put("requestRealTime", true)
                        put("persistent", true)
                        put("backgroundMode", !isViewModelActive) // ✅ INDICAR SI ESTÁ EN BACKGROUND
                    }

                    try {
                        val success = webSocket.send(subscribeMessage.toString())
                        Log.d(
                            TAG,
                            "📡 PERSISTENT subscription sent: $success for device: $deviceUniqueId"
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error sending subscription: ${e.message}")
                    }

                    // ✅ SOLICITAR POSICIÓN INMEDIATA
                    webSocketScope.launch {
                        delay(1000)
                        try {
                            val requestPosition = JSONObject().apply {
                                put("type", "REQUEST_CURRENT_POSITION")
                                put("deviceId", deviceUniqueId)
                                put("persistent", true)
                            }
                            webSocket.send(requestPosition.toString())
                            Log.d(TAG, "📍 PERSISTENT position request sent")
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Error requesting position: ${e.message}")
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "📨 PERSISTENT WebSocket message: ${text.take(150)}...")
                try {
                    handleWebSocketMessagePersistent(text)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error handling PERSISTENT message: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "❌ PERSISTENT WebSocket error: ${t.message}")
                isWebSocketConnected = false
                _connectionStatus.value = ConnectionStatus.ERROR

                if (keepWebSocketAlive && isViewModelActive && reconnectAttempts < maxReconnectAttempts) {
                    reconnectAttempts++
                    val delayMs = calculateReconnectDelay(reconnectAttempts)
                    Log.d(
                        TAG,
                        "🔄 PERSISTENT WebSocket reconnecting in ${delayMs}ms (attempt $reconnectAttempts)"
                    )
                    scheduleReconnection(reconnectAttempts, delayMs)
                } else {
                    Log.e(TAG, "❌ PERSISTENT WebSocket: Max reconnect attempts reached")
                    _connectionStatus.value = ConnectionStatus.DISCONNECTED
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "🔌 PERSISTENT WebSocket closed: $code - $reason")
                isWebSocketConnected = false

                if (code != 1000 && keepWebSocketAlive && isViewModelActive && reconnectAttempts < maxReconnectAttempts) {
                    reconnectAttempts++
                    Log.d(TAG, "🔄 PERSISTENT WebSocket auto-reconnecting after unexpected close...")
                    scheduleReconnection(reconnectAttempts)
                } else {
                    _connectionStatus.value = ConnectionStatus.DISCONNECTED
                }
            }
        })
    }

    // ✅ FUNCIÓN PARA PROGRAMAR RECONEXIÓN
    private fun scheduleReconnection(
        attempt: Int,
        delayMs: Long = calculateReconnectDelay(attempt)
    ) {
        reconnectJob?.cancel()
        reconnectJob = webSocketScope.launch {
            delay(delayMs)
            if (keepWebSocketAlive && isViewModelActive) {
                Log.d(TAG, "🔄 Executing scheduled reconnection (attempt $attempt)")
                connectWebSocketPersistent(attempt)
            }
        }
    }

    // ✅ CALCULAR DELAY DE RECONEXIÓN EXPONENCIAL
    private fun calculateReconnectDelay(attempt: Int): Long {
        val baseDelay = 1000L // 1 segundo
        val maxDelay = 30000L // 30 segundos máximo
        val exponentialDelay = baseDelay * (2.0.pow(attempt.coerceAtMost(5))).toLong()
        return exponentialDelay.coerceAtMost(maxDelay)
    }

    // ✅ MANEJO MEJORADO DE MENSAJES WEBSOCKET
    private fun handleWebSocketMessagePersistent(message: String) {
        try {
            val json = JSONObject(message)
            val type = json.getString("type")

            when (type) {
                "POSITION_UPDATE" -> {
                    val data = json.getJSONObject("data")
                    val deviceId = data.getString("deviceId")

                    val ourDeviceId = getDeviceUniqueId()
                    if (deviceId == ourDeviceId) {
                        val position = VehiclePosition(
                            deviceId = deviceId,
                            latitude = data.getDouble("latitude"),
                            longitude = data.getDouble("longitude"),
                            speed = data.optDouble("speed", 0.0),
                            bearing = data.optDouble("course", 0.0),
                            timestamp = System.currentTimeMillis(),
                            accuracy = 8f,
                            quality = "websocket_realtime"
                        )

                        Log.d(
                            TAG,
                            "🎯 PERSISTENT Real-time position: ${position.latitude}, ${position.longitude}"
                        )

                        // ✅ ACTUALIZAR INMEDIATAMENTE EN MAIN THREAD
                        processNewGPSPositionPersistent(position)
                    }
                }

                "CONNECTION_CONFIRMED" -> {
                    Log.d(TAG, "✅ PERSISTENT WebSocket subscription confirmed")
                    _connectionStatus.value = ConnectionStatus.CONNECTED
                }

                "CURRENT_POSITION" -> {
                    val data = json.getJSONObject("data")
                    val position = VehiclePosition(
                        deviceId = data.getString("deviceId"),
                        latitude = data.getDouble("latitude"),
                        longitude = data.getDouble("longitude"),
                        speed = data.optDouble("speed", 0.0),
                        bearing = data.optDouble("course", 0.0),
                        timestamp = System.currentTimeMillis(),
                        accuracy = 10f,
                        quality = "current_requested"
                    )

                    Log.d(
                        TAG,
                        "📍 PERSISTENT Current position received: ${position.latitude}, ${position.longitude}"
                    )
                    processNewGPSPositionPersistent(position)
                }

                "ERROR" -> {
                    val errorMsg = json.optString("message", "Error desconocido")
                    Log.e(TAG, "❌ PERSISTENT WebSocket server error: $errorMsg")
                    postError("Error del servidor: $errorMsg")
                }

                "PING" -> {
                    // ✅ RESPONDER A PINGS INMEDIATAMENTE
                    val pongMessage = JSONObject().apply {
                        put("type", "PONG")
                        put("timestamp", System.currentTimeMillis())
                    }
                    webSocket?.send(pongMessage.toString())
                    Log.d(TAG, "💓 PERSISTENT WebSocket ping responded")
                }

                "PONG" -> {
                    Log.d(TAG, "💓 PERSISTENT WebSocket pong received")
                }

                else -> {
                    Log.d(TAG, "ℹ️ PERSISTENT Unknown WebSocket message type: $type")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error processing PERSISTENT WebSocket message: ${e.message}")
        }
    }

    // ✅ PROCESAR POSICIÓN GPS CON PERSISTENCE
    private fun processNewGPSPositionPersistent(rawPosition: VehiclePosition) {
        try {
            val filteredPosition = kalmanFilter?.update(rawPosition) ?: rawPosition

            trackingHistory.add(filteredPosition)
            if (trackingHistory.size > maxHistorySize) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    trackingHistory.removeFirst()
                }
            }

            lastGPSPosition = filteredPosition
            lastUpdateTime = System.currentTimeMillis()

            // ✅ ACTUALIZAR INMEDIATAMENTE EN MAIN THREAD
            _vehiclePosition.value = filteredPosition

            Log.d(
                TAG,
                "🎯 PERSISTENT Position processed: lat=${filteredPosition.latitude}, lon=${filteredPosition.longitude}, quality=${filteredPosition.quality}"
            )

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error processing PERSISTENT GPS position: ${e.message}")
        }
    }

    // ✅ FUNCIÓN PARA MANTENER WEBSOCKET VIVO
    fun keepWebSocketAlive() {
        Log.d(TAG, "🔄 Keeping WebSocket alive...")
        keepWebSocketAlive = true

        if (!isWebSocketHealthy() && isViewModelActive) {
            Log.d(TAG, "🔌 WebSocket not healthy, reconnecting...")
            connectWebSocketPersistent()
        } else if (isWebSocketHealthy()) {
            // ✅ Enviar ping de mantenimiento
            try {
                val keepAliveMessage = JSONObject().apply {
                    put("type", "KEEP_ALIVE")
                    put("timestamp", System.currentTimeMillis())
                }
                webSocket?.send(keepAliveMessage.toString())
                Log.d(TAG, "💓 Keep alive ping sent")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Keep alive ping failed: ${e.message}")
                connectWebSocketPersistent()
            }
        }
    }

    // ✅ FUNCIÓN PARA PAUSAR WEBSOCKET (cuando app va a background)
    fun pauseWebSocket() {
        Log.d(TAG, "⏸️ Pausing WebSocket (app backgrounded)")
        // NO cerrar WebSocket, solo marcar como pausado
    }

    // ✅ FUNCIÓN PARA REACTIVAR WEBSOCKET (cuando app vuelve a foreground)
    fun resumeWebSocket() {
        Log.d(TAG, "▶️ Resuming GPS WebSocket (app foregrounded)")

        if (!isWebSocketConnected && keepWebSocketAlive && isViewModelActive) {
            Log.d(TAG, "🔌 GPS WebSocket disconnected, reconnecting on resume...")
            connectWebSocketForRealTimeGPS()
            return
        }

        // ✅ VERIFICAR CONEXIÓN GPS CON PING
        webSocket?.let { ws ->
            try {
                val pingMessage = JSONObject().apply {
                    put("type", "PING")
                    put("timestamp", System.currentTimeMillis())
                    put("gpsClient", true)
                }

                val success = ws.send(pingMessage.toString())
                if (success) {
                    Log.d(TAG, "💓 GPS resume ping sent successfully")
                    isWebSocketConnected = true
                    _connectionStatus.value = ConnectionStatus.CONNECTED
                } else {
                    Log.d(TAG, "❌ GPS resume ping failed, WebSocket may be closed")
                    isWebSocketConnected = false
                    connectWebSocketForRealTimeGPS()
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error sending GPS resume ping: ${e.message}")
                isWebSocketConnected = false
                connectWebSocketForRealTimeGPS()
            }
        } ?: run {
            Log.d(TAG, "🔌 No GPS WebSocket on resume, creating new connection...")
            connectWebSocketForRealTimeGPS()
        }
    }

    // 6. FUNCIÓN AUXILIAR: Programar reconexión GPS
    private fun scheduleGPSReconnection(
        attempt: Int,
        delayMs: Long = calculateReconnectDelay(attempt)
    ) {
        reconnectJob?.cancel()
        reconnectJob = webSocketScope.launch {
            delay(delayMs)
            if (keepWebSocketAlive && isViewModelActive) {
                Log.d(TAG, "🔄 Executing GPS reconnection (attempt $attempt)")
                connectWebSocketForRealTimeGPS(attempt)
            }
        }
    }

    // 7. FIX forceReconnectWebSocket - ESPECÍFICO PARA GPS
    fun forceReconnectWebSocket() {
        Log.d(TAG, "🔄 FORCE reconnecting GPS WebSocket...")
        reconnectAttempts = 0
        connectWebSocketForRealTimeGPS()
    }

    // 8. FIX debugVehicleState - INCLUIR INFO GPS
    fun debugGPSState(): String {
        val deviceId = getDeviceUniqueId()
        val hasPosition = _vehiclePosition.value != null
        val wsConnected = isWebSocketConnected
        val connectionStatus = _connectionStatus.value

        return buildString {
            appendLine("🔍 GPS REAL-TIME DEBUG STATE:")
            appendLine("Device ID: $deviceId")
            appendLine("Has GPS Position: $hasPosition")
            appendLine("WebSocket Connected: $wsConnected")
            appendLine("Connection Status: $connectionStatus")
            appendLine("Tracking Active: $isTrackingActive")
            appendLine("Keep Alive: $keepWebSocketAlive")

            if (hasPosition) {
                val pos = _vehiclePosition.value!!
                appendLine("GPS Position: ${pos.latitude}, ${pos.longitude}")
                appendLine("GPS Quality: ${pos.quality}")
                appendLine("GPS Speed: ${pos.speed} km/h")
                appendLine("Is Interpolated: ${pos.isInterpolated}")
                appendLine("Last Update: ${System.currentTimeMillis() - lastUpdateTime}ms ago")
            }

            appendLine("Tracking History: ${trackingHistory.size} points")
            appendLine("Kalman Filter: ${if (kalmanFilter != null) "Active" else "Inactive"}")
        }
    }

    // 9. NUEVA FUNCIÓN: Verificar estado de GPS
    fun getGPSStatus(): GPSStatus {
        return GPSStatus(
            isConnected = isWebSocketConnected,
            connectionStatus = _connectionStatus.value,
            hasPosition = _vehiclePosition.value != null,
            lastUpdate = lastUpdateTime,
            deviceId = getDeviceUniqueId(),
            trackingPoints = trackingHistory.size,
            isRealTime = isTrackingActive && keepWebSocketAlive
        )
    }

    data class GPSStatus(
        val isConnected: Boolean,
        val connectionStatus: ConnectionStatus,
        val hasPosition: Boolean,
        val lastUpdate: Long,
        val deviceId: String?,
        val trackingPoints: Int,
        val isRealTime: Boolean
    )

    // 11. NUEVA FUNCIÓN: Forzar actualización GPS
    fun forceGPSUpdate() {
        Log.d(TAG, "🔄 Forcing GPS update...")

        val deviceUniqueId = getDeviceUniqueId()
        if (deviceUniqueId == null) {
            _error.value = "No hay dispositivo GPS configurado"
            return
        }

        if (!isWebSocketConnected) {
            Log.d(TAG, "🔌 WebSocket not connected, reconnecting for GPS update...")
            connectWebSocketForRealTimeGPS()
            return
        }

        // ✅ SOLICITAR POSICIÓN ACTUAL VIA WEBSOCKET
        try {
            val requestMessage = JSONObject().apply {
                put("type", "REQUEST_CURRENT_POSITION")
                put("deviceId", deviceUniqueId)
                put("timestamp", System.currentTimeMillis())
                put("forceUpdate", true)
            }

            val success = webSocket?.send(requestMessage.toString()) ?: false
            if (success) {
                Log.d(TAG, "📍 GPS position update requested via WebSocket")
                _error.value = "🔄 Solicitando actualización GPS..."
            } else {
                Log.w(TAG, "❌ Failed to send GPS update request")
                _error.value = "❌ Error solicitando actualización GPS"
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error forcing GPS update: ${e.message}")
            _error.value = "❌ Error: ${e.message}"
        }
    }

    // 12. NUEVA FUNCIÓN: Obtener estadísticas GPS
    fun getGPSTrackingStats(): GPSTrackingStats {
        val history = trackingHistory.filter { !it.isInterpolated } // Solo datos reales GPS
        val totalDistance = calculateTotalDistance(history)
        val averageSpeed = history.map { it.speed }.average().takeIf { !it.isNaN() } ?: 0.0
        val maxSpeed = history.maxOfOrNull { it.speed } ?: 0.0

        return GPSTrackingStats(
            totalGPSPoints = history.size,
            interpolatedPoints = trackingHistory.count { it.isInterpolated },
            totalDistance = totalDistance,
            averageSpeed = averageSpeed,
            maxSpeed = maxSpeed,
            isGPSConnected = isWebSocketConnected,
            lastGPSUpdate = lastUpdateTime,
            gpsQuality = lastGPSPosition?.quality ?: "unknown",
            connectionUptime = if (lastConnectionTime > 0) System.currentTimeMillis() - lastConnectionTime else 0
        )
    }

    data class GPSTrackingStats(
        val totalGPSPoints: Int,
        val interpolatedPoints: Int,
        val totalDistance: Double,
        val averageSpeed: Double,
        val maxSpeed: Double,
        val isGPSConnected: Boolean,
        val lastGPSUpdate: Long,
        val gpsQuality: String,
        val connectionUptime: Long
    )

    // 3. CREAR FUNCIÓN AUXILIAR PARA VERIFICAR ESTADO DEL WEBSOCKET:
    private fun isWebSocketHealthy(): Boolean {
        return webSocket != null && isWebSocketConnected
    }

    // ============ FUNCIÓN PARA DETENER TRACKING ============
    fun stopRealTimeTracking() {
        Log.d(TAG, "🛑 Stopping PERSISTENT real-time tracking")

        isTrackingActive = false
        keepWebSocketAlive = false
        isWebSocketConnected = false

        // ✅ CERRAR WEBSOCKET LIMPIAMENTE
        webSocket?.close(1000, "Tracking stopped")
        webSocket = null

        // ✅ CANCELAR JOBS
        reconnectJob?.cancel()
        interpolationJob?.cancel()

        kalmanFilter = null
        lastGPSPosition = null
        trackingHistory.clear()

        _connectionStatus.value = ConnectionStatus.DISCONNECTED

        Log.d(TAG, "✅ PERSISTENT real-time tracking stopped")
    }

// 6. AGREGAR función de debug para verificar estado:

    fun debugVehicleState(): String {
        val deviceId = getDeviceUniqueId()
        val hasPosition = _vehiclePosition.value != null
        val cacheSize = positionCache.size
        val isTracking = isTrackingActive
        val wsConnected = isWebSocketConnected

        return buildString {
            appendLine("🔍 VEHICLE DEBUG STATE:")
            appendLine("Device ID: $deviceId")
            appendLine("Has Position: $hasPosition")
            appendLine("Cache Size: $cacheSize")
            appendLine("Tracking Active: $isTracking")
            appendLine("WebSocket: $wsConnected")
            if (hasPosition) {
                val pos = _vehiclePosition.value!!
                appendLine("Last Position: ${pos.latitude}, ${pos.longitude}")
                appendLine("Quality: ${pos.quality}")
                appendLine("Timestamp: ${pos.timestamp}")
            }
        }
    }
    // ============ FIX EN TrackingViewModel.kt ============
// Reemplaza la función connectWebSocket() con esta versión:

    private fun connectWebSocket(retryCount: Int = 0) {
        if (webSocket != null) {
            webSocket?.close(1000, "Reconectando")
        }

        val wsUrl = "$WS_URL/ws?token=${SecondFragment.JWT_TOKEN}"
        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("User-Agent", "PeregrinoGPS-Enhanced-WS/3.0")
            .addHeader("Origin", "https://app.socialengeneering.work")
            .build()

        Log.d(TAG, "🔌 Connecting WebSocket (attempt ${retryCount + 1}): $wsUrl")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val deviceUniqueId = getDeviceUniqueId()
                Log.d(TAG, "✅ GPS WebSocket CONNECTED")
                isWebSocketConnected = true
                _connectionStatus.value = ConnectionStatus.CONNECTED

                // ✅ SOLO suscribirse - NO solicitar posición actual
                val subscribeMessage = JSONObject().apply {
                    put("type", "SUBSCRIBE")
                    put("deviceId", deviceUniqueId)
                    put("requestRealTime", true)
                    put("waitForRealGPS", true) // ✅ AGREGAR ESTO
                }
                webSocket.send(subscribeMessage.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "📨 WebSocket message: ${text.take(150)}...")

                try {
                    handleWebSocketMessage(text)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error handling WebSocket message: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "❌ WebSocket error: ${t.message}")
                isWebSocketConnected = false

                // ✅ LÓGICA DE REINTENTOS MEJORADA
                if (isTrackingActive && retryCount < 3) {
                    webSocketScope.launch {
                        val delayMs = (retryCount + 1) * 2000L // 2s, 4s, 6s
                        Log.d(
                            TAG,
                            "🔄 Reintentando WebSocket en ${delayMs}ms (intento ${retryCount + 1})"
                        )
                        delay(delayMs)

                        if (isTrackingActive) { // Verificar que aún estamos activos
                            connectWebSocket(retryCount + 1)
                        }
                    }
                } else {
                    Log.e(TAG, "❌ WebSocket: Máximo de reintentos alcanzado")
                    postError("⚠️ Error de conexión en tiempo real. Usando modo local.")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "🔌 WebSocket cerrado: $code - $reason")
                isWebSocketConnected = false

                // ✅ AUTO-RECONECTAR SOLO SI FUE CIERRE INESPERADO
                if (code != 1000 && isTrackingActive && retryCount < 2) {
                    webSocketScope.launch {
                        delay(3000)
                        if (isTrackingActive) {
                            Log.d(TAG, "🔄 Auto-reconnecting after unexpected close...")
                            connectWebSocket(retryCount + 1)
                        }
                    }
                }
            }
        })
    }

    // ============ NUEVA FUNCIÓN: handleWebSocketMessage MEJORADA ============
    private fun handleWebSocketMessage(message: String) {
        try {
            val json = JSONObject(message)
            val type = json.getString("type")

            when (type) {
                "POSITION_UPDATE" -> {
                    val data = json.getJSONObject("data")
                    val deviceId = data.getString("deviceId")

                    // ✅ VERIFICAR QUE ES NUESTRO DISPOSITIVO
                    val ourDeviceId = getDeviceUniqueId()
                    if (deviceId == ourDeviceId) {
                        val position = VehiclePosition(
                            deviceId = deviceId,
                            latitude = data.getDouble("latitude"),
                            longitude = data.getDouble("longitude"),
                            speed = data.optDouble("speed", 0.0),
                            bearing = data.optDouble("course", 0.0),
                            timestamp = System.currentTimeMillis(),
                            accuracy = 8f, // Mejor accuracy para tiempo real
                            quality = "websocket_realtime"
                        )

                        Log.d(
                            TAG,
                            "🎯 Real-time position via WebSocket: ${position.latitude}, ${position.longitude}"
                        )

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                            processNewGPSPosition(position)
                        }
                    } else {
                        Log.d(
                            TAG,
                            "📍 Position for different device: $deviceId (ours: $ourDeviceId)"
                        )
                    }
                }

                "CONNECTION_CONFIRMED" -> {
                    Log.d(TAG, "✅ WebSocket subscription confirmed")
                    isWebSocketConnected = true
                }

                "CURRENT_POSITION" -> {
                    // ✅ POSICIÓN ACTUAL SOLICITADA
                    val data = json.getJSONObject("data")
                    val position = VehiclePosition(
                        deviceId = data.getString("deviceId"),
                        latitude = data.getDouble("latitude"),
                        longitude = data.getDouble("longitude"),
                        speed = data.optDouble("speed", 0.0),
                        bearing = data.optDouble("course", 0.0),
                        timestamp = System.currentTimeMillis(),
                        accuracy = 10f,
                        quality = "current_requested"
                    )

                    Log.d(
                        TAG,
                        "📍 Current position received: ${position.latitude}, ${position.longitude}"
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                        processNewGPSPosition(position)
                    }
                }

                "ERROR" -> {
                    val errorMsg = json.optString("message", "Error desconocido")
                    Log.e(TAG, "❌ WebSocket server error: $errorMsg")
                    postError("Error del servidor: $errorMsg")
                }

                "PING", "PONG" -> {
                    // ✅ RESPONDER A PINGS
                    if (type == "PING") {
                        val pongMessage = JSONObject().apply {
                            put("type", "PONG")
                            put("timestamp", System.currentTimeMillis())
                        }
                        webSocket?.send(pongMessage.toString())
                    }
                    Log.d(TAG, "💓 WebSocket heartbeat: $type")
                }

                else -> {
                    Log.d(TAG, "ℹ️ Unknown WebSocket message type: $type")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error processing WebSocket message: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun processNewGPSPosition(rawPosition: VehiclePosition) {
        val filteredPosition = kalmanFilter?.update(rawPosition) ?: rawPosition

        trackingHistory.add(filteredPosition)
        if (trackingHistory.size > maxHistorySize) {
            trackingHistory.removeFirst()
        }

        lastGPSPosition = filteredPosition
        lastUpdateTime = System.currentTimeMillis()

        _vehiclePosition.value = filteredPosition

        Log.d(TAG, "🎯 Position processed: Kalman filtered, accuracy: ${filteredPosition.accuracy}m")
    }

    // ============ INTERPOLACIÓN SUAVE 60FPS ============
    private fun startInterpolation() {
        interpolationJob?.cancel()
        interpolationJob = interpolationScope.launch {
            while (isTrackingActive) {
                try {
                    val currentTime = System.currentTimeMillis()
                    val timeSinceUpdate = currentTime - lastUpdateTime

                    if (lastGPSPosition != null && timeSinceUpdate < 15000) {
                        val interpolated = kalmanFilter?.interpolate(timeSinceUpdate)

                        if (interpolated != null) {
                            _vehiclePosition.value = interpolated.copy(
                                deviceId = lastGPSPosition!!.deviceId,
                                isInterpolated = true,
                                quality = "smooth_interpolated"
                            )
                        }
                    } else if (timeSinceUpdate > 30000) {
                        _vehiclePosition.value = lastGPSPosition?.copy(
                            quality = "stale",
                            isInterpolated = false
                        )
                    }

                    delay(33) // 30 FPS para mejor batería

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Interpolation error: ${e.message}")
                    delay(1000)
                }
            }
        }
    }

    // ============ FUNCIÓN PARA OBTENER POSICIÓN INICIAL MEJORADA ============
    @RequiresApi(Build.VERSION_CODES.O)
    private fun fetchInitialPosition(deviceUniqueId: String) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "🔍 Fetching initial position for real-time tracking...")

                // ✅ ENHANCED: Try multiple endpoints for initial position
                val response = try {
                    executeRequest<LastPositionResponse> {
                        Request.Builder()
                            .url("$BASE_URL/api/quick-load-position?deviceId=$deviceUniqueId&allowOld=true")
                            .get()
                            .addAuthHeader()
                            .build()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "❌ Quick load failed, trying last position endpoint: ${e.message}")
                    executeRequest<LastPositionResponse> {
                        Request.Builder()
                            .url("$BASE_URL/api/last-position?deviceId=$deviceUniqueId&allowOld=true&maxAge=240")
                            .get()
                            .addAuthHeader()
                            .build()
                    }
                }

                if (response != null) {
                    val initialPosition = VehiclePosition(
                        deviceId = deviceUniqueId,
                        latitude = response.latitude,
                        longitude = response.longitude,
                        speed = response.speed,
                        bearing = response.course,
                        timestamp = System.currentTimeMillis(),
                        accuracy = 15f,
                        quality = "initial_load"
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                        processNewGPSPosition(initialPosition)
                    }
                    Log.d(TAG, "✅ Initial position loaded for real-time tracking")
                } else {
                    Log.w(TAG, "⚠️ No initial position available")
                    _error.value = "⚠️ No hay posición inicial disponible - configura GPS primero"
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error fetching initial position: ${e.message}")
                _error.value = "Error cargando posición inicial: ${e.message}"
            }
        }
    }

    // ============ FUNCIÓN PARA OBTENER HISTORIAL DE TRACKING ============
    fun getTrackingHistory(): List<VehiclePosition> {
        return trackingHistory.toList()
    }

    // ============ FUNCIÓN PARA OBTENER ESTADÍSTICAS ============
    fun getTrackingStats(): TrackingStats {
        val history = trackingHistory
        val totalDistance = calculateTotalDistance(history)
        val averageSpeed = history.filter { !it.isInterpolated }
            .map { it.speed }.average().takeIf { !it.isNaN() } ?: 0.0

        return TrackingStats(
            totalPoints = history.size,
            realPoints = history.count { !it.isInterpolated },
            interpolatedPoints = history.count { it.isInterpolated },
            totalDistance = totalDistance,
            averageSpeed = averageSpeed,
            isConnected = isWebSocketConnected,
            lastUpdate = lastUpdateTime
        )
    }

    data class TrackingStats(
        val totalPoints: Int,
        val realPoints: Int,
        val interpolatedPoints: Int,
        val totalDistance: Double,
        val averageSpeed: Double,
        val isConnected: Boolean,
        val lastUpdate: Long
    )

    private fun calculateTotalDistance(positions: List<VehiclePosition>): Double {
        if (positions.size < 2) return 0.0

        var total = 0.0
        for (i in 1 until positions.size) {
            val prev = positions[i - 1]
            val curr = positions[i]

            if (!curr.isInterpolated) {
                total += distanceBetween(
                    prev.latitude,
                    prev.longitude,
                    curr.latitude,
                    curr.longitude
                )
            }
        }
        return total
    }

    private fun distanceBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0].toDouble()
    }

    // ✅ ENHANCED: Safe zone fetching with proper error handling
    fun fetchSafeZoneFromServer() {
        val safeContext = getSafeContext()
        if (safeContext == null) {
            Log.d(TAG, "No safe context available, skipping fetchSafeZoneFromServer")
            return
        }

        val deviceUniqueId = safeContext.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            .getString(DEVICE_UNIQUE_ID_PREF, null)

        if (deviceUniqueId == null) {
            Log.d(TAG, "No device uniqueId, skipping fetchSafeZoneFromServer")
            return
        }

        // Rest of the method remains the same...
        Log.d(TAG, "🔍 Fetching safe zone from server for uniqueId: $deviceUniqueId")

        safeZoneCache[deviceUniqueId]?.let { cached ->
            if (cached.isValid()) {
                _safeZone.value = cached.data
                Log.d(TAG, "✅ Safe zone from local cache: ${cached.data}")
                return
            }
        }

        val lat = safeContext.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            .getString(SecondFragment.PREF_SAFEZONE_LAT, null)?.toDoubleOrNull()
        val lon = safeContext.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            .getString(SecondFragment.PREF_SAFEZONE_LON, null)?.toDoubleOrNull()

        if (lat != null && lon != null) {
            val latLng = LatLng(lat, lon)
            safeZoneCache[deviceUniqueId] = CacheEntry(latLng, System.currentTimeMillis(), SAFE_ZONE_TTL)
            _safeZone.value = latLng
            Log.d(TAG, "✅ Safe zone restored from preferences: lat=$lat, lon=$lon")
            syncWithServerInBackground(deviceUniqueId)
            return
        }

        syncWithServerInBackground(deviceUniqueId)
    }

    private fun syncWithServerInBackground(deviceUniqueId: String) {
        networkScope.launch {
            try {
                if (SecondFragment.JWT_TOKEN.isNullOrEmpty()) {
                    Log.d(TAG, "No JWT token for server sync")
                    return@launch
                }

                Log.d(TAG, "🌐 Syncing safe zone from server for uniqueId: $deviceUniqueId")

                val safeZone = executeRequest<SafeZoneResponse> {
                    Request.Builder()
                        .url("$BASE_URL/api/safezone?deviceId=$deviceUniqueId")
                        .get()
                        .addAuthHeader()
                        .build()
                }

                if (safeZone != null) {
                    val latLng = LatLng(safeZone.latitude, safeZone.longitude)
                    safeZoneCache[deviceUniqueId] =
                        CacheEntry(latLng, System.currentTimeMillis(), SAFE_ZONE_TTL)
                    _safeZone.value = latLng

                    context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE).edit().apply {
                        putString(SecondFragment.PREF_SAFEZONE_LAT, safeZone.latitude.toString())
                        putString(SecondFragment.PREF_SAFEZONE_LON, safeZone.longitude.toString())
                        apply()
                    }

                    Log.d(
                        TAG,
                        "✅ Safe zone synced from server: lat=${safeZone.latitude}, lon=${safeZone.longitude}"
                    )
                } else {
                    Log.d(TAG, "ℹ️ No safe zone found on server")
                }
            } catch (e: Exception) {
                Log.d(TAG, "ℹ️ Server sync failed - using local data only: ${e.message}")
            }
        }
    }

    // ✅ ENHANCED: Vehicle position update with validation
    fun updateVehiclePosition(deviceUniqueId: String, position: GeoPoint, timestamp: Long? = null) {
        val vehiclePos = VehiclePosition(
            deviceId = deviceUniqueId,
            latitude = position.latitude,
            longitude = position.longitude,
            timestamp = timestamp ?: System.currentTimeMillis(),
            quality = "manual_update"
        )

        if (isTrackingActive) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                processNewGPSPosition(vehiclePos)
            }
        } else {
            _vehiclePosition.value = vehiclePos
        }
    }

    // ✅ FUNCIONES AUXILIARES (mantener las existentes)
    private fun getDeviceUniqueId(): String? {
        return try {
            val safeContext = getSafeContext() ?: return null
            safeContext.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                .getString(DEVICE_UNIQUE_ID_PREF, null)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get device unique ID: ${e.message}")
            null
        }
    }

    // ✅ ENHANCED: Device status check with better error handling
    fun checkDeviceStatus(deviceUniqueId: String, callback: (Boolean, String) -> Unit) {
        Log.d(TAG, "🔍 Checking device status for uniqueId: $deviceUniqueId")

        deviceStatusCache[deviceUniqueId]?.let { cached ->
            if (cached.isValid()) {
                val status = cached.data
                val message = if (status.isOnline) {
                    "✅ ${status.deviceName} está en línea - ${status.recentPositions} posiciones recientes"
                } else {
                    "⚠️ ${status.deviceName} está fuera de línea - sin posiciones recientes"
                }
                Log.d(TAG, "📊 Status from cache: $message")
                callback(status.isOnline, message)
                return
            }
        }

        networkScope.launch {
            try {
                if (SecondFragment.JWT_TOKEN.isNullOrEmpty()) {
                    throw Exception("Token de autenticación inválido")
                }

                Log.d(TAG, "🌐 Fetching device status from server for uniqueId: $deviceUniqueId")

                val statusResponse = executeRequest<DeviceStatusResponse> {
                    Request.Builder()
                        .url("$BASE_URL/api/device/status/$deviceUniqueId")
                        .get()
                        .addAuthHeader()
                        .build()
                } ?: throw Exception("No se recibió respuesta del servidor")

                val status = DeviceStatus(
                    isOnline = statusResponse.isReceivingData,
                    deviceName = statusResponse.device.name,
                    recentPositions = statusResponse.recentPositions
                )

                deviceStatusCache[deviceUniqueId] =
                    CacheEntry(status, System.currentTimeMillis(), DEVICE_STATUS_TTL)

                val message = if (status.isOnline) {
                    "${status.deviceName} está en línea - ${status.recentPositions} posiciones recientes"
                } else {
                    "${status.deviceName} está fuera de línea - sin posiciones recientes"
                }

                Log.d(
                    TAG,
                    "✅ Device status fetched: isOnline=${status.isOnline}, recentPositions=${status.recentPositions}"
                )

                withContext(Dispatchers.Main) {
                    callback(status.isOnline, message)
                }
            } catch (e: Exception) {
                val errorMsg = when {
                    e.message?.contains("401") == true -> "Token de autenticación inválido. Inicia sesión nuevamente."
                    e.message?.contains("404") == true -> "Dispositivo no encontrado."
                    e.message?.contains("502") == true -> "Servidor temporalmente no disponible. Intenta más tarde."
                    else -> "Error al verificar estado del dispositivo: ${e.localizedMessage}"
                }
                Log.e(TAG, "❌ Error checking device status: $errorMsg", e)
                withContext(Dispatchers.Main) {
                    callback(false, errorMsg)
                }
                handleError(errorMsg, e)
            }
        }
    }

    // ✅ ENHANCED: GPS configuration with fallback endpoints
    @RequiresApi(Build.VERSION_CODES.O)
    fun getGPSClientConfig(callback: (String, Map<String, String>, Map<String, String>) -> Unit) {
        networkScope.launch {
            try {
                if (SecondFragment.JWT_TOKEN.isNullOrEmpty()) {
                    throw Exception("Token de autenticación inválido")
                }

                Log.d(TAG, "🌐 Getting GPS config from Cloudflare endpoint...")

                val config = executeRequest<GPSConfigResponse> {
                    Request.Builder()
                        .url("$BASE_URL/api/gps/config")
                        .get()
                        .addAuthHeader()
                        .build()
                }

                if (config != null) {
                    withContext(Dispatchers.Main) {
                        callback(
                            config.recommendedEndpoint,
                            config.gpsEndpoints,
                            config.instructions
                        )
                    }
                    Log.d(
                        TAG,
                        "✅ GPS config fetched from Cloudflare: recommended=${config.recommendedEndpoint}"
                    )
                } else {
                    // ✅ ENHANCED: Better fallback configuration
                    val defaultEndpoint = "$BASE_URL/gps/osmand"
                    val defaultEndpoints = mapOf(
                        "primary" to "$BASE_URL/gps/osmand",
                        "secondary" to "$BASE_URL/gps/http",
                        "traccar_direct" to "$TRACCAR_URL/",
                        "generic" to "$BASE_URL/gps"
                    )
                    val defaultInstructions = mapOf(
                        "protocol" to "HTTP GET/POST via Cloudflare DNS",
                        "parameters" to "id, lat, lon, timestamp, speed (opcional)",
                        "example" to "$defaultEndpoint?id=DEVICE_ID&lat=-37.32167&lon=-59.13316&timestamp=${java.time.Instant.now().epochSecond}&speed=0",
                        "direct_traccar" to "$TRACCAR_URL?id=DEVICE_ID&lat=-37.32167&lon=-59.13316&speed=0"
                    )
                    withContext(Dispatchers.Main) {
                        callback(defaultEndpoint, defaultEndpoints, defaultInstructions)
                    }
                    Log.d(TAG, "✅ Using enhanced default GPS configuration")
                }
            } catch (e: Exception) {
                val errorMsg = when {
                    e.message?.contains("401") == true -> "Token de autenticación inválido."
                    e.message?.contains("timeout") == true -> "Timeout conectando con servidor."
                    e.message?.contains("502") == true -> "Servidor temporalmente no disponible."
                    else -> "Error al obtener configuración GPS: ${e.localizedMessage}"
                }

                // ✅ ENHANCED: Better fallback with error context
                val fallbackEndpoint = "$BASE_URL/gps/osmand"
                val fallbackEndpoints = mapOf(
                    "cloudflare_primary" to "$BASE_URL/gps/osmand",
                    "cloudflare_http" to "$BASE_URL/gps/http",
                    "traccar_direct" to "$TRACCAR_URL/",
                    "generic_fallback" to "$BASE_URL/gps"
                )
                val fallbackInstructions = mapOf(
                    "protocol" to "HTTP GET/POST (Fallback Mode)",
                    "error" to "Conexión fallback activada: $errorMsg",
                    "primary_example" to "$fallbackEndpoint?id=DEVICE_ID&lat=-37.32167&lon=-59.13316",
                    "traccar_example" to "$TRACCAR_URL?id=DEVICE_ID&lat=-37.32167&lon=-59.13316",
                    "note" to "Usa endpoints directos si persisten errores"
                )

                withContext(Dispatchers.Main) {
                    callback(fallbackEndpoint, fallbackEndpoints, fallbackInstructions)
                }

                Log.e(TAG, "❌ GPS config error: $errorMsg")
                handleError(errorMsg, e)
            }
        }
    }

    // ✅ ENHANCED: Safe zone management with better error handling
    fun sendSafeZoneToServer(latitude: Double, longitude: Double, deviceUniqueId: String) {
        val latLng = LatLng(latitude, longitude)
        safeZoneCache[deviceUniqueId] =
            CacheEntry(latLng, System.currentTimeMillis(), SAFE_ZONE_TTL)
        _safeZone.value = latLng

        context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE).edit().apply {
            putString(SecondFragment.PREF_SAFEZONE_LAT, latitude.toString())
            putString(SecondFragment.PREF_SAFEZONE_LON, longitude.toString())
            apply()
        }

        Log.d(TAG, "✅ Zona segura establecida localmente: lat=$latitude, lon=$longitude")

        networkScope.launch {
            try {
                if (SecondFragment.JWT_TOKEN.isNullOrEmpty()) {
                    Log.w(TAG, "No JWT token for server sync")
                    return@launch
                }

                Log.d(TAG, "🌐 Sending safe zone to server for uniqueId: $deviceUniqueId")

                val json = JSONObject().apply {
                    put("latitude", latitude)
                    put("longitude", longitude)
                    put("deviceId", deviceUniqueId)
                }

                val requestBody = json.toString().toRequestBody("application/json".toMediaType())

                executeRequest<Unit> {
                    Request.Builder()
                        .url("$BASE_URL/api/safezone")
                        .post(requestBody)
                        .addAuthHeader()
                        .build()
                }

                Log.d(TAG, "✅ Zona segura sincronizada con servidor exitosamente")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Server sync failed (zone remains local): ${e.message}")

                if (e.message?.contains("403") == true) {
                    postError("Error de autorización al guardar zona segura. Verifica tu dispositivo.")
                }
            }
        }
    }

    // ✅ ENHANCED: Safe zone deletion with proper cleanup
    fun deleteSafeZoneFromServer(callback: (Boolean) -> Unit = {}) {
        val deviceUniqueId = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            .getString(SecondFragment.DEVICE_UNIQUE_ID_PREF, null)

        if (deviceUniqueId == null) {
            Log.d(TAG, "No device uniqueId, skipping deleteSafeZoneFromServer")
            postError("No se encontró dispositivo asociado")
            callback(false)
            return
        }

        Log.d(TAG, "🗑️ Deleting safe zone for uniqueId: $deviceUniqueId")

        safeZoneCache.remove(deviceUniqueId)
        _safeZone.value = null

        context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE).edit().apply {
            remove(SecondFragment.PREF_SAFEZONE_LAT)
            remove(SecondFragment.PREF_SAFEZONE_LON)
            apply()
        }

        Log.d(TAG, "✅ Safe zone deleted locally")

        networkScope.launch {
            try {
                if (SecondFragment.JWT_TOKEN.isNullOrEmpty()) {
                    Log.w(TAG, "No JWT token for server sync")
                    withContext(Dispatchers.Main) { callback(true) }
                    return@launch
                }

                val json = JSONObject().apply {
                    put("deviceId", deviceUniqueId)
                }

                val requestBody = json.toString().toRequestBody("application/json".toMediaType())

                executeRequest<Unit> {
                    Request.Builder()
                        .url("$BASE_URL/api/safezone")
                        .delete(requestBody)
                        .addAuthHeader()
                        .build()
                }

                Log.d(TAG, "✅ Safe zone deleted from server successfully")
                withContext(Dispatchers.Main) { callback(true) }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error deleting safe zone from server: ${e.message}")

                val isSuccessCase = e.message?.contains("404") == true ||
                        e.message?.contains("No se encontró zona segura") == true

                if (isSuccessCase) {
                    Log.d(TAG, "✅ Safe zone already deleted on server (404) - treating as success")
                    withContext(Dispatchers.Main) { callback(true) }
                } else {
                    Log.e(TAG, "❌ Real error deleting safe zone: ${e.message}")
                    withContext(Dispatchers.Main) { callback(true) }

                    if (e.message?.contains("403") == true) {
                        postError("Error de autorización al eliminar zona segura")
                    }
                }
            }
        }
    }

    // ✅ ENHANCED: Device association with better validation
    fun associateDevice(deviceUniqueId: String, name: String, callback: (Int, String) -> Unit) {
        if (deviceUniqueId.isBlank() || name.isBlank()) {
            postError("El ID del dispositivo y el nombre no pueden estar vacíos")
            return
        }

        Log.d(TAG, "Associating device: uniqueId=$deviceUniqueId, name=$name")

        networkScope.launch {
            try {
                if (SecondFragment.JWT_TOKEN.isNullOrEmpty()) {
                    throw Exception("Token de autenticación inválido")
                }

                val json = JSONObject().apply {
                    put("deviceId", deviceUniqueId.trim())
                    put("name", name.trim())
                }

                val requestBody = json.toString().toRequestBody("application/json".toMediaType())

                val response = executeRequest<AssociateDeviceResponse> {
                    Request.Builder()
                        .url("$BASE_URL/api/user/devices")
                        .post(requestBody)
                        .addAuthHeader()
                        .build()
                }

                if (response != null) {
                    Log.d(TAG, "Device association response received: ${response.status}")

                    when (response.status) {
                        "newly_associated" -> {
                            Log.d(
                                TAG,
                                "Device newly associated: id=${response.id}, name=${response.name}"
                            )

                            context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE).edit {
                                putString(DEVICE_UNIQUE_ID_PREF, response.uniqueId)
                                putString(DEVICE_NAME_PREF, response.name)
                                putInt(DEVICE_ID_PREF, response.uniqueId.hashCode())
                            }

                            clearAllCaches()

                            withContext(Dispatchers.Main) {
                                callback(response.id, response.name)
                                postError("✅ Dispositivo ${response.name} asociado exitosamente")
                            }
                        }

                        "already_associated" -> {
                            Log.d(
                                TAG,
                                "Device already associated: id=${response.id}, name=${response.name}"
                            )

                            context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE).edit {
                                putString(DEVICE_UNIQUE_ID_PREF, response.uniqueId)
                                putString(DEVICE_NAME_PREF, response.name)
                                putInt(DEVICE_ID_PREF, response.uniqueId.hashCode())
                            }

                            clearAllCaches()

                            withContext(Dispatchers.Main) {
                                callback(response.id, response.name)
                                postError("ℹ️ Dispositivo ${response.name} ya estaba asociado a tu cuenta")
                            }
                        }

                        else -> {
                            Log.w(TAG, "Unknown association status: ${response.status}")
                            withContext(Dispatchers.Main) {
                                callback(response.id, response.name)
                                postError("Dispositivo procesado: ${response.name}")
                            }
                        }
                    }

                    Log.d(TAG, "Device association completed successfully")

                } else {
                    throw Exception("No se recibió respuesta del servidor")
                }

            } catch (e: Exception) {
                val errorMsg = when {
                    e.message?.contains("404") == true -> "Dispositivo no encontrado. Verifica el ID único."
                    e.message?.contains("409") == true -> "El dispositivo ya está asociado a otra cuenta."
                    e.message?.contains("401") == true -> "Token de autenticación inválido. Inicia sesión nuevamente."
                    e.message?.contains("400") == true -> "Datos de dispositivo inválidos. Verifica el ID único y nombre."
                    e.message?.contains("502") == true -> "Servidor temporalmente no disponible. Intenta más tarde."
                    else -> "Error al asociar dispositivo: ${e.localizedMessage}"
                }
                Log.e(TAG, "Error associating device: $errorMsg", e)
                handleError(errorMsg, e)
            }
        }
    }

    // ✅ ENHANCED: Show available devices with better formatting
    fun showAvailableDevices(callback: (String) -> Unit) {
        networkScope.launch {
            try {
                if (SecondFragment.JWT_TOKEN.isNullOrEmpty()) {
                    throw Exception("Token de autenticación inválido")
                }
                val devicesResponse = executeRequest<AvailableDevicesResponse> {
                    Request.Builder()
                        .url("$BASE_URL/api/traccar/devices")
                        .get()
                        .addAuthHeader()
                        .build()
                }
                val deviceList = devicesResponse?.let { response ->
                    if (response.devices.isEmpty()) {
                        "No se encontraron dispositivos en Traccar. Agrega un dispositivo primero."
                    } else {
                        buildString {
                            appendLine("Dispositivos disponibles:\n")
                            response.devices.forEach { device ->
                                appendLine("• ${device.name}")
                                appendLine("  UniqueID: ${device.uniqueId}")
                                appendLine("  Estado: ${device.status}\n")
                            }
                        }
                    }
                } ?: "Error: Sin respuesta del servidor"
                withContext(Dispatchers.Main) {
                    callback(deviceList)
                }
                Log.d(
                    TAG,
                    "Obtenidos ${devicesResponse?.devices?.size ?: 0} dispositivos disponibles"
                )
            } catch (e: Exception) {
                val errorMsg = when {
                    e.message?.contains("401") == true -> "Token de autenticación inválido. Inicia sesión nuevamente."
                    e.message?.contains("404") == true -> "Servicio Traccar no encontrado. Verifica la configuración del servidor."
                    e.message?.contains("502") == true -> "Servidor Traccar temporalmente no disponible."
                    else -> "Error al obtener dispositivos: ${e.localizedMessage}"
                }
                postError(errorMsg)
                withContext(Dispatchers.Main) {
                    callback(errorMsg)
                }
                Log.e(TAG, errorMsg, e)
            }
        }
    }

    // ✅ ENHANCED: Last position with multiple fallback strategies
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun getLastPosition(
        deviceUniqueId: String,
        allowOldPositions: Boolean = false,
        maxAgeMinutes: Int = 30
    ): LastPositionResponse {
        Log.d(
            TAG,
            "📍 Getting last position for uniqueId: $deviceUniqueId, allowOld: $allowOldPositions, maxAge: $maxAgeMinutes"
        )

        // ✅ VERIFICAR CACHE PRIMERO (opcional para carga rápida)
        if (!allowOldPositions) {
            positionCache[deviceUniqueId]?.let { cached ->
                if (cached.isValid()) {
                    Log.d(TAG, "✅ Position from cache for uniqueId: $deviceUniqueId")
                    val ageMinutes = (System.currentTimeMillis() - cached.timestamp) / 60000L
                    return LastPositionResponse(
                        deviceId = deviceUniqueId,
                        traccarDeviceId = deviceUniqueId.hashCode(),
                        latitude = cached.data.latitude,
                        longitude = cached.data.longitude,
                        speed = 0.0,
                        course = 0.0,
                        timestamp = java.time.Instant.now().toString(),
                        age = ageMinutes.toInt(),
                        quality = if (ageMinutes < 5) "excellent" else "good"
                    )
                }
            }
        }

        return withContext(ioDispatcher) {
            withTimeout(20_000L) { // Aumentar timeout
                if (SecondFragment.JWT_TOKEN.isNullOrEmpty()) {
                    throw Exception("Token de autenticación inválido")
                }

                Log.d(TAG, "🌐 Fetching position from server for uniqueId: $deviceUniqueId")

                // ✅ ESTRATEGIA MÚLTIPLE MEJORADA
                val strategies = listOf(
                    { // Estrategia 1: Posición reciente estricta
                        executeRequest<LastPositionResponse> {
                            Request.Builder()
                                .url("$BASE_URL/api/last-position?deviceId=$deviceUniqueId&fresh=true&maxAge=30")
                                .get()
                                .addAuthHeader()
                                .build()
                        }
                    },
                    { // Estrategia 2: Posición reciente relajada
                        executeRequest<LastPositionResponse> {
                            Request.Builder()
                                .url("$BASE_URL/api/last-position?deviceId=$deviceUniqueId&allowOld=true&maxAge=120")
                                .get()
                                .addAuthHeader()
                                .build()
                        }
                    },
                    { // Estrategia 3: Quick load (cualquier posición)
                        executeRequest<LastPositionResponse> {
                            Request.Builder()
                                .url("$BASE_URL/api/quick-load-position?deviceId=$deviceUniqueId")
                                .get()
                                .addAuthHeader()
                                .build()
                        }
                    },
                    { // Estrategia 4: Posición antigua si se permite
                        if (allowOldPositions) {
                            executeRequest<LastPositionResponse> {
                                Request.Builder()
                                    .url("$BASE_URL/api/last-position?deviceId=$deviceUniqueId&allowOld=true&maxAge=$maxAgeMinutes")
                                    .get()
                                    .addAuthHeader()
                                    .build()
                            }
                        } else null
                    }
                )

                var lastError: Exception? = null

                for ((index, strategy) in strategies.withIndex()) {
                    try {
                        val response = strategy?.invoke()
                        if (response != null) {
                            // ✅ CACHE LA RESPUESTA EXITOSA
                            val latLng = LatLng(response.latitude, response.longitude)
                            positionCache[deviceUniqueId] =
                                CacheEntry(latLng, System.currentTimeMillis(), POSITION_TTL)

                            Log.d(
                                TAG,
                                "✅ Position fetched successfully using strategy ${index + 1}"
                            )

                            // ✅ FIX PARA SMART CAST - crear variable local
                            val responseAge = response.age
                            val safeAge = responseAge ?: 0

                            // ✅ ASEGURAR CAMPOS REQUERIDOS
                            return@withTimeout response.copy(
                                deviceId = deviceUniqueId,
                                timestamp = response.timestamp.takeIf { !it.isNullOrEmpty() }
                                    ?: java.time.Instant.now().toString(),
                                age = safeAge, // ✅ Usar variable local
                                quality = response.quality ?: "unknown"
                            )
                        }
                    } catch (e: Exception) {
                        lastError = e
                        Log.w(TAG, "❌ Strategy ${index + 1} failed: ${e.message}")
                        continue
                    }
                }

                // ✅ SI TODAS LAS ESTRATEGIAS FALLAN
                throw lastError ?: Exception("No se pudo obtener posición desde ningún endpoint")
            }
        }
    }

    suspend fun verifyAndDisplayPosition(deviceUniqueId: String): LastPositionResponse? {
        return try {
            Log.d(TAG, "🔍 Verifying position for device: $deviceUniqueId")

            // Intentar obtener posición con múltiples estrategias
            val position = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getLastPosition(deviceUniqueId, allowOldPositions = true, maxAgeMinutes = 1440)
            } else {
                TODO("VERSION.SDK_INT < O")
            }

            Log.d(
                TAG,
                "✅ Position verified: lat=${position.latitude}, lon=${position.longitude}, age=${position.age}min"
            )

            // Actualizar inmediatamente la UI
            val vehiclePos = VehiclePosition(
                deviceId = deviceUniqueId,
                latitude = position.latitude,
                longitude = position.longitude,
                speed = position.speed,
                bearing = position.course,
                timestamp = System.currentTimeMillis(),
                accuracy = 15f,
                quality = "verified"
            )

            _vehiclePosition.value = vehiclePos

            position
        } catch (e: Exception) {
            Log.e(TAG, "❌ Position verification failed: ${e.message}")
            _error.value = "⚠️ No se pudo verificar posición: ${e.message}"
            null
        }
    }

// 3. MEJORAR forceDataRefresh():

    @RequiresApi(Build.VERSION_CODES.O)
    fun forceDataRefresh(showVehicle: Boolean = true) {
        Log.d(TAG, "🔄 Force data refresh with vehicle display: $showVehicle")

        clearAllCaches()
        fetchAssociatedDevices()

        getDeviceUniqueId()?.let { uniqueId ->
            networkScope.launch {
                try {
                    delay(1000) // Permitir que se complete la búsqueda de dispositivos

                    if (showVehicle) {
                        Log.d(TAG, "📍 Attempting to display vehicle on map...")
                        val position = verifyAndDisplayPosition(uniqueId)

                        if (position != null) {
                            Log.d(TAG, "✅ Vehicle position loaded and displayed successfully")
                            postError("✅ Vehículo cargado en el mapa")
                        } else {
                            Log.w(TAG, "⚠️ Could not load vehicle position")
                            postError("⚠️ No se pudo cargar posición del vehículo")
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Force refresh failed: ${e.message}")
                    _error.value = "⚠️ Error en actualización: ${e.message}"
                }
            }
        } ?: run {
            Log.w(TAG, "⚠️ No device unique ID for refresh")
            _error.value = "⚠️ No hay dispositivo asociado"
        }
    }

    // ✅ ENHANCED: Clear old positions with better feedback
    @RequiresApi(Build.VERSION_CODES.O)
    fun clearOldPositionsAndForceRefresh(deviceUniqueId: String) {
        Log.d(TAG, "🧹 Clearing old positions and forcing refresh for uniqueId: $deviceUniqueId")

        positionCache.remove(deviceUniqueId)
        deviceStatusCache.remove(deviceUniqueId)

        _vehiclePosition.value = null
        _error.value = "Limpiando posiciones antiguas..."

        viewModelScope.launch {
            try {
                delay(1000)
                val freshPosition = getLastPosition(deviceUniqueId)
                val geoPoint = GeoPoint(freshPosition.latitude, freshPosition.longitude)
                updateVehiclePosition(deviceUniqueId, geoPoint)

                val ageInfo = if (freshPosition.age != null && freshPosition.age > 0) {
                    " (${freshPosition.age} min)"
                } else ""

                _error.value = "✅ Posición actualizada correctamente$ageInfo"
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error getting fresh position: ${e.message}")
                _error.value = "⚠️ ${e.message}"
            }
        }
    }

    // ✅ ENHANCED: Force data refresh with better sequencing
    @RequiresApi(Build.VERSION_CODES.O)
    fun forceDataRefresh() {
        Log.d(TAG, "🔄 Forcing data refresh...")

        clearAllCaches()
        fetchAssociatedDevices()

        getDeviceUniqueId()?.let { uniqueId ->
            networkScope.launch {
                try {
                    delay(500) // Allow device fetch to complete
                    val position = getLastPosition(uniqueId)
                    val geoPoint = GeoPoint(position.latitude, position.longitude)
                    updateVehiclePosition(uniqueId, geoPoint)
                    Log.d(TAG, "✅ Force refresh completed with position update")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Force refresh failed: ${e.message}")
                    _error.value = "⚠️ Refresh failed: ${e.message}"
                }
            }
        }
    }

    // ✅ ENHANCED: Fetch associated devices with better error handling
    fun fetchAssociatedDevices() {
        Log.d(TAG, "🔄 Fetching associated devices...")

        networkScope.launch {
            try {
                if (SecondFragment.JWT_TOKEN.isNullOrEmpty()) {
                    throw Exception("Token de autenticación inválido")
                }

                deviceListCache.set(null)

                val devices = getUserDevices()
                Log.d(TAG, "📱 Fetched ${devices.size} devices from server")

                if (devices.isNotEmpty()) {
                    val firstDevice = devices.first()
                    val info = "Vehículo: ${firstDevice.name} (UniqueID: ${firstDevice.uniqueId})"
                    _deviceInfo.value = info

                    context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE).edit {
                        putInt(DEVICE_ID_PREF, firstDevice.uniqueId.hashCode())
                        putString(DEVICE_NAME_PREF, firstDevice.name)
                        putString(DEVICE_UNIQUE_ID_PREF, firstDevice.uniqueId)
                    }

                    Log.d(
                        TAG,
                        "✅ Device info saved: uniqueId=${firstDevice.uniqueId}, name=${firstDevice.name}"
                    )

                    checkDeviceStatus(firstDevice.uniqueId) { _, message ->
                        Log.d(TAG, "📊 Initial device status: $message")
                    }

                } else {
                    _deviceInfo.value = "No hay vehículos asociados"
                    Log.d(TAG, "⚠️ No associated devices found")

                    context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE).edit {
                        remove(DEVICE_ID_PREF)
                        remove(DEVICE_NAME_PREF)
                        remove(DEVICE_UNIQUE_ID_PREF)
                    }
                }
            } catch (e: Exception) {
                val errorMsg = when {
                    e.message?.contains("401") == true -> "Token de autenticación inválido. Inicia sesión nuevamente."
                    e.message?.contains("404") == true -> "No hay dispositivos asociados a tu cuenta."
                    e.message?.contains("502") == true -> "Servidor temporalmente no disponible."
                    else -> "Error al obtener dispositivos asociados: ${e.localizedMessage}"
                }
                Log.e(TAG, "❌ Error fetching associated devices: $errorMsg", e)
                handleError(errorMsg, e)
            }
        }
    }

    // ============ FUNCIONES AUXILIARES OPTIMIZADAS ============

    private suspend fun getUserDevices(): List<DeviceInfo> {
        deviceListCache.get()?.let { cached ->
            if (cached.isValid()) {
                Log.d(TAG, "Using cached devices: ${cached.data.size}")
                return cached.data
            }
        }

        Log.d(TAG, "Fetching devices from server...")

        try {
            val response = executeRequest<List<DeviceInfoResponse>> {
                Request.Builder()
                    .url("$BASE_URL/api/user/devices?forceRefresh=true")
                    .get()
                    .addAuthHeader()
                    .build()
            }

            Log.d(TAG, "Raw API response received: ${response?.size ?: 0} devices")

            val devices = response?.mapNotNull { deviceResponse ->
                if (deviceResponse.id.isNotEmpty()) {
                    DeviceInfo(
                        id = deviceResponse.uniqueId ?: deviceResponse.id,
                        name = deviceResponse.name,
                        uniqueId = deviceResponse.uniqueId ?: "N/A",
                        status = deviceResponse.status ?: "unknown"
                    )
                } else {
                    Log.w(TAG, "Skipping invalid device with id=${deviceResponse.id}")
                    null
                }
            } ?: emptyList()

            Log.d(TAG, "Processed ${devices.size} valid devices")
            deviceListCache.set(CacheEntry(devices, System.currentTimeMillis(), DEVICE_LIST_TTL))
            return devices

        } catch (e: Exception) {
            Log.e(TAG, "Error fetching user devices: ${e.message}", e)
            if (e.message?.contains("404") == true) {
                return emptyList()
            }
            throw e
        }
    }

    private fun clearAllCaches() {
        Log.d(TAG, "Clearing all caches...")

        positionCache.clear()
        deviceStatusCache.clear()
        safeZoneCache.clear()
        deviceListCache.set(null)

        Log.d(TAG, "All caches cleared")
    }

    // ============ FUNCIONES AUXILIARES PARA REQUEST ============
    private inline fun <reified T> executeRequest(requestBuilder: () -> Request): T? {
        return try {
            val request = requestBuilder()
            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                response.body.string().let { responseBody ->
                    when (T::class) {
                        Unit::class -> Unit as T
                        String::class -> responseBody as T
                        else -> parseJsonResponse<T>(responseBody)
                    }
                }
            } else {
                val errorBody = response.body?.string() ?: "Sin cuerpo de error"
                val errorMessage = when (response.code) {
                    401 -> "Token de autenticación inválido (401)"
                    403 -> "Acceso denegado (403)"
                    404 -> "Recurso no encontrado (404)"
                    502 -> "Servidor temporalmente no disponible (502)"
                    503 -> "Servicio no disponible (503)"
                    else -> "HTTP ${response.code}: $errorBody"
                }
                Log.e(TAG, "❌ Petición fallida: $errorMessage")
                null // Devolver null para que los llamadores manejen el error
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Fallo en ejecución de petición: ${e.message}")
            null
        }
    }

    // ============ ENHANCED JSON PARSING ============
    private inline fun <reified T> parseJsonResponse(json: String): T? {
        return try {
            Log.d(TAG, "Parsing JSON for ${T::class.simpleName}: ${json.take(200)}...")

            when (T::class) {
                DeviceStatusResponse::class -> {
                    val jsonObject = JSONObject(json)
                    val deviceObj = jsonObject.getJSONObject("device")

                    val deviceName = deviceObj.optString("name", "Unknown Device")
                    if (deviceName.isEmpty()) {
                        Log.w(TAG, "Device name is empty, using uniqueId as fallback")
                    }

                    DeviceStatusResponse(
                        isReceivingData = jsonObject.getBoolean("isReceivingData"),
                        device = DeviceStatusResponse.Device(
                            name = if (deviceName.isNotEmpty()) deviceName else deviceObj.optString(
                                "uniqueId",
                                "Device"
                            )
                        ),
                        recentPositions = jsonObject.getInt("recentPositions")
                    ) as T
                }

                LastPositionResponse::class -> {
                    val jsonObject = JSONObject(json)

                    if (!jsonObject.has("latitude") || !jsonObject.has("longitude")) {
                        Log.e(TAG, "Missing required position fields in response")
                        throw JSONException("Missing latitude or longitude in position response")
                    }

                    LastPositionResponse(
                        deviceId = jsonObject.getString("deviceId"),
                        traccarDeviceId = jsonObject.getInt("traccarDeviceId"),
                        latitude = jsonObject.getDouble("latitude"),
                        longitude = jsonObject.getDouble("longitude"),
                        speed = jsonObject.optDouble("speed", 0.0),
                        course = jsonObject.optDouble("course", 0.0),
                        timestamp = jsonObject.optString("timestamp", ""),
                        age = jsonObject.optInt("age", 0),
                        quality = jsonObject.optString("quality", "unknown")
                    ) as T
                }

                List::class -> {
                    val devices = mutableListOf<DeviceInfoResponse>()
                    try {
                        val jsonArray = org.json.JSONArray(json)
                        for (i in 0 until jsonArray.length()) {
                            val deviceObj = jsonArray.getJSONObject(i)
                            devices.add(
                                DeviceInfoResponse(
                                    id = deviceObj.getString("uniqueId"),
                                    name = deviceObj.getString("name"),
                                    uniqueId = deviceObj.optString("uniqueId", "N/A"),
                                    status = deviceObj.optString("status", "unknown")
                                )
                            )
                        }
                    } catch (e: org.json.JSONException) {
                        try {
                            val jsonObject = JSONObject(json)
                            if (jsonObject.has("devices")) {
                                val jsonArray = jsonObject.getJSONArray("devices")
                                for (i in 0 until jsonArray.length()) {
                                    val deviceObj = jsonArray.getJSONObject(i)
                                    devices.add(
                                        DeviceInfoResponse(
                                            id = deviceObj.getString("uniqueId"),
                                            name = deviceObj.getString("name"),
                                            uniqueId = deviceObj.optString("uniqueId", "N/A"),
                                            status = deviceObj.optString("status", "unknown")
                                        )
                                    )
                                }
                            }
                        } catch (e2: org.json.JSONException) {
                            Log.e(TAG, "Failed to parse both direct array and object format", e2)
                            throw e
                        }
                    }
                    devices as T
                }

                SafeZoneResponse::class -> {
                    val jsonObject = JSONObject(json)
                    SafeZoneResponse(
                        latitude = jsonObject.getDouble("latitude"),
                        longitude = jsonObject.getDouble("longitude")
                    ) as T
                }

                AssociateDeviceResponse::class -> {
                    val jsonObject = JSONObject(json)
                    val deviceObj = jsonObject.getJSONObject("device")

                    AssociateDeviceResponse(
                        success = jsonObject.optBoolean("success", true),
                        message = jsonObject.optString("message", ""),
                        device = AssociateDeviceResponse.DeviceDetails(
                            id = deviceObj.getInt("id"),
                            name = deviceObj.getString("name"),
                            uniqueId = deviceObj.getString("uniqueId"),
                            traccarDeviceId = deviceObj.getInt("traccarDeviceId"),
                            status = deviceObj.getString("status")
                        )
                    ) as T
                }

                AvailableDevicesResponse::class -> {
                    val jsonObject = JSONObject(json)
                    val jsonArray = jsonObject.getJSONArray("devices")
                    val devices = mutableListOf<DeviceInfoResponse>()
                    for (i in 0 until jsonArray.length()) {
                        val deviceObj = jsonArray.getJSONObject(i)
                        devices.add(
                            DeviceInfoResponse(
                                id = deviceObj.getInt("id").toString(),
                                name = deviceObj.getString("name"),
                                uniqueId = deviceObj.optString("uniqueId", "N/A"),
                                status = deviceObj.optString("status", "unknown")
                            )
                        )
                    }
                    AvailableDevicesResponse(devices) as T
                }

                GPSConfigResponse::class -> {
                    val jsonObject = JSONObject(json)
                    val endpointsObj = jsonObject.getJSONObject("gpsEndpoints")
                    val instructionsObj = jsonObject.getJSONObject("instructions")

                    val endpoints = mutableMapOf<String, String>()
                    endpointsObj.keys().forEach { key ->
                        endpoints[key] = endpointsObj.getString(key)
                    }

                    val instructions = mutableMapOf<String, String>()
                    instructionsObj.keys().forEach { key ->
                        instructions[key] = instructionsObj.getString(key)
                    }

                    GPSConfigResponse(
                        recommendedEndpoint = jsonObject.getString("recommendedEndpoint"),
                        gpsEndpoints = endpoints,
                        instructions = instructions
                    ) as T
                }

                else -> {
                    Log.w(TAG, "Unknown type for parsing: ${T::class.simpleName}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing ${T::class.simpleName}: ${e.message}", e)
            Log.e(TAG, "Failed JSON: $json")
            null
        }
    }

    private fun cleanupCaches() {
        positionCache.values.removeAll { !it.isValid() }
        deviceStatusCache.values.removeAll { !it.isValid() }
        safeZoneCache.values.removeAll { !it.isValid() }
        deviceListCache.get()?.let { cached ->
            if (!cached.isValid()) {
                deviceListCache.set(null)
            }
        }
        Log.d(TAG, "Cache cleanup completed")
    }

    private fun handleError(message: String, exception: Exception) {
        Log.e(TAG, "$message: ${exception.message}", exception)
        postError(message)
    }

    private fun postError(message: String) {
        if (isFragmentAttached && isViewModelActive) {
            _error.value = message
        } else {
            Log.d(TAG, "📝 Error message deferred (Fragment detached): $message")
        }
    }

    // ============ DATA CLASSES PARA RESPONSES ============
    data class SafeZoneResponse(val latitude: Double, val longitude: Double)

    data class DeviceStatusResponse(
        val isReceivingData: Boolean,
        val device: Device,
        val recentPositions: Int
    ) {
        data class Device(val name: String)
    }

    data class AvailableDevicesResponse(val devices: List<DeviceInfoResponse>)

    data class GPSConfigResponse(
        val recommendedEndpoint: String,
        val gpsEndpoints: Map<String, String>,
        val instructions: Map<String, String>
    )

    // 14. FUNCIÓN PARA LIMPIAR DATOS GPS
    fun clearGPSData() {
        Log.d(TAG, "🧹 Clearing GPS data...")

        _vehiclePosition.value = null
        trackingHistory.clear()
        lastGPSPosition = null
        lastUpdateTime = 0L

        // Limpiar caches relacionados con GPS
        val deviceUniqueId = getDeviceUniqueId()
        if (deviceUniqueId != null) {
            // Esto se haría si tuvieras acceso al cache desde aquí
            // hyperCache.delete('position', deviceUniqueId)
        }

        _error.value = "🧹 Datos GPS limpiados"
        Log.d(TAG, "✅ GPS data cleared")
    }

    // 15. FUNCIÓN PARA REINICIAR TRACKING GPS
    @RequiresApi(Build.VERSION_CODES.O)
    fun restartGPSTracking() {
        Log.d(TAG, "🔄 Restarting GPS tracking...")

        // ✅ DETENER TRACKING ACTUAL
        stopRealTimeTracking()

        // ✅ ESPERAR UN MOMENTO
        viewModelScope.launch {
            delay(2000)

            // ✅ REINICIAR TRACKING
            startRealTimeTracking(loadInitialPosition = true)

            _error.value = "🔄 GPS reiniciado - Reconectando..."
            Log.d(TAG, "✅ GPS tracking restarted")
        }
    }

    fun setContext(context: Context) {
        this.context = context.applicationContext
    }

    private fun Request.Builder.addAuthHeader(): Request.Builder {
        return addHeader("Authorization", "Bearer ${SecondFragment.JWT_TOKEN}")
            .addHeader("User-Agent", "PeregrinoGPS-Enhanced/3.0")
            .addHeader("Accept", "application/json")
    }

    // ============ EXPLICACIÓN DETALLADA DE onCleared() ============
    // ✅ CLEANUP MEJORADO
    override fun onCleared() {
        super.onCleared()

        Log.d(TAG, "🧹 TrackingViewModel onCleared - CLEANING UP")

        // ✅ 1. Mark as inactive first
        isViewModelActive = false
        isFragmentAttached = false
        keepWebSocketAlive = false

        // ✅ 2. Stop tracking before cleaning resources
        stopRealTimeTracking()

        // ✅ 3. Cancel coroutine scopes
        try {
            webSocketScope.cancel()
            interpolationScope.cancel()
            networkScope.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Error canceling scopes: ${e.message}")
        }

        // ✅ 4. Clear all data
        trackingHistory.clear()
        clearAllCaches()

        Log.d(TAG, "✅ TrackingViewModel cleanup completed - All resources freed")
    }

}