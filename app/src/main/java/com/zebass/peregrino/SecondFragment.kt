package com.zebass.peregrino

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.zebass.peregrino.databinding.FragmentSecondBinding
import com.zebass.peregrino.service.AlertManager
import com.zebass.peregrino.service.TrackingService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference


class SecondFragment : Fragment() {

    // ============ BINDING Y PROPIEDADES CORE ============
    private var _binding: FragmentSecondBinding? = null
    private val binding get() = _binding ?: run {
        Log.w(TAG, "Binding accessed when null - fragment may be destroyed")
        throw IllegalStateException("Fragment binding is null - fragment destroyed")
    }
    private lateinit var sharedPreferences: SharedPreferences
    private val args: SecondFragmentArgs by navArgs()
    private val viewModel: TrackingViewModel by viewModels()

    // ============ MAPA Y OVERLAYS ============
    private var map: MapView? = null
    private val vehicleMarker = AtomicReference<Marker?>(null)
    private val safeZonePolygon = AtomicReference<Polygon?>(null)
    private var myLocationOverlay: MyLocationNewOverlay? = null

    // ============ WEBSOCKET Y NETWORKING ============
    private var webSocket: WebSocket? = null
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    // ============ ESTADO Y CONTROL ============
    private val shouldReconnect = AtomicBoolean(true)
    private val isMapReady = AtomicBoolean(false)
    private val lastPositionUpdate = AtomicLong(0)
    private val lastStatusCheck = AtomicLong(0)
    private val handler = Handler(Looper.getMainLooper())
    private var statusCheckRunnable: Runnable? = null
    private var reconnectRunnable: Runnable? = null

    // ============ NUEVAS VARIABLES PARA SEGUIMIENTO ============
    private val isFollowingVehicle = AtomicBoolean(false)
    private var currentVehicleState = VehicleState.NORMAL
    private var safeZoneCenter: GeoPoint? = null

    // ============ ENUMS Y CONSTANTES ============
    enum class VehicleState {
        NORMAL,        // Verde - funcionamiento normal
        IN_SAFE_ZONE,  // Azul - dentro de zona segura
        OUTSIDE_SAFE_ZONE // Rojo - fuera de zona segura
    }


    // ============ CACHE LOCAL ULTRA-RÁPIDO ============
    private data class LocalCache<T>(
        val data: AtomicReference<T?> = AtomicReference(null),
        val timestamp: AtomicLong = AtomicLong(0),
        val ttl: Long = 30_000L
    ) {
        fun set(value: T) {
            data.set(value)
            timestamp.set(System.currentTimeMillis())
        }

        fun get(): T? {
            val cached = data.get()
            return if (cached != null && (System.currentTimeMillis() - timestamp.get()) < ttl) {
                cached
            } else null
        }

        fun clear() {
            data.set(null)
            timestamp.set(0)
        }
    }

    private val deviceInfoCache = LocalCache<String>(ttl = 60_000L)
    private val safeZoneCache = LocalCache<GeoPoint>(ttl = 120_000L)
    private val lastPositionCache = LocalCache<GeoPoint>(ttl = 5_000L)

    // ----ALARMA-----
    private lateinit var alertManager: AlertManager

