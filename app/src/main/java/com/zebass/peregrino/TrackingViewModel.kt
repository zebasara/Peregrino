package com.zebass.peregrino

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.caverock.androidsvg.BuildConfig
import com.google.android.gms.maps.model.LatLng
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TrackingViewModel : ViewModel() {

    // ============ ESTADO ULTRA-OPTIMIZADO CON CACHE ============
    private val _vehiclePosition = MutableStateFlow<Pair<Int, LatLng>?>(null)
    val vehiclePosition: StateFlow<Pair<Int, LatLng>?> = _vehiclePosition.asStateFlow()

    private val _safeZone = MutableStateFlow<LatLng?>(null)
    val safeZone: StateFlow<LatLng?> = _safeZone.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _deviceInfo = MutableStateFlow<String?>(null)
    val deviceInfo: StateFlow<String?> = _deviceInfo.asStateFlow()

    // ============ HTTP CLIENT ULTRA-OPTIMIZADO ============
    private val client by lazy {
        OkHttpClient.Builder().apply {
            connectTimeout(2, TimeUnit.SECONDS)
            readTimeout(3, TimeUnit.SECONDS)
            writeTimeout(2, TimeUnit.SECONDS)
            callTimeout(5, TimeUnit.SECONDS)
            retryOnConnectionFailure(true)
            connectionPool(ConnectionPool(10, 30, TimeUnit.SECONDS))
            cache(Cache(File.createTempFile("http_cache", ""), 10 * 1024 * 1024))
            if (BuildConfig.DEBUG) {
                addInterceptor { chain ->
                    val startTime = System.currentTimeMillis()
                    val response = chain.proceed(chain.request())
                    val duration = System.currentTimeMillis() - startTime
                    if (duration > 1000) {
                        Log.w(TAG, "⚠️ Slow request: ${chain.request().url} (${duration}ms)")
                    }
                    response
                }
            }
        }.build()
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

    // ============ MODELOS OPTIMIZADOS ============
    data class DeviceStatus(
        val isOnline: Boolean,
        val deviceName: String,
        val recentPositions: Int,
        val lastSeen: Long = System.currentTimeMillis()
    )

    data class DeviceInfo(
        val id: String,        // Cambiado a String para uniqueId
        val name: String,
        val uniqueId: String,
        val status: String = "unknown"
    )

    data class DeviceInfoResponse(
        val id: String, // Cambiado de Int a String
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
        val timestamp: String // ✅ ASEGURAR QUE EXISTE
    )

    companion object {
        const val DEVICE_ID_PREF = "associated_device_id"
        const val DEVICE_NAME_PREF = "associated_device_name"
        const val DEVICE_UNIQUE_ID_PREF = "associated_device_unique_id"
        private const val BASE_URL = "https://carefully-arriving-shepherd.ngrok-free.app"
        private const val TAG = "TrackingViewModel"
        private const val POSITION_TTL = 5_000L
        private const val DEVICE_STATUS_TTL = 30_000L
        private const val SAFE_ZONE_TTL = 120_000L
        private const val DEVICE_LIST_TTL = 60_000L
    }

    private lateinit var context: Context

    // ============ FUNCIONES CORE ULTRA-OPTIMIZADAS ============

    fun setContext(context: Context) {
        this.context = context.applicationContext
    }

    // REEMPLAZAR la función updateVehiclePosition completa
    fun updateVehiclePosition(deviceUniqueId: String, position: GeoPoint, timestamp: Long? = null) {
        val currentTime = System.currentTimeMillis()

        // ✅ VALIDACIÓN DE TIMESTAMP MEJORADA
        val validTimestamp = when {
            timestamp == null -> {
                Log.d(TAG, "✅ No timestamp provided, using current time")
                currentTime
            }
            timestamp > currentTime + 60000L -> {
                Log.w(TAG, "⚠️ Future timestamp detected, using current time instead")
                currentTime
            }
            (currentTime - timestamp).let { diff ->
                val absMinutes = kotlin.math.abs(diff) / 60000L
                absMinutes > 60 // ✅ 1 HORA EN LUGAR DE 10 MINUTOS
            } -> {
                val minutes = (currentTime - timestamp) / 60000L
                Log.w(TAG, "❌ Rejecting old/future position: ${minutes} minutes difference")
                return
            }
            else -> {
                Log.d(TAG, "✅ Valid timestamp: ${(currentTime - timestamp) / 1000L} seconds old")
                timestamp
            }
        }

        val latLng = LatLng(position.latitude, position.longitude)

        // ✅ USAR UNIQUE_ID COMO CLAVE DE CACHE
        positionCache[deviceUniqueId] = CacheEntry(latLng, currentTime, POSITION_TTL)

        // ✅ MANTENER CONSISTENCIA CON HASH CODE
        val deviceIdInt = deviceUniqueId.hashCode()
        _vehiclePosition.value = Pair(deviceIdInt, latLng)

        Log.d(TAG, "✅ Position updated: uniqueId=$deviceUniqueId, lat=${position.latitude}, lon=${position.longitude}, timestamp=${validTimestamp}")
    }

    fun fetchSafeZoneFromServer() {
        val deviceUniqueId = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            .getString(SecondFragment.DEVICE_UNIQUE_ID_PREF, null)

        if (deviceUniqueId == null) {
            Log.d(TAG, "No device uniqueId, skipping fetchSafeZoneFromServer")
            return
        }

        Log.d(TAG, "🔍 Fetching safe zone from server for uniqueId: $deviceUniqueId")

        // ✅ USAR CACHE LOCAL Y PREFERENCIAS PRIMERO
        safeZoneCache[deviceUniqueId]?.let { cached ->
            if (cached.isValid()) {
                _safeZone.value = cached.data
                Log.d(TAG, "✅ Safe zone from local cache: ${cached.data}")
                return
            }
        }

        // Verificar preferencias locales como fallback
        val lat = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            .getString(SecondFragment.PREF_SAFEZONE_LAT, null)?.toDoubleOrNull()
        val lon = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            .getString(SecondFragment.PREF_SAFEZONE_LON, null)?.toDoubleOrNull()

        if (lat != null && lon != null) {
            val latLng = LatLng(lat, lon)
            safeZoneCache[deviceUniqueId] = CacheEntry(latLng, System.currentTimeMillis(), SAFE_ZONE_TTL)
            _safeZone.value = latLng
            Log.d(TAG, "✅ Safe zone restored from preferences: lat=$lat, lon=$lon")

            // Pero aún intentar sincronizar con servidor en background
            syncWithServerInBackground(deviceUniqueId)
            return
        }

        // Si no hay cache ni preferencias, intentar desde servidor
        syncWithServerInBackground(deviceUniqueId)
    }
    // Nueva función para sincronización en background
    private fun syncWithServerInBackground(deviceUniqueId: String) {
        networkScope.launch {
            try {
                if (SecondFragment.JWT_TOKEN.isNullOrEmpty()) {
                    Log.d(TAG, "No JWT token for server sync")
                    return@launch
                }

                Log.d(TAG, "🌐 Syncing safe zone from server for uniqueId: $deviceUniqueId")

                // ✅ USAR EL UNIQUE_ID COMO QUERY PARAMETER
                val safeZone = executeRequest<SafeZoneResponse> {
                    Request.Builder()
                        .url("$BASE_URL/api/safezone?deviceId=$deviceUniqueId")
                        .get()
                        .addAuthHeader()
                        .build()
                }

                if (safeZone != null) {
                    val latLng = LatLng(safeZone.latitude, safeZone.longitude)
                    safeZoneCache[deviceUniqueId] = CacheEntry(latLng, System.currentTimeMillis(), SAFE_ZONE_TTL)
                    _safeZone.value = latLng

                    // Guardar en preferencias para persistencia
                    context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE).edit().apply {
                        putString(SecondFragment.PREF_SAFEZONE_LAT, safeZone.latitude.toString())
                        putString(SecondFragment.PREF_SAFEZONE_LON, safeZone.longitude.toString())
                        apply()
                    }

                    Log.d(TAG, "✅ Safe zone synced from server: lat=${safeZone.latitude}, lon=${safeZone.longitude}")
                } else {
                    Log.d(TAG, "ℹ️ No safe zone found on server")
                }
            } catch (e: Exception) {
                Log.d(TAG, "ℹ️ Server sync failed - using local data only: ${e.message}")
            }
        }
    }

    fun checkDeviceStatus(deviceUniqueId: String, callback: (Boolean, String) -> Unit) {
        Log.d(TAG, "🔍 Checking device status for uniqueId: $deviceUniqueId")

        // Cache usando uniqueId
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

                // Cache usando uniqueId
                deviceStatusCache[deviceUniqueId] = CacheEntry(status, System.currentTimeMillis(), DEVICE_STATUS_TTL)

                val message = if (status.isOnline) {
                    "${status.deviceName} está en línea - ${status.recentPositions} posiciones recientes"
                } else {
                    "${status.deviceName} está fuera de línea - sin posiciones recientes"
                }

                Log.d(TAG, "✅ Device status fetched: isOnline=${status.isOnline}, recentPositions=${status.recentPositions}")

                withContext(Dispatchers.Main) {
                    callback(status.isOnline, message)
                }
            } catch (e: Exception) {
                val errorMsg = when {
                    e.message?.contains("401") == true -> "Token de autenticación inválido. Inicia sesión nuevamente."
                    e.message?.contains("404") == true -> "Dispositivo no encontrado."
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

    @RequiresApi(Build.VERSION_CODES.O)
    fun getGPSClientConfig(callback: (String, Map<String, String>, Map<String, String>) -> Unit) {
        networkScope.launch {
            try {
                if (SecondFragment.JWT_TOKEN.isNullOrEmpty()) {
                    throw Exception("Token de autenticación inválido")
                }
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
                    Log.d(TAG, "GPS config fetched: recommended=${config.recommendedEndpoint}")
                } else {
                    val defaultEndpoint = "$BASE_URL/gps/osmand"
                    val defaultEndpoints = mapOf(
                        "osmand" to defaultEndpoint,
                        "http" to "$BASE_URL/gps/http",
                        "generic" to "$BASE_URL/gps"
                    )
                    val defaultInstructions = mapOf(
                        "protocol" to "HTTP GET/POST",
                        "parameters" to "id, lat, lon, timestamp, speed (opcional)",
                        "example" to "$defaultEndpoint?id=DEVICE_ID&lat=-37.32167&lon=-59.13316&timestamp=${java.time.Instant.now()}&speed=0"
                    )
                    withContext(Dispatchers.Main) {
                        callback(defaultEndpoint, defaultEndpoints, defaultInstructions)
                    }
                    postError("No se recibió configuración GPS del servidor")
                }
            } catch (e: Exception) {
                val errorMsg = when {
                    e.message?.contains("401") == true -> "Token de autenticación inválido. Inicia sesión nuevamente."
                    else -> "Error al obtener configuración GPS: ${e.localizedMessage}"
                }
                val defaultEndpoint = "$BASE_URL/gps/osmand"
                val defaultEndpoints = mapOf(
                    "osmand" to defaultEndpoint,
                    "http" to "$BASE_URL/gps/http",
                    "generic" to "$BASE_URL/gps"
                )
                val defaultInstructions = mapOf(
                    "protocol" to "HTTP GET/POST",
                    "parameters" to "id, lat, lon, timestamp, speed (opcional)",
                    "example" to "$defaultEndpoint?id=DEVICE_ID&lat=-37.32167&lon=-59.13316&timestamp=${java.time.Instant.now()}&speed=0"
                )
                withContext(Dispatchers.Main) {
                    callback(defaultEndpoint, defaultEndpoints, defaultInstructions)
                }
                handleError(errorMsg, e)
            }
        }
    }

    fun sendSafeZoneToServer(latitude: Double, longitude: Double, deviceUniqueId: String) {
        // ✅ FUNCIONALIDAD LOCAL INMEDIATA
        val latLng = LatLng(latitude, longitude)
        safeZoneCache[deviceUniqueId] = CacheEntry(latLng, System.currentTimeMillis(), SAFE_ZONE_TTL)
        _safeZone.value = latLng

        // Guardar en preferencias inmediatamente
        context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE).edit().apply {
            putString(SecondFragment.PREF_SAFEZONE_LAT, latitude.toString())
            putString(SecondFragment.PREF_SAFEZONE_LON, longitude.toString())
            apply()
        }

        Log.d(TAG, "✅ Zona segura establecida localmente: lat=$latitude, lon=$longitude")

        // Enviar al servidor en background
        networkScope.launch {
            try {
                if (SecondFragment.JWT_TOKEN.isNullOrEmpty()) {
                    Log.w(TAG, "No JWT token for server sync")
                    return@launch
                }

                Log.d(TAG, "🌐 Sending safe zone to server for uniqueId: $deviceUniqueId")

                // ✅ ENVIAR USANDO UNIQUE_ID DIRECTAMENTE
                val json = JSONObject().apply {
                    put("latitude", latitude)
                    put("longitude", longitude)
                    put("deviceId", deviceUniqueId) // Usar uniqueId directamente
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

                // Si es error de autorización, mostrar mensaje
                if (e.message?.contains("403") == true) {
                    postError("Error de autorización al guardar zona segura. Verifica tu dispositivo.")
                }
            }
        }
    }
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

        // ✅ ELIMINAR LOCALMENTE PRIMERO
        safeZoneCache.remove(deviceUniqueId)
        _safeZone.value = null

        // Limpiar preferencias
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

                // ✅ ENVIAR UNIQUE_ID DIRECTAMENTE AL SERVIDOR
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

                // CRÍTICO: Tratar 404 como éxito (ya no existe)
                val isSuccessCase = e.message?.contains("404") == true ||
                        e.message?.contains("No se encontró zona segura") == true

                if (isSuccessCase) {
                    Log.d(TAG, "✅ Safe zone already deleted on server (404) - treating as success")
                    withContext(Dispatchers.Main) { callback(true) }
                } else {
                    Log.e(TAG, "❌ Real error deleting safe zone: ${e.message}")

                    // Como ya eliminamos localmente, no restaurar
                    // El usuario ya no ve la zona segura
                    withContext(Dispatchers.Main) { callback(true) } // Tratar como éxito para UX

                    if (e.message?.contains("403") == true) {
                        postError("Error de autorización al eliminar zona segura")
                    }
                }
            }
        }
    }
    // 4. Nueva función para debug de zona segura
    fun debugSafeZoneState(callback: (String) -> Unit) {
        val deviceUniqueId = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            .getString(SecondFragment.DEVICE_UNIQUE_ID_PREF, null)

        if (deviceUniqueId == null) {
            callback("❌ No device uniqueId found")
            return
        }

        networkScope.launch {
            try {
                if (SecondFragment.JWT_TOKEN.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        callback("❌ No JWT token")
                    }
                    return@launch
                }

                val debugInfo = executeRequest<Any> {
                    Request.Builder()
                        .url("$BASE_URL/api/debug/safezone-associations")
                        .get()
                        .addAuthHeader()
                        .build()
                }

                withContext(Dispatchers.Main) {
                    callback("Debug info: $debugInfo")
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback("❌ Debug failed: ${e.message}")
                }
            }
        }
        Log.d(TAG, "🔧 Safe zone methods fixed for proper server sync")
    }

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
                } ?: throw Exception("No se recibió respuesta del servidor")

                Log.d(TAG, "Device associated successfully: id=${response.id}, name=${response.name}")

                // ✅ CAMBIO PRINCIPAL AQUÍ:
                context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE).edit {
                    putString(DEVICE_UNIQUE_ID_PREF, response.uniqueId)  // Guardar uniqueId como String
                    putString(DEVICE_NAME_PREF, response.name)
                    // Mantener id interno solo como referencia:
                    putInt(DEVICE_ID_PREF, response.uniqueId.hashCode()) // Para compatibilidad
                }

                // Clear all caches
                clearAllCaches()

                withContext(Dispatchers.Main) {
                    callback(response.id, response.name)
                }

                Log.d(TAG, "Device association completed successfully")

            } catch (e: Exception) {
                val errorMsg = when {
                    e.message?.contains("404") == true -> "Dispositivo no encontrado. Verifica el ID único en Traccar."
                    e.message?.contains("409") == true -> "El dispositivo ya está asociado."
                    e.message?.contains("401") == true -> "Token de autenticación inválido. Inicia sesión nuevamente."
                    e.message?.contains("400") == true -> "Datos de dispositivo inválidos. Verifica el ID único y nombre."
                    else -> "Error al asociar dispositivo: ${e.localizedMessage}"
                }
                Log.e(TAG, "Error associating device: $errorMsg", e)
                handleError(errorMsg, e)
            }
        }
    }

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
                Log.d(TAG, "Obtenidos ${devicesResponse?.devices?.size ?: 0} dispositivos disponibles")
            } catch (e: Exception) {
                val errorMsg = when {
                    e.message?.contains("401") == true -> "Token de autenticación inválido. Inicia sesión nuevamente."
                    e.message?.contains("404") == true -> "Servicio Traccar no encontrado. Verifica la configuración del servidor."
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

    // REEMPLAZAR la función getLastPosition completa
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun getLastPosition(deviceUniqueId: String): LastPositionResponse {
        Log.d(TAG, "📍 Getting last position for uniqueId: $deviceUniqueId")

        // Check cache usando uniqueId
        positionCache[deviceUniqueId]?.let { cached ->
            if (cached.isValid()) {
                Log.d(TAG, "✅ Position from cache for uniqueId: $deviceUniqueId")
                // ✅ RETORNAR ESTRUCTURA COMPLETA DESDE CACHE
                return LastPositionResponse(
                    deviceId = deviceUniqueId,
                    traccarDeviceId = deviceUniqueId.hashCode(),
                    latitude = cached.data.latitude,
                    longitude = cached.data.longitude,
                    speed = 0.0,
                    course = 0.0,
                    timestamp = java.time.Instant.now().toString() // ✅ TIMESTAMP VÁLIDO
                )
            }
        }

        return withContext(ioDispatcher) {
            withTimeout(10_000L) {
                if (SecondFragment.JWT_TOKEN.isNullOrEmpty()) {
                    throw Exception("Token de autenticación inválido")
                }

                Log.d(TAG, "🌐 Fetching position from server for uniqueId: $deviceUniqueId")

                val response = executeRequest<LastPositionResponse> {
                    Request.Builder()
                        .url("$BASE_URL/api/last-position?deviceId=$deviceUniqueId")
                        .get()
                        .addAuthHeader()
                        .build()
                } ?: throw Exception("No se recibió datos de posición")

                val latLng = LatLng(response.latitude, response.longitude)

                // Cache usando uniqueId
                positionCache[deviceUniqueId] = CacheEntry(latLng, System.currentTimeMillis(), POSITION_TTL)

                Log.d(TAG, "✅ Position fetched from server: uniqueId=$deviceUniqueId, lat=${response.latitude}, lon=${response.longitude}")

                // ✅ ASEGURAR QUE TIMESTAMP EXISTE
                if (response.timestamp.isNullOrEmpty()) {
                    response.copy(timestamp = java.time.Instant.now().toString())
                } else {
                    response
                }
            }
        }
    }
    private fun getDeviceUniqueId(): String? {
        return context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            .getString(SecondFragment.DEVICE_UNIQUE_ID_PREF, null)
    }
    @RequiresApi(Build.VERSION_CODES.O)
    fun forceDataRefresh() {
        Log.d(TAG, "🔄 Forcing data refresh...")

        // Clear all caches
        clearAllCaches()

        // Re-fetch associated devices
        fetchAssociatedDevices()

        // Try to get current position
        getDeviceUniqueId()?.let { uniqueId ->
            networkScope.launch {
                try {
                    val position = getLastPosition(uniqueId)
                    val geoPoint = GeoPoint(position.latitude, position.longitude)
                    updateVehiclePosition(uniqueId, geoPoint)
                    Log.d(TAG, "✅ Force refresh completed with position update")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Force refresh failed: ${e.message}")
                }
            }
        }
    }
    fun fetchAssociatedDevices() {
        Log.d(TAG, "🔄 Fetching associated devices...")

        networkScope.launch {
            try {
                if (SecondFragment.JWT_TOKEN.isNullOrEmpty()) {
                    throw Exception("Token de autenticación inválido")
                }

                // Clear cache before fetching
                deviceListCache.set(null)

                val devices = getUserDevices()
                Log.d(TAG, "📱 Fetched ${devices.size} devices from server")

                if (devices.isNotEmpty()) {
                    val firstDevice = devices.first()
                    val info = "Vehículo: ${firstDevice.name} (UniqueID: ${firstDevice.uniqueId})"
                    _deviceInfo.value = info

                    // Save to preferences usando String para deviceId
                    context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE).edit {
                        // Guardar también el ID interno como int para compatibilidad
                        putInt(DEVICE_ID_PREF, firstDevice.uniqueId.hashCode())
                        putString(DEVICE_NAME_PREF, firstDevice.name)
                        putString(DEVICE_UNIQUE_ID_PREF, firstDevice.uniqueId)
                    }

                    Log.d(TAG, "✅ Device info saved: uniqueId=${firstDevice.uniqueId}, name=${firstDevice.name}")

                    // Forzar actualización de status inmediatamente
                    checkDeviceStatus(firstDevice.uniqueId) { isOnline, message ->
                        Log.d(TAG, "📊 Initial device status: $message")
                    }

                } else {
                    _deviceInfo.value = "No hay vehículos asociados"
                    Log.d(TAG, "⚠️ No associated devices found")

                    // Clear preferences if no devices
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
                if (deviceResponse.id.isNotEmpty()) { // Validar que id no esté vacío
                    DeviceInfo(
                        id = deviceResponse.uniqueId ?: deviceResponse.id, // Usar uniqueId como id principal
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
                // Return empty list for 404, don't cache
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

    private suspend inline fun <reified T> executeRequest(requestBuilder: () -> Request): T? {
        return try {
            val request = requestBuilder()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()?.let { responseBody ->
                    when (T::class) {
                        Unit::class -> Unit as T
                        String::class -> responseBody as T
                        else -> {
                            try {
                                parseJsonResponse<T>(responseBody)
                            } catch (e: Exception) {
                                Log.e(TAG, "JSON parsing error for ${T::class.simpleName}: ${e.message}")
                                null
                            }
                        }
                    }
                }
            } else {
                val errorBody = response.body?.string() ?: "No response body"
                throw Exception("HTTP ${response.code}: $errorBody")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request failed: ${e.message}")
            throw e
        }
    }

    private inline fun <reified T> parseJsonResponse(json: String): T? {
        return try {
            Log.d(TAG, "Parsing JSON for ${T::class.simpleName}: ${json.take(200)}...")

            when (T::class) {
                List::class -> {
                    val devices = mutableListOf<DeviceInfoResponse>()
                    try {
                        val jsonArray = org.json.JSONArray(json)
                        for (i in 0 until jsonArray.length()) {
                            val deviceObj = jsonArray.getJSONObject(i)
                            devices.add(
                                DeviceInfoResponse(
                                    id = deviceObj.getString("uniqueId"), // Usar uniqueId como id
                                    name = deviceObj.getString("name"),
                                    uniqueId = deviceObj.optString("uniqueId", "N/A"),
                                    status = deviceObj.optString("status", "unknown")
                                )
                            )
                        }
                    } catch (e: org.json.JSONException) {
                        // Intentar formato con objeto devices
                        try {
                            val jsonObject = JSONObject(json)
                            if (jsonObject.has("devices")) {
                                val jsonArray = jsonObject.getJSONArray("devices")
                                for (i in 0 until jsonArray.length()) {
                                    val deviceObj = jsonArray.getJSONObject(i)
                                    devices.add(
                                        DeviceInfoResponse(
                                            id = deviceObj.getString("uniqueId"), // Usar uniqueId como id
                                            name = deviceObj.getString("name"),
                                            uniqueId = deviceObj.optString("uniqueId", "N/A"),
                                            status = deviceObj.optString("status", "unknown")
                                        )
                                    )
                                }
                            } else {
                                Log.e(TAG, "JSON object doesn't contain 'devices' array")
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

                DeviceStatusResponse::class -> {
                    val jsonObject = JSONObject(json)
                    val deviceObj = jsonObject.getJSONObject("device")
                    DeviceStatusResponse(
                        isReceivingData = jsonObject.getBoolean("isReceivingData"),
                        device = DeviceStatusResponse.Device(name = deviceObj.getString("name")),
                        recentPositions = jsonObject.getInt("recentPositions")
                    ) as T
                }

                LastPositionResponse::class -> {
                    val jsonObject = JSONObject(json)
                    LastPositionResponse(
                        deviceId = jsonObject.getString("deviceId"),
                        traccarDeviceId = jsonObject.getInt("traccarDeviceId"),
                        latitude = jsonObject.getDouble("latitude"),
                        longitude = jsonObject.getDouble("longitude"),
                        speed = jsonObject.getDouble("speed"),
                        course = jsonObject.getDouble("course"),
                        timestamp = jsonObject.getString("timestamp")
                    ) as T
                }

                AssociateDeviceResponse::class -> {
                    val jsonObject = JSONObject(json)
                    AssociateDeviceResponse(
                        id = jsonObject.getInt("id"),
                        name = jsonObject.getString("name"),
                        uniqueId = jsonObject.getString("uniqueId")
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

    private fun Request.Builder.addAuthHeader(): Request.Builder {
        return addHeader("Authorization", "Bearer ${SecondFragment.JWT_TOKEN}")
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
        _error.value = message
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

    data class AssociateDeviceResponse(val id: Int, val name: String, val uniqueId: String)

    data class AvailableDevicesResponse(val devices: List<DeviceInfoResponse>)

    data class GPSConfigResponse(
        val recommendedEndpoint: String,
        val gpsEndpoints: Map<String, String>,
        val instructions: Map<String, String>
    )

    override fun onCleared() {
        super.onCleared()
        networkScope.cancel()
        cacheCleanupJob.cancel()
    }
}