package com.zebass.peregrino

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.work.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "SyncWorker"
        private const val BASE_URL = "https://carefully-arriving-shepherd.ngrok-free.app"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_JWT_TOKEN = "jwt_token"
        const val KEY_SYNC_TYPE = "sync_type"
        const val SYNC_ALL = "sync_all"
        const val SYNC_SAFEZONE = "sync_safezone"
        const val SYNC_POSITIONS = "sync_positions"
        const val SYNC_DEVICE_STATUS = "sync_device_status"
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MS = 2000L
        const val CACHE_LAST_SYNC = "last_sync_timestamp"
        const val CACHE_SYNC_STATUS = "sync_status"

        fun schedulePeriodicSync(context: Context, intervalMinutes: Long = 15) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                intervalMinutes, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag("periodic_sync")
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    "periodic_sync",
                    ExistingPeriodicWorkPolicy.KEEP,
                    syncRequest
                )
        }

        fun syncNow(
            context: Context,
            syncType: String = SYNC_ALL,
            deviceId: Int? = null,
            jwtToken: String? = null
        ) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val inputData = Data.Builder().apply {
                putString(KEY_SYNC_TYPE, syncType)
                deviceId?.let { putInt(KEY_DEVICE_ID, it) }
                jwtToken?.let { putString(KEY_JWT_TOKEN, it) }
            }.build()

            val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setInputData(inputData)
                .addTag("immediate_sync")
                .addTag("sync_$syncType")
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "immediate_sync_$syncType",
                    ExistingWorkPolicy.REPLACE,
                    syncRequest
                )
        }

        fun cancelAllSync(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelAllWorkByTag("periodic_sync")
                cancelAllWorkByTag("immediate_sync")
            }
        }

        fun getSyncStatus(context: Context): LiveData<List<WorkInfo>> {
            return WorkManager.getInstance(context)
                .getWorkInfosByTagLiveData("periodic_sync")
        }
    }

    private val prefs: SharedPreferences by lazy {
        applicationContext.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    }

    private val syncCache: SharedPreferences by lazy {
        applicationContext.getSharedPreferences("sync_cache", Context.MODE_PRIVATE)
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .connectionPool(ConnectionPool(3, 30, TimeUnit.SECONDS))
            .build()
    }

    private val retryCount = AtomicInteger(0)

    data class SyncResult(
        val success: Boolean,
        val message: String,
        val data: Any? = null
    )

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val deviceId = inputData.getInt(KEY_DEVICE_ID,
                prefs.getInt(SecondFragment.DEVICE_ID_PREF, -1))
            val jwtToken = inputData.getString(KEY_JWT_TOKEN)
                ?: SecondFragment.JWT_TOKEN
                ?: return@withContext Result.failure()
            val syncType = inputData.getString(KEY_SYNC_TYPE) ?: SYNC_ALL

            if (deviceId == -1 || jwtToken.isEmpty()) {
                Log.e(TAG, "ID de dispositivo o token inválido, omitiendo sincronización")
                return@withContext Result.failure()
            }

            Log.d(TAG, "Iniciando sincronización: type=$syncType, deviceId=$deviceId")

            if (!shouldSync(syncType)) {
                Log.d(TAG, "No se necesita sincronización en este momento")
                return@withContext Result.success()
            }

            setProgress(workDataOf("progress" to 0))

            val result = when (syncType) {
                SYNC_SAFEZONE -> syncSafeZone(deviceId, jwtToken)
                SYNC_POSITIONS -> syncPositions(deviceId, jwtToken)
                SYNC_DEVICE_STATUS -> syncDeviceStatus(deviceId, jwtToken)
                SYNC_ALL -> syncAll(deviceId, jwtToken)
                else -> SyncResult(false, "Tipo de sincronización desconocido")
            }

            updateSyncStatus(syncType, result.success)

            return@withContext if (result.success) {
                setProgress(workDataOf("progress" to 100))
                Result.success(workDataOf("message" to result.message))
            } else {
                handleSyncFailure(result.message)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Sincronización fallida con excepción", e)
            return@withContext handleSyncFailure(e.message ?: "Error desconocido")
        }
    }

    private suspend fun syncAll(deviceId: Int, token: String): SyncResult {
        val results = mutableListOf<SyncResult>()

        supervisorScope {
            val jobs = listOf(
                async { syncSafeZone(deviceId, token) },
                async { syncDeviceStatus(deviceId, token) },
                async { syncPositions(deviceId, token) }
            )

            jobs.forEachIndexed { index, deferred ->
                try {
                    val result = deferred.await()
                    results.add(result)
                    setProgress(workDataOf("progress" to ((index + 1) * 33)))
                } catch (e: Exception) {
                    results.add(SyncResult(false, "Fallo: ${e.message}"))
                }
            }
        }

        val successCount = results.count { it.success }
        return SyncResult(
            success = successCount > 0,
            message = "Sincronizados $successCount de ${results.size} elementos"
        )
    }

    private suspend fun syncSafeZone(deviceId: Int, token: String): SyncResult {
        return executeWithRetry("zona segura") {
            if (token.isEmpty()) {
                throw Exception("Token de autenticación inválido")
            }
            val request = Request.Builder()
                .url("$BASE_URL/api/safezone?deviceId=$deviceId")
                .get()
                .addHeader("Authorization", "Bearer $token")
                .addHeader("X-Device-ID", deviceId.toString())
                .tag("safezone_sync")
                .build()

            val response = httpClient.newCall(request).execute()

            if (response.isSuccessful) {
                response.body?.string()?.let { responseBody ->
                    val json = JSONObject(responseBody)
                    val lat = json.getDouble("latitude")
                    val lon = json.getDouble("longitude")

                    prefs.edit {
                        putString(SecondFragment.PREF_SAFEZONE_LAT, lat.toString())
                        putString(SecondFragment.PREF_SAFEZONE_LON, lon.toString())
                    }

                    syncCache.edit {
                        putLong("${CACHE_LAST_SYNC}_safezone", System.currentTimeMillis())
                    }

                    SyncResult(true, "Zona segura sincronizada: ($lat, $lon)")
                } ?: SyncResult(false, "Respuesta vacía del servidor")
            } else if (response.code == 404) {
                prefs.edit {
                    remove(SecondFragment.PREF_SAFEZONE_LAT)
                    remove(SecondFragment.PREF_SAFEZONE_LON)
                }
                SyncResult(true, "No hay zona segura configurada")
            } else {
                val errorMsg = when (response.code) {
                    401 -> "Token de autenticación inválido. Inicia sesión nuevamente."
                    403 -> "Acceso denegado para este dispositivo."
                    else -> "Error del servidor al sincronizar zona segura: ${response.code}"
                }
                throw Exception(errorMsg)
            }
        }
    }

    private suspend fun syncPositions(deviceId: Int, token: String): SyncResult {
        return executeWithRetry("posiciones") {
            if (token.isEmpty()) {
                throw Exception("Token de autenticación inválido")
            }
            val unsyncedPositions = getUnsyncedPositions()

            if (unsyncedPositions.isEmpty()) {
                return@executeWithRetry SyncResult(true, "No hay posiciones para sincronizar")
            }

            val jsonArray = JSONArray()
            unsyncedPositions.forEach { position ->
                jsonArray.put(JSONObject().apply {
                    put("deviceId", deviceId)
                    put("latitude", position.latitude)
                    put("longitude", position.longitude)
                    put("timestamp", position.timestamp)
                    put("speed", position.speed)
                    put("accuracy", position.accuracy)
                })
            }

            val requestBody = jsonArray.toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$BASE_URL/api/positions/batch")
                .post(requestBody)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("X-Device-ID", deviceId.toString())
                .tag("positions_sync")
                .build()

            val response = httpClient.newCall(request).execute()

            if (response.isSuccessful) {
                markPositionsAsSynced(unsyncedPositions)
                syncCache.edit {
                    putLong("${CACHE_LAST_SYNC}_positions", System.currentTimeMillis())
                }
                SyncResult(true, "Sincronizadas ${unsyncedPositions.size} posiciones")
            } else {
                val errorMsg = when (response.code) {
                    401 -> "Token de autenticación inválido. Inicia sesión nuevamente."
                    403 -> "Acceso denegado para este dispositivo."
                    400 -> "Datos de posiciones inválidos."
                    else -> "Fallo al sincronizar posiciones: ${response.code}"
                }
                throw Exception(errorMsg)
            }
        }
    }

    private suspend fun syncDeviceStatus(deviceId: Int, token: String): SyncResult {
        return executeWithRetry("estado del dispositivo") {
            if (token.isEmpty()) {
                throw Exception("Token de autenticación inválido")
            }
            val request = Request.Builder()
                .url("$BASE_URL/api/device/status/$deviceId")
                .get()
                .addHeader("Authorization", "Bearer $token")
                .tag("device_status_sync")
                .build()

            val response = httpClient.newCall(request).execute()

            if (response.isSuccessful) {
                response.body?.string()?.let { responseBody ->
                    val json = JSONObject(responseBody)
                    val isOnline = json.getBoolean("isReceivingData")
                    val recentPositions = json.getInt("recentPositions")
                    val deviceName = json.getJSONObject("device").getString("name")

                    syncCache.edit {
                        putBoolean("device_${deviceId}_online", isOnline)
                        putInt("device_${deviceId}_positions", recentPositions)
                        putString("device_${deviceId}_name", deviceName)
                        putLong("${CACHE_LAST_SYNC}_device_status", System.currentTimeMillis())
                    }

                    SyncResult(
                        true,
                        "Dispositivo $deviceName: ${if (isOnline) "En línea" else "Fuera de línea"} ($recentPositions posiciones)"
                    )
                } ?: SyncResult(false, "Respuesta vacía del servidor")
            } else {
                val errorMsg = when (response.code) {
                    401 -> "Token de autenticación inválido. Inicia sesión nuevamente."
                    404 -> "Dispositivo no encontrado."
                    else -> "Fallo al obtener estado del dispositivo: ${response.code}"
                }
                throw Exception(errorMsg)
            }
        }
    }

    private suspend fun <T> executeWithRetry(
        operation: String,
        block: suspend () -> T
    ): T {
        var lastException: Exception? = null

        repeat(MAX_RETRIES) { attempt ->
            try {
                Log.d(TAG, "Ejecutando $operation (intento ${attempt + 1})")
                return block()
            } catch (e: IOException) {
                lastException = e
                Log.w(TAG, "Error de red en $operation (intento ${attempt + 1}): ${e.message}")
                if (attempt < MAX_RETRIES - 1) {
                    delay(RETRY_DELAY_MS * (attempt + 1))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error inesperado en $operation: ${e.message}", e)
                throw e
            }
        }

        throw lastException ?: Exception("Fallo después de $MAX_RETRIES intentos")
    }

    private fun shouldSync(syncType: String): Boolean {
        if (!isNetworkAvailable()) {
            Log.d(TAG, "Sin conexión de red disponible")
            return false
        }

        val lastSyncKey = "${CACHE_LAST_SYNC}_$syncType"
        val lastSync = syncCache.getLong(lastSyncKey, 0)
        val timeSinceLastSync = System.currentTimeMillis() - lastSync

        val minInterval = when (syncType) {
            SYNC_SAFEZONE -> TimeUnit.MINUTES.toMillis(5)
            SYNC_POSITIONS -> TimeUnit.MINUTES.toMillis(1)
            SYNC_DEVICE_STATUS -> TimeUnit.MINUTES.toMillis(3)
            SYNC_ALL -> TimeUnit.MINUTES.toMillis(15)
            else -> TimeUnit.MINUTES.toMillis(10)
        }

        return timeSinceLastSync >= minInterval
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true ||
                    capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true
        } else {
            val activeNetwork: NetworkInfo? = connectivityManager.activeNetworkInfo
            activeNetwork?.isConnected == true
        }
    }

    private fun updateSyncStatus(syncType: String, success: Boolean) {
        syncCache.edit {
            putBoolean("${CACHE_SYNC_STATUS}_$syncType", success)
            putLong("${CACHE_SYNC_STATUS}_${syncType}_time", System.currentTimeMillis())
            if (success) {
                putInt("sync_failures_$syncType", 0)
            } else {
                val failures = syncCache.getInt("sync_failures_$syncType", 0) + 1
                putInt("sync_failures_$syncType", failures)
            }
        }
    }

    private suspend fun handleSyncFailure(errorMessage: String): Result {
        val currentRetries = retryCount.get()
        return if (currentRetries < MAX_RETRIES && isRetriableError(errorMessage)) {
            retryCount.incrementAndGet()
            Log.w(TAG, "Sincronización fallida, reintentando (intento $currentRetries): $errorMessage")
            Result.retry()
        } else {
            Log.e(TAG, "Sincronización fallida permanentemente: $errorMessage")
            Result.failure(workDataOf("error" to errorMessage))
        }
    }

    private fun isRetriableError(error: String): Boolean {
        return error.contains("timeout", ignoreCase = true) ||
                error.contains("network", ignoreCase = true) ||
                error.contains("connection", ignoreCase = true) ||
                error.contains("50", ignoreCase = true)
    }

    data class UnsyncedPosition(
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long,
        val speed: Float = 0f,
        val accuracy: Float = 0f
    )

    private fun getUnsyncedPositions(): List<UnsyncedPosition> {
        return emptyList()
    }

    private fun markPositionsAsSynced(positions: List<UnsyncedPosition>) {
        // Implementación para marcar posiciones como sincronizadas
    }
}