    companion object {
        var JWT_TOKEN: String? = ""
        private var safeZone: GeoPoint? = null

        // ============ CONSTANTES CORREGIDAS ============
        const val PREF_SAFEZONE_LAT = "safezone_lat"
        const val PREF_SAFEZONE_LON = "safezone_lon"
        const val DEVICE_ID_PREF = "associated_device_id"
        const val DEVICE_NAME_PREF = "associated_device_name"
        const val DEVICE_UNIQUE_ID_PREF = "associated_device_unique_id"
        const val GEOFENCE_RADIUS = 15.0
        const val RECONNECT_DELAY = 5000L
        const val STATUS_CHECK_INTERVAL = 30000L
        const val POSITION_UPDATE_THROTTLE = 1000L
        const val TAG = "SecondFragment"

        // ============ NUEVAS CONSTANTES PARA SEGUIMIENTO ============
        const val FOLLOW_ZOOM_LEVEL = 18.0
        const val OVERVIEW_ZOOM_LEVEL = 12.0
        const val TANDIL_LAT = -37.32167
        const val TANDIL_LON = -59.13316
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            enableMyLocation()
        } else {
            showSnackbar("Permiso de ubicación denegado", Snackbar.LENGTH_LONG)
            Log.e(TAG, "Permiso de ubicación denegado")
        }
    }

    // ============ LIFECYCLE OPTIMIZADO ============

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        // ===== CONFIGURACIÓN OSMDroid ANTES DE CREAR EL MAPA =====
        val ctx = requireContext()

        // Configurar cache de tiles más grande para fluidez
        Configuration.getInstance().load(ctx, ctx.getSharedPreferences("osmdroid", 0))
        Configuration.getInstance().apply {
            // ===== CACHE OPTIMIZADO =====
            tileFileSystemCacheMaxBytes = 100L * 1024L * 1024L  // 100MB cache
            tileFileSystemCacheTrimBytes = 80L * 1024L * 1024L   // Limpiar a 80MB

            // ===== CONFIGURACIÓN DE THREADS =====
            tileDownloadThreads = 8                              // Más threads para descargas
            tileFileSystemThreads = 4                            // Threads para acceso a archivos

            // ===== CONFIGURACIÓN DE RED =====
            userAgentValue = "PeregrinoGPS/1.0"

            // ===== CONFIGURACIÓN DE DEBUG =====
            isDebugMode = false                                  // Sin debug para mejor rendimiento
            isDebugTileProviders = false
        }

        _binding = FragmentSecondBinding.inflate(inflater, container, false)
        sharedPreferences = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        viewModel.setContext(requireContext())
        return binding.root
    }

    private fun restoreDeviceFromPreferences(): Boolean {
        val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)

        val uniqueId = prefs.getString(DEVICE_UNIQUE_ID_PREF, null)
        val deviceName = prefs.getString(DEVICE_NAME_PREF, null)
        val deviceId = prefs.getInt(DEVICE_ID_PREF, -1)

        if (uniqueId != null && deviceName != null && deviceId != -1) {
            Log.d(TAG, "✅ Device restored from preferences: uniqueId=$uniqueId, name=$deviceName")

            // Actualizar UI inmediatamente
            updateStatusUI("📱 $deviceName", android.R.color.holo_green_dark)

            return true
        } else {
            Log.w(TAG, "❌ No device found in preferences")
            return false
        }
    }
    // Fix en onViewCreated para mejor inicialización
    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // ✅ LIMPIAR CACHES CORRUPTOS AL INICIO
        clearCorruptedCaches()

        // ✅ RESTAURAR DISPOSITIVO INMEDIATAMENTE
        if (!restoreDeviceFromPreferences()) {
            updateStatusUI("Asocia un vehículo para comenzar", android.R.color.darker_gray)
        }

        val userEmail = args.userEmail
        JWT_TOKEN = args.jwtToken
        Log.d(TAG, "🚀 onViewCreated: userEmail=$userEmail, hasToken=${!JWT_TOKEN.isNullOrEmpty()}")

        // UI Setup inmediato
        binding.textUser.text = "Bienvenido: $userEmail"

        // Inicialización secuencial para evitar race conditions
        lifecycleScope.launch {
            try {
                // 1. Setup UI first
                setupUI()

                // 2. Setup map
                setupMap()

                // 3. Setup ViewModel observers
                observeViewModel()

                // 4. Check and load device info
                delay(100) // Small delay
                loadDeviceInfo()

                // 5. If device exists, start services
                delay(200) // Another small delay
                if (hasAssociatedDevice()) {
                    Log.d(TAG, "✅ Device found, starting services...")
                    viewModel.forceDataRefresh()
                    startServices()
                } else {
                    Log.d(TAG, "⚠️ No device associated, skipping service startup")
                    updateStatusUI("⚠️ Asocia un dispositivo para comenzar", android.R.color.holo_orange_dark)
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in onViewCreated", e)
                showSnackbar("❌ Error de inicialización: ${e.message}", Snackbar.LENGTH_LONG)
            }
        }
        alertManager = AlertManager(requireContext())
        // Configurar logout
        setupLogoutButton()

        // Manejar back button
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            logout() // Logout cuando presione back
        }
        // En SecondFragment, agrega este botón de test

    }

    private fun clearCorruptedCaches() {
        try {
            // Verificar integridad de datos
            val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            val uniqueId = prefs.getString(DEVICE_UNIQUE_ID_PREF, null)
            val deviceName = prefs.getString(DEVICE_NAME_PREF, null)
            val jwtToken = prefs.getString("jwt_token", null)

            if (uniqueId != null && deviceName != null && jwtToken != null) {
                Log.d(TAG, "✅ Cache integrity OK")
                return
            }

            if (uniqueId == null || deviceName == null) {
                Log.w(TAG, "🧹 Incomplete device data detected, forcing refresh")

                // Limpiar todo para evitar estados inconsistentes
                prefs.edit {
                    remove(DEVICE_ID_PREF)
                    remove(DEVICE_NAME_PREF)
                    remove(DEVICE_UNIQUE_ID_PREF)
                    remove(PREF_SAFEZONE_LAT)
                    remove(PREF_SAFEZONE_LON)
                }

                // Limpiar caches locales
                deviceInfoCache.clear()
                safeZoneCache.clear()
                lastPositionCache.clear()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error checking cache integrity: ${e.message}")
        }
    }

    // Nueva función para debug - verificar estado del dispositivo
    private fun debugDeviceState() {
        Log.d(TAG, "=== DEBUG DEVICE STATE ===")
        Log.d(TAG, "JWT_TOKEN: ${if (JWT_TOKEN.isNullOrEmpty()) "EMPTY" else "OK"}")
        Log.d(TAG, "hasAssociatedDevice: ${hasAssociatedDevice()}")

        val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)
        val deviceName = sharedPreferences.getString(DEVICE_NAME_PREF, null)
        val deviceUniqueId = sharedPreferences.getString(DEVICE_UNIQUE_ID_PREF, null)

        Log.d(TAG, "Device ID (internal): $deviceId")
        Log.d(TAG, "Device Name: $deviceName")
        Log.d(TAG, "Device UniqueID: $deviceUniqueId")
        Log.d(TAG, "WebSocket connected: ${webSocket != null}")
        Log.d(TAG, "WebSocket ready state: ${webSocket?.let { it.hashCode() }}")
        Log.d(TAG, "Map ready: ${isMapReady.get()}")
        Log.d(TAG, "Vehicle marker exists: ${vehicleMarker.get() != null}")
        Log.d(TAG, "=========================")
    }
    // Agregar este debug call en checkDeviceStatus
    @RequiresApi(Build.VERSION_CODES.O)
    private fun checkDeviceStatus() {
        debugDeviceState()

        // ✅ USAR SIEMPRE EL UNIQUE_ID REAL
        val deviceUniqueId = sharedPreferences.getString(DEVICE_UNIQUE_ID_PREF, null)
        if (deviceUniqueId == null) {
            showSnackbar("❌ No hay dispositivo asociado", Snackbar.LENGTH_SHORT)
            updateStatusUI("❌ No hay dispositivo asociado", android.R.color.holo_red_dark)
            return
        }

        // ✅ VALIDAR QUE ES UN ID VÁLIDO, NO UN MENSAJE DE ERROR
        if (deviceUniqueId.contains("offline") || deviceUniqueId.contains("error")) {
            Log.e(TAG, "❌ Invalid deviceUniqueId detected: $deviceUniqueId")
            showSnackbar("❌ ID de dispositivo corrupto. Re-asocia el vehículo.", Snackbar.LENGTH_LONG)
            clearDevicePreferences()
            return
        }

        Log.d(TAG, "🔍 Checking device status for REAL uniqueId: $deviceUniqueId")

        // Throttling para evitar spam
        val now = System.currentTimeMillis()
        if (now - lastStatusCheck.get() < 5000L) {
            showSnackbar("⏳ Espera antes de verificar nuevamente", Snackbar.LENGTH_SHORT)
            return
        }
        lastStatusCheck.set(now)

        // UI feedback inmediato
        updateStatusUI("🔄 Verificando estado del dispositivo...", android.R.color.darker_gray)

        // ✅ USAR UNIQUE_ID VALIDADO
        viewModel.checkDeviceStatus(deviceUniqueId) { isOnline, message ->
            val statusIcon = if (isOnline) "🟢" else "🔴"
            val displayMessage = "$statusIcon $message"

            showSnackbar(
                displayMessage,
                if (isOnline) Snackbar.LENGTH_SHORT else Snackbar.LENGTH_LONG
            )

            updateStatusUI(
                displayMessage,
                if (isOnline) android.R.color.holo_green_dark else android.R.color.holo_red_dark
            )

            if (!isOnline) {
                handler.postDelayed({ showOfflineHelpDialog() }, 2000)
            }

            Log.d(TAG, "Device status check completed: $displayMessage")
        }
    }

    // ✅ AGREGAR FUNCIÓN PARA LIMPIAR PREFERENCIAS CORRUPTAS
    private fun clearDevicePreferences() {
        sharedPreferences.edit {
            remove(DEVICE_ID_PREF)
            remove(DEVICE_NAME_PREF)
            remove(DEVICE_UNIQUE_ID_PREF)
            remove(PREF_SAFEZONE_LAT)
            remove(PREF_SAFEZONE_LON)
        }

        deviceInfoCache.clear()
        safeZoneCache.clear()
        lastPositionCache.clear()

        updateStatusUI("⚠️ Dispositivo desasociado. Vuelve a asociar tu vehículo.", android.R.color.holo_orange_dark)
    }

    // ============ SETUP FUNCTIONS OPTIMIZADAS ============

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun setupUI() = withContext(Dispatchers.Main) {
        binding.buttonLogout.setOnClickListener {
            logout()
        }
        setupButtons()
    }
    @SuppressLint("MissingPermission")
    private fun enableMyLocation() {
        if (!hasLocationPermission()) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }

        if (myLocationOverlay == null) {
            myLocationOverlay = MyLocationNewOverlay(
                GpsMyLocationProvider(requireContext()),
                map
            ).apply {
                enableMyLocation()
                disableFollowLocation()

                val drawable = ContextCompat.getDrawable(
                    requireContext(),
                    android.R.drawable.ic_menu_mylocation
                )
                val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: run {
                    val bitmap = Bitmap.createBitmap(
                        drawable.intrinsicWidth,
                        drawable.intrinsicHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    val canvas = Canvas(bitmap)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    bitmap
                }
                setPersonIcon(bitmap)

                setDrawAccuracyEnabled(true)
            }

            map?.overlays?.add(0, myLocationOverlay)
            Log.d(TAG, "📍 Personal location overlay configurado (sin auto-seguimiento)")
        }

        map?.invalidate()
        Log.d(TAG, "✅ Ubicación personal habilitada - navegación libre mantenida")
    }



    @RequiresApi(Build.VERSION_CODES.O)
    private fun setupButtons() {
        with(binding) {
            // Botón de ubicación con seguimiento del vehículo
            buttonMyLocation.setOnClickListener {
                toggleVehicleFollowing()
            }

            // ✅ NUEVO: Long click para refrescar posición
            buttonMyLocation.setOnLongClickListener {
                performForceRefresh(getDeviceUniqueId() ?: return@setOnLongClickListener false)
                true
            }

            buttonZonaSegura.setOnClickListener { handleSafeZoneButton() }
            buttonZonaSeguraMain.setOnClickListener { handleSafeZoneButton() }
            buttonAssociateDevice.setOnClickListener { showAssociateDeviceDialog() }
            buttonDeviceStatus.setOnClickListener { checkDeviceStatus() }
            buttonShowConfig.setOnClickListener { showTraccarClientConfig() }
            binding.buttonTestWS.setOnClickListener {
                Log.d(TAG, "🧪 Testing WebSocket connection...")
                if (webSocket != null) {
                    val testMessage = JSONObject().apply {
                        put("type", "TEST_CONNECTION")
                        put("deviceId", getDeviceUniqueId())
                        put("timestamp", System.currentTimeMillis())
                    }
                    webSocket?.send(testMessage.toString())
                    showSnackbar("📤 Test message sent", Snackbar.LENGTH_SHORT)
                } else {
                    showSnackbar("❌ WebSocket not connected", Snackbar.LENGTH_SHORT)
                    setupWebSocket() // Intentar reconectar
                }
            }

            // ✅ NUEVO: Long press para limpiar posiciones antiguas
            buttonDeviceStatus.setOnLongClickListener {
                clearOldPositionsDialog()
                true
            }

            // ✅ NUEVO: Ayuda sobre solución de problemas
            binding.buttonShowConfig.setOnLongClickListener {
                showPositionTroubleshootingDialog()
                true
            }

            // Actualizar texto del botón para reflejar nueva funcionalidad
            updateFollowButtonText()

            // Ocultar elementos no necesarios
            buttonDescargarOffline.visibility = View.GONE
            progressBarDownload.visibility = View.GONE
        }
        Log.d(TAG, "✅ Botones configurados con funciones de limpieza de posiciones antiguas")
    }

    // ============ ACTUALIZACIÓN DINÁMICA DE ICONOS ============
    private fun updateMarkerIcon(marker: Marker, state: VehicleState) {
        // Crear bitmap personalizado con forma y color
        val size = 48 // Tamaño en píxeles
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Color según estado
        val color = when (state) {
            VehicleState.NORMAL -> Color.GREEN
            VehicleState.IN_SAFE_ZONE -> Color.BLUE
            VehicleState.OUTSIDE_SAFE_ZONE -> Color.RED
        }

        // Dibujar círculo de fondo blanco
        paint.color = Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2, paint)

        // Dibujar círculo principal de color
        paint.color = color
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 6, paint)

        // Dibujar punto central más oscuro
        paint.color = when (state) {
            VehicleState.NORMAL -> Color.parseColor("#006400") // Verde oscuro
            VehicleState.IN_SAFE_ZONE -> Color.parseColor("#000080") // Azul oscuro
            VehicleState.OUTSIDE_SAFE_ZONE -> Color.parseColor("#8B0000") // Rojo oscuro
        }
        canvas.drawCircle(size / 2f, size / 2f, 8f, paint)

        // Aplicar el bitmap al marcador
        marker.icon = BitmapDrawable(requireContext().resources, bitmap)

        Log.d(TAG, "🎨 Updated marker icon for state: $state with custom bitmap")
    }
    // ============ NUEVA FUNCIÓN PARA ALTERNAR SEGUIMIENTO ============
    private fun toggleVehicleFollowing() {
        if (vehicleMarker.get() == null) {
            showSnackbar("⚠️ No hay vehículo para seguir", Snackbar.LENGTH_SHORT)
            return
        }

        val wasFollowing = isFollowingVehicle.get()
        isFollowingVehicle.set(!wasFollowing)

        if (isFollowingVehicle.get()) {
            // Activar seguimiento - centrar en vehículo
            vehicleMarker.get()?.position?.let { position ->
                map?.controller?.animateTo(position, FOLLOW_ZOOM_LEVEL, 1000L)
                showSnackbar("🎯 Siguiendo al vehículo", Snackbar.LENGTH_SHORT)
                Log.d(TAG, "🎯 Vehicle following activated")
            }
        } else {
            // Desactivar seguimiento - vista general de Tandil
            val tandilPosition = GeoPoint(TANDIL_LAT, TANDIL_LON)
            map?.controller?.animateTo(tandilPosition, OVERVIEW_ZOOM_LEVEL, 1000L)
            showSnackbar("🗺️ Vista general activada", Snackbar.LENGTH_SHORT)
            Log.d(TAG, "🗺️ Vehicle following deactivated - overview mode")
        }

        updateFollowButtonText()
    }

    private fun updateFollowButtonText() {
        binding.buttonMyLocation.text = if (isFollowingVehicle.get()) {
            "🎯 Siguiendo"
        } else {
            "📍 Ubicación"
        }
    }
    // AGREGAR esta función nueva en SecondFragment.kt
    private fun refreshJWTToken() {
        val userEmail = args.userEmail

        // ✅ INTENTAR OBTENER PASSWORD DE PREFERENCIAS
        val userPassword = sharedPreferences.getString("user_password", null)

        if (userPassword == null) {
            Log.e(TAG, "❌ No password saved - cannot refresh token")
            forceLogout()
            return
        }

        Log.d(TAG, "🔄 Refreshing JWT token for user: $userEmail")

        lifecycleScope.launch {
            try {
                val json = JSONObject().apply {
                    put("email", userEmail)
                    put("password", userPassword)
                }

                val requestBody = json.toString().toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("https://carefully-arriving-shepherd.ngrok-free.app/api/auth/login")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    val responseJson = JSONObject(responseBody ?: "")
                    val newToken = responseJson.getString("token")

                    // ✅ ACTUALIZAR TOKEN GLOBALMENTE
                    JWT_TOKEN = newToken

                    // ✅ GUARDAR EN PREFERENCIAS
                    sharedPreferences.edit {
                        putString("jwt_token", newToken)
                    }

                    Log.d(TAG, "✅ JWT token refreshed successfully")
                    showSnackbar("✅ Token renovado automáticamente", Snackbar.LENGTH_SHORT)

                    // ✅ REINICIAR SERVICIOS CON NUEVO TOKEN
                    setupWebSocket()

                } else {
                    Log.e(TAG, "❌ Token refresh failed: ${response.code}")
                    forceLogout()
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to refresh token: ${e.message}")
                forceLogout()
            }
        }
    }
    private fun showSnackbar(message: String, duration: Int) {
        if (!isFragmentValid()) {
            Log.w(TAG, "⚠️ Fragment not valid for snackbar, message was: $message")
            // Fallback a Toast solo si es absolutamente necesario
            try {
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            } catch (toastError: Exception) {
                Log.e(TAG, "❌ Error showing toast: ${toastError.message}")
            }
            return
        }

        try {
            // Crear el Snackbar en el CoordinatorLayout raíz
            val snackbar = Snackbar.make(
                requireActivity().findViewById(R.id.coordinator_layout),
                message,
                duration
            )

            // Ajustar la elevación para que esté por encima de todos los elementos
            snackbar.view.elevation = 30f // Elevación alta para superar CardViews (16dp) y FABs (8dp)

            // Opcional: Personalizar el diseño del Snackbar para mejor visibilidad
            snackbar.view.setBackgroundResource(R.drawable.snackbar_background)
            val textView = snackbar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
            textView.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
            textView.textSize = 14f
            textView.maxLines = 3

            // Ajustar márgenes para que no se superponga con el panel inferior
            val params = snackbar.view.layoutParams as CoordinatorLayout.LayoutParams
            params.setMargins(16, 16, 16, 160) // Margen inferior para evitar el panel inferior
            params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            snackbar.view.layoutParams = params

            // Mostrar el Snackbar
            snackbar.show()
            Log.d(TAG, "📱 Snackbar shown with high elevation: $message")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing snackbar: ${e.message}")
            // Fallback a Toast
            try {
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            } catch (toastError: Exception) {
                Log.e(TAG, "❌ Error showing toast: ${toastError.message}")
            }
        }
    }

    // ============ FIX 10: Nueva función para centrar en ubicación personal ============
    private suspend fun setupMap() = withContext(Dispatchers.Main) {
        map = binding.mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
            controller.setCenter(GeoPoint(-37.32167, -59.13316))

            isTilesScaledToDpi = true
            setUseDataConnection(true)

            isFlingEnabled = true
            setMultiTouchControls(true)
            setBuiltInZoomControls(false)

            minZoomLevel = 3.0
            maxZoomLevel = 21.0

            isHorizontalMapRepetitionEnabled = false
            isVerticalMapRepetitionEnabled = false
            setScrollableAreaLimitDouble(null)

            overlayManager.tilesOverlay.setLoadingBackgroundColor(android.graphics.Color.TRANSPARENT)
            overlayManager.tilesOverlay.setLoadingLineColor(android.graphics.Color.TRANSPARENT)

            setMapListener(object : org.osmdroid.events.MapListener {
                override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                    return true
                }

                override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                    return true
                }
            })
        }

        isMapReady.set(true)
        Log.d(TAG, "🗺️ Mapa configurado para navegación SÚPER FLUIDA")
        Log.d(TAG, "📏 Zoom range: ${map?.minZoomLevel} - ${map?.maxZoomLevel}")
        Log.d(TAG, "💨 Fling enabled: ${map?.isFlingEnabled}")

        loadSafeZone()
        if (hasAssociatedDevice()) {
            viewModel.fetchSafeZoneFromServer()
        }

        if (hasLocationPermission()) {
            enableMyLocation()
        }
    }


    // Fix en hasAssociatedDevice para mejor validation
    private fun hasAssociatedDevice(): Boolean {
        val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val uniqueId = prefs.getString(DEVICE_UNIQUE_ID_PREF, null)
        val deviceName = prefs.getString(DEVICE_NAME_PREF, null)

        // ✅ VALIDAR QUE NO SEAN MENSAJES DE ERROR
        val hasValidDevice = !uniqueId.isNullOrEmpty() &&
                !deviceName.isNullOrEmpty() &&
                !uniqueId.contains("offline") &&
                !uniqueId.contains("error") &&
                !uniqueId.contains("Traccar")

        if (hasValidDevice) {
            Log.d(TAG, "✅ Valid device found: uniqueId=$uniqueId, name=$deviceName")
        } else {
            Log.w(TAG, "❌ No valid device found or corrupted data: uniqueId=$uniqueId")
            if (!uniqueId.isNullOrEmpty() && (uniqueId.contains("offline") || uniqueId.contains("error"))) {
                // Limpiar datos corruptos
                clearDevicePreferences()
            }
        }

        return hasValidDevice
    }
    @RequiresApi(Build.VERSION_CODES.O)
    private fun observeViewModel() {
        // Observar posición del vehículo
        lifecycleScope.launch {
            viewModel.vehiclePosition.collectLatest { position ->
                Log.d(TAG, "🔍 Vehicle position observer triggered: $position")
                position?.let {
                    if (shouldUpdatePosition()) {
                        Log.d(TAG, "🚗 Vehicle position from ViewModel: deviceId=${it.first}, position=${it.second}")
                        updateVehiclePosition(it.first, GeoPoint(it.second.latitude, it.second.longitude))
                        lastPositionUpdate.set(System.currentTimeMillis())
                    } else {
                        Log.d(TAG, "⏳ Vehicle position update throttled")
                    }
                } ?: run {
                    Log.d(TAG, "❌ Vehicle position is null")
                }
            }
        }

        // CRÍTICO: Observar zona segura con manejo de eliminación mejorado
        lifecycleScope.launch {
            viewModel.safeZone.collectLatest { zone ->
                Log.d(TAG, "🛡️ Safe zone update from ViewModel: $zone")

                if (zone != null) {
                    val geoPoint = GeoPoint(zone.latitude, zone.longitude)
                    safeZone = geoPoint
                    safeZoneCache.set(geoPoint)
                    updateSafeZoneUI(geoPoint)
                    Log.d(TAG, "✅ Safe zone activated")
                } else {
                    // ZONA SEGURA ELIMINADA - limpiar completamente
                    Log.d(TAG, "🗑️ Safe zone deleted - clearing UI completely")

                    safeZone = null
                    safeZoneCache.clear()

                    // Remover polígono del mapa
                    safeZonePolygon.get()?.let {
                        map?.overlays?.remove(it)
                        Log.d(TAG, "🗑️ Removed safe zone polygon from map")
                    }
                    safeZonePolygon.set(null)

                    // FORZAR botón inactivo
                    updateSafeZoneButton(false)

                    // Limpiar preferencias
                    sharedPreferences.edit {
                        remove(PREF_SAFEZONE_LAT)
                        remove(PREF_SAFEZONE_LON)
                    }

                    map?.postInvalidate()
                    Log.d(TAG, "✅ Safe zone UI cleared completely")
                }
            }
        }

        // Observar errores
        lifecycleScope.launch {
            viewModel.error.collectLatest { error ->
                error?.let {
                    if (it.contains("Posición muy antigua ignorada")) {
                        // ✅ MOSTRAR DIÁLOGO AUTOMÁTICO PARA LIMPIAR
                        handler.postDelayed({
                            if (isAdded) {
                                MaterialAlertDialogBuilder(requireContext())
                                    .setTitle("⚠️ Posición Antigua Detectada")
                                    .setMessage("Se detectó una posición muy antigua. ¿Quieres limpiar el caché y obtener datos frescos?")
                                    .setPositiveButton("🧹 Sí, Limpiar") { _, _ ->
                                        getDeviceUniqueId()?.let { uniqueId ->
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                performClearOldPositions(uniqueId)
                                            }
                                        }
                                    }
                                    .setNegativeButton("❌ No", null)
                                    .show()
                            }
                        }, 1500)
                    } else {
                        showSnackbar(it, Snackbar.LENGTH_LONG)
                        Log.e(TAG, "❌ Error from ViewModel: $it")
                        if (it.contains("401")) {
                            handleUnauthorizedError()
                        }
                    }
                }
            }
        }

        // Observar información del dispositivo
        lifecycleScope.launch {
            viewModel.deviceInfo.collectLatest { info ->
                info?.let {
                    deviceInfoCache.set(it)
                    updateDeviceInfoUI(it)
                    Log.d(TAG, "📱 Device info updated: $it")
                }
            }
        }
    }
    // ============ SERVICIOS OPTIMIZADOS ============

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun startServices() = withContext(Dispatchers.IO) {
        launch { startTrackingService() }
        launch { setupWebSocket() }
        launch { schedulePeriodicSync() }
        launch {
            // Pequeño delay para asegurar que WebSocket esté conectado
            delay(1000)
            fetchInitialPosition()
        }
        launch {
            // Delay más grande para status check
            delay(2000)
            startPeriodicStatusCheck()
        }
    }
    private fun formatPositionAge(age: Int?): String {
        return when {
            age == null -> ""
            age == 0 -> " (ahora)"
            age == 1 -> " (1 min)"
            age < 60 -> " (${age} min)"
            else -> {
                val hours = age / 60
                val minutes = age % 60
                if (minutes == 0) " (${hours}h)" else " (${hours}h ${minutes}min)"
            }
        }
    }



    // REEMPLAZAR la función startTrackingService completa
    private fun startTrackingService() {
        if (!hasAssociatedDevice()) {
            Log.e(TAG, "❌ No associated device for TrackingService")
            return
        }

        val deviceUniqueId = sharedPreferences.getString(DEVICE_UNIQUE_ID_PREF, null)
        if (deviceUniqueId == null) {
            Log.e(TAG, "❌ No deviceUniqueId for TrackingService")
            return
        }

        // ✅ OBTENER DEVICE_ID TAMBIÉN (NECESARIO PARA EL SERVICIO)
        val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)

        Log.d(TAG, "🚀 Starting TrackingService with:")
        Log.d(TAG, "   deviceUniqueId: $deviceUniqueId")
        Log.d(TAG, "   deviceId: $deviceId")
        Log.d(TAG, "   jwtToken: ${if (JWT_TOKEN.isNullOrEmpty()) "MISSING" else "OK"}")

        val intent = Intent(requireContext(), TrackingService::class.java).apply {
            putExtra("jwtToken", JWT_TOKEN)
            putExtra("deviceUniqueId", deviceUniqueId)
            putExtra("deviceId", deviceId) // ✅ AGREGAR ESTE EXTRA
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireContext().startForegroundService(intent)
            } else {
                requireContext().startService(intent)
            }
            Log.d(TAG, "✅ TrackingService iniciado correctamente")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting TrackingService: ${e.message}")
            showSnackbar("❌ Error iniciando servicio de rastreo", Snackbar.LENGTH_LONG)
        }
    }

    private fun schedulePeriodicSync() {
        val syncWork = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .addTag("sync_work")
            .build()

        WorkManager.getInstance(requireContext())
            .enqueueUniquePeriodicWork(
                "sync_work",
                ExistingPeriodicWorkPolicy.KEEP,
                syncWork
            )
        Log.d(TAG, "Sincronización periódica programada")
    }

    // ============ WEBSOCKET ULTRA-OPTIMIZADO ============
    private fun getCurrentTime(): String {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date())
    }
    // Fix en fetchInitialPosition con mejor error handling
    @RequiresApi(Build.VERSION_CODES.O)
    private fun fetchInitialPosition() {
        if (!hasAssociatedDevice()) {
            Log.w(TAG, "⚠️ No associated device for initial position fetch")
            return
        }

        val deviceUniqueId = sharedPreferences.getString(DEVICE_UNIQUE_ID_PREF, null)
        if (deviceUniqueId == null) {
            Log.e(TAG, "❌ No device uniqueId found in preferences")
            updateStatusUI("❌ No se encontró ID único del dispositivo", android.R.color.holo_red_dark)
            return
        }

        Log.d(TAG, "📍 Fetching initial position for device uniqueId: $deviceUniqueId")

        // ✅ NO USAR CACHE - SIEMPRE OBTENER POSICIÓN FRESCA
        lastPositionCache.clear()

        lifecycleScope.launch {
            try {
                updateStatusUI("🔄 Obteniendo posición actual...", android.R.color.darker_gray)

                val position = viewModel.getLastPosition(deviceUniqueId)
                val geoPoint = GeoPoint(position.latitude, position.longitude)

                Log.d(TAG, "✅ Fresh position obtained for uniqueId $deviceUniqueId: lat=${position.latitude}, lon=${position.longitude}")
                updateVehiclePosition(deviceUniqueId.hashCode(), geoPoint)

                // ✅ MOSTRAR INFORMACIÓN DE LA POSICIÓN CON VERIFICACIÓN SEGURA
                val ageInfo = when {
                    position.age != null && position.age > 0 -> " (${position.age} min)"
                    position.quality != null -> " (${position.quality})"
                    else -> ""
                }

                val qualityEmoji = when (position.quality) {
                    "excellent" -> "🟢"
                    "good" -> "🟡"
                    "acceptable" -> "🟠"
                    else -> "🔵"
                }

                updateStatusUI("✅ Posición actual cargada$ageInfo $qualityEmoji", android.R.color.holo_green_dark)

            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to fetch initial position for uniqueId $deviceUniqueId", e)

                when {
                    e.message?.contains("muy antigua") == true -> {
                        updateStatusUI("⚠️ Solo hay posiciones antiguas disponibles", android.R.color.holo_orange_dark)
                        showSnackbar("⚠️ ${e.message} - Usa Long Press en 'Estado' para limpiar", Snackbar.LENGTH_LONG)
                    }
                    e.message?.contains("404") == true -> {
                        updateStatusUI("❌ No hay posiciones disponibles", android.R.color.holo_red_dark)
                        showSnackbar("❌ Dispositivo no tiene posiciones. Verifica configuración GPS.", Snackbar.LENGTH_LONG)
                    }
                    e.message?.contains("401") == true -> {
                        handleUnauthorizedError()
                    }
                    else -> {
                        val errorMsg = "Error al obtener posición: ${e.message}"
                        showSnackbar("❌ $errorMsg", Snackbar.LENGTH_LONG)
                        updateStatusUI("❌ $errorMsg", android.R.color.holo_red_dark)
                    }
                }
            }
        }
    }
    // Fix en handlePositionUpdate con mejor logging
    // REEMPLAZAR la función handlePositionUpdate
    @RequiresApi(Build.VERSION_CODES.O)
    private fun handlePositionUpdate(json: JSONObject, expectedDeviceUniqueId: String) {
        try {
            val data = json.getJSONObject("data")
            val deviceId = data.getString("deviceId")
            val lat = data.getDouble("latitude")
            val lon = data.getDouble("longitude")
            val timestampStr = data.optString("timestamp", "")

            Log.d(TAG, "📨 WebSocket position update:")
            Log.d(TAG, "   DeviceId: $deviceId")
            Log.d(TAG, "   Expected: $expectedDeviceUniqueId")
            Log.d(TAG, "   Position: $lat, $lon")
            Log.d(TAG, "   Timestamp: $timestampStr")

            // ✅ VERIFICAR DISPOSITIVO CORRECTO
            if (deviceId != expectedDeviceUniqueId) {
                Log.w(TAG, "❌ Position for wrong device: received=$deviceId, expected=$expectedDeviceUniqueId")
                return
            }

            // ✅ TIMESTAMP PARSING MEJORADO
            val timestamp = try {
                when {
                    timestampStr.isEmpty() -> System.currentTimeMillis()
                    timestampStr.contains('T') -> {
                        java.time.Instant.parse(timestampStr).toEpochMilli()
                    }
                    timestampStr.all { it.isDigit() } -> {
                        val ts = timestampStr.toLong()
                        if (ts < 9999999999L) ts * 1000L else ts
                    }
                    else -> System.currentTimeMillis()
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Timestamp parse error, using current time: ${e.message}")
                System.currentTimeMillis()
            }

            // ✅ VALIDACIÓN DE EDAD MÁS PERMISIVA
            val ageMinutes = (System.currentTimeMillis() - timestamp) / 60000L
            if (ageMinutes > 30) {
                Log.w(TAG, "⚠️ Old position (${ageMinutes} min) but processing anyway...")
            }

            lifecycleScope.launch(Dispatchers.Main) {
                if (shouldUpdatePosition()) {
                    Log.d(TAG, "🔄 Updating vehicle position from WebSocket")
                    val geoPoint = GeoPoint(lat, lon)

                    // ✅ ACTUALIZAR VIEWMODEL Y UI
                    viewModel.updateVehiclePosition(deviceId, geoPoint, timestamp)
                    updateVehiclePosition(deviceId.hashCode(), geoPoint)

                    lastPositionUpdate.set(System.currentTimeMillis())
                    updateStatusUI("🟢 Actualizado: ${getCurrentTime()}", android.R.color.holo_green_dark)

                    Log.d(TAG, "✅ Position update completed successfully")
                } else {
                    Log.d(TAG, "⏳ Position update throttled")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling position update: ${e.message}", e)
            Log.e(TAG, "❌ Raw JSON: $json")
        }
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    // ============ UI UPDATES OPTIMIZADAS ============

    private fun updateVehiclePosition(deviceId: Int, position: GeoPoint) {
        if (!isMapReady.get()) return

        Log.d(TAG, "🎯 Updating vehicle position: deviceId=$deviceId")

        lastPositionCache.set(position)

        // Verificar zona segura ANTES de actualizar el marcador
        val newState = determineVehicleState(position)
        currentVehicleState = newState

        // ===== GESTIÓN DE MARCADOR OPTIMIZADA =====
        var marker = vehicleMarker.get()
        if (marker == null) {
            // Primera vez - crear marcador
            marker = createVehicleMarker(deviceId, position, newState)
            vehicleMarker.set(marker)
            map?.overlays?.add(marker)

            // Solo centrar en el primer marcador si no estamos siguiendo
            if (!isFollowingVehicle.get()) {
                map?.controller?.animateTo(position, 16.0, 1000L)
            }
            Log.d(TAG, "✅ Created vehicle marker and centered (first time only)")

        } else {
            // Actualizaciones - mover marcador y actualizar icono
            updateMarkerPosition(marker, deviceId, position, newState)
            // AGREGAR ESTA LÍNEA para forzar actualización visual:
            map?.invalidate() // Forzar redibujado del mapa
            Log.d(TAG, "✅ Updated vehicle marker position")
        }

        // Si estamos siguiendo al vehículo, mantener centrado
        if (isFollowingVehicle.get()) {
            map?.controller?.animateTo(position, FOLLOW_ZOOM_LEVEL, 800L)
            Log.d(TAG, "🎯 Following vehicle - map centered on new position")
        }

        // Verificar zona segura para alertas
        checkSafeZone(position, deviceId)

        // ===== INVALIDACIÓN OPTIMIZADA =====
        map?.invalidate()
    }

    private fun determineVehicleState(vehiclePosition: GeoPoint): VehicleState {
        val safeZoneCenter = safeZoneCenter

        return if (safeZoneCenter != null) {
            val distance = safeZoneCenter.distanceToAsDouble(vehiclePosition)
            if (distance <= GEOFENCE_RADIUS) {
                VehicleState.IN_SAFE_ZONE
            } else {
                VehicleState.OUTSIDE_SAFE_ZONE
            }
        } else {
            VehicleState.NORMAL
        }
    }


    private fun createVehicleMarker(deviceId: Int, position: GeoPoint, state: VehicleState): Marker {
        return Marker(map).apply {
            this.position = position
            setAnchor(0.5f, 1.0f) // Centro-abajo para mejor visibilidad

            // Configurar icono según estado
            updateMarkerIcon(this, state)

            title = formatMarkerTitle(deviceId, position)
            isDraggable = false
            setInfoWindow(null) // Sin info window para mejor rendimiento
            alpha = 1.0f // Totalmente opaco para máxima visibilidad
        }
    }

    private fun updateMarkerPosition(marker: Marker, deviceId: Int, position: GeoPoint, state: VehicleState) {
        marker.position = position
        marker.title = formatMarkerTitle(deviceId, position)
        updateMarkerIcon(marker, state)
    }


    private fun formatMarkerTitle(deviceId: Int, position: GeoPoint): String {
        return "Vehículo ID: $deviceId\nLat: ${"%.6f".format(position.latitude)}\nLon: ${"%.6f".format(position.longitude)}"
    }

    private fun checkSafeZone(position: GeoPoint, deviceId: Int) {
        safeZoneCenter?.let { center ->
            val distance = calculateAccurateDistance(center, position)
            Log.d(TAG, "📏 Distance to safe zone: ${String.format("%.2f", distance)}m")

            if (distance > GEOFENCE_RADIUS) {
                // ✅ VEHÍCULO FUERA - ACTIVAR ALARMA
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    triggerAlarm(deviceId, distance)
                }
                Log.d(TAG, "🚨 Vehicle outside safe zone: ${String.format("%.1f", distance)}m")
            } else {
                // ✅ VEHÍCULO DENTRO - DETENER ALARMA SIEMPRE
                stopAlarmIfActive()
                Log.d(TAG, "✅ Vehicle inside safe zone: ${String.format("%.1f", distance)}m - Alarm stopped")
            }
        }
    }

    // ============ CÁLCULO DE DISTANCIA MÁS PRECISO ============
    private fun calculateAccurateDistance(point1: GeoPoint, point2: GeoPoint): Double {
        val results = FloatArray(1)
        Location.distanceBetween(
            point1.latitude, point1.longitude,
            point2.latitude, point2.longitude,
            results
        )
        return results[0].toDouble()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun triggerAlarm(deviceId: Int, distance: Double) {
        // Vibración rápida como feedback inicial
        val vibrator = requireContext().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))

        // Activar alarma crítica con sonido
        alertManager.startCriticalAlert(deviceId, distance)

        showSnackbar(
            "¡ALERTA! El vehículo $deviceId está a ${"%.1f".format(distance)} metros",
            Snackbar.LENGTH_LONG
        )
    }
    private fun stopAlarmIfActive() {
        try {
            if (::alertManager.isInitialized) {
                alertManager.stopAlert()
                Log.d(TAG, "🔇 AlertManager stopped successfully")
            } else {
                Log.w(TAG, "⚠️ AlertManager not initialized")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error stopping alarm: ${e.message}")
        }
    }

    private fun updateSafeZoneUI(position: GeoPoint) {
        if (!isMapReady.get()) return

        Log.d(TAG, "🛡️ Updating safe zone UI at position: lat=${position.latitude}, lon=${position.longitude}")

        // Guardar centro de zona segura
        safeZoneCenter = position

        // Remover polígono anterior
        safeZonePolygon.get()?.let {
            map?.overlays?.remove(it)
            Log.d(TAG, "🗑️ Removed previous safe zone polygon")
        }

        // Crear nuevo polígono más visible
        val polygon = Polygon().apply {
            points = Polygon.pointsAsCircle(position, GEOFENCE_RADIUS)
            fillColor = 0x33007FFF // Azul semi-transparente más visible
            strokeColor = Color.BLUE
            strokeWidth = 4f // Línea más gruesa
        }

        safeZonePolygon.set(polygon)
        map?.overlays?.add(polygon)
        Log.d(TAG, "✅ Added new safe zone polygon")

        // Actualizar botón
        updateSafeZoneButton(true)

        // Solo centrar si no estamos siguiendo vehículo
        if (!isFollowingVehicle.get()) {
            val currentCenter = map?.mapCenter
            val currentDistance = currentCenter?.let {
                GeoPoint(it.latitude, it.longitude).distanceToAsDouble(position)
            } ?: Double.MAX_VALUE

            if (currentDistance > 2000.0) { // Solo si estamos muy lejos (2km+)
                Log.d(TAG, "📍 Centering map on safe zone (far away)")
                map?.controller?.animateTo(position, 17.0, 500L)
            }
        }

        map?.invalidate()
        Log.d(TAG, "✅ Safe zone UI updated completely")
    }




    private fun updateSafeZoneButton(active: Boolean) {
        Log.d(TAG, "🔘 Updating safe zone button: active=$active")

        binding.buttonZonaSegura.apply {
            text = if (active) "Zona Segura Activa ✓" else "Establecer Zona Segura"
            setBackgroundColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (active) android.R.color.holo_green_dark
                    else android.R.color.holo_blue_dark
                )
            )
        }

        Log.d(TAG, "✅ Safe zone button updated: text='${binding.buttonZonaSegura.text}'")
    }


    private fun updateDeviceInfoUI(info: String) {
        binding.textDeviceInfo.apply {
            text = info
            visibility = View.VISIBLE
        }
    }

    private fun startPeriodicStatusCheck() {
        if (!hasAssociatedDevice()) return

        cancelStatusCheck()

        statusCheckRunnable = object : Runnable {
            override fun run() {
                if (isAdded && hasAssociatedDevice()) {
                    // USAR EL UNIQUE_ID REAL DE LAS PREFERENCIAS
                    val deviceUniqueId = sharedPreferences.getString(DEVICE_UNIQUE_ID_PREF, null)
                    if (!deviceUniqueId.isNullOrEmpty()) {
                        Log.d(TAG, "🔄 Automatic status check for REAL device: $deviceUniqueId")

                        viewModel.checkDeviceStatus(deviceUniqueId) { isOnline, message ->
                            val statusIcon = if (isOnline) "🟢" else "🔴"
                            val displayMessage = "$statusIcon ${message.substringAfter(" ")}"

                            updateStatusUI(
                                displayMessage,
                                if (isOnline) android.R.color.holo_green_dark
                                else android.R.color.holo_red_dark
                            )

                            Log.d(TAG, "📊 Automatic status updated: $displayMessage")
                        }
                    } else {
                        Log.w(TAG, "⚠️ No deviceUniqueId found for status check")
                    }
                }
                handler.postDelayed(this, STATUS_CHECK_INTERVAL)
            }
        }

        // Ejecutar inmediatamente la primera vez, luego cada 30 segundos
        handler.post(statusCheckRunnable!!)
        Log.d(TAG, "✅ Periodic status check started")
    }



    private fun cancelStatusCheck() {
        statusCheckRunnable?.let { handler.removeCallbacks(it) }
        statusCheckRunnable = null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterSafeZoneSetupMode() {
        if (!hasAssociatedDevice()) {
            showSnackbar("Asocia un vehículo primero", Snackbar.LENGTH_SHORT)
            return
        }

        // USAR SIEMPRE EL UNIQUE_ID REAL
        val deviceUniqueId = sharedPreferences.getString(DEVICE_UNIQUE_ID_PREF, null)
        if (deviceUniqueId != null) {
            establishSafeZoneForDevice(deviceUniqueId)
        } else {
            showSnackbar("❌ No se encontró dispositivo asociado", Snackbar.LENGTH_SHORT)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun establishSafeZoneForDevice(deviceIdString: String) {
        if (JWT_TOKEN.isNullOrEmpty()) {
            showSnackbar("Token de autenticación faltante. Inicia sesión nuevamente.", Snackbar.LENGTH_LONG)
            handleUnauthorizedError()
            return
        }

        val deviceUniqueId = sharedPreferences.getString(DEVICE_UNIQUE_ID_PREF, null)
        if (deviceUniqueId == null) {
            showSnackbar("❌ No se encontró ID único del dispositivo", Snackbar.LENGTH_SHORT)
            return
        }

        Log.d(TAG, "🛡️ Establishing safe zone for REAL uniqueId: $deviceUniqueId")

        binding.buttonZonaSegura.apply {
            text = "Obteniendo ubicación del vehículo..."
            isEnabled = false
        }

        lifecycleScope.launch {
            try {
                // ✅ INTENTAR PRIMERO CON POSICIONES RECIENTES
                val position = try {
                    viewModel.getLastPosition(deviceUniqueId)
                } catch (e: Exception) {
                    if (e.message?.contains("muy antigua") == true || e.message?.contains("too_old") == true) {
                        // ✅ SI FALLA POR POSICIÓN ANTIGUA, PREGUNTAR AL USUARIO
                        showOldPositionDialog(deviceUniqueId, e.message)
                        return@launch
                    } else {
                        throw e
                    }
                }

                // Si llegamos aquí, la posición es reciente
                val geoPoint = GeoPoint(position.latitude, position.longitude)
                createSafeZoneSuccessfully(geoPoint, deviceUniqueId, position.age)

            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to establish safe zone for uniqueId: $deviceUniqueId", e)
                showSnackbar("Fallo al establecer zona segura: ${e.message}", Snackbar.LENGTH_LONG)
                restoreSafeZoneButton()
            }
        }
    }


    private fun toggleSafeZone() {
        if (JWT_TOKEN.isNullOrEmpty()) {
            showSnackbar("Token de autenticación faltante. Inicia sesión nuevamente.", Snackbar.LENGTH_LONG)
            handleUnauthorizedError()
            return
        }

        Log.d(TAG, "🛡️ Toggling safe zone - current state: ${safeZoneCenter != null}")

        // Mostrar diálogo de confirmación para eliminación
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Eliminar Zona Segura")
            .setMessage("¿Estás seguro de que quieres eliminar la zona segura actual? Esta acción no se puede deshacer.")
            .setPositiveButton("Sí, eliminar") { _, _ ->
                performSafeZoneDeletion()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun performSafeZoneDeletion() {
        binding.buttonZonaSegura.apply {
            text = "Eliminando zona segura..."
            isEnabled = false
        }

        viewModel.deleteSafeZoneFromServer { success ->
            lifecycleScope.launch(Dispatchers.Main) {
                if (success) {
                    Log.d(TAG, "✅ Safe zone deletion confirmed by server")

                    // Limpiar completamente
                    safeZone = null
                    safeZoneCenter = null
                    safeZoneCache.clear()

                    // Remover polígono del mapa
                    safeZonePolygon.get()?.let {
                        map?.overlays?.remove(it)
                        Log.d(TAG, "🗑️ Removed safe zone polygon from map")
                    }
                    safeZonePolygon.set(null)

                    // Limpiar preferencias
                    sharedPreferences.edit {
                        remove(PREF_SAFEZONE_LAT)
                        remove(PREF_SAFEZONE_LON)
                    }

                    // Actualizar estado del vehículo a normal
                    vehicleMarker.get()?.let { marker ->
                        currentVehicleState = VehicleState.NORMAL
                        updateMarkerIcon(marker, VehicleState.NORMAL)
                    }

                    // Actualizar UI
                    updateSafeZoneButton(false)

                    binding.buttonZonaSegura.apply {
                        text = "Establecer Zona Segura"
                        isEnabled = true
                    }

                    showSnackbar("✅ Zona segura eliminada correctamente", Snackbar.LENGTH_SHORT)

                    // Forzar actualización del ViewModel
                    viewModel.fetchSafeZoneFromServer()

                    map?.postInvalidate()

                } else {
                    Log.e(TAG, "❌ Failed to delete safe zone")

                    binding.buttonZonaSegura.apply {
                        text = "Zona Segura Activa ✓"
                        isEnabled = true
                    }

                    showSnackbar("❌ Error al eliminar zona segura. Intenta nuevamente.", Snackbar.LENGTH_LONG)
                }
            }
        }
    }

    // ============ CENTRADO MEJORADO EN MI UBICACIÓN ============
    @SuppressLint("MissingPermission")
    private fun centerOnMyLocation() {
        if (!hasLocationPermission()) {
            showSnackbar("⚠️ Se necesitan permisos de ubicación", Snackbar.LENGTH_SHORT)
            return
        }

        // Desactivar seguimiento del vehículo al centrar en mi ubicación
        isFollowingVehicle.set(false)
        updateFollowButtonText()

        myLocationOverlay?.myLocation?.let { location ->
            val myPosition = GeoPoint(location.latitude, location.longitude)
            map?.controller?.animateTo(myPosition, 18.0, 1200L)
            showSnackbar("📍 Centrado en tu ubicación", Snackbar.LENGTH_SHORT)
            Log.d(TAG, "📍 Manually centered on user location - vehicle following disabled")
        } ?: run {
            showSnackbar("⚠️ Ubicación no disponible", Snackbar.LENGTH_SHORT)
            Log.w(TAG, "⚠️ User location not available for centering")
        }
    }


    private fun loadSafeZone() {
        // Primero del cache
        safeZoneCache.get()?.let {
            safeZone = it
            updateSafeZoneUI(it)
            return
        }

        // Luego de preferencias
        val lat = sharedPreferences.getString(PREF_SAFEZONE_LAT, null)?.toDoubleOrNull()
        val lon = sharedPreferences.getString(PREF_SAFEZONE_LON, null)?.toDoubleOrNull()

        if (lat != null && lon != null) {
            val geoPoint = GeoPoint(lat, lon)
            safeZone = geoPoint
            safeZoneCache.set(geoPoint)
            updateSafeZoneUI(geoPoint)
        }
    }

    // ============ DIALOGS OPTIMIZADOS ============

    @RequiresApi(Build.VERSION_CODES.O)
    private fun showAssociateDeviceDialog() {
        if (JWT_TOKEN.isNullOrEmpty()) {
            showSnackbar("Token de autenticación faltante. Inicia sesión nuevamente.", Snackbar.LENGTH_LONG)
            handleUnauthorizedError()
            return
        }

        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_associate_device, null)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Asociar Vehículo")
            .setMessage("Ingresa el ID único del dispositivo GPS")
            .setView(dialogView)
            .setPositiveButton("Asociar") { _, _ ->
                val deviceUniqueId = dialogView.findViewById<EditText>(R.id.editDeviceId)
                    .text.toString().trim()
                val deviceName = dialogView.findViewById<EditText>(R.id.editDeviceName)
                    .text.toString().trim()

                if (deviceUniqueId.isNotEmpty() && deviceName.isNotEmpty()) {
                    associateDevice(deviceUniqueId, deviceName)
                } else {
                    showSnackbar("Ingresa un ID único y nombre válidos", Snackbar.LENGTH_SHORT)
                }
            }
            .setNeutralButton("Ver Dispositivos") { _, _ ->
                viewModel.showAvailableDevices { devices ->
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Dispositivos Disponibles")
                        .setMessage(if (devices.isEmpty()) "No se encontraron dispositivos" else devices)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun associateDevice(deviceUniqueId: String, deviceName: String) {
        Log.d(TAG, "Asociando dispositivo: uniqueId=$deviceUniqueId, name=$deviceName")

        viewModel.associateDevice(deviceUniqueId, deviceName) { deviceId, name ->
            // Clear all relevant caches
            deviceInfoCache.clear()
            lastPositionCache.clear()

            // Update UI immediately
            val info = "Dispositivo: $name (ID: $deviceId)"
            deviceInfoCache.set(info)
            updateDeviceInfoUI(info)
            viewModel.forceDataRefresh()
            // Force refresh all data
            lifecycleScope.launch {
                delay(500) // Small delay to ensure caches are cleared

                // Reinitialize everything
                supervisorScope {
                    launch { setupWebSocket() }
                    launch { startTrackingService() }
                    launch { fetchInitialPosition() }
                    launch { viewModel.fetchSafeZoneFromServer() }
                    launch { checkDeviceStatus() }
                }
            }
        }
    }
    // Fix en createWebSocketListener con mejor manejo de mensajes
    @RequiresApi(Build.VERSION_CODES.O)
    private fun showTraccarClientConfig() {
        if (JWT_TOKEN.isNullOrEmpty()) {
            showSnackbar("Token de autenticación faltante. Inicia sesión nuevamente.", Snackbar.LENGTH_LONG)
            handleUnauthorizedError()
            return
        }

        val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)
        val deviceUniqueId = sharedPreferences.getString(DEVICE_UNIQUE_ID_PREF, null)

        if (deviceId == -1 || deviceUniqueId == null) {
            showSnackbar("Asocia un dispositivo primero desde 'Asociar Vehículo'", Snackbar.LENGTH_LONG)
            return
        }

        viewModel.getGPSClientConfig { recommendedEndpoint, endpoints, instructions ->
            val configText = buildString {
                appendLine("📱 Configuración del Cliente GPS:")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("🔗 URL DEL SERVIDOR RECOMENDADA:")
                appendLine("$recommendedEndpoint")
                appendLine()
                appendLine("📋 CONFIGURACIÓN DEL DISPOSITIVO:")
                appendLine("ID del Dispositivo: $deviceUniqueId")
                appendLine("Protocolo: ${instructions["protocol"] ?: "HTTP GET/POST"}")
                appendLine("Parámetros: ${instructions["parameters"] ?: "id, lat, lon, timestamp, speed"}")
                appendLine("Frecuencia: 5 segundos")
                appendLine("Distancia: 10 metros")
                appendLine()
                appendLine("🌐 ENDPOINTS ALTERNATIVOS:")
                endpoints.forEach { (name, url) ->
                    appendLine("• ${name.uppercase()}: $url")
                }
                appendLine()
                appendLine("📱 PARA LA APLICACIÓN TRACCAR CLIENT:")
                appendLine("1. Instala Traccar Client desde Play Store")
                appendLine("2. URL del Servidor: $recommendedEndpoint")
                appendLine("3. ID del Dispositivo: $deviceUniqueId")
                appendLine("4. Habilita permisos de ubicación")
                appendLine("5. Inicia el servicio")
                appendLine()
                appendLine("🔧 PARA DISPOSITIVOS GPS PERSONALIZADOS:")
                appendLine("Envía solicitudes HTTP GET/POST a:")
                appendLine("$recommendedEndpoint")
                appendLine("Parámetros: ${instructions["parameters"] ?: "id, lat, lon, timestamp, speed"}")
                appendLine()
                appendLine("📡 EJEMPLO DE SOLICITUD:")
                appendLine(instructions["example"] ?: "GET $recommendedEndpoint?id=$deviceUniqueId&lat=-37.32167&lon=-59.13316&timestamp=${java.time.Instant.now()}&speed=0")
                appendLine()
                appendLine("✅ VERIFICA LA CONEXIÓN:")
                appendLine("Usa el botón 'Estado del Dispositivo' para comprobar si se reciben datos")
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Guía de Configuración de Dispositivo GPS")
                .setMessage(configText)
                .setPositiveButton("Copiar URL del Servidor") { _, _ ->
                    copyToClipboard("URL del Servidor", recommendedEndpoint)
                    showSnackbar("✅ ¡URL del servidor copiada!", Snackbar.LENGTH_SHORT)
                }
                .setNeutralButton("Probar URL") { _, _ ->
                    val testUrl = instructions["example"] ?:
                    "$recommendedEndpoint?id=$deviceUniqueId&lat=-37.32167&lon=-59.13316&timestamp=${java.time.Instant.now()}&speed=0"
                    openInBrowser(testUrl)
                }
                .setNegativeButton("Cerrar", null)
                .show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun showOfflineHelpDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("🔴 Dispositivo Fuera de Línea - Solución de Problemas")
            .setMessage(buildString {
                appendLine("Tu dispositivo GPS no está enviando datos. Aquí tienes cómo solucionarlo:")
                appendLine()
                appendLine("✅ VERIFICA ESTOS PUNTOS:")
                appendLine("1. El dispositivo GPS/aplicación está funcionando")
                appendLine("2. Los permisos de ubicación están habilitados")
                appendLine("3. La conexión a internet está activa")
                appendLine("4. La URL del servidor está configurada correctamente")
                appendLine("5. El ID del dispositivo coincide exactamente")
                appendLine()
                appendLine("🔧 PRÓXIMOS PASOS:")
                appendLine("• Usa 'Mostrar Configuración del Cliente GPS' para la configuración")
                appendLine("• Prueba la URL en tu navegador")
                appendLine("• Revisa la configuración de la aplicación GPS")
                appendLine("• Reinicia el servicio de rastreo GPS")
            })
            .setPositiveButton("Mostrar Configuración") { _, _ ->
                showTraccarClientConfig()
            }
            .setNegativeButton("OK", null)
            .show()
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    // ============ UTILIDADES OPTIMIZADAS ============

    private fun shouldUpdatePosition(): Boolean {
        val now = System.currentTimeMillis()
        return (now - lastPositionUpdate.get()) >= POSITION_UPDATE_THROTTLE
    }

    // Fix en loadDeviceInfo para refresh completo
    private fun loadDeviceInfo() {
        Log.d(TAG, "Loading device info...")

        // Siempre refresh desde el servidor en lugar de cache
        viewModel.fetchAssociatedDevices()

        // También verificar preferencias como fallback
        if (hasAssociatedDevice()) {
            val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)
            val deviceName = sharedPreferences.getString(DEVICE_NAME_PREF, null)
            val info = "Dispositivo: $deviceName (ID: $deviceId)"
            updateDeviceInfoUI(info)

            Log.d(TAG, "Device info loaded from preferences: $info")
        } else {
            updateDeviceInfoUI("No hay dispositivos asociados")
            Log.d(TAG, "No device found in preferences")
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }

    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            showSnackbar("🌐 Abriendo URL de prueba en el navegador", Snackbar.LENGTH_SHORT)
        } catch (e: Exception) {
            showSnackbar("❌ No hay navegador disponible", Snackbar.LENGTH_SHORT)
        }
    }

    // REEMPLAZAR handleUnauthorizedError POR ESTA VERSIÓN
    private fun handleUnauthorizedError() {
        Log.d(TAG, "🔄 Handling unauthorized error - attempting token refresh")

        // ✅ SOLO INTENTAR REFRESH - NO ELIMINAR NADA AÚN
        refreshJWTToken()
    }
    // ============ ZONA SEGURA OPTIMIZADA ============
    @RequiresApi(Build.VERSION_CODES.O)
    private fun handleSafeZoneButton() {
        Log.d(TAG, "🛡️ Botón zona segura presionado - Estado actual: safeZone=${safeZone != null}")

        if (safeZone == null || !isSafeZoneActive()) {
            // ✅ NO HAY ZONA SEGURA - CREAR NUEVA
            enterSafeZoneSetupMode()
        } else {
            // ✅ HAY ZONA SEGURA ACTIVA - MOSTRAR OPCIONES
            showSafeZoneOptionsDialog()
        }
    }
    @RequiresApi(Build.VERSION_CODES.O)
    private fun showSafeZoneOptionsDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("🛡️ Zona Segura Activa")
            .setMessage("¿Qué quieres hacer con la zona segura actual?")
            .setPositiveButton("✏️ Reconfigurar Aquí") { _, _ ->
                // ✅ ESTABLECER NUEVA ZONA EN POSICIÓN ACTUAL DEL VEHÍCULO
                reconfigureSafeZoneAtVehiclePosition()
            }
            .setNeutralButton("📍 Reconfigurar en Mi Ubicación") { _, _ ->
                // ✅ ESTABLECER NUEVA ZONA EN UBICACIÓN DEL USUARIO
                reconfigureSafeZoneAtMyLocation()
            }
            .setNegativeButton("🗑️ Desactivar Zona") { _, _ ->
                // ✅ CONFIRMAR ELIMINACIÓN
                showDeleteSafeZoneConfirmation()
            }
            .show()
    }
    /**
     * ✅ RECONFIGURAR ZONA EN POSICIÓN DEL VEHÍCULO
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun reconfigureSafeZoneAtVehiclePosition() {
        val deviceUniqueId = sharedPreferences.getString(DEVICE_UNIQUE_ID_PREF, null)
        if (deviceUniqueId == null) {
            showSnackbar("❌ No hay dispositivo asociado", Snackbar.LENGTH_SHORT)
            return
        }

        Log.d(TAG, "🔄 Reconfigurando zona segura en posición del vehículo")

        // ✅ UI FEEDBACK INMEDIATO
        updateSafeZoneButtonText("🔄 Obteniendo posición del vehículo...")
        binding.buttonZonaSegura.isEnabled = false

        lifecycleScope.launch {
            try {
                val position = viewModel.getLastPosition(deviceUniqueId)
                val geoPoint = GeoPoint(position.latitude, position.longitude)

                // ✅ CONFIRMAR CON EL USUARIO
                showConfirmNewSafeZone(geoPoint, "posición del vehículo") { confirmed ->
                    if (confirmed) {
                        establishSafeZoneAt(geoPoint, deviceUniqueId)
                    } else {
                        restoreSafeZoneButton()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error obteniendo posición del vehículo", e)
                showSnackbar("❌ Error: ${e.message}", Snackbar.LENGTH_LONG)
                restoreSafeZoneButton()
            }
        }
    }
    /**
     * ✅ RECONFIGURAR ZONA EN UBICACIÓN DEL USUARIO
     */
    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("MissingPermission")
    private fun reconfigureSafeZoneAtMyLocation() {
        if (!hasLocationPermission()) {
            showSnackbar("❌ Se necesitan permisos de ubicación", Snackbar.LENGTH_SHORT)
            return
        }

        val deviceUniqueId = sharedPreferences.getString(DEVICE_UNIQUE_ID_PREF, null)
        if (deviceUniqueId == null) {
            showSnackbar("❌ No hay dispositivo asociado", Snackbar.LENGTH_SHORT)
            return
        }

        Log.d(TAG, "📍 Reconfigurando zona segura en mi ubicación")

        // ✅ UI FEEDBACK
        updateSafeZoneButtonText("📍 Obteniendo tu ubicación...")
        binding.buttonZonaSegura.isEnabled = false

        // ✅ OBTENER UBICACIÓN ACTUAL DEL USUARIO
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    val geoPoint = GeoPoint(location.latitude, location.longitude)

                    showConfirmNewSafeZone(geoPoint, "tu ubicación actual") { confirmed ->
                        if (confirmed) {
                            establishSafeZoneAt(geoPoint, deviceUniqueId)
                        } else {
                            restoreSafeZoneButton()
                        }
                    }
                } else {
                    showSnackbar("❌ No se pudo obtener tu ubicación", Snackbar.LENGTH_SHORT)
                    restoreSafeZoneButton()
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ Error obteniendo ubicación del usuario", e)
                showSnackbar("❌ Error obteniendo ubicación: ${e.message}", Snackbar.LENGTH_SHORT)
                restoreSafeZoneButton()
            }
    }
    /**
     * ✅ CONFIRMAR NUEVA ZONA SEGURA CON PREVIEW
     */
    private fun showConfirmNewSafeZone(
        position: GeoPoint,
        locationDescription: String,
        callback: (Boolean) -> Unit
    ) {
        val distanceFromCurrent = safeZone?.let { current ->
            calculateAccurateDistance(current, position)
        } ?: 0.0

        val message = buildString {
            appendLine("Nueva zona segura en $locationDescription:")
            appendLine()
            appendLine("📍 Coordenadas:")
            appendLine("   Lat: ${String.format("%.6f", position.latitude)}")
            appendLine("   Lon: ${String.format("%.6f", position.longitude)}")

            if (distanceFromCurrent > 0) {
                appendLine()
                appendLine("📏 Distancia de zona actual: ${String.format("%.0f", distanceFromCurrent)}m")
            }

            appendLine()
            appendLine("⚠️ Esto reemplazará la zona segura actual.")
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("🛡️ Confirmar Nueva Zona Segura")
            .setMessage(message)
            .setPositiveButton("✅ Sí, Establecer Aquí") { _, _ ->
                callback(true)
            }
            .setNegativeButton("❌ Cancelar") { _, _ ->
                callback(false)
            }
            .setCancelable(false)
            .show()
    }
    /**
     * ✅ ESTABLECER ZONA SEGURA EN POSICIÓN ESPECÍFICA
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun establishSafeZoneAt(position: GeoPoint, deviceUniqueId: String) {
        updateSafeZoneButtonText("🔄 Estableciendo zona segura...")

        lifecycleScope.launch {
            try {
                // ✅ ENVIAR AL SERVIDOR
                viewModel.sendSafeZoneToServer(
                    position.latitude,
                    position.longitude,
                    deviceUniqueId
                )

                // ✅ ACTUALIZAR LOCALMENTE
                safeZone = position
                safeZoneCache.set(position)
                updateSafeZoneUI(position)

                // ✅ GUARDAR EN PREFERENCIAS
                sharedPreferences.edit {
                    putString(PREF_SAFEZONE_LAT, position.latitude.toString())
                    putString(PREF_SAFEZONE_LON, position.longitude.toString())
                }

                // ✅ CENTRAR MAPA EN NUEVA ZONA (SIN AFECTAR SEGUIMIENTO DE VEHÍCULO)
                if (!isFollowingVehicle.get()) {
                    map?.controller?.animateTo(position, 17.0, 1000L)
                }

                // ✅ CONFIRMACIÓN
                showSnackbar("✅ Nueva zona segura establecida", Snackbar.LENGTH_LONG)
                updateSafeZoneButtonText("🛡️ Zona Segura Activa ✓")
                binding.buttonZonaSegura.isEnabled = true

                Log.d(TAG, "✅ Zona segura reconfigurada: lat=${position.latitude}, lon=${position.longitude}")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error estableciendo nueva zona segura", e)
                showSnackbar("❌ Error: ${e.message}", Snackbar.LENGTH_LONG)
                restoreSafeZoneButton()
            }
        }
    }

    /**
     * ✅ CONFIRMACIÓN DE ELIMINACIÓN MÁS CLARA
     */
    private fun showDeleteSafeZoneConfirmation() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("⚠️ Eliminar Zona Segura")
            .setMessage("¿Estás COMPLETAMENTE SEGURO de que quieres eliminar la zona segura?\n\n" +
                    "ESTO SIGNIFICA:\n" +
                    "• NO habrá más alertas de seguridad\n" +
                    "• El vehículo podrá moverse libremente sin avisos\n" +
                    "• Tendrás que configurar una nueva zona manualmente\n\n" +
                    "⚠️ Solo hazlo si realmente no necesitas el monitoreo.")
            .setPositiveButton("🗑️ SÍ, ELIMINAR COMPLETAMENTE") { _, _ ->
                performSafeZoneDeletion()
            }
            .setNeutralButton("🔇 SOLO SILENCIAR ALARMAS") { _, _ ->
                temporarilySilenceAlerts()
            }
            .setNegativeButton("❌ Cancelar") { _, _ ->
                // No hacer nada
            }
            .setCancelable(true)
            .show()
    }

    /**
     * ✅ SILENCIAR TEMPORALMENTE (NUEVA OPCIÓN)
     */
    private fun temporarilySilenceAlerts() {
        // ✅ GUARDAR ESTADO DE "SILENCIADO TEMPORALMENTE"
        sharedPreferences.edit {
            putLong("alerts_silenced_until", System.currentTimeMillis() + (60 * 60 * 1000)) // 1 hora
        }

        // ✅ ENVIAR BROADCAST PARA SILENCIAR ALARMAS ACTIVAS
        val intent = Intent("com.peregrino.SILENCE_ALERTS_TEMPORARILY")
        requireContext().sendBroadcast(intent)

        showSnackbar("🔇 Alertas silenciadas por 1 hora - Zona segura sigue activa", Snackbar.LENGTH_LONG)
        updateSafeZoneButtonText("🛡️ Zona Activa (Silenciada 1h)")

        // ✅ RESTAURAR TEXTO DESPUÉS DE 1 HORA
        handler.postDelayed({
            if (isAdded) {
                updateSafeZoneButtonText("🛡️ Zona Segura Activa ✓")
                showSnackbar("🔔 Alertas de zona segura reactivadas", Snackbar.LENGTH_SHORT)
            }
        }, 60 * 60 * 1000)
    }

    /**
     * ✅ FUNCIONES AUXILIARES PARA UI
     */
    private fun updateSafeZoneButtonText(text: String) {
        binding.buttonZonaSegura.text = text
        binding.buttonZonaSeguraMain.text = text
    }

    private fun showOldPositionDialog(deviceUniqueId: String, errorMessage: String?) {
        val ageInfo = extractAgeFromError(errorMessage)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("⚠️ Posición Muy Antigua")
            .setMessage(
                buildString {
                    appendLine("La última posición del dispositivo es muy antigua${ageInfo}.")
                    appendLine()
                    appendLine("OPCIONES:")
                    appendLine()
                    appendLine("🛡️ CREAR ZONA SEGURA:")
                    appendLine("• Usar la posición antigua disponible")
                    appendLine("• La zona funcionará pero es recomendable actualizar GPS")
                    appendLine()
                    appendLine("📱 CONFIGURAR GPS:")
                    appendLine("• Configura tu dispositivo para enviar datos actuales")
                    appendLine("• Usa 'Mostrar Configuración' para ver instrucciones")
                    appendLine()
                    appendLine("⚠️ IMPORTANTE: Para máxima precisión, configura tu GPS para enviar datos cada 5-10 minutos.")
                }
            )
            .setPositiveButton("🛡️ Crear con Posición Antigua") { _, _ ->
                createSafeZoneWithOldPosition(deviceUniqueId)
            }
            .setNeutralButton("📱 Ver Configuración GPS") { _, _ ->
                restoreSafeZoneButton()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    showTraccarClientConfig()
                }
            }
            .setNegativeButton("❌ Cancelar") { _, _ ->
                restoreSafeZoneButton()
            }
            .setCancelable(false)
            .show()
    }

    private fun isSafeZoneActive(): Boolean {
        // ✅ VERIFICAR SI LA ZONA ESTÁ REALMENTE ACTIVA
        val hasCoordinates = safeZone != null
        val hasSavedZone = sharedPreferences.contains(PREF_SAFEZONE_LAT) &&
                sharedPreferences.contains(PREF_SAFEZONE_LON)

        return hasCoordinates || hasSavedZone
    }

    // ✅ TAMBIÉN AGREGAR ESTA PROPIEDAD AL INICIO DE LA CLASE
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // ✅ Y ESTA INICIALIZACIÓN EN onViewCreated() DESPUÉS DE setupMap()
    private fun initializeFusedLocationClient() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
    }

// ============ BROADCAST RECEIVER PARA DETECTAR CUANDO SE DESACTIVA LA ZONA ============

    private val safeZoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.peregrino.SAFEZONE_DISABLED" -> {
                    Log.d(TAG, "🛡️ Zona segura desactivada desde servicio")

                    // ✅ ACTUALIZAR UI
                    safeZone = null
                    safeZoneCache.clear()

                    // ✅ REMOVER POLÍGONO DEL MAPA
                    safeZonePolygon.get()?.let {
                        map?.overlays?.remove(it)
                    }
                    safeZonePolygon.set(null)

                    // ✅ ACTUALIZAR BOTÓN
                    updateSafeZoneButton(false)
                    updateSafeZoneButtonText("🛡️ Establecer Zona Segura")

                    map?.postInvalidate()

                    // ✅ MOSTRAR CONFIRMACIÓN
                    showSnackbar("🛡️ Zona segura desactivada desde alarma", Snackbar.LENGTH_LONG)
                }

                "com.peregrino.SAFEZONE_ALERT" -> {
                    val distance = intent.getDoubleExtra("distance", 0.0)
                    Log.w(TAG, "🚨 Alerta recibida desde servicio: ${distance}m")

                    // ✅ MOSTRAR ALERTA EN LA APP (SI ESTÁ ABIERTA)
                    lifecycleScope.launch(Dispatchers.Main) {
                        showSnackbar(
                            "🚨 VEHÍCULO FUERA DE ZONA: ${distance.toInt()}m",
                            Snackbar.LENGTH_LONG
                        )
                    }
                }
            }
        }
    }
    @RequiresApi(Build.VERSION_CODES.O)
    private fun clearOldPositionsDialog() {
        val deviceUniqueId = sharedPreferences.getString(DEVICE_UNIQUE_ID_PREF, null)

        if (deviceUniqueId == null) {
            showSnackbar("❌ No hay dispositivo asociado", Snackbar.LENGTH_SHORT)
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("🧹 Limpiar Posiciones Antiguas")
            .setMessage(
                "¿Quieres limpiar todas las posiciones en caché y forzar la obtención de datos frescos?\n\n" +
                        "Esto es útil si:\n" +
                        "• Ves posiciones muy antiguas\n" +
                        "• El GPS no se actualiza\n" +
                        "• Hay problemas de sincronización"
            )
            .setPositiveButton("🧹 Sí, Limpiar") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    performClearOldPositions(deviceUniqueId)
                }
            }
            .setNeutralButton("🔄 Solo Refrescar") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    performForceRefresh(deviceUniqueId)
                }
            }
            .setNegativeButton("❌ Cancelar", null)
            .show()
    }
