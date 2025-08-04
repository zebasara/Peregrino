package com.zebass.peregrino

import android.content.Context
import android.content.SharedPreferences
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

        // Work constraints keys
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_JWT_TOKEN = "jwt_token"
        const val KEY_SYNC_TYPE = "sync_type"

        // Sync types
        const val SYNC_ALL = "sync_all"
        const val SYNC_SAFEZONE = "sync_safezone"
        const val SYNC_POSITIONS = "sync_positions"
        const val SYNC_DEVICE_STATUS = "sync_device_status"

        // Retry policy
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MS = 2000L

        // Cache keys
        const val CACHE_LAST_SYNC = "last_sync_timestamp"
        const val CACHE_SYNC_STATUS = "sync_status"
        /**
         * Programar sincronización periódica
         */
        fun schedulePeriodicSync(context: Context, intervalMinutes: Long = 15) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                intervalMinutes, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES // Flex interval
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

        /**
         * Sincronización inmediata
         */
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

        /**
         * Cancelar todas las sincronizaciones
         */
        fun cancelAllSync(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelAllWorkByTag("periodic_sync")
                cancelAllWorkByTag("immediate_sync")
            }
        }

        /**
         * Obtener estado de sincronización
         */
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
            .retryOnConnectionFailure(false) // Manejamos reintentos manualmente
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
            // Obtener parámetros
            val deviceId = inputData.getInt(KEY_DEVICE_ID,
                prefs.getInt(SecondFragment.DEVICE_ID_PREF, -1))
            val jwtToken = inputData.getString(KEY_JWT_TOKEN)
                ?: SecondFragment.JWT_TOKEN
                ?: return@withContext Result.failure()
            val syncType = inputData.getString(KEY_SYNC_TYPE) ?: SYNC_ALL

            if (deviceId == -1) {
                Log.d(TAG, "No device ID, skipping sync")
                return@withContext Result.success()
            }

            Log.d(TAG, "Starting sync: type=$syncType, deviceId=$deviceId")

            // Verificar si necesitamos sincronizar
            if (!shouldSync(syncType)) {
                Log.d(TAG, "Sync not needed at this time")
                return@withContext Result.success()
            }

            // Progress tracking
            setProgress(workDataOf("progress" to 0))

            // Ejecutar sincronización según tipo
            val result = when (syncType) {
                SYNC_SAFEZONE -> syncSafeZone(deviceId, jwtToken)
                SYNC_POSITIONS -> syncPositions(deviceId, jwtToken)
                SYNC_DEVICE_STATUS -> syncDeviceStatus(deviceId, jwtToken)
                SYNC_ALL -> syncAll(deviceId, jwtToken)
                else -> SyncResult(false, "Unknown sync type")
            }

            // Actualizar estado
            updateSyncStatus(syncType, result.success)

            return@withContext if (result.success) {
                setProgress(workDataOf("progress" to 100))
                Result.success(workDataOf("message" to result.message))
            } else {
                handleSyncFailure(result.message)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Sync failed with exception", e)
            return@withContext handleSyncFailure(e.message ?: "Unknown error")
        }
    }

    // ============ SYNC OPERATIONS ============

    private suspend fun syncAll(deviceId: Int, token: String): SyncResult {
        val results = mutableListOf<SyncResult>()

        // Sincronizar en paralelo con supervisión
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
                    results.add(SyncResult(false, "Failed: ${e.message}"))
                }
            }
        }

        val successCount = results.count { it.success }
        return SyncResult(
            success = successCount > 0,
            message = "Synced $successCount of ${results.size} items"
        )
    }

    private suspend fun syncSafeZone(deviceId: Int, token: String): SyncResult {
        return executeWithRetry("safe zone") {
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

                    // Guardar en preferencias
                    prefs.edit {
                        putString(SecondFragment.PREF_SAFEZONE_LAT, lat.toString())
                        putString(SecondFragment.PREF_SAFEZONE_LON, lon.toString())
                    }

                    // Cache timestamp
                    syncCache.edit {
                        putLong("${CACHE_LAST_SYNC}_safezone", System.currentTimeMillis())
                    }

                    SyncResult(true, "Safe zone synced: ($lat, $lon)")
                } ?: SyncResult(false, "Empty response")
            } else if (response.code == 404) {
                // No hay zona segura configurada
                prefs.edit {
                    remove(SecondFragment.PREF_SAFEZONE_LAT)
                    remove(SecondFragment.PREF_SAFEZONE_LON)
                }
                SyncResult(true, "No safe zone configured")
            } else {
                SyncResult(false, "Server error: ${response.code}")
            }
        }
    }

    private suspend fun syncPositions(deviceId: Int, token: String): SyncResult {
        return executeWithRetry("positions") {
            // Obtener posiciones no sincronizadas (si las almacenamos localmente)
            val unsyncedPositions = getUnsyncedPositions()

            if (unsyncedPositions.isEmpty()) {
                return@executeWithRetry SyncResult(true, "No positions to sync")
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
                // Marcar posiciones como sincronizadas
                markPositionsAsSynced(unsyncedPositions)

                syncCache.edit {
                    putLong("${CACHE_LAST_SYNC}_positions", System.currentTimeMillis())
                }

                SyncResult(true, "Synced ${unsyncedPositions.size} positions")
            } else {
                SyncResult(false, "Failed to sync positions: ${response.code}")
            }
        }
    }

    private suspend fun syncDeviceStatus(deviceId: Int, token: String): SyncResult {
        return executeWithRetry("device status") {
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

                    // Cache device status
                    syncCache.edit {
                        putBoolean("device_${deviceId}_online", isOnline)
                        putInt("device_${deviceId}_positions", recentPositions)
                        putString("device_${deviceId}_name", deviceName)
                        putLong("${CACHE_LAST_SYNC}_device_status", System.currentTimeMillis())
                    }

                    SyncResult(
                        true,
                        "Device $deviceName: ${if (isOnline) "Online" else "Offline"} ($recentPositions positions)"
                    )
                } ?: SyncResult(false, "Empty response")
            } else {
                SyncResult(false, "Failed to get device status: ${response.code}")
            }
        }
    }

    // ============ HELPER FUNCTIONS ============

    private suspend fun <T> executeWithRetry(
        operation: String,
        block: suspend () -> T
    ): T {
        var lastException: Exception? = null

        repeat(MAX_RETRIES) { attempt ->
            try {
                Log.d(TAG, "Executing $operation (attempt ${attempt + 1})")
                return block()
            } catch (e: IOException) {
                lastException = e
                Log.w(TAG, "Network error in $operation (attempt ${attempt + 1}): ${e.message}")

                if (attempt < MAX_RETRIES - 1) {
                    delay(RETRY_DELAY_MS * (attempt + 1)) // Exponential backoff
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error in $operation", e)
                throw e
            }
        }

        throw lastException ?: Exception("Failed after $MAX_RETRIES attempts")
    }

    private fun shouldSync(syncType: String): Boolean {
        // Verificar conectividad
        if (!isNetworkAvailable()) {
            Log.d(TAG, "No network connection available")
            return false
        }

        // Verificar última sincronización
        val lastSyncKey = "${CACHE_LAST_SYNC}_$syncType"
        val lastSync = syncCache.getLong(lastSyncKey, 0)
        val timeSinceLastSync = System.currentTimeMillis() - lastSync

        // Diferentes intervalos según el tipo
        val minInterval = when (syncType) {
            SYNC_SAFEZONE -> TimeUnit.MINUTES.toMillis(5)     // 5 minutos
            SYNC_POSITIONS -> TimeUnit.MINUTES.toMillis(1)    // 1 minuto
            SYNC_DEVICE_STATUS -> TimeUnit.MINUTES.toMillis(3) // 3 minutos
            SYNC_ALL -> TimeUnit.MINUTES.toMillis(15)         // 15 minutos
            else -> TimeUnit.MINUTES.toMillis(10)
        }

        return timeSinceLastSync >= minInterval
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager

        val network = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivityManager.activeNetwork ?: return false
        } else {
            TODO("VERSION.SDK_INT < M")
        }
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
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
            Log.w(TAG, "Sync failed, will retry (attempt $currentRetries): $errorMessage")
            Result.retry()
        } else {
            Log.e(TAG, "Sync failed permanently: $errorMessage")
            Result.failure(workDataOf("error" to errorMessage))
        }
    }

    private fun isRetriableError(error: String): Boolean {
        return error.contains("timeout", ignoreCase = true) ||
                error.contains("network", ignoreCase = true) ||
                error.contains("connection", ignoreCase = true) ||
                error.contains("50", ignoreCase = true) // Server errors 500-599
    }

    // ============ POSITION MANAGEMENT (PLACEHOLDER) ============

    data class UnsyncedPosition(
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long,
        val speed: Float = 0f,
        val accuracy: Float = 0f
    )

    private fun getUnsyncedPositions(): List<UnsyncedPosition> {
        // Esta función debería recuperar posiciones almacenadas localmente
        // que no se han sincronizado con el servidor
        // Por ahora retorna lista vacía
        return emptyList()
    }

    private fun markPositionsAsSynced(positions: List<UnsyncedPosition>) {
        // Marcar las posiciones como sincronizadas en el almacenamiento local
        // Implementación dependería de cómo se almacenan las posiciones
    }
}