package com.zebass.peregrino

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val prefs = applicationContext.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            val deviceId = prefs.getInt(SecondFragment.DEVICE_ID_PREF, -1)
            if (deviceId == -1) return@withContext Result.success()

            val client = OkHttpClient()
            val request = Request.Builder()
                .url("https://carefully-arriving-shepherd.ngrok-free.app/api/safezone?deviceId=$deviceId")
                .get()
                .addHeader("Authorization", "Bearer ${SecondFragment.JWT_TOKEN}")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()?.let { responseBody ->
                    val jsonObject = JSONObject(responseBody)
                    val lat = jsonObject.getDouble("latitude")
                    val lon = jsonObject.getDouble("longitude")
                    prefs.edit()
                        .putString(SecondFragment.PREF_SAFEZONE_LAT, lat.toString())
                        .putString(SecondFragment.PREF_SAFEZONE_LON, lon.toString())
                        .apply()
                }
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}