// ============ SOLO REFRESCAR SIN LIMPIAR ============
    private fun showPositionTroubleshootingDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("🔧 Solución de Problemas de Posición")
            .setMessage(
                buildString {
                    appendLine("Si tienes problemas con posiciones antiguas:")
                    appendLine()
                    appendLine("🧹 LIMPIAR POSICIONES ANTIGUAS:")
                    appendLine("• Long press en 'Estado del Dispositivo'")
                    appendLine("• Selecciona 'Sí, Limpiar'")
                    appendLine()
                    appendLine("🔄 SOLO REFRESCAR:")
                    appendLine("• Long press en 'Ubicación'")
                    appendLine("• O Long press en 'Estado' → 'Solo Refrescar'")
                    appendLine()
                    appendLine("📱 CONFIGURAR GPS:")
                    appendLine("• Verifica que tu app GPS esté enviando datos")
                    appendLine("• Usa 'Mostrar Configuración' para revisar settings")
                    appendLine("• Asegúrate de que el timestamp sea correcto")
                    appendLine()
                    appendLine("⏰ CALIDAD DE POSICIONES:")
                    appendLine("• 🟢 Excelente: < 5 minutos")
                    appendLine("• 🟡 Buena: < 15 minutos")
                    appendLine("• 🟠 Aceptable: < 30 minutos")
                    appendLine("• 🔴 Muy antigua: > 30 minutos")
                    appendLine()
                    appendLine("💡 TIP: Las posiciones muestran su edad y calidad")
                }
            )
            .setPositiveButton("✅ Entendido", null)
            .setNeutralButton("🧹 Limpiar Ahora") { _, _ ->
                getDeviceUniqueId()?.let { uniqueId ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        performClearOldPositions(uniqueId)
                    }
                }
            }
            .show()
    }
    private fun getDeviceUniqueId(): String? {
        return sharedPreferences.getString(DEVICE_UNIQUE_ID_PREF, null)
    }

    // ============ RECIBIENDO POSICIONES ANTIGUAS
    private fun createSafeZoneWithOldPosition(deviceUniqueId: String) {
        binding.buttonZonaSegura.apply {
            text = "⚠️ Usando posición antigua..."
            isEnabled = false
        }

        lifecycleScope.launch {
            try {
                val json = JSONObject().apply {
                    put("deviceId", deviceUniqueId)
                }

                val requestBody = json.toString().toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("https://carefully-arriving-shepherd.ngrok-free.app/api/safezone/force-old-position")
                    .post(requestBody)
                    .addHeader("Authorization", "Bearer $JWT_TOKEN")
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    val responseJson = JSONObject(responseBody ?: "{}")

                    val lat = responseJson.getJSONObject("safeZone").getDouble("latitude")
                    val lon = responseJson.getJSONObject("safeZone").getDouble("longitude")
                    val warning = responseJson.optString("warning", "")
                    val ageHours = responseJson.optInt("ageHours", 0)

                    val geoPoint = GeoPoint(lat, lon)
                    createSafeZoneSuccessfully(geoPoint, deviceUniqueId, ageHours * 60) // Convertir a minutos

                    // Mostrar warning adicional
                    handler.postDelayed({
                        showSnackbar("⚠️ $warning", Snackbar.LENGTH_LONG)
                    }, 1000)

                } else {
                    val errorBody = response.body?.string()
                    throw Exception("Error del servidor: $errorBody")
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error creating safe zone with old position", e)
                showSnackbar("❌ Error: ${e.message}", Snackbar.LENGTH_LONG)
                restoreSafeZoneButton()
            }
        }
    }

