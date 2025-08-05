package com.zebass.peregrino

import android.Manifest
import android.R.drawable
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getDrawable
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.zebass.peregrino.databinding.FragmentSecondBinding
import com.zebass.peregrino.service.TrackingService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import okhttp3.*
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class SecondFragment : Fragment() {

    // ============ BINDING Y PROPIEDADES CORE ============
    private var _binding: FragmentSecondBinding? = null
    private val binding get() = _binding!!
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

    companion object {
        var JWT_TOKEN: String? = ""
        private var safeZone: GeoPoint? = null
        const val PREF_SAFEZONE_LAT = "safezone_lat"
        const val PREF_SAFEZONE_LON = "safezone_lon"
        const val DEVICE_ID_PREF = "associated_device_id"
        const val DEVICE_NAME_PREF = "associated_device_name"
        const val DEVICE_UNIQUE_ID_PREF = "associated_device_unique_id"
        const val GEOFENCE_RADIUS = 15.0
        const val RECONNECT_DELAY = 5000L
        const val STATUS_CHECK_INTERVAL = 30000L
        const val POSITION_UPDATE_THROTTLE = 1000L // Mínimo 1s entre updates
        const val TAG = "SecondFragment"
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
            userAgentValue = "PeregrinoGPS/1.0"                 // CORREGIDO

            // ===== CONFIGURACIÓN DE DEBUG =====
            isDebugMode = false                                  // Sin debug para mejor rendimiento
            isDebugTileProviders = false
        }

        _binding = FragmentSecondBinding.inflate(inflater, container, false)
        sharedPreferences = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        viewModel.setContext(requireContext())
        return binding.root
    }


    // Fix en onViewCreated para mejor inicialización
    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


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

        // USAR EL UNIQUE_ID REAL DE LAS PREFERENCIAS
        val deviceUniqueId = sharedPreferences.getString(DEVICE_UNIQUE_ID_PREF, null)
        if (deviceUniqueId == null) {
            showSnackbar("❌ No hay dispositivo asociado", Snackbar.LENGTH_SHORT)
            updateStatusUI("❌ No hay dispositivo asociado", android.R.color.holo_red_dark)
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

        // Usar uniqueId REAL
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
            buttonMyLocation.setOnClickListener {
                centerOnMyLocation()
            }

            // Long click para centrar en vehículo
            buttonMyLocation.setOnLongClickListener {
                centerOnVehicle()
                true
            }

            buttonZonaSegura.setOnClickListener { handleSafeZoneButton() }
            buttonAssociateDevice.setOnClickListener { showAssociateDeviceDialog() }
            buttonDeviceStatus.setOnClickListener { checkDeviceStatus() }
            buttonShowConfig.setOnClickListener { showTraccarClientConfig() }

            // Ocultar elementos no necesarios
            buttonDescargarOffline.visibility = View.GONE
            progressBarDownload.visibility = View.GONE
        }
        Log.d(TAG, "✅ Botones configurados - Mi ubicación (tap), Vehículo (long press)")
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
        val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)
        val deviceName = sharedPreferences.getString(DEVICE_NAME_PREF, null)
        val deviceUniqueId = sharedPreferences.getString(DEVICE_UNIQUE_ID_PREF, null)

        val hasDevice = deviceId != -1 &&
                !deviceName.isNullOrEmpty() &&
                !deviceUniqueId.isNullOrEmpty()

        Log.d(TAG, "hasAssociatedDevice: $hasDevice (deviceId=$deviceId, name=$deviceName, uniqueId=$deviceUniqueId)")

        return hasDevice
    }
    private fun observeViewModel() {
        // Observar posición del vehículo
        lifecycleScope.launch {
            viewModel.vehiclePosition.collectLatest { position ->
                position?.let {
                    if (shouldUpdatePosition()) {
                        Log.d(TAG, "🚗 Vehicle position from ViewModel: deviceId=${it.first}, position=${it.second}")
                        updateVehiclePosition(it.first, GeoPoint(it.second.latitude, it.second.longitude))
                        lastPositionUpdate.set(System.currentTimeMillis())
                    }
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
                    showSnackbar(it, Snackbar.LENGTH_LONG)
                    Log.e(TAG, "❌ Error from ViewModel: $it")
                    if (it.contains("401")) {
                        handleUnauthorizedError()
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



    private fun startTrackingService() {
        if (!hasAssociatedDevice()) return

        val intent = Intent(requireContext(), TrackingService::class.java).apply {
            putExtra("jwtToken", JWT_TOKEN)
            putExtra("deviceId", sharedPreferences.getInt(DEVICE_ID_PREF, -1))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(intent)
        } else {
            requireContext().startService(intent)
        }
        Log.d(TAG, "TrackingService iniciado")
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
    private fun fetchInitialPosition() {
        if (!hasAssociatedDevice()) {
            Log.w(TAG, "⚠️ No associated device for initial position fetch")
            return
        }

        // USAR EL UNIQUE_ID REAL DE LAS PREFERENCIAS
        val deviceUniqueId = sharedPreferences.getString(DEVICE_UNIQUE_ID_PREF, null)
        if (deviceUniqueId == null) {
            Log.e(TAG, "❌ No device uniqueId found in preferences")
            updateStatusUI("❌ No se encontró ID único del dispositivo", android.R.color.holo_red_dark)
            return
        }

        Log.d(TAG, "📍 Fetching initial position for device uniqueId: $deviceUniqueId")

        // Check cache primero usando uniqueId REAL
        lastPositionCache.get()?.let { cached ->
            Log.d(TAG, "✅ Using cached position for uniqueId: $deviceUniqueId")
            updateVehiclePosition(deviceUniqueId.toIntOrNull() ?: deviceUniqueId.hashCode(), cached)
            return
        }

        lifecycleScope.launch {
            try {
                updateStatusUI("🔄 Obteniendo posición inicial...", android.R.color.darker_gray)

                // Pasar uniqueId REAL al ViewModel
                val position = viewModel.getLastPosition(deviceUniqueId)
                val geoPoint = GeoPoint(position.latitude, position.longitude)

                Log.d(TAG, "✅ Initial position fetched for uniqueId $deviceUniqueId: lat=${position.latitude}, lon=${position.longitude}")
                updateVehiclePosition(deviceUniqueId.toIntOrNull() ?: deviceUniqueId.hashCode(), geoPoint)

                updateStatusUI("✅ Posición inicial cargada", android.R.color.holo_green_dark)

            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to fetch initial position for uniqueId $deviceUniqueId", e)
                val errorMsg = when {
                    e.message?.contains("404") == true -> "No hay posiciones disponibles para este dispositivo"
                    e.message?.contains("403") == true -> "Dispositivo no autorizado"
                    e.message?.contains("401") == true -> "Token de autenticación inválido"
                    else -> "Error al obtener posición inicial: ${e.message}"
                }

                showSnackbar("❌ $errorMsg", Snackbar.LENGTH_LONG)
                updateStatusUI("❌ $errorMsg", android.R.color.holo_red_dark)

                if (e.message?.contains("401") == true) {
                    handleUnauthorizedError()
                }
            }
        }
    }

    // Fix en setupWebSocket con mejor manejo de errores
    private fun setupWebSocket() {
        if (JWT_TOKEN.isNullOrEmpty()) {
            Log.e(TAG, "❌ JWT_TOKEN is null or empty")
            showSnackbar("❌ Token de autenticación faltante. Inicia sesión nuevamente.", Snackbar.LENGTH_LONG)
            handleUnauthorizedError()
            return
        }

        val deviceUniqueId = getDeviceUniqueId()
        if (deviceUniqueId == null) {
            Log.e(TAG, "❌ No device uniqueId for WebSocket connection")
            updateStatusUI("⚠️ No hay dispositivo asociado", android.R.color.holo_orange_dark)
            return
        }

        Log.d(TAG, "🔗 Setting up WebSocket for device uniqueId: $deviceUniqueId")

        // Cancel any existing reconnect
        cancelReconnect()

        // Close existing connection
        webSocket?.close(1000, "Reconnecting")
        webSocket = null

        val wsUrl = "wss://carefully-arriving-shepherd.ngrok-free.app/ws?token=$JWT_TOKEN"
        Log.d(TAG, "🔗 WebSocket URL: ${wsUrl.replace(JWT_TOKEN ?: "", "***TOKEN***")}")

        val request = Request.Builder()
            .url(wsUrl)
            .header("Origin", "https://carefully-arriving-shepherd.ngrok-free.app")
            .build()

        updateStatusUI("🔄 Conectando WebSocket...", android.R.color.darker_gray)
        webSocket = client.newWebSocket(request, createWebSocketListener(deviceUniqueId))
    }


    // Fix en handlePositionUpdate con mejor logging
    private fun handlePositionUpdate(json: JSONObject, expectedDeviceUniqueId: String) {
        try {
            val data = json.getJSONObject("data")
            val deviceId = data.getString("deviceId") // uniqueId como string
            val lat = data.getDouble("latitude")
            val lon = data.getDouble("longitude")
            val timestamp = data.optString("timestamp", "")

            Log.d(TAG, "📍 Position update received: deviceUniqueId=$deviceId, lat=$lat, lon=$lon")

            // Verificar que es nuestro dispositivo usando uniqueId
            if (deviceId != expectedDeviceUniqueId) {
                Log.w(TAG, "⚠️ Position for different device: received=$deviceId, expected=$expectedDeviceUniqueId")
                return
            }

            lifecycleScope.launch(Dispatchers.Main) {
                if (shouldUpdatePosition()) {
                    Log.d(TAG, "🔄 Processing position update on main thread")

                    // CAMBIO CRÍTICO: Usar el ViewModel para actualizar la posición
                    val geoPoint = GeoPoint(lat, lon)
                    viewModel.updateVehiclePosition(deviceId, geoPoint)

                    // También actualizar directamente el mapa para asegurar que se muestre
                    updateVehiclePosition(deviceId.hashCode(), geoPoint)

                    lastPositionUpdate.set(System.currentTimeMillis())

                    // Update status
                    updateStatusUI("🟢 Última actualización: ${getCurrentTime()}", android.R.color.holo_green_dark)

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

    private fun scheduleReconnect() {
        cancelReconnect()
        reconnectRunnable = Runnable {
            if (shouldReconnect.get() && isAdded) {
                Log.d(TAG, "Reconectando WebSocket...")
                setupWebSocket()
            }
        }
        handler.postDelayed(reconnectRunnable!!, RECONNECT_DELAY)
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

        // ===== GESTIÓN DE MARCADOR OPTIMIZADA =====
        var marker = vehicleMarker.get()
        if (marker == null) {
            // Primera vez - crear marcador
            marker = createVehicleMarker(deviceId, position)
            vehicleMarker.set(marker)
            map?.overlays?.add(marker)

            // Solo centrar en el primer marcador
            map?.controller?.animateTo(position, 16.0, 1000L)
            Log.d(TAG, "✅ Created vehicle marker and centered (first time only)")

        } else {
            // Actualizaciones - solo mover marcador
            updateMarkerPosition(marker, deviceId, position)
            Log.d(TAG, "✅ Updated vehicle marker position (no map movement)")
        }

        // Verificar zona segura
        checkSafeZone(position, deviceId)

        // ===== INVALIDACIÓN OPTIMIZADA =====
        map?.invalidate()  // CORREGIDO
    }


    private fun createVehicleMarker(deviceId: Int, position: GeoPoint): Marker {
        return Marker(map).apply {
            this.position = position

            // ===== CONFIGURACIÓN DE ANCHOR OPTIMIZADA =====
            setAnchor(0.5f, 1.0f)           // Centro-abajo para mejor visibilidad

            // ===== ICONO OPTIMIZADO =====
            icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_vehicle)

            // ===== CONFIGURACIÓN DE RENDIMIENTO =====
            title = formatMarkerTitle(deviceId, position)
            isDraggable = false
            setInfoWindow(null)              // Sin info window para mejor rendimiento

            // ===== CONFIGURACIÓN VISUAL =====
            alpha = 1.0f                     // Totalmente opaco
        }
    }


    private fun updateMarkerPosition(marker: Marker, deviceId: Int, position: GeoPoint) {
        marker.position = position
        marker.title = formatMarkerTitle(deviceId, position)
    }

    private fun formatMarkerTitle(deviceId: Int, position: GeoPoint): String {
        return "Vehículo ID: $deviceId\nLat: ${"%.6f".format(position.latitude)}\nLon: ${"%.6f".format(position.longitude)}"
    }

    private fun checkSafeZone(position: GeoPoint, deviceId: Int) {
        safeZone?.let { zone ->
            val distance = zone.distanceToAsDouble(position)
            val marker = vehicleMarker.get()

            if (distance > GEOFENCE_RADIUS) {
                marker?.icon = getDrawable(requireContext(), R.drawable.ic_vehicle_alert)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    triggerAlarm(deviceId, distance)
                }
            } else {
                marker?.icon = getDrawable(requireContext(), R.drawable.ic_vehicle)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun triggerAlarm(deviceId: Int, distance: Double) {
        val vibrator = requireContext().getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        vibrator.vibrate(
            android.os.VibrationEffect.createOneShot(
                1000,
                android.os.VibrationEffect.DEFAULT_AMPLITUDE
            )
        )
        showSnackbar(
            "¡ALERTA! El vehículo $deviceId está a ${"%.1f".format(distance)} metros",
            Snackbar.LENGTH_LONG
        )
    }

    private fun updateSafeZoneUI(position: GeoPoint) {
        if (!isMapReady.get()) return

        Log.d(TAG, "🛡️ Updating safe zone UI at position: lat=${position.latitude}, lon=${position.longitude}")

        // Remover polígono anterior
        safeZonePolygon.get()?.let {
            map?.overlays?.remove(it)
            Log.d(TAG, "🗑️ Removed previous safe zone polygon")
        }

        // Crear nuevo polígono
        val polygon = Polygon().apply {
            points = Polygon.pointsAsCircle(position, GEOFENCE_RADIUS)
            fillColor = 0x33FF6B6B // Rojo semi-transparente más visible
            strokeColor = android.graphics.Color.RED
            strokeWidth = 3f // Línea más gruesa
        }

        safeZonePolygon.set(polygon)
        map?.overlays?.add(polygon)
        Log.d(TAG, "✅ Added new safe zone polygon")

        // Actualizar botón
        updateSafeZoneButton(true)

        // NO centrar automáticamente para permitir navegación libre
        // Solo centrar si estamos muy lejos
        val currentCenter = map?.mapCenter
        val currentDistance = currentCenter?.let {
            GeoPoint(it.latitude, it.longitude).distanceToAsDouble(position)
        } ?: Double.MAX_VALUE

        if (currentDistance > 2000.0) { // Solo si estamos muy lejos (2km+)
            Log.d(TAG, "📍 Centering map on safe zone (far away)")
            map?.controller?.animateTo(position, 17.0, 500L)
        }

        map?.invalidate()  // CORREGIDO
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

    // ============ FUNCIONES DE DISPOSITIVO OPTIMIZADAS ============

    private fun updateStatusUI(message: String, colorResId: Int) {
        binding.textDeviceStatus.apply {
            text = message
            visibility = View.VISIBLE
            setTextColor(ContextCompat.getColor(requireContext(), colorResId))
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

    // ============ ZONA SEGURA OPTIMIZADA ============

    private fun handleSafeZoneButton() {
        if (safeZone == null) {
            enterSafeZoneSetupMode()
        } else {
            toggleSafeZone()
        }
    }

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

    private fun establishSafeZoneForDevice(deviceIdString: String) {
        if (JWT_TOKEN.isNullOrEmpty()) {
            showSnackbar("Token de autenticación faltante. Inicia sesión nuevamente.", Snackbar.LENGTH_LONG)
            handleUnauthorizedError()
            return
        }

        // USAR SIEMPRE EL UNIQUE_ID REAL DE LAS PREFERENCIAS
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
                // Usar uniqueId REAL para obtener posición
                val position = viewModel.getLastPosition(deviceUniqueId)
                val geoPoint = GeoPoint(position.latitude, position.longitude)

                // Enviar al servidor usando uniqueId REAL
                viewModel.sendSafeZoneToServer(
                    position.latitude,
                    position.longitude,
                    deviceUniqueId
                )

                // Actualizar UI y caché
                safeZone = geoPoint
                safeZoneCache.set(geoPoint)
                updateSafeZoneUI(geoPoint)

                // Guardar en preferencias
                sharedPreferences.edit {
                    putString(PREF_SAFEZONE_LAT, position.latitude.toString())
                    putString(PREF_SAFEZONE_LON, position.longitude.toString())
                }

                // Confirmar con el servidor
                viewModel.fetchSafeZoneFromServer()

                binding.buttonZonaSegura.apply {
                    text = "Zona Segura Activa ✓"
                    isEnabled = true
                }

                Log.d(TAG, "✅ Safe zone established successfully for uniqueId: $deviceUniqueId")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to establish safe zone for uniqueId: $deviceUniqueId", e)
                showSnackbar("Fallo al establecer zona segura: ${e.message}", Snackbar.LENGTH_LONG)
                binding.buttonZonaSegura.apply {
                    text = "Establecer Zona Segura"
                    isEnabled = true
                }
            }
        }
    }



    private fun toggleSafeZone() {
        if (JWT_TOKEN.isNullOrEmpty()) {
            showSnackbar("Token de autenticación faltante. Inicia sesión nuevamente.", Snackbar.LENGTH_LONG)
            handleUnauthorizedError()
            return
        }

        Log.d(TAG, "🛡️ Toggling safe zone - current state: ${safeZone != null}")

        // NO hacer cambios optimistas - esperar confirmación del servidor
        binding.buttonZonaSegura.apply {
            text = "Eliminando zona segura..."
            isEnabled = false
        }

        viewModel.deleteSafeZoneFromServer { success ->
            lifecycleScope.launch(Dispatchers.Main) {
                if (success) {
                    Log.d(TAG, "✅ Safe zone deletion confirmed by server")

                    // AHORA sí limpiar la UI después de confirmación
                    safeZone = null
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

                    // FORZAR botón inactivo
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

                    showSnackbar("❌ Error al eliminar zona segura", Snackbar.LENGTH_LONG)
                }
            }
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
    private fun createWebSocketListener(deviceUniqueId: String) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "🔗 WebSocket conectado!")

            // Enviar PING inmediato para verificar
            val pingMessage = JSONObject().apply {
                put("type", "PING")
                put("timestamp", System.currentTimeMillis())
            }

            webSocket.send(pingMessage.toString())
            Log.d(TAG, "📤 PING enviado al servidor")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "📨 WebSocket message received: ${text.take(100)}...")

            try {
                val json = JSONObject(text)
                val type = json.getString("type")

                Log.d(TAG, "📋 Message type: $type")

                when (type) {
                    "POSITION_UPDATE" -> {
                        Log.d(TAG, "📍 Processing position update")
                        handlePositionUpdate(json, deviceUniqueId)
                    }
                    "CONNECTION_CONFIRMED" -> {
                        Log.d(TAG, "✅ Connection confirmed")
                        lifecycleScope.launch(Dispatchers.Main) {
                            showSnackbar("✅ Conectado y suscrito", Snackbar.LENGTH_SHORT)
                        }
                    }
                    "SUBSCRIBE_DEVICE" -> {
                        Log.d(TAG, "🔔 Device subscription confirmed")
                    }
                    "ERROR" -> {
                        val errorMsg = json.optString("message", "Error desconocido")
                        Log.e(TAG, "❌ WebSocket error: $errorMsg")
                        lifecycleScope.launch(Dispatchers.Main) {
                            showSnackbar("❌ Error: $errorMsg", Snackbar.LENGTH_LONG)
                        }
                    }
                    else -> {
                        Log.d(TAG, "❓ Unknown message type: $type")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error processing WebSocket message: ${e.message}", e)
                Log.e(TAG, "❌ Raw message: $text")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "❌ WebSocket failure: ${t.message}")
            Log.e(TAG, "❌ Response: ${response?.code} - ${response?.message}")

            lifecycleScope.launch(Dispatchers.Main) {
                updateStatusUI("🔴 Conexión perdida", android.R.color.holo_red_dark)
                showSnackbar("❌ Conexión perdida - Reintentando...", Snackbar.LENGTH_SHORT)
            }

            if (shouldReconnect.get() && isAdded) {
                scheduleReconnect()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "🔐 WebSocket cerrado: $code - $reason")

            lifecycleScope.launch(Dispatchers.Main) {
                updateStatusUI("🔴 Desconectado", android.R.color.holo_red_dark)
            }

            if (shouldReconnect.get() && isAdded && code != 1000) {
                scheduleReconnect()
            }
        }
    }

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
    // ============ FIX 2: Nueva función para obtener el uniqueId correcto ============
    private fun getDeviceUniqueId(): String? {
        return sharedPreferences.getString(DEVICE_UNIQUE_ID_PREF, null)
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

    private fun showSnackbar(message: String, duration: Int) {
        if (isAdded && view != null) {
            Snackbar.make(binding.root, message, duration).show()
        }
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

    private fun logout() {
        // Limpiar todo
        shouldReconnect.set(false)
        cancelStatusCheck()
        cancelReconnect()

        sharedPreferences.edit {
            remove("jwt_token")
            remove("user_email")
            apply()
        }

        findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment)
        Log.d(TAG, "Usuario cerró sesión")
    }

    private fun handleUnauthorizedError() {
        sharedPreferences.edit {
            remove("jwt_token")
            remove("user_email")
        }
        findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment)
        showSnackbar("Sesión expirada. Inicia sesión nuevamente.", Snackbar.LENGTH_LONG)
    }
    @SuppressLint("MissingPermission")
    private fun centerOnMyLocation() {
        if (!hasLocationPermission()) {
            showSnackbar("⚠️ Se necesitan permisos de ubicación", Snackbar.LENGTH_SHORT)
            return
        }

        myLocationOverlay?.myLocation?.let { location ->
            val myPosition = GeoPoint(location.latitude, location.longitude)

            // ===== CENTRADO SUAVE SIN INTERFERIR NAVEGACIÓN FUTURA =====
            map?.controller?.animateTo(myPosition, 18.0, 1200L)

            showSnackbar("📍 Centrado en tu ubicación", Snackbar.LENGTH_SHORT)
            Log.d(TAG, "📍 Manually centered on user location")

        } ?: run {
            showSnackbar("⚠️ Ubicación no disponible", Snackbar.LENGTH_SHORT)
            Log.w(TAG, "⚠️ User location not available for centering")
        }
    }
    // ============ CENTRADO EN VEHÍCULO CORREGIDO ============
    private fun centerOnVehicle() {
        vehicleMarker.get()?.position?.let { vehiclePosition ->
            map?.controller?.animateTo(vehiclePosition, 18.0, 1200L)
            showSnackbar("🚗 Centrado en el vehículo", Snackbar.LENGTH_SHORT)
            Log.d(TAG, "🚗 Manually centered on vehicle")
        } ?: run {
            showSnackbar("⚠️ Posición del vehículo no disponible", Snackbar.LENGTH_SHORT)
            Log.w(TAG, "⚠️ Vehicle position not available for centering")
        }
    }


    // ============ LIFECYCLE OPTIMIZADO ============

    override fun onResume() {
        super.onResume()

        // ===== REANUDAR MAPA PRIMERO =====
        map?.onResume()

        // ===== CONFIGURACIÓN POST-RESUME =====
        shouldReconnect.set(true)

        if (hasAssociatedDevice()) {
            lifecycleScope.launch {
                supervisorScope {
                    launch {
                        delay(500)
                        setupWebSocket()
                    }
                    launch {
                        delay(1000)
                        startPeriodicStatusCheck()
                    }
                    launch {
                        delay(1500)
                        if (System.currentTimeMillis() - lastPositionUpdate.get() > 30000L) {
                            fetchInitialPosition()
                        }
                    }
                }
            }
        }
        Log.d(TAG, "🔄 Fragment resumed - mapa mantenido fluido")
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
    }

    override fun onDestroyView() {
        super.onDestroyView()

        // Limpieza completa
        shouldReconnect.set(false)
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

        _binding = null
        Log.d(TAG, "Vista del fragment destruida")
    }
}