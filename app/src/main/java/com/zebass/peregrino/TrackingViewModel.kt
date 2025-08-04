package com.zebass.peregrino

import android.content.Context
import android.util.Log
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

    private val _deviceStatus = MutableStateFlow<DeviceStatus?>(null)
    val deviceStatus: StateFlow<DeviceStatus?> = _deviceStatus.asStateFlow()

    // ============ HTTP CLIENT ULTRA-OPTIMIZADO ============
    private val client by lazy {
        OkHttpClient.Builder().apply {
            connectTimeout(2, TimeUnit.SECONDS)        // Timeout agresivo
            readTimeout(3, TimeUnit.SECONDS)
            writeTimeout(2, TimeUnit.SECONDS)
            callTimeout(5, TimeUnit.SECONDS)           // Timeout total
            retryOnConnectionFailure(true)
            connectionPool(ConnectionPool(10, 30, TimeUnit.SECONDS)) // Pool optimizado

            // Cache HTTP optimizado
            cache(Cache(File.createTempFile("http_cache", ""), 10 * 1024 * 1024)) // 10MB

            // Interceptor para logging rápido (solo en debug)
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
        val ttl: Long = 30_000L // 30 segundos por defecto
    ) {
        fun isValid(): Boolean = System.currentTimeMillis() - timestamp < ttl
    }

    private val positionCache = ConcurrentHashMap<Int, CacheEntry<LatLng>>()
    private val deviceStatusCache = ConcurrentHashMap<Int, CacheEntry<DeviceStatus>>()
    private val safeZoneCache = ConcurrentHashMap<Int, CacheEntry<LatLng>>()
    private val deviceListCache = AtomicReference<CacheEntry<List<DeviceInfo>>?>()

    // ============ COROUTINES OPTIMIZADAS ============
    private val ioDispatcher = Dispatchers.IO.limitedParallelism(8) // Limitar paralelismo
    private val networkScope = CoroutineScope(ioDispatcher + SupervisorJob())
    private val cacheCleanupJob = viewModelScope.launch {
        while (true) {
            delay(60_000L) // Limpiar cada minuto
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
        val id: Int,
        val name: String,
        val uniqueId: String,
        val status: String = "unknown"
    )

    data class GPSConfig(
        val recommendedEndpoint: String,
        val endpoints: Map<String, String>,
        val instructions: Map<String, String>
    )

    companion object {
        const val DEVICE_ID_PREF = "associated_device_id"
        const val DEVICE_NAME_PREF = "associated_device_name"
        const val DEVICE_UNIQUE_ID_PREF = "associated_device_unique_id"
        private const val BASE_URL = "https://carefully-arriving-shepherd.ngrok-free.app"
        private const val TAG = "TrackingViewModel"

        // Cache TTLs optimizados
        private const val POSITION_TTL = 5_000L      // 5s para posiciones
        private const val DEVICE_STATUS_TTL = 30_000L // 30s para estado
        private const val SAFE_ZONE_TTL = 120_000L    // 2min para zona segura
        private const val DEVICE_LIST_TTL = 60_000L   // 1min para lista dispositivos
    }

    private lateinit var context: Context

    // ============ FUNCIONES CORE ULTRA-OPTIMIZADAS ============

    fun setContext(context: Context) {
        this.context = context.applicationContext // Evitar memory leaks
    }

    fun updateVehiclePosition(deviceId: Int, position: GeoPoint) {
        val latLng = LatLng(position.latitude, position.longitude)

        // Cache inmediato
        positionCache[deviceId] = CacheEntry(latLng, System.currentTimeMillis(), POSITION_TTL)

        // Update state
        _vehiclePosition.value = Pair(deviceId, latLng)

        Log.d(TAG, "Position updated: deviceId=$deviceId, lat=${position.latitude}, lon=${position.longitude}")
    }

    fun fetchSafeZoneFromServer() {
        val deviceId = getDeviceId()
        if (deviceId == -1) {
            Log.d(TAG, "No device ID, skipping fetchSafeZoneFromServer")
            return
        }

        // Check cache primero
        safeZoneCache[deviceId]?.let { cached ->
            if (cached.isValid()) {
                _safeZone.value = cached.data
                Log.d(TAG, "Safe zone from cache: ${cached.data}")
                return
            }
        }

        networkScope.launch {
            try {
                val safeZone = executeRequest<SafeZoneResponse> {
                    Request.Builder()
                        .url("$BASE_URL/api/safezone?deviceId=$deviceId")
                        .get()
                        .addAuthHeader()
                        .build()
                }

                safeZone?.let { zone ->
                    val latLng = LatLng(zone.latitude, zone.longitude)
                    safeZoneCache[deviceId] = CacheEntry(latLng, System.currentTimeMillis(), SAFE_ZONE_TTL)
                    _safeZone.value = latLng
                    Log.d(TAG, "Fetched safe zone: lat=${zone.latitude}, lon=${zone.longitude}")
                }
            } catch (e: Exception) {
                handleError("Failed to fetch safe zone", e)
            }
        }
    }

    fun checkDeviceStatus(deviceId: Int, callback: (Boolean, String) -> Unit) {
        // Check cache primero
        deviceStatusCache[deviceId]?.let { cached ->
            if (cached.isValid()) {
                val status = cached.data
                val message = if (status.isOnline) {
                    "✅ ${status.deviceName} is online - ${status.recentPositions} recent positions"
                } else {
                    "⚠️ ${status.deviceName} is offline - no recent positions"
                }
                callback(status.isOnline, message)
                return
            }
        }

        networkScope.launch {
            try {
                val statusResponse = executeRequest<DeviceStatusResponse> {
                    Request.Builder()
                        .url("$BASE_URL/api/device/status/$deviceId")
                        .get()
                        .addAuthHeader()
                        .build()
                }

                statusResponse?.let { response ->
                    val status = DeviceStatus(
                        isOnline = response.isReceivingData,
                        deviceName = response.device.name,
                        recentPositions = response.recentPositions
                    )

                    deviceStatusCache[deviceId] = CacheEntry(status, System.currentTimeMillis(), DEVICE_STATUS_TTL)
                    _deviceStatus.value = status

                    val message = if (status.isOnline) {
                        "✅ ${status.deviceName} is online - ${status.recentPositions} recent positions"
                    } else {
                        "⚠️ ${status.deviceName} is offline - no recent positions"
                    }

                    withContext(Dispatchers.Main) {
                        callback(status.isOnline, message)
                    }
                    Log.d(TAG, "Device status: $message")
                }
            } catch (e: Exception) {
                val errorMsg = "Error checking device status: ${e.localizedMessage}"
                withContext(Dispatchers.Main) {
                    callback(false, errorMsg)
                }
                handleError("Error checking device status", e)
            }
        }
    }

    fun getGPSClientConfig(callback: (String, Map<String, String>, Map<String, String>) -> Unit) {
        networkScope.launch {
            try {
                val config = executeRequest<GPSConfigResponse> {
                    Request.Builder()
                        .url("$BASE_URL/api/gps/config")
                        .get()
                        .addAuthHeader()
                        .build()
                }

                config?.let { response ->
                    withContext(Dispatchers.Main) {
                        callback(
                            response.recommendedEndpoint,
                            response.gpsEndpoints,
                            response.instructions
                        )
                    }
                    Log.d(TAG, "GPS config fetched: recommended=${response.recommendedEndpoint}")
                }
            } catch (e: Exception) {
                handleError("Error getting GPS config", e)
            }
        }
    }

    fun sendSafeZoneToServer(latitude: Double, longitude: Double, deviceId: Int) {
        networkScope.launch {
            try {
                val requestBody = FormBody.Builder()
                    .add("latitude", latitude.toString())
                    .add("longitude", longitude.toString())
                    .add("deviceId", deviceId.toString())
                    .build()

                executeRequest<Unit> {
                    Request.Builder()
                        .url("$BASE_URL/api/safezone")
                        .post(requestBody)
                        .addAuthHeader()
                        .build()
                }

                // Update cache inmediatamente
                val latLng = LatLng(latitude, longitude)
                safeZoneCache[deviceId] = CacheEntry(latLng, System.currentTimeMillis(), SAFE_ZONE_TTL)
                _safeZone.value = latLng

                Log.d(TAG, "Safe zone sent: lat=$latitude, lon=$longitude, deviceId=$deviceId")
            } catch (e: Exception) {
                handleError("Failed to send safe zone", e)
            }
        }
    }

    fun deleteSafeZoneFromServer() {
        val deviceId = getDeviceId()
        if (deviceId == -1) {
            Log.d(TAG, "No device ID, skipping deleteSafeZoneFromServer")
            postError("No associated device found")
            return
        }

        networkScope.launch {
            try {
                executeRequest<Unit> {
                    Request.Builder()
                        .url("$BASE_URL/api/safezone?deviceId=$deviceId")
                        .delete()
                        .addAuthHeader()
                        .build()
                }

                // Clear cache inmediatamente
                safeZoneCache.remove(deviceId)
                _safeZone.value = null

                Log.d(TAG, "Safe zone deleted for deviceId=$deviceId")
            } catch (e: Exception) {
                handleError("Failed to delete safe zone", e)
            }
        }
    }

    fun associateDevice(deviceUniqueId: String, name: String, callback: (Int, String) -> Unit) {
        if (deviceUniqueId.isBlank() || name.isBlank()) {
            postError("Device ID and name cannot be empty")
            return
        }

        networkScope.launch {
            try {
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

                response?.let { deviceResponse ->
                    // Save to preferences
                    context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE).edit {
                        putInt(DEVICE_ID_PREF, deviceResponse.id)
                        putString(DEVICE_NAME_PREF, deviceResponse.name)
                        putString(DEVICE_UNIQUE_ID_PREF, deviceResponse.uniqueId)
                    }

                    // Clear caches for new device
                    clearCachesForDevice(deviceResponse.id)

                    withContext(Dispatchers.Main) {
                        callback(deviceResponse.id, deviceResponse.name)
                    }

                    Log.d(TAG, "Device associated: id=${deviceResponse.id}, name=${deviceResponse.name}")
                }
            } catch (e: Exception) {
                when {
                    e.message?.contains("404") == true -> {
                        postError("Device not found. Verify the Unique ID in Traccar")
                    }
                    e.message?.contains("409") == true -> {
                        postError("Device already associated")
                    }
                    e.message?.contains("401") == true -> {
                        postError("Invalid authentication token")
                    }
                    else -> handleError("Error associating device", e)
                }
            }
        }
    }

    fun showAvailableDevices(callback: (String) -> Unit) {
        networkScope.launch {
            try {
                val devicesResponse = executeRequest<AvailableDevicesResponse> {
                    Request.Builder()
                        .url("$BASE_URL/api/traccar/devices")
                        .get()
                        .addAuthHeader()
                        .build()
                }

                devicesResponse?.let { response ->
                    val deviceList = buildString {
                        appendLine("Available devices:\n")
                        response.devices.forEach { device ->
                            appendLine("• ${device.name}")
                            appendLine("  UniqueID: ${device.uniqueId}")
                            appendLine("  Status: ${device.status}\n")
                        }
                    }

                    withContext(Dispatchers.Main) {
                        callback(deviceList)
                    }
                    Log.d(TAG, "Fetched ${response.devices.size} available devices")
                }
            } catch (e: Exception) {
                handleError("Error fetching devices", e)
            }
        }
    }

    fun showDeviceSelectionForSafeZone(callback: (Int) -> Unit) {
        networkScope.launch {
            try {
                val devices = getUserDevices()
                if (devices.isEmpty()) {
                    postError("No associated devices found")
                    return@launch
                }

                val uniqueDevices = devices.distinctBy { it.id }
                if (uniqueDevices.isEmpty()) {
                    postError("No valid devices found")
                    return@launch
                }

                val deviceNames = uniqueDevices.map { "${it.name} (ID: ${it.id})" }.toTypedArray()
                val deviceIds = uniqueDevices.map { it.id }.toIntArray()

                withContext(Dispatchers.Main) {
                    Log.d(TAG, "Showing device selection with ${deviceIds.size} devices")
                    MaterialAlertDialogBuilder(context)
                        .setTitle("Select vehicle for safe zone")
                        .setItems(deviceNames) { _, which ->
                            if (which in deviceIds.indices) {
                                callback(deviceIds[which])
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            } catch (e: Exception) {
                handleError("Error loading devices", e)
            }
        }
    }

    suspend fun getLastPosition(deviceId: Int): LatLng {
        // Check cache primero
        positionCache[deviceId]?.let { cached ->
            if (cached.isValid()) {
                Log.d(TAG, "Position from cache for deviceId=$deviceId")
                return cached.data
            }
        }

        return withContext(ioDispatcher) {
            withTimeout(10_000L) { // 10 second timeout
                val position = executeRequest<LastPositionResponse> {
                    Request.Builder()
                        .url("$BASE_URL/api/last-position?deviceId=$deviceId")
                        .get()
                        .addAuthHeader()
                        .build()
                } ?: throw Exception("No position data received")

                val latLng = LatLng(position.latitude, position.longitude)

                // Cache result
                positionCache[deviceId] = CacheEntry(latLng, System.currentTimeMillis(), POSITION_TTL)

                Log.d(TAG, "Fetched last position: deviceId=$deviceId, lat=${position.latitude}, lon=${position.longitude}")
                latLng
            }
        }
    }

    fun fetchAssociatedDevices() {
        networkScope.launch {
            try {
                val devices = getUserDevices()
                if (devices.isNotEmpty()) {
                    val firstDevice = devices.first()
                    _deviceInfo.value = "Vehicle: ${firstDevice.name} (ID: ${firstDevice.id})"

                    // Save to preferences
                    context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE).edit {
                        putInt(DEVICE_ID_PREF, firstDevice.id)
                        putString(DEVICE_NAME_PREF, firstDevice.name)
                    }

                    Log.d(TAG, "Fetched associated device: id=${firstDevice.id}, name=${firstDevice.name}")
                } else {
                    _deviceInfo.value = "No associated vehicles"
                    Log.d(TAG, "No associated devices found")
                }
            } catch (e: Exception) {
                handleError("Error fetching associated devices", e)
            }
        }
    }

    // ============ FUNCIONES AUXILIARES OPTIMIZADAS ============

    private suspend fun getUserDevices(): List<DeviceInfo> {
        // Check cache primero
        deviceListCache.get()?.let { cached ->
            if (cached.isValid()) {
                return cached.data
            }
        }

        val response = executeRequest<List<DeviceInfoResponse>> {
            Request.Builder()
                .url("$BASE_URL/api/user/devices")
                .get()
                .addAuthHeader()
                .build()
        } ?: emptyList()

        val devices = response.mapNotNull { deviceResponse ->
            if (deviceResponse.id > 0) {
                DeviceInfo(
                    id = deviceResponse.id,
                    name = deviceResponse.name,
                    uniqueId = deviceResponse.uniqueId ?: "N/A",
                    status = deviceResponse.status ?: "unknown"
                )
            } else null
        }

        // Cache result
        deviceListCache.set(CacheEntry(devices, System.currentTimeMillis(), DEVICE_LIST_TTL))
        return devices
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
            when (T::class) {
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
                        latitude = jsonObject.getDouble("latitude"),
                        longitude = jsonObject.getDouble("longitude")
                    ) as T
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing ${T::class.simpleName}: ${e.message}")
            null
        }
    }

    private fun Request.Builder.addAuthHeader(): Request.Builder {
        return addHeader("Authorization", "Bearer ${SecondFragment.JWT_TOKEN}")
    }

    private fun cleanupCaches() {
        val now = System.currentTimeMillis()

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

    private fun clearCachesForDevice(deviceId: Int) {
        positionCache.remove(deviceId)
        deviceStatusCache.remove(deviceId)
        safeZoneCache.remove(deviceId)
        deviceListCache.set(null)
    }

    private fun handleError(message: String, exception: Exception) {
        Log.e(TAG, "$message: ${exception.message}", exception)
        postError("$message: ${exception.localizedMessage}")
    }

    fun postError(message: String) {
        _error.value = message
    }

    private fun getDeviceId(): Int {
        return context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            .getInt(DEVICE_ID_PREF, -1)
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

    data class LastPositionResponse(val latitude: Double, val longitude: Double)

    data class AssociateDeviceResponse(val id: Int, val name: String, val uniqueId: String)

    data class AvailableDevicesResponse(val devices: List<DeviceInfoResponse>)

    data class DeviceInfoResponse(
        val id: Int,
        val name: String,
        val uniqueId: String?,
        val status: String?
    )

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