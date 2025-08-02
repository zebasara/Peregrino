package com.zebass.peregrino

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint

class TrackingViewModel : ViewModel() {

    private val _vehiclePosition = MutableStateFlow<Pair<Int, LatLng>?>(null)
    val vehiclePosition: StateFlow<Pair<Int, LatLng>?> = _vehiclePosition

    private val _safeZone = MutableStateFlow<LatLng?>(null)
    val safeZone: StateFlow<LatLng?> = _safeZone

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _deviceInfo = MutableStateFlow<String?>(null)
    val deviceInfo: StateFlow<String?> = _deviceInfo

    private val client = OkHttpClient()
    private val BASE_URL = "https://carefully-arriving-shepherd.ngrok-free.app"
    private val TAG = "TrackingViewModel"

    companion object {
        const val DEVICE_ID_PREF = "associated_device_id"
        const val DEVICE_NAME_PREF = "associated_device_name"
        const val DEVICE_UNIQUE_ID_PREF = "associated_device_unique_id"
    }

    fun updateVehiclePosition(deviceId: Int, position: GeoPoint) {
        _vehiclePosition.value = Pair(deviceId, LatLng(position.latitude, position.longitude))
        Log.d(TAG, "Updated vehicle position: deviceId=$deviceId, lat=${position.latitude}, lon=${position.longitude}")
    }

    fun fetchSafeZoneFromServer() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val deviceId = getDeviceId()
                if (deviceId == -1) {
                    Log.d(TAG, "No device ID, skipping fetchSafeZoneFromServer")
                    return@launch
                }

