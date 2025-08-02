package com.zebass.peregrino.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.LatLng
import com.zebass.peregrino.R
import com.zebass.peregrino.SecondFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class TrackingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var deviceId: Int = -1
    private var safeZone: LatLng? = null
    private val GEOFENCE_RADIUS = 15.0
    private val TAG = "TrackingService"

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        Log.d(TAG, "TrackingService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        deviceId = intent?.getIntExtra("deviceId", -1) ?: -1
        val jwtToken = intent?.getStringExtra("jwtToken")
        Log.d(TAG, "onStartCommand: deviceId=$deviceId, jwtToken=$jwtToken")

        startForeground(1, createNotification())
        loadSafeZone()
        if (hasLocationPermission()) {
            startLocationUpdates()
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "tracking_channel",
                "Tracking Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created")
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "tracking_channel")
            .setContentTitle("Peregrino Tracking")
            .setContentText("Monitoring vehicle location")
            .setSmallIcon(R.drawable.ic_vehicle)
            .build()
    }

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) {
            Log.d(TAG, "No location permission, skipping location updates")
            return
        }

        val locationRequest = LocationRequest.create().apply {
            interval = 5000L
            fastestInterval = 2000L
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            smallestDisplacement = 10f
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    val currentLocation = LatLng(location.latitude, location.longitude)
                    safeZone?.let { zone ->
                        val distance = calculateDistance(zone, currentLocation)
                        if (distance > GEOFENCE_RADIUS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            triggerAlarm(distance)
                        }
                    }
                    Log.d(TAG, "Location update: lat=${currentLocation.latitude}, lon=${currentLocation.longitude}")
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null)
            Log.d(TAG, "Started location updates")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in startLocationUpdates", e)
        }
    }

    private fun loadSafeZone() {
        val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
        val lat = prefs.getString(SecondFragment.PREF_SAFEZONE_LAT, null)?.toDoubleOrNull()
        val lon = prefs.getString(SecondFragment.PREF_SAFEZONE_LON, null)?.toDoubleOrNull()
        if (lat != null && lon != null) {
            safeZone = LatLng(lat, lon)
            Log.d(TAG, "Loaded safe zone: lat=$lat, lon=$lon")
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun triggerAlarm(distance: Double) {
        val notification = NotificationCompat.Builder(this, "tracking_channel")
            .setContentTitle("Geofence Alert")
            .setContentText("Vehicle is ${"%.1f".format(distance)} meters away")
            .setSmallIcon(R.drawable.ic_vehicle_alert)
            .build()
        getSystemService(NotificationManager::class.java).notify(2, notification)

        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
        Log.d(TAG, "Geofence alert triggered: distance=$distance")
    }

    private fun calculateDistance(point1: LatLng, point2: LatLng): Double {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            point1.latitude, point1.longitude,
            point2.latitude, point2.longitude,
            results
        )
        return results[0].toDouble()
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        if (hasLocationPermission()) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        serviceScope.cancel()
        Log.d(TAG, "TrackingService destroyed")
    }
}