// ============ FUNCIÓN HELPER PARA CREAR ZONA SEGURA EXITOSA ============

    private fun createSafeZoneSuccessfully(geoPoint: GeoPoint, deviceUniqueId: String, ageMinutes: Int?) {
        // Actualizar UI y caché
        safeZone = geoPoint
        safeZoneCache.set(geoPoint)
        updateSafeZoneUI(geoPoint)

        // Guardar en preferencias
        sharedPreferences.edit {
            putString(PREF_SAFEZONE_LAT, geoPoint.latitude.toString())
            putString(PREF_SAFEZONE_LON, geoPoint.longitude.toString())
        }

        // Actualizar botón con información de edad
        val ageInfo = when {
            ageMinutes == null -> ""
            ageMinutes < 60 -> " (${ageMinutes}min)"
            ageMinutes < 1440 -> " (${ageMinutes/60}h)"
            else -> " (${ageMinutes/1440}d)"
        }

        binding.buttonZonaSegura.apply {
            text = "Zona Segura Activa ✓$ageInfo"
            isEnabled = true
        }

        val successMessage = if (ageMinutes != null && ageMinutes > 60) {
            "✅ Zona segura creada con posición de ${ageMinutes/60}h. Configura GPS para datos actuales."
        } else {
            "✅ Zona segura establecida correctamente"
        }

        showSnackbar(successMessage, Snackbar.LENGTH_LONG)

        Log.d(TAG, "✅ Safe zone established successfully for uniqueId: $deviceUniqueId (age: ${ageMinutes}min)")
    }