                val request = Request.Builder()
                    .url("$BASE_URL/api/safezone?deviceId=$deviceId")
                    .get()
                    .addHeader("Authorization", "Bearer ${SecondFragment.JWT_TOKEN}")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    response.body?.string()?.let { responseBody ->
                        val jsonObject = JSONObject(responseBody)
                        val lat = jsonObject.getDouble("latitude")
                        val lon = jsonObject.getDouble("longitude")
                        _safeZone.value = LatLng(lat, lon)
                        Log.d(TAG, "Fetched safe zone: lat=$lat, lon=$lon, deviceId=$deviceId")
                    }
                } else {
                    val errorMsg = "Failed to fetch safe zone: HTTP ${response.code}, body=${response.body?.string()}"
                    postError(errorMsg)
                    Log.e(TAG, errorMsg)
                }
            } catch (e: Exception) {
                postError("Failed to fetch safe zone: ${e.message}")
                Log.e(TAG, "Error fetching safe zone", e)
            }
        }
    }
    fun checkDeviceStatus(deviceId: Int, callback: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$BASE_URL/api/device/status/$deviceId")
                    .get()
                    .addHeader("Authorization", "Bearer ${SecondFragment.JWT_TOKEN}")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    response.body?.string()?.let { responseBody ->
                        val jsonObject = JSONObject(responseBody)
                        val isReceivingData = jsonObject.getBoolean("isReceivingData")
                        val deviceName = jsonObject.getJSONObject("device").getString("name")
                        val recentPositions = jsonObject.getInt("recentPositions")

                        val statusMessage = if (isReceivingData) {
                            "✅ $deviceName is online - $recentPositions recent positions"
                        } else {
                            "⚠️ $deviceName is offline - no recent positions"
                        }

                        withContext(Dispatchers.Main) {
                            callback(isReceivingData, statusMessage)
                        }
                        Log.d(TAG, "Device status: $statusMessage")
                    }
                } else {
                    val errorMsg = "Error checking device status: HTTP ${response.code}"
                    withContext(Dispatchers.Main) {
                        callback(false, errorMsg)
                    }
                    Log.e(TAG, errorMsg)
                }
            } catch (e: Exception) {
                val errorMsg = "Error checking device status: ${e.message}"
                withContext(Dispatchers.Main) {
                    callback(false, errorMsg)
                }
                Log.e(TAG, "Error checking device status", e)
            }
        }
    }
    // Agregar al TrackingViewModel.kt

    fun getGPSClientConfig(callback: (String, Map<String, String>, JSONObject?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$BASE_URL/api/gps/config")
                    .get()
                    .addHeader("Authorization", "Bearer ${SecondFragment.JWT_TOKEN}")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    response.body?.string()?.let { responseBody ->
                        val jsonObject = JSONObject(responseBody)
                        val recommendedEndpoint = jsonObject.getString("recommendedEndpoint")
                        val instructions = jsonObject.getJSONObject("instructions")

                        val endpoints = mutableMapOf<String, String>()
                        val gpsEndpoints = jsonObject.getJSONObject("gpsEndpoints")
                        gpsEndpoints.keys().forEach { key ->
                            endpoints[key] = gpsEndpoints.getString(key)
                        }

                        withContext(Dispatchers.Main) {
                            callback(recommendedEndpoint, endpoints, instructions) // Incluir instructions
                        }
                        Log.d(TAG, "GPS config fetched: recommended=$recommendedEndpoint, endpoints=$endpoints")
                    }
                } else {
                    val errorMsg = "Error getting GPS config: HTTP ${response.code}"
                    postError(errorMsg)
                    Log.e(TAG, errorMsg)
                }
            } catch (e: Exception) {
                val errorMsg = "Error getting GPS config: ${e.message}"
                postError(errorMsg)
                Log.e(TAG, "Error getting GPS config", e)
            }
        }
    }

    fun sendSafeZoneToServer(latitude: Double, longitude: Double, deviceId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$BASE_URL/api/safezone")
                    .post(
                        FormBody.Builder()
                            .add("latitude", latitude.toString())
                            .add("longitude", longitude.toString())
                            .add("deviceId", deviceId.toString())
                            .build()
                    )
                    .addHeader("Authorization", "Bearer ${SecondFragment.JWT_TOKEN}")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    Log.d(TAG, "Safe zone sent to server: lat=$latitude, lon=$longitude, deviceId=$deviceId")
                } else {
                    val errorMsg = "Failed to send safe zone: HTTP ${response.code}, body=${response.body?.string()}"
                    postError(errorMsg)
                    Log.e(TAG, errorMsg)
                }
            } catch (e: Exception) {
                postError("Failed to send safe zone: ${e.message}")
                Log.e(TAG, "Error sending safe zone", e)
            }
        }
    }

    fun deleteSafeZoneFromServer() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val deviceId = getDeviceId()
                if (deviceId == -1) {
                    Log.d(TAG, "No device ID, skipping deleteSafeZoneFromServer")
                    return@launch
                }

                val request = Request.Builder()
                    .url("$BASE_URL/api/safezone?deviceId=$deviceId")
                    .delete()
                    .addHeader("Authorization", "Bearer ${SecondFragment.JWT_TOKEN}")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    Log.d(TAG, "Safe zone deleted from server for deviceId=$deviceId")
                } else {
                    val errorMsg = "Failed to delete safe zone: HTTP ${response.code}, body=${response.body?.string()}"
                    postError(errorMsg)
                    Log.e(TAG, errorMsg)
                }
            } catch (e: Exception) {
                postError("Failed to delete safe zone: ${e.message}")
                Log.e(TAG, "Error deleting safe zone", e)
            }
        }
    }

    fun associateDevice(deviceUniqueId: String, name: String, callback: (Int, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("deviceId", deviceUniqueId.trim())
                    put("name", name.trim())
                }

                val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                val request = Request.Builder()
                    .url("$BASE_URL/api/user/devices")
                    .post(requestBody)
                    .addHeader("Authorization", "Bearer ${SecondFragment.JWT_TOKEN}")
                    .build()

                val response = client.newCall(request).execute()
                withContext(Dispatchers.Main) {
                    when {
                        response.isSuccessful -> {
                            val jsonResponse = JSONObject(response.body?.string() ?: "{}")
                            val deviceId = jsonResponse.getInt("id")
                            val deviceName = jsonResponse.getString("name")
                            val uniqueId = jsonResponse.getString("uniqueId")
                            _context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE).edit()
                                .putInt(DEVICE_ID_PREF, deviceId)
                                .putString(DEVICE_NAME_PREF, deviceName)
                                .putString(DEVICE_UNIQUE_ID_PREF, uniqueId)
                                .apply()
                            callback(deviceId, deviceName)
                            Log.d(TAG, "Device associated: id=$deviceId, name=$deviceName, uniqueId=$uniqueId")
                        }
                        response.code == 404 -> {
                            postError("Dispositivo no encontrado. Verifica el Unique ID en Traccar")
                            Log.e(TAG, "Device not found: Unique ID=$deviceUniqueId")
                        }
                        response.code == 409 -> {
                            postError("El dispositivo ya está asociado")
                            Log.e(TAG, "Device already associated: Unique ID=$deviceUniqueId")
                        }
                        response.code == 401 -> {
                            postError("Token de autenticación inválido")
                            Log.e(TAG, "Invalid authentication token")
                        }
                        else -> {
                            val errorMsg = "Error ${response.code}: ${response.body?.string()}"
                            postError(errorMsg)
                            Log.e(TAG, errorMsg)
                        }
                    }
                }
            } catch (e: Exception) {
                postError("Error de conexión: ${e.message}")
                Log.e(TAG, "Error associating device", e)
            }
        }
    }

    fun showAvailableDevices(callback: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$BASE_URL/api/traccar/devices")
                    .get()
                    .addHeader("Authorization", "Bearer ${SecondFragment.JWT_TOKEN}")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    response.body?.string()?.let { responseBody ->
                        val jsonObject = JSONObject(responseBody)
                        val devices = jsonObject.getJSONArray("devices")
                        val deviceList = StringBuilder("Dispositivos disponibles:\n\n")
                        for (i in 0 until devices.length()) {
                            val device = devices.getJSONObject(i)
                            deviceList.append("• ${device.getString("name")}\n")
                            deviceList.append("  UniqueID: ${device.getString("uniqueId")}\n")
                            deviceList.append("  Estado: ${device.optString("status", "N/A")}\n\n")
                        }
                        withContext(Dispatchers.Main) { callback(deviceList.toString()) }
                        Log.d(TAG, "Fetched available devices: $deviceList")
                    }
                } else {
                    val errorMsg = "Error al obtener dispositivos: HTTP ${response.code}, body=${response.body?.string()}"
                    postError(errorMsg)
                    Log.e(TAG, errorMsg)
                }
            } catch (e: Exception) {
                postError("Error al obtener dispositivos: ${e.message}")
                Log.e(TAG, "Error fetching devices", e)
            }
        }
    }

    fun showDeviceSelectionForSafeZone(callback: (Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$BASE_URL/api/user/devices")
                    .get()
                    .addHeader("Authorization", "Bearer ${SecondFragment.JWT_TOKEN}")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val devicesArray = JSONArray(response.body?.string())
                    if (devicesArray.length() == 0) {
                        postError("No se encontraron dispositivos asociados")
                        Log.e(TAG, "No associated devices found")
                        return@launch
                    }

                    // Filtrar dispositivos duplicados por ID
                    val uniqueDevices = mutableMapOf<Int, Pair<String, Int>>()
                    for (i in 0 until devicesArray.length()) {
                        val device = devicesArray.getJSONObject(i)
                        val id = device.getInt("id")
                        val name = device.getString("name")
                        uniqueDevices[id] = Pair(name, id)
                    }

                    val deviceNames = uniqueDevices.values.map { "${it.first} (ID: ${it.second})" }.toTypedArray()
                    val deviceIds = uniqueDevices.values.map { it.second }.toTypedArray()

                    withContext(Dispatchers.Main) {
                        Log.d(TAG, "Showing device selection dialog with ${deviceIds.size} unique devices: ${deviceNames.joinToString()}")
                        MaterialAlertDialogBuilder(_context)
                            .setTitle("Seleccionar vehículo para la zona segura")
                            .setItems(deviceNames) { _, which -> callback(deviceIds[which]) }
                            .setNegativeButton("Cancelar", null)
                            .show()
                    }
                } else {
                    val errorMsg = "Error al cargar dispositivos: HTTP ${response.code}, body=${response.body?.string()}"
                    postError(errorMsg)
                    Log.e(TAG, errorMsg)
                }
            } catch (e: Exception) {
                postError("Error al cargar dispositivos: ${e.message}")
                Log.e(TAG, "Error loading devices", e)
            }
        }
    }

    suspend fun getLastPosition(deviceId: Int): LatLng {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Fetching last position for deviceId=$deviceId")
            val request = Request.Builder()
                .url("$BASE_URL/api/last-position?deviceId=$deviceId")
                .get()
                .addHeader("Authorization", "Bearer ${SecondFragment.JWT_TOKEN}")
                .build()

            val response = withTimeoutOrNull(10000) { client.newCall(request).execute() }
                ?: throw Exception("Timeout al obtener la última posición")
            if (response.isSuccessful) {
                val jsonObject = JSONObject(response.body?.string() ?: "{}")
                val lat = jsonObject.getDouble("latitude")
                val lon = jsonObject.getDouble("longitude")
                Log.d(TAG, "Fetched last position: deviceId=$deviceId, lat=$lat, lon=$lon")
                LatLng(lat, lon)
            } else {
                val errorMsg = when (response.code) {
                    404 -> "No se encontró posición para el dispositivo ID=$deviceId"
                    401 -> "Token de autenticación inválido"
                    else -> "Error al obtener la última posición: HTTP ${response.code}, body=${response.body?.string()}"
                }
                Log.e(TAG, errorMsg)
                throw Exception(errorMsg)
            }
        }
    }

    fun postError(message: String) {
        _error.value = message
    }

    private fun getDeviceId(): Int {
        return _context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            .getInt(DEVICE_ID_PREF, -1)
    }

    private lateinit var _context: Context
    fun setContext(context: Context) {
        _context = context
    }

    fun fetchAssociatedDevices() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$BASE_URL/api/user/devices")
                    .get()
                    .addHeader("Authorization", "Bearer ${SecondFragment.JWT_TOKEN}")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    response.body?.string()?.let { responseBody ->
                        val jsonArray = JSONArray(responseBody)
                        if (jsonArray.length() > 0) {
                            // Tomar el primer dispositivo no duplicado
                            val uniqueDevices = mutableMapOf<Int, JSONObject>()
                            for (i in 0 until jsonArray.length()) {
                                val device = jsonArray.getJSONObject(i)
                                uniqueDevices[device.getInt("id")] = device
                            }
                            val firstDevice = uniqueDevices.values.first()
                            val deviceId = firstDevice.getInt("id")
                            val deviceName = firstDevice.getString("name")
                            _deviceInfo.value = "Vehículo: $deviceName (ID: $deviceId)"
                            _context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE).edit()
                                .putInt(DEVICE_ID_PREF, deviceId)
                                .putString(DEVICE_NAME_PREF, deviceName)
                                .apply()
                            Log.d(TAG, "Fetched associated device: id=$deviceId, name=$deviceName")
                        } else {
                            _deviceInfo.value = "No hay vehículos asociados"
                            Log.d(TAG, "No associated devices found")
                        }
                    }
                } else {
                    val errorMsg = "Error al obtener dispositivos: HTTP ${response.code}, body=${response.body?.string()}"
                    postError(errorMsg)
                    Log.e(TAG, errorMsg)
                }
            } catch (e: Exception) {
                postError("Error de conexión: ${e.message}")
                Log.e(TAG, "Error fetching associated devices", e)
            }
        }
    }
}