// ============ HELPER PARA EXTRAER EDAD DEL ERROR ============

    private fun extractAgeFromError(errorMessage: String?): String {
        return try {
            when {
                errorMessage?.contains("horas") == true -> {
                    val hours = errorMessage.substringAfter("(").substringBefore(" horas").toIntOrNull()
                    if (hours != null) " (${hours} horas)" else ""
                }
                errorMessage?.contains("hour") == true -> {
                    val hours = errorMessage.substringAfter("(").substringBefore(" hour").toIntOrNull()
                    if (hours != null) " (${hours} horas)" else ""
                }
                else -> ""
            }
        } catch (e: Exception) {
            ""
        }
    }

// ============ RESTAURAR BOTÓN DE ZONA SEGURA ============

    private fun restoreSafeZoneButton() {
        binding.buttonZonaSegura.apply {
            text = "Establecer Zona Segura"
            isEnabled = true
        }
    }
    // ✅ FUNCIÓN PRINCIPAL DE LOGOUT
    private fun logout() {
        try {
            Log.d(TAG, "Initiating logout process")

            // 1. Mostrar confirmación al usuario
            showLogoutConfirmation()

        } catch (e: Exception) {
            Log.e(TAG, "Error during logout", e)
            forceLogout()
        }
    }

    // ✅ MOSTRAR DIÁLOGO DE CONFIRMACIÓN
    private fun showLogoutConfirmation() {
        try {
            AlertDialog.Builder(requireContext())
                .setTitle("Cerrar Sesión")
                .setMessage("¿Estás seguro que deseas cerrar la sesión?")
                .setPositiveButton("Cerrar Sesión") { _, _ ->
                    performLogout()
                }
                .setNegativeButton("Cancelar") { dialog, _ ->
                    dialog.dismiss()
                }
                .setCancelable(true)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing logout confirmation", e)
            performLogout() // Logout directo si hay error
        }
    }

    // ✅ EJECUTAR LOGOUT
    private fun performLogout()     {
        try {
            Log.d(TAG, "Performing logout")

            // 1. Limpiar sesión guardada
            clearUserSession()

            // 2. Detener WebSocket si existe
            stopWebSocket()

            // 3. Mostrar mensaje
            Toast.makeText(requireContext(), "Sesión cerrada", Toast.LENGTH_SHORT).show()

            // 4. Navegar a FirstFragment
            navigateToFirstFragment()

        } catch (e: Exception) {
            Log.e(TAG, "Error performing logout", e)
            forceLogout()
        }
    }

    // ✅ LIMPIAR SESIÓN GUARDADA
    private fun clearUserSession() {
        try {
            with(sharedPreferences.edit()) {
                remove("jwt_token")
                remove("user_email")
                remove("user_password")
                remove("session_timestamp")
                clear() // Limpiar todo
                apply()
            }
            Log.d(TAG, "User session cleared successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing user session", e)
        }
    }

    // ✅ DETENER WEBSOCKET
    private fun stopWebSocket() {
        try {
            // Si tienes WebSocket, detenerlo aquí
            // webSocket?.close()
            Log.d(TAG, "WebSocket stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping WebSocket", e)
        }
    }

    // ✅ NAVEGAR A FIRSTFRAGMENT SEGURO
    private fun navigateToFirstFragment() {
        try {
            if (!isAdded || isDetached || _binding == null) {
                Log.w(TAG, "Cannot navigate - fragment not in valid state")
                forceLogout()
                return
            }

            val navController = findNavController()

            // ✅ OPCIÓN 1: NAVEGAR CON POPBACKSTACK (RECOMENDADO)
            if (navController.currentDestination?.id == R.id.SecondFragment) {
                Log.d(TAG, "Navigating back to FirstFragment")

                // Limpiar el back stack y ir a FirstFragment
                navController.popBackStack(R.id.FirstFragment, false)

            } else {
                Log.w(TAG, "Not in SecondFragment, current: ${navController.currentDestination?.id}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error navigating to FirstFragment", e)
            forceLogout()
        }
    }

    // ✅ LOGOUT FORZADO EN CASO DE ERROR
    private fun forceLogout() {
        try {
            Log.w(TAG, "Force logout initiated")

            // Limpiar sesión
            clearUserSession()

            // Reiniciar la actividad completamente
            val intent = requireActivity().intent
            requireActivity().finish()
            startActivity(intent)

        } catch (e: Exception) {
            Log.e(TAG, "Error in force logout", e)

            // Último recurso: salir de la app
            requireActivity().finishAffinity()
        }
    }

    // ✅ CONFIGURAR BOTÓN DE LOGOUT EN TU VISTA
    private fun setupLogoutButton() {
        // Ejemplo de cómo agregar el botón de logout
        binding.buttonLogout.setOnClickListener {
            logout()
        }

        // O si usas un menú:
        // menuItem.setOnMenuItemClickListener { logout(); true }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun performClearOldPositions(deviceUniqueId: String) {
        Log.d(TAG, "🧹 Clearing old positions for device: $deviceUniqueId")

        // ✅ VALIDACIONES INICIALES
        if (JWT_TOKEN.isNullOrEmpty()) {
            Log.e(TAG, "❌ JWT_TOKEN is null")
            showSnackbar("❌ Token de autenticación faltante", Snackbar.LENGTH_LONG)
            return
        }

        if (deviceUniqueId.isBlank()) {
            Log.e(TAG, "❌ deviceUniqueId is blank")
            showSnackbar("❌ ID de dispositivo inválido", Snackbar.LENGTH_SHORT)
            return
        }

        updateStatusUI("🧹 Limpiando posiciones antiguas...", android.R.color.darker_gray)

        // ✅ LIMPIAR EN EL VIEWMODEL PRIMERO (sin crash)
        try {
            viewModel.clearOldPositionsAndForceRefresh(deviceUniqueId)
            Log.d(TAG, "✅ ViewModel cache cleared")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error clearing ViewModel cache: ${e.message}")
            // Continuar de todos modos
        }

        // ✅ LIMPIAR EN EL SERVIDOR CON DISPATCHER CORRECTO
        lifecycleScope.launch(Dispatchers.IO) { // ✅ USAR DISPATCHER.IO PARA NETWORK
            try {
                Log.d(TAG, "🌐 Sending clear request to server...")

                val json = JSONObject().apply {
                    put("deviceId", deviceUniqueId.trim()) // ✅ TRIM por seguridad
                }

                val requestBody = json.toString().toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("https://carefully-arriving-shepherd.ngrok-free.app/api/clear-old-positions")
                    .post(requestBody)
                    .addHeader("Authorization", "Bearer $JWT_TOKEN")
                    .addHeader("Content-Type", "application/json")
                    .build()

                Log.d(TAG, "📤 Sending request: ${json}")

                // ✅ LLAMADA DE RED EN BACKGROUND THREAD
                val response = client.newCall(request).execute()

                Log.d(TAG, "📥 Response code: ${response.code}")

                // ✅ CAMBIAR A MAIN THREAD PARA UI UPDATES
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string()
                        Log.d(TAG, "📥 Response body: $responseBody")

                        if (responseBody.isNullOrEmpty()) {
                            // ✅ MANEJAR RESPUESTA VACÍA
                            showSnackbar("✅ Cache limpiado (sin respuesta del servidor)", Snackbar.LENGTH_SHORT)
                            updateStatusUI("✅ Cache limpiado", android.R.color.holo_green_dark)
                        } else {
                            try {
                                val responseJson = JSONObject(responseBody)
                                val message = responseJson.optString("message", "Cache limpiado")
                                val recentPositions = responseJson.optInt("recentPositionsFound", 0)

                                // ✅ INFORMACIÓN ADICIONAL DEL SERVIDOR (OPCIONAL)
                                val latestAge = responseJson.optInt("latestPositionAge", -1)
                                val latestQuality = responseJson.optString("latestPositionQuality", "unknown")

                                val detailedMessage = buildString {
                                    append("✅ $message")
                                    if (recentPositions > 0) {
                                        append(" ($recentPositions recientes)")
                                    }

                                    if (latestAge >= 0) {
                                        append(" - Última: ${latestAge}min")

                                        val qualityEmoji = when (latestQuality) {
                                            "excellent" -> "🟢"
                                            "good" -> "🟡"
                                            "acceptable" -> "🟠"
                                            else -> "🔵"
                                        }
                                        append(" $qualityEmoji")
                                    }
                                }

                                showSnackbar(detailedMessage, Snackbar.LENGTH_LONG)
                                updateStatusUI("✅ Cache limpiado - esperando datos frescos", android.R.color.holo_green_dark)

                            } catch (jsonError: Exception) {
                                Log.w(TAG, "⚠️ Error parsing JSON response: ${jsonError.message}")
                                // ✅ SI HAY ERROR EN JSON, PERO RESPUESTA EXITOSA
                                showSnackbar("✅ Cache limpiado correctamente", Snackbar.LENGTH_SHORT)
                                updateStatusUI("✅ Cache limpiado", android.R.color.holo_green_dark)
                            }
                        }

                    } else {
                        // ✅ MANEJAR ERRORES HTTP ESPECÍFICOS
                        val errorBody = response.body?.string()
                        Log.e(TAG, "❌ Server error: ${response.code} - $errorBody")

                        val errorMessage = when (response.code) {
                            401 -> "Token expirado. Reinicia sesión."
                            403 -> "Sin permisos para limpiar cache"
                            404 -> "Dispositivo no encontrado"
                            500 -> "Error interno del servidor"
                            else -> "Error del servidor (${response.code})"
                        }

                        showSnackbar("❌ $errorMessage", Snackbar.LENGTH_LONG)
                        updateStatusUI("❌ Error limpiando cache", android.R.color.holo_red_dark)

                        if (response.code == 401) {
                            handleUnauthorizedError()
                        }
                    }
                }

                // ✅ FORZAR NUEVA OBTENCIÓN DESPUÉS DE LIMPIAR (EN BACKGROUND)
                delay(2000)
                withContext(Dispatchers.Main) {
                    try {
                        fetchInitialPosition()
                    } catch (fetchError: Exception) {
                        Log.w(TAG, "⚠️ Error fetching initial position after clear: ${fetchError.message}")
                        showSnackbar("⚠️ Cache limpiado, pero error obteniendo nueva posición", Snackbar.LENGTH_LONG)
                    }
                }

            } catch (networkError: Exception) {
                Log.e(TAG, "❌ Network error clearing old positions: ${networkError.message}", networkError)

                // ✅ CAMBIAR A MAIN THREAD PARA UI UPDATES
                withContext(Dispatchers.Main) {
                    val errorMessage = when {
                        networkError.message?.contains("timeout") == true -> "Timeout - Servidor no responde"
                        networkError.message?.contains("connect") == true -> "Error de conexión"
                        networkError.message?.contains("ssl") == true -> "Error de certificado SSL"
                        networkError is java.net.UnknownHostException -> "Sin conexión a internet"
                        else -> "Error de red: ${networkError.message}"
                    }

                    showSnackbar("❌ $errorMessage", Snackbar.LENGTH_LONG)
                    updateStatusUI("❌ Error de conexión", android.R.color.holo_red_dark)

                    // ✅ OFRECER REINTENTAR
                    handler.postDelayed({
                        if (isAdded) {
                            MaterialAlertDialogBuilder(requireContext())
                                .setTitle("❌ Error de Conexión")
                                .setMessage("No se pudo conectar al servidor. ¿Quieres intentar nuevamente?")
                                .setPositiveButton("🔄 Reintentar") { _, _ ->
                                    performClearOldPositions(deviceUniqueId)
                                }
                                .setNegativeButton("❌ Cancelar", null)
                                .show()
                        }
                    }, 1000)
                }
            }
        }
    }

    // ✅ TAMBIÉN AGREGAR ESTA VERSIÓN MEJORADA DE performForceRefresh
    @RequiresApi(Build.VERSION_CODES.O)
    private fun performForceRefresh(deviceUniqueId: String) {
        Log.d(TAG, "🔄 Force refreshing position for device: $deviceUniqueId")

        updateStatusUI("🔄 Forzando actualización...", android.R.color.darker_gray)

        // ✅ USAR DISPATCHER.IO PARA CUALQUIER OPERACIÓN DE RED POTENCIAL
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // ✅ OBTENER POSICIÓN FRESCA DEL SERVIDOR
                val position = viewModel.getLastPosition(deviceUniqueId)

                // ✅ CAMBIAR A MAIN THREAD PARA UI
                withContext(Dispatchers.Main) {
                    val geoPoint = GeoPoint(position.latitude, position.longitude)

                    // ✅ ACTUALIZAR INMEDIATAMENTE
                    updateVehiclePosition(deviceUniqueId.hashCode(), geoPoint)

                    // ✅ MOSTRAR INFORMACIÓN DETALLADA CON VERIFICACIÓN SEGURA
                    val ageInfo = when {
                        position.age != null && position.age > 0 -> " (${position.age} min)"
                        position.quality != null -> " (${position.quality})"
                        else -> ""
                    }

                    val qualityEmoji = when (position.quality) {
                        "excellent" -> "🟢"
                        "good" -> "🟡"
                        "acceptable" -> "🟠"
                        "too_old" -> "🔴"
                        else -> "🔵"
                    }

                    updateStatusUI("✅ Posición actualizada forzadamente$ageInfo $qualityEmoji", android.R.color.holo_green_dark)
                    showSnackbar("✅ Posición refrescada correctamente$ageInfo", Snackbar.LENGTH_SHORT)
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error force refreshing: ${e.message}")

                // ✅ CAMBIAR A MAIN THREAD PARA UI
                withContext(Dispatchers.Main) {
                    updateStatusUI("❌ Error en actualización forzada", android.R.color.holo_red_dark)

                    if (e.message?.contains("posiciones recientes") == true) {
                        showSnackbar("⚠️ ${e.message}", Snackbar.LENGTH_LONG)

                        // ✅ OFRECER LIMPIAR POSICIONES ANTIGUAS
                        handler.postDelayed({
                            if (isAdded) {
                                MaterialAlertDialogBuilder(requireContext())
                                    .setTitle("⚠️ No hay posiciones recientes")
                                    .setMessage("¿Quieres limpiar las posiciones antiguas y esperar datos frescos del GPS?")
                                    .setPositiveButton("🧹 Sí, Limpiar") { _, _ ->
                                        performClearOldPositions(deviceUniqueId)
                                    }
                                    .setNegativeButton("❌ No", null)
                                    .show()
                            }
                        }, 1000)
                    } else {
                        showSnackbar("❌ Error: ${e.message}", Snackbar.LENGTH_LONG)
                    }
                }
            }
        }
    }
    // ✅ FUNCIÓN AUXILIAR PARA VALIDAR ESTADO DEL FRAGMENT
    private fun isFragmentValid(): Boolean {
        return isAdded && !isDetached && _binding != null && isResumed
    }
    // ✅ VERSIÓN MEJORADA DE updateStatusUI CON VALIDACIÓN
    private fun updateStatusUI(message: String, colorResId: Int? = null) {
        if (!isFragmentValid()) {
            Log.w(TAG, "Fragment not ready for UI update, skipping: $message")
            return
        }

        try {
            binding.textDeviceStatus.text = message
            binding.textDeviceStatus.visibility = View.VISIBLE

            colorResId?.let {
                binding.textDeviceStatus.setTextColor(
                    ContextCompat.getColor(requireContext(), it)
                )
            }

            // ✅ AGREGAR TIMESTAMP AL STATUS SOLO SI NO ES ERROR
            if (!message.contains("❌") && !message.contains("🔄")) {
                val timeString = getCurrentTime()
                binding.textDeviceStatus.text = "$message ($timeString)"
            }

            Log.d(TAG, "✅ Status updated: $message")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error updating status UI: ${e.message}")
        }
    }
    // ✅ REEMPLAZAR setupWebSocket en SecondFragment con esta versión ultra-estable

    private fun setupWebSocket() {
        if (JWT_TOKEN.isNullOrEmpty()) {
            Log.e(TAG, "❌ JWT_TOKEN is null or empty")
            showSnackbar("❌ Token de autenticación faltante.", Snackbar.LENGTH_LONG)
            return
        }

        val deviceUniqueId = getDeviceUniqueId()
        if (deviceUniqueId == null) {
            Log.e(TAG, "❌ No device uniqueId for WebSocket connection")
            updateStatusUI("⚠️ No hay dispositivo asociado", android.R.color.holo_orange_dark)
            return
        }

        Log.d(TAG, "🔗 Setting up ULTRA-STABLE WebSocket for device uniqueId: $deviceUniqueId")

        // ✅ CANCELAR RECONEXIONES PREVIAS
        cancelReconnect()

        // ✅ CERRAR CONEXIÓN EXISTENTE LIMPIAMENTE
        webSocket?.close(1000, "Reconnecting")
        webSocket = null

        val wsUrl = "wss://carefully-arriving-shepherd.ngrok-free.app/ws?token=$JWT_TOKEN"
        Log.d(TAG, "🔗 Connecting to WebSocket...")

        // ✅ CLIENT MEJORADO CON KEEPALIVE AGRESIVO
        val wsClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // ✅ SIN TIMEOUT DE LECTURA
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS) // ✅ PING CADA 15 SEGUNDOS
            .retryOnConnectionFailure(true)
            .build()

        val request = Request.Builder()
            .url(wsUrl)
            .header("Origin", "https://carefully-arriving-shepherd.ngrok-free.app")
            .header("User-Agent", "PeregrinoGPS-Android/1.0")
            .header("Connection", "Upgrade") // ✅ FORZAR UPGRADE
            .header("Upgrade", "websocket")
            .build()

        updateStatusUI("🔄 Conectando WebSocket ultra-estable...", android.R.color.darker_gray)

        webSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            private var lastPongTime = System.currentTimeMillis()
            private var manualPingTimer: Timer? = null

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "🔗 WebSocket conectado exitosamente!")
                lastPongTime = System.currentTimeMillis()

                lifecycleScope.launch(Dispatchers.Main) {
                    updateStatusUI("✅ WebSocket conectado", android.R.color.holo_green_dark)
                    showSnackbar("✅ Conectado al servidor en tiempo real", Snackbar.LENGTH_SHORT)
                }

                // ✅ PING INICIAL INMEDIATO
                val pingMessage = JSONObject().apply {
                    put("type", "PING")
                    put("deviceId", deviceUniqueId)
                    put("timestamp", System.currentTimeMillis())
                }

                webSocket.send(pingMessage.toString())
                Log.d(TAG, "📤 Initial PING sent to server")

                // ✅ TIMER MANUAL DE PING COMO BACKUP
                startManualPingTimer(webSocket, deviceUniqueId)
            }

            @RequiresApi(Build.VERSION_CODES.O)
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "📨 WebSocket message received: ${text.take(200)}...")

                try {
                    val json = JSONObject(text)
                    val type = json.getString("type")

                    when (type) {
                        "POSITION_UPDATE" -> {
                            Log.d(TAG, "📍 Processing position update")
                            handlePositionUpdate(json, deviceUniqueId)
                        }
                        "CONNECTION_CONFIRMED" -> {
                            Log.d(TAG, "✅ Connection confirmed by server")
                            lifecycleScope.launch(Dispatchers.Main) {
                                showSnackbar("✅ Conectado y suscrito correctamente", Snackbar.LENGTH_SHORT)
                                updateStatusUI("✅ Suscrito a actualizaciones", android.R.color.holo_green_dark)
                            }
                        }
                        "SUBSCRIBE_DEVICE" -> {
                            val deviceId = json.optString("deviceId", "")
                            Log.d(TAG, "🔔 Device subscription confirmed: $deviceId")
                        }
                        "SUBSCRIPTION_COMPLETE" -> {
                            Log.d(TAG, "✅ All subscriptions completed")
                        }
                        "PONG" -> {
                            lastPongTime = System.currentTimeMillis()
                            Log.d(TAG, "💓 Pong received from server - connection healthy")
                        }
                        "ERROR" -> {
                            val errorMsg = json.optString("message", "Error desconocido")
                            Log.e(TAG, "❌ WebSocket error: $errorMsg")
                            lifecycleScope.launch(Dispatchers.Main) {
                                showSnackbar("❌ Error del servidor: $errorMsg", Snackbar.LENGTH_LONG)
                            }
                        }
                        else -> {
                            Log.d(TAG, "❓ Unknown message type: $type")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error processing WebSocket message: ${e.message}", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "❌ WebSocket failure: ${t.message}")
                Log.e(TAG, "❌ Response: ${response?.code} - ${response?.message}")

                // ✅ DETENER PING TIMER
                manualPingTimer?.cancel()

                lifecycleScope.launch(Dispatchers.Main) {
                    updateStatusUI("🔴 Conexión perdida - Reintentando...", android.R.color.holo_red_dark)
                    showSnackbar("❌ Conexión perdida - Reintentando en 5s...", Snackbar.LENGTH_SHORT)
                }

                // ✅ RECONEXIÓN INTELIGENTE CON BACKOFF
                if (shouldReconnect.get() && isAdded) {
                    val timeSinceLastPong = System.currentTimeMillis() - lastPongTime
                    Log.d(TAG, "🔄 Last pong was ${timeSinceLastPong}ms ago")
                    scheduleIntelligentReconnect()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "🔐 WebSocket cerrado: code=$code, reason=$reason")

                // ✅ DETENER PING TIMER
                manualPingTimer?.cancel()

                lifecycleScope.launch(Dispatchers.Main) {
                    updateStatusUI("🔴 Desconectado del servidor", android.R.color.holo_red_dark)
                }

                if (shouldReconnect.get() && isAdded && code != 1000) {
                    Log.d(TAG, "🔄 Scheduling reconnect due to unexpected close")
                    scheduleIntelligentReconnect()
                }
            }

            // ✅ FUNCIÓN PARA PING MANUAL AGRESIVO
            private fun startManualPingTimer(ws: WebSocket, deviceId: String) {
                manualPingTimer?.cancel()

                manualPingTimer = Timer().apply {
                    scheduleAtFixedRate(object : TimerTask() {
                        override fun run() {
                            try {
                                if (ws.hashCode() == webSocket?.hashCode()) { // ✅ VERIFICAR QUE SEA LA MISMA CONEXIÓN
                                    val timeSinceLastPong = System.currentTimeMillis() - lastPongTime

                                    if (timeSinceLastPong > 45000) { // ✅ 45 segundos sin PONG = reconectar
                                        Log.w(TAG, "⚠️ No pong for ${timeSinceLastPong}ms - forcing reconnect")
                                        ws.close(1000, "Ping timeout")
                                        return
                                    }

                                    val pingMessage = JSONObject().apply {
                                        put("type", "PING")
                                        put("deviceId", deviceId)
                                        put("timestamp", System.currentTimeMillis())
                                        put("keepalive", true)
                                    }

                                    ws.send(pingMessage.toString())
                                    Log.d(TAG, "💓 Manual keepalive ping sent")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ Error in manual ping: ${e.message}")
                            }
                        }
                    }, 20000, 20000) // ✅ PING CADA 20 SEGUNDOS
                }
            }
        })
    }

    // ✅ RECONEXIÓN INTELIGENTE CON BACKOFF EXPONENCIAL
    private fun scheduleIntelligentReconnect() {
        cancelReconnect()

        val baseDelay = 3000L
        val jitter = (Math.random() * 2000).toLong()
        val delay = baseDelay + jitter

        reconnectRunnable = Runnable {
            if (shouldReconnect.get() && isAdded && !isDetached) {
                Log.d(TAG, "🔄 Attempting intelligent WebSocket reconnection...")
                setupWebSocket()
            }
        }
        handler.postDelayed(reconnectRunnable!!, delay)
        Log.d(TAG, "⏰ Intelligent reconnect scheduled in ${delay}ms")
    }
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onResume() {
        super.onResume()

        // ===== REANUDAR MAPA PRIMERO =====
        map?.onResume()

        // ===== CONFIGURACIÓN POST-RESUME =====
        shouldReconnect.set(true)


        // ✅ REGISTRAR RECEIVER con flag explícito
        val filter = IntentFilter().apply {
            addAction("com.peregrino.SAFEZONE_DISABLED")
            addAction("com.peregrino.SAFEZONE_ALERT")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Para Android 13+ usa RECEIVER_NOT_EXPORTED (recomendado para receivers internos)
            ContextCompat.registerReceiver(
                requireContext(),
                safeZoneReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } else {
            // Para versiones anteriores, usa el método tradicional
            requireContext().registerReceiver(safeZoneReceiver, filter)
        }


        Log.d(TAG, "✅ SafeZone receiver registrado")
        // Verificar que la sesión sigue válida
        val token = sharedPreferences.getString("jwt_token", null)
        if (token == null) {
            Log.w(TAG, "No token found on resume, redirecting to login")
            navigateToFirstFragment()
        }
    }

    override fun onPause() {
        super.onPause()

        // ===== PAUSAR MAPA CORRECTAMENTE =====
        map?.onPause()

        // ===== LIMPIEZA NORMAL =====
        shouldReconnect.set(false)
        cancelStatusCheck()
        cancelReconnect()
        handler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "Fragment pausado")
        webSocket = null

        Log.d(TAG, "⏸️ Fragment pausado - mapa pausado correctamente")
        // ✅ DESREGISTRAR RECEIVER
        try {
            requireContext().unregisterReceiver(safeZoneReceiver)
            Log.d(TAG, "✅ SafeZone receiver desregistrado")
        } catch (e: Exception) {
            Log.w(TAG, "Receiver ya desregistrado: ${e.message}")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()

        // Limpieza completa incluyendo nuevas variables
        shouldReconnect.set(false)
        isFollowingVehicle.set(false)
        safeZoneCenter = null
        currentVehicleState = VehicleState.NORMAL

        cancelStatusCheck()
        cancelReconnect()
        handler.removeCallbacksAndMessages(null)

        // Cerrar WebSocket
        webSocket?.close(1000, "Fragment destruido")
        webSocket = null

        // Detener servicio
        requireContext().stopService(Intent(requireContext(), TrackingService::class.java))

        // Limpiar mapa
        myLocationOverlay?.disableMyLocation()
        myLocationOverlay?.disableFollowLocation()
        map?.overlays?.clear()
        map?.onDetach()

        // Limpiar referencias
        vehicleMarker.set(null)
        safeZonePolygon.set(null)
        myLocationOverlay = null
        map = null

        // Limpiar caches locales
        deviceInfoCache.clear()
        safeZoneCache.clear()
        lastPositionCache.clear()
        // ✅ SOLO DETENER ALARMA SI REALMENTE SALIMOS DE LA APP
        if (requireActivity().isFinishing) {
            stopAlarmIfActive()
            Log.d(TAG, "🔇 Alarma detenida: app cerrándose")
        } else {
            Log.d(TAG, "🎵 Alarma continúa: solo navegación interna")
        }
        _binding = null
        Log.d(TAG, "Vista del fragment destruida")
    }
}
