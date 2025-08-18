package com.zebass.peregrino

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import android.content.SharedPreferences
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
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
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import android.animation.ValueAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import com.google.android.gms.location.Priority
import com.zebass.peregrino.R.string
import com.zebass.peregrino.service.BackgroundSecurityService
import com.zebass.peregrino.service.LocationSharingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect

class SecondFragment : Fragment() {


    // ============ BINDING Y PROPIEDADES CORE ============
    private var _binding: FragmentSecondBinding? = null
    private val binding
        get() = _binding ?: run {
            Log.w(TAG, "Binding accessed when null - fragment may be destroyed")
            throw IllegalStateException("Fragment binding is null - fragment destroyed")
        }
    private lateinit var sharedPreferences: SharedPreferences
    private val args: SecondFragmentArgs by navArgs()
    private val viewModel: TrackingViewModel by viewModels()

    private val _vehiclePosition = MutableStateFlow<TrackingViewModel.VehiclePosition?>(null)

    private var isSecurityModeActive = false
    private var securityReceiver: BroadcastReceiver? = null

    private var webSocketReceiver: BroadcastReceiver? = null
    // ============ NUEVAS VARIABLES PARA TRACKING PERSISTENTE ============
    private var isFragmentVisible = false
    private var isFragmentResumed = false
    // ============ NUEVAS VARIABLES PARA COMPARTIR UBICACIÓN ============
    private var isCurrentlySharingLocation = false
    private var currentShareId: String? = null
    private var sharingStartTime: Long = 0

    // ============ MAPA Y OVERLAYS ============
    private var map: MapView? = null
    private val vehicleMarker = AtomicReference<Marker?>(null)
    private val safeZonePolygon = AtomicReference<Polygon?>(null)
    private var myLocationOverlay: MyLocationNewOverlay? = null

    // ============ NUEVO: TRACKING SUAVE Y ANIMACIONES ============
    //private var pathOverlay: Polyline? = null
    //private val trackPoints = mutableListOf<GeoPoint>()
    //private val maxTrackPoints = 200
    private var lastMapUpdate = 0L
    private val mapUpdateThrottle = 50L

    // ============ ANIMACIONES Y ORIENTACIÓN ============
    private var lastBearing = 0.0
    private var mapRotationAnimator: ValueAnimator? = null
    private var vehicleAnimator: ValueAnimator? = null
    private var autoRotateMap = true

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
    private var webSocketPingHandler: Handler? = null
    private var webSocketPingRunnable: Runnable? = null

    // ============ VARIABLES PARA SEGUIMIENTO MEJORADO ============
    private val isFollowingVehicle = AtomicBoolean(true) // Activado por defecto
    private var currentVehicleState = VehicleState.NORMAL
    private var safeZoneCenter: GeoPoint? = null

    // ============ LOCATION SERVICES ============
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var alertManager: AlertManager

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

    companion object {
        private var safeZone: GeoPoint? = null
        const val GEOFENCE_RADIUS = 15.0
        const val RECONNECT_DELAY = 5000L
        const val STATUS_CHECK_INTERVAL = 30000L
        const val POSITION_UPDATE_THROTTLE = 1000L
        const val TAG = "SecondFragment"

        // ============ CONSTANTES PARA SEGUIMIENTO MEJORADO ============
        const val FOLLOW_ZOOM_LEVEL = 18.0 // Más cercano para seguimiento
        const val OVERVIEW_ZOOM_LEVEL = 12.0
        const val TANDIL_LAT = -37.32167
        const val TANDIL_LON = -59.13316

        // ============ URLs Y CONFIGURACIÓN ============
        private const val BASE_URL = "https://app.socialengeneering.work"
        private const val WS_BASE_URL = "wss://app.socialengeneering.work"
        private const val TRACCAR_URL = "https://traccar.socialengeneering.work"
        var JWT_TOKEN: String? = null
        var USER_EMAIL: String? = null
        const val DEVICE_ID_PREF = "associated_device_id"
        const val DEVICE_NAME_PREF = "associated_device_name"
        const val DEVICE_UNIQUE_ID_PREF = "associated_device_unique_id"
        const val PREF_SAFEZONE_LAT = "safezone_lat"
        const val PREF_SAFEZONE_LON = "safezone_lon"
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
        }
    }

    // ============ LIFECYCLE OPTIMIZADO ============

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        // Configuración OSMDroid antes de crear el mapa
        val ctx = requireContext()
        Configuration.getInstance().load(ctx, ctx.getSharedPreferences("osmdroid", 0))
        Configuration.getInstance().apply {
            tileFileSystemCacheMaxBytes = 100L * 1024L * 1024L
            tileFileSystemCacheTrimBytes = 80L * 1024L * 1024L
            tileDownloadThreads = 8
            tileFileSystemThreads = 4
            userAgentValue = "PeregrinoGPS/2.0"
            isDebugMode = false
            isDebugTileProviders = false
        }

        _binding = FragmentSecondBinding.inflate(inflater, container, false)
        sharedPreferences =
            requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        viewModel.setContext(requireContext())
        return binding.root
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // ✅ MARCAR FRAGMENTO COMO VISIBLE
        isFragmentVisible = true

        // Inicializar servicios
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        alertManager = AlertManager(requireContext())

        val userEmail = args.userEmail
        JWT_TOKEN = args.jwtToken
        Log.d(TAG, "🚀 onViewCreated: userEmail=$userEmail, hasToken=${!JWT_TOKEN.isNullOrEmpty()}")

        binding.textUser.text = "$userEmail"

        // Inicialización secuencial
        lifecycleScope.launch {
            try {
                setupUI()
                setupEnhancedMap()
                observeViewModel()

                delay(100)
                loadDeviceInfo()

                delay(200)
                if (hasAssociatedDevice()) {
                    Log.d(TAG, "✅ Device found, starting PERSISTENT tracking...")

                    // ✅ FORZAR REFRESH Y INICIAR TRACKING PERSISTENTE
                    viewModel.forceDataRefresh()
                    startPersistentTrackingServices()
                } else {
                    Log.d(TAG, "⚠️ No device associated")
                    updateStatusUI("⚠️ Asocia un dispositivo para comenzar", android.R.color.holo_orange_dark)
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in onViewCreated", e)
                showSnackbar("❌ Error de inicialización: ${e.message}", Snackbar.LENGTH_LONG)
            }
        }

        setupLogoutButton()
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            logout()
        }
        restoreSharingState()
        restoreSecurityState()
        registerSecurityReceiver()
    }
    // ✅ NUEVA FUNCIÓN: OBSERVADORES PERSISTENTES
    @RequiresApi(Build.VERSION_CODES.O)
    private fun observeViewModelPersistent() {
        Log.d(TAG, "🔍 Setting up PERSISTENT observers...")

        // ✅ OBSERVAR POSICIÓN DEL VEHÍCULO - ACTUALIZACIÓN INMEDIATA
        lifecycleScope.launch {
            viewModel.vehiclePosition.collectLatest { position ->
                if (!isFragmentVisible) {
                    Log.d(TAG, "📍 Position update received but fragment not visible, caching...")
                    return@collectLatest
                }

                if (position != null) {
                    Log.d(TAG, "🎯 LIVE POSITION UPDATE: lat=${position.latitude}, lon=${position.longitude}, quality=${position.quality}")

                    // ✅ ACTUALIZAR INMEDIATAMENTE SIN THROTTLE
                    updateVehiclePositionEnhanced(position)

                    // ✅ MOSTRAR NOTIFICACIÓN VISUAL DE ACTUALIZACIÓN
                    if (!position.isInterpolated && position.quality == "websocket_realtime") {
                        showPositionUpdateIndicator()
                    }
                } else {
                    Log.d(TAG, "📍 Position is null, may need to load initial position")

                    // ✅ Si no hay posición después de 5 segundos, intentar cargar
                    handler.postDelayed({
                        if (vehicleMarker.get() == null && isFragmentVisible) {
                            Log.w(TAG, "⚠️ No vehicle position after timeout, forcing load...")
                            forceLoadInitialPosition()
                        }
                    }, 5000)
                }
            }
        }

        // ✅ OBSERVAR ESTADO DE CONEXIÓN WEBSOCKET
        lifecycleScope.launch {
            viewModel.connectionStatus.collectLatest { status ->
                updateConnectionStatusUI(status)
            }
        }

        // ✅ RESTO DE OBSERVADORES (sin cambios)
        lifecycleScope.launch {
            viewModel.safeZone.collectLatest { zone ->
                if (zone != null) {
                    val geoPoint = GeoPoint(zone.latitude, zone.longitude)
                    safeZone = geoPoint
                    safeZoneCache.set(geoPoint)
                    updateSafeZoneUI(geoPoint)
                } else {
                    clearSafeZoneUI()
                }
            }
        }

        lifecycleScope.launch {
            viewModel.error.collectLatest { error ->
                error?.let {
                    handleViewModelError(it)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.deviceInfo.collectLatest { info ->
                info?.let {
                    deviceInfoCache.set(info)
                    updateDeviceInfoUI(info)
                }
            }
        }
    }

    // ✅ NUEVA FUNCIÓN: INDICADOR VISUAL DE ACTUALIZACIÓN
    private fun showPositionUpdateIndicator() {
        try {
            // ✅ Cambiar temporalmente el color del status para mostrar actividad
            binding.textConnectionStatus?.let { statusText ->
                val originalColor = statusText.currentTextColor
                statusText.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_light))
                statusText.text = "📡 Actualizado en tiempo real"

                // ✅ Restaurar color original después de 1 segundo
                handler.postDelayed({
                    if (_binding != null) {
                        statusText.setTextColor(originalColor)
                        statusText.text = "✅ En Tiempo Real"
                    }
                }, 1000)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing position update indicator: ${e.message}")
        }
    }
    private fun animateTraccarVehicleMarker(
        marker: Marker,
        newPosition: GeoPoint,
        bearing: Double,
        speed: Double,
        state: VehicleState,
        vehiclePos: TrackingViewModel.VehiclePosition
    ) {
        val oldPosition = marker.position

        // Cancelar animación anterior
        vehicleAnimator?.cancel()

        // Animar posición
        vehicleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (speed > 5) 300 else 600 // Más fluido para GPS real
            interpolator = AccelerateDecelerateInterpolator()

            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                val interpolatedLat = oldPosition.latitude +
                        (newPosition.latitude - oldPosition.latitude) * progress
                val interpolatedLon = oldPosition.longitude +
                        (newPosition.longitude - oldPosition.longitude) * progress

                marker.position = GeoPoint(interpolatedLat, interpolatedLon)
                marker.icon = createTraccarVehicleIcon(bearing, speed, state, vehiclePos)

                map?.invalidate()
            }

            start()
        }

        // Actualizar información del marcador
        marker.snippet = buildTraccarMarkerInfo(vehiclePos, speed, bearing)

        Log.d(TAG, "🎬 Traccar vehicle marker animated to new position")
    }

    // ✅ NUEVA FUNCIÓN: ACTUALIZAR ESTADO DE CONEXIÓN WEBSOCKET
    private fun updateConnectionStatusUI(status: TrackingViewModel.ConnectionStatus) {
        if (!isFragmentValid()) return

        try {
            val (text, color) = when (status) {
                TrackingViewModel.ConnectionStatus.CONNECTED ->
                    "✅ En Tiempo Real" to android.R.color.holo_green_light
                TrackingViewModel.ConnectionStatus.CONNECTING ->
                    "🔄 Conectando..." to android.R.color.holo_orange_light
                TrackingViewModel.ConnectionStatus.RECONNECTING ->
                    "🔄 Reconectando..." to android.R.color.holo_orange_dark
                TrackingViewModel.ConnectionStatus.ERROR ->
                    "❌ Error Conexión" to android.R.color.holo_red_light
                TrackingViewModel.ConnectionStatus.DISCONNECTED ->
                    "🔴 Desconectado" to android.R.color.holo_red_dark
            }

            binding.textConnectionStatus?.apply {
                this.text = text
                setTextColor(ContextCompat.getColor(requireContext(), color))
                visibility = View.VISIBLE
            }

            Log.d(TAG, "📊 Connection status updated: $text")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error updating connection status UI: ${e.message}")
        }
    }

    // ✅ NUEVA FUNCIÓN: INICIAR SERVICIOS DE TRACKING PERSISTENTE
    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun startPersistentTrackingServices() = withContext(Dispatchers.IO) {
        Log.d(TAG, "🚀 Starting PERSISTENT Traccar GPS tracking services")

        try {
            // ✅ PASO 1: VERIFICAR CONEXIÓN A TRACCAR
            launch {
                Log.d(TAG, "🔍 Step 1: Verifying Traccar connection...")
                val deviceUniqueId = getDeviceUniqueId()
                if (deviceUniqueId != null) {
                    // Probar conexión directa con Traccar
                    delay(500)
                    Log.d(TAG, "📡 Testing Traccar WebSocket for real-time GPS...")
                }
            }

            // ✅ PASO 2: INICIAR WEBSOCKET PRIORITARIO PARA TRACCAR
            launch {
                Log.d(TAG, "🔌 Step 2: Starting PRIORITY WebSocket for Traccar GPS...")
                viewModel.startRealTimeTracking(loadInitialPosition = true)
                delay(2000)

                // ✅ FORZAR SUSCRIPCIÓN A DISPOSITIVO
                getDeviceUniqueId()?.let { uniqueId ->
                    Log.d(TAG, "📡 Forcing subscription to Traccar device: $uniqueId")
                    viewModel.forceReconnectWebSocket()
                }
            }

            delay(3000)

            // ✅ PASO 3: VERIFICAR QUE RECIBIMOS DATOS
            launch {
                Log.d(TAG, "🔍 Step 3: Verifying GPS data reception...")
                delay(5000)

                if (_vehiclePosition.value == null) {
                    Log.w(TAG, "⚠️ No GPS data received from Traccar - investigating...")

                    // ✅ INVESTIGAR PROBLEMA
                    withContext(Dispatchers.Main) {
                        showSnackbar("⚠️ Verificando conexión con GPS...", Snackbar.LENGTH_LONG)
                    }

                    // Intentar carga manual
                    forceLoadInitialPosition()
                } else {
                    Log.d(TAG, "✅ GPS data flowing from Traccar successfully!")
                    withContext(Dispatchers.Main) {
                        showSnackbar("📡 GPS conectado - Datos en tiempo real", Snackbar.LENGTH_SHORT)
                    }
                }
            }

            Log.d(TAG, "✅ All PERSISTENT Traccar GPS services started successfully")

        } catch (error: Exception) {
            Log.e(TAG, "❌ Error starting Traccar GPS services: ${error.message}")
            withContext(Dispatchers.Main) {
                showSnackbar("❌ Error conectando GPS: ${error.message}", Snackbar.LENGTH_LONG)
            }
        }
    }

    // ============ CONFIGURACIÓN MEJORADA DEL MAPA ============
    private suspend fun setupEnhancedMap() = withContext(Dispatchers.Main) {
        map = binding.mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(FOLLOW_ZOOM_LEVEL)
            controller.setCenter(GeoPoint(TANDIL_LAT, TANDIL_LON))

            isTilesScaledToDpi = true
            setUseDataConnection(true)
            isFlingEnabled = true
            setBuiltInZoomControls(false)
            minZoomLevel = 3.0
            maxZoomLevel = 21.0

            // Configurar listeners mejorados
            setupMapListeners()
        }

        isMapReady.set(true)
        Log.d(TAG, "🗺️ Enhanced map configured")

        loadSafeZone()
        if (hasAssociatedDevice()) {
            viewModel.fetchSafeZoneFromServer()
        }

        if (hasLocationPermission()) {
            enableMyLocation()
        }
    }

    private fun setupMapListeners() {
        map?.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                // ✅ PAUSAR SEGUIMIENTO SOLO AL HACER SCROLL MANUAL
                if (event != null && isFollowingVehicle.get()) {
                    pauseAutoFollowing()
                }
                return true
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                return true
            }
        })

        // ✅ PAUSAR SEGUIMIENTO AL TOCAR EL MAPA
        map?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (isFollowingVehicle.get()) {
                        pauseAutoFollowing()
                    }
                }
            }
            false
        }
    }

    private fun pauseAutoFollowing() {
        if (isFollowingVehicle.get()) {
            isFollowingVehicle.set(false)
            updateFollowButtonText()
            Log.d(TAG, "⏸️ Auto-following paused due to user interaction")

            showSnackbar(
                "⏸️ Seguimiento pausado - Toca 'Siguiendo' para reactivar",
                Snackbar.LENGTH_SHORT
            )
        }
    }

    // ============ OBSERVADORES MEJORADOS ============
    @RequiresApi(Build.VERSION_CODES.O)
    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.vehiclePosition.collectLatest { position ->
                if (position != null) {
                    Log.d(TAG, "🎯 TRACCAR POSITION RECEIVED: lat=${position.latitude}, lon=${position.longitude}, quality=${position.quality}")

                    // ✅ ACTUALIZAR INMEDIATAMENTE - DATOS DE TRACCAR SON PRIORITARIOS
                    updateVehiclePositionEnhanced(position)

                    // ✅ MOSTRAR NOTIFICACIÓN SOLO PARA DATOS REALES
                    if (!position.isInterpolated && position.quality.contains("realtime")) {
                        showPositionUpdateIndicator()
                        Log.d(TAG, "📍 REAL-TIME GPS UPDATE from Traccar!")
                    }
                } else {
                    Log.w(TAG, "⚠️ No vehicle position from Traccar - checking connection...")

                    // ✅ VERIFICAR CONEXIÓN WEBSOCKET
                    handler.postDelayed({
                        if (vehicleMarker.get() == null && isFragmentVisible) {
                            Log.w(TAG, "🔄 No vehicle visible after timeout, forcing reconnection...")
                            viewModel.forceReconnectWebSocket()
                            forceLoadInitialPosition()
                        }
                    }, 10000) // 10 segundos
                }
            }
        }

        // ✅ OBSERVAR ESTADO DE CONEXIÓN CRÍTICO
        lifecycleScope.launch {
            viewModel.connectionStatus.collectLatest { status ->
                updateConnectionStatusUI(status)

                // ✅ SI SE DESCONECTA, INTENTAR RECONECTAR INMEDIATAMENTE
                if (status == TrackingViewModel.ConnectionStatus.DISCONNECTED) {
                    Log.w(TAG, "🔴 WebSocket disconnected - attempting immediate reconnection...")
                    handler.postDelayed({
                        if (hasAssociatedDevice()) {
                            viewModel.forceReconnectWebSocket()
                        }
                    }, 2000)
                }
            }
        }

        // Observar zona segura
        lifecycleScope.launch {
            viewModel.safeZone.collectLatest { zone ->
                if (zone != null) {
                    val geoPoint = GeoPoint(zone.latitude, zone.longitude)
                    safeZone = geoPoint
                    safeZoneCache.set(geoPoint)
                    updateSafeZoneUI(geoPoint)
                } else {
                    clearSafeZoneUI()
                }
            }
        }

        // Observar errores
        lifecycleScope.launch {
            viewModel.error.collectLatest { error ->
                error?.let {
                    handleViewModelError(it)
                }
            }
        }

        // Observar información del dispositivo
        lifecycleScope.launch {
            viewModel.deviceInfo.collectLatest { info ->
                info?.let {
                    deviceInfoCache.set(it)
                    updateDeviceInfoUI(it)
                }
            }
        }
    }

    // ============ ACTUALIZACIÓN MEJORADA DE POSICIÓN DEL VEHÍCULO ============
    private fun updateVehiclePositionEnhanced(position: TrackingViewModel.VehiclePosition) {
        if (!isMapReady.get()) {
            Log.w(TAG, "⚠️ Map not ready for Traccar position update")
            return
        }

        val currentTime = System.currentTimeMillis()

        // ✅ SIEMPRE ACTUALIZAR DATOS DE TRACCAR INMEDIATAMENTE
        Log.d(TAG, "📍 PROCESSING TRACCAR GPS: lat=${position.latitude}, lon=${position.longitude}")

        val newPoint = GeoPoint(position.latitude, position.longitude)

        // ✅ VALIDAR POSICIÓN ANTES DE MOSTRAR
        if (!isValidTraccarPosition(newPoint, position)) {
            Log.w(TAG, "❌ Invalid Traccar position rejected: lat=${position.latitude}, lon=${position.longitude}")
            return
        }

        val newState = determineVehicleState(newPoint)

        // ✅ ACTUALIZAR MARCADOR CON DATOS DE TRACCAR
        updateVehicleMarkerFromTraccar(newPoint, position.bearing, position.speed, newState, position)

        // ✅ SEGUIMIENTO AUTOMÁTICO SOLO CON DATOS REALES
        if (isFollowingVehicle.get()) {
            smoothMoveMapToPosition(newPoint, position.bearing)
        }

        // ✅ VERIFICAR ZONA SEGURA SOLO CON DATOS NO INTERPOLADOS
        if (!position.isInterpolated) {
            checkSafeZone(newPoint, position.deviceId.hashCode())
        }

        updatePositionInfo(position)
        lastPositionCache.set(newPoint)

        Log.d(TAG, "✅ Traccar position displayed: quality=${position.quality}, speed=${position.speed}km/h")
    }
    // 5. NUEVA FUNCIÓN - Validar posición de Traccar
    private fun isValidTraccarPosition(position: GeoPoint, vehiclePos: TrackingViewModel.VehiclePosition): Boolean {
        // ✅ VALIDACIONES ESPECÍFICAS PARA TRACCAR
        if (position.latitude == 0.0 && position.longitude == 0.0) {
            Log.w(TAG, "❌ Traccar sent 0,0 coordinates - rejecting")
            return false
        }

        if (position.latitude < -90.0 || position.latitude > 90.0 ||
            position.longitude < -180.0 || position.longitude > 180.0) {
            Log.w(TAG, "❌ Traccar coordinates out of range - rejecting")
            return false
        }

        // ✅ RECHAZAR POSICIONES HARDCODEADAS/FALLBACK
        if (position.latitude == TANDIL_LAT && position.longitude == TANDIL_LON) {
            Log.w(TAG, "❌ Traccar sent hardcoded Tandil coordinates - rejecting")
            return false
        }

        // ✅ VERIFICAR DISTANCIA EXCESIVA (más de 500km de salto)
        val lastKnown = lastPositionCache.get()
        if (lastKnown != null) {
            val distance = position.distanceToAsDouble(lastKnown)
            if (distance > 500000) { // 500km
                Log.w(TAG, "❌ Traccar position jump too large: ${distance}m - rejecting")
                return false
            }
        }

        return true
    }

    // 6. NUEVA FUNCIÓN - Marcador específico para datos de Traccar
    private fun updateVehicleMarkerFromTraccar(
        position: GeoPoint,
        bearing: Double,
        speed: Double,
        state: VehicleState,
        vehiclePos: TrackingViewModel.VehiclePosition
    ) {
        try {
            var marker = vehicleMarker.get()

            if (marker == null) {
                // ✅ CREAR MARCADOR ESPECÍFICO PARA TRACCAR
                marker = createTraccarVehicleMarker(position, bearing, speed, state, vehiclePos)
                vehicleMarker.set(marker)
                map?.overlays?.add(marker)

                // ✅ CENTRAR SOLO EN EL PRIMER MARCADOR DE TRACCAR
                if (isFollowingVehicle.get()) {
                    map?.controller?.animateTo(position, FOLLOW_ZOOM_LEVEL, 1500L)
                    Log.d(TAG, "🎯 Centered map on first Traccar position")
                }
            } else {
                // ✅ ANIMAR MOVIMIENTO CON DATOS DE TRACCAR
                animateTraccarVehicleMarker(marker, position, bearing, speed, state, vehiclePos)
            }

            map?.invalidate()
            Log.d(TAG, "🚗 Vehicle marker updated from Traccar data")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error updating Traccar vehicle marker: ${e.message}")
        }
    }


    // 7. NUEVA FUNCIÓN - Crear marcador específico para Traccar
    private fun createTraccarVehicleMarker(
        position: GeoPoint,
        bearing: Double,
        speed: Double,
        state: VehicleState,
        vehiclePos: TrackingViewModel.VehiclePosition
    ): Marker {
        return Marker(map).apply {
            this.position = position
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = createTraccarVehicleIcon(bearing, speed, state, vehiclePos)
            title = "🚗 Vehículo GPS (Traccar)"
            snippet = buildTraccarMarkerInfo(vehiclePos, speed, bearing)
            isDraggable = false
            setInfoWindow(null)
        }
    }

    // 8. NUEVA FUNCIÓN - Ícono específico para Traccar
    private fun createTraccarVehicleIcon(
        bearing: Double,
        speed: Double,
        state: VehicleState,
        vehiclePos: TrackingViewModel.VehiclePosition
    ): android.graphics.drawable.Drawable {
        val size = 64 // Tamaño más grande para Traccar
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
        }

        val center = size / 2f

        // ✅ COLOR ESPECÍFICO PARA DATOS DE TRACCAR
        val vehicleColor = when {
            !vehiclePos.isInterpolated && vehiclePos.quality.contains("realtime") -> Color.GREEN // Tiempo real
            vehiclePos.quality.contains("websocket") -> Color.CYAN // WebSocket
            vehiclePos.isInterpolated -> Color.YELLOW // Interpolado
            state == VehicleState.OUTSIDE_SAFE_ZONE -> Color.RED // Fuera de zona
            else -> Color.BLUE // Default Traccar
        }

        // Círculo principal más grande
        paint.color = vehicleColor
        canvas.drawCircle(center, center, 22f, paint)

        // Borde blanco grueso
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = Color.WHITE
        canvas.drawCircle(center, center, 22f, paint)

        // ✅ FLECHA MÁS GRANDE PARA DIRECCIÓN
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE

        canvas.save()
        canvas.rotate(bearing.toFloat(), center, center)

        val path = Path().apply {
            moveTo(center, center - 16)      // Flecha más grande
            lineTo(center - 8, center + 8)
            lineTo(center + 8, center + 8)
            close()
        }
        canvas.drawPath(path, paint)
        canvas.restore()

        // ✅ INDICADOR DE CALIDAD DE DATOS
        paint.style = Paint.Style.FILL
        paint.color = when {
            vehiclePos.quality.contains("realtime") -> Color.GREEN
            vehiclePos.quality.contains("websocket") -> Color.CYAN
            vehiclePos.isInterpolated -> Color.YELLOW
            else -> Color.GRAY
        }
        canvas.drawCircle(center + 18, center - 18, 6f, paint)

        return BitmapDrawable(resources, bitmap)
    }

    // 9. NUEVA FUNCIÓN - Info del marcador para Traccar
    private fun buildTraccarMarkerInfo(
        vehiclePos: TrackingViewModel.VehiclePosition,
        speed: Double,
        bearing: Double
    ): String {
        return buildString {
            appendLine("🚗 Vehículo GPS desde Traccar")
            appendLine("📍 Velocidad: ${String.format("%.1f", speed)} km/h")
            appendLine("🧭 Dirección: ${String.format("%.0f", bearing)}°")
            appendLine("📡 Calidad: ${vehiclePos.quality}")
            appendLine("⏰ Tiempo: ${if (vehiclePos.isInterpolated) "Interpolado" else "Real"}")
            if (vehiclePos.accuracy > 0) {
                appendLine("🎯 Precisión: ${vehiclePos.accuracy.toInt()}m")
            }
        }
    }


    // ✅ NUEVA FUNCIÓN: VALIDAR POSICIÓN
    private fun isValidPosition(position: GeoPoint): Boolean {
        return position.latitude != 0.0 &&
                position.longitude != 0.0 &&
                position.latitude >= -90.0 &&
                position.latitude <= 90.0 &&
                position.longitude >= -180.0 &&
                position.longitude <= 180.0 &&
                // ✅ EVITAR POSICIONES HARDCODEADAS O MUY LEJANAS
                !(position.latitude == TANDIL_LAT && position.longitude == TANDIL_LON) &&
                // ✅ VALIDAR QUE LA POSICIÓN ESTÉ EN UN RANGO RAZONABLE
                isPositionReasonable(position)
    }

    private fun isPositionReasonable(position: GeoPoint): Boolean {
        // Si tienes una última posición conocida válida, verificar que no esté muy lejos
        val lastKnown = lastPositionCache.get()
        if (lastKnown != null) {
            val distance = position.distanceToAsDouble(lastKnown)
            // Si la nueva posición está a más de 100km de la anterior, es sospechosa
            return distance < 25000 // 25km en metros
        }
        return true
    }

    // ============ ACTUALIZACIÓN SUAVE DEL MARCADOR ============
    private fun updateVehicleMarkerSmooth(
        position: GeoPoint,
        bearing: Double,
        speed: Double,
        state: VehicleState
    ) {
        try {
            var marker = vehicleMarker.get()

            if (marker == null) {
                // Crear marcador inicial
                marker = createEnhancedVehicleMarker(position, bearing, speed, state)
                vehicleMarker.set(marker)
                map?.overlays?.add(marker)

                // Solo centrar en el primer marcador
                if (isFollowingVehicle.get()) {
                    map?.controller?.animateTo(position, FOLLOW_ZOOM_LEVEL, 1000L)
                }
            } else {
                // Animar movimiento del marcador existente
                animateVehicleMarker(marker, position, bearing, speed, state)
            }

            map?.invalidate()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error updating vehicle marker: ${e.message}")
        }
    }


    private fun createEnhancedVehicleMarker(
        position: GeoPoint,
        bearing: Double,
        speed: Double,
        state: VehicleState
    ): Marker {
        return Marker(map).apply {
            this.position = position
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = createDynamicVehicleIcon(bearing, speed, state)
            title = "Mi Vehículo"
            snippet = "Velocidad: ${String.format("%.1f", speed)} km/h\n" +
                    "Dirección: ${String.format("%.0f", bearing)}°"
            isDraggable = false
            setInfoWindow(null)
        }
    }

    private fun animateVehicleMarker(
        marker: Marker,
        newPosition: GeoPoint,
        bearing: Double,
        speed: Double,
        state: VehicleState
    ) {
        val oldPosition = marker.position

        // Cancelar animación anterior
        vehicleAnimator?.cancel()

        // Animar posición
        vehicleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (speed > 5) 300 else 600 // Más fluido
            interpolator = AccelerateDecelerateInterpolator()

            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                val interpolatedLat = oldPosition.latitude +
                        (newPosition.latitude - oldPosition.latitude) * progress
                val interpolatedLon = oldPosition.longitude +
                        (newPosition.longitude - oldPosition.longitude) * progress

                marker.position = GeoPoint(interpolatedLat, interpolatedLon)
                marker.icon = createDynamicVehicleIcon(bearing, speed, state)

                map?.invalidate()
            }

            start()
        }

        // Actualizar información del marcador
        marker.snippet = "Velocidad: ${String.format("%.1f", speed)} km/h\n" +
                "Dirección: ${String.format("%.0f", bearing)}°"
    }


    // ============ CREACIÓN DE ÍCONO DINÁMICO MEJORADO ============
    private fun createDynamicVehicleIcon(
        bearing: Double,
        speed: Double,
        state: VehicleState
    ): android.graphics.drawable.Drawable {
        val size = 56 // Tamaño aumentado
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
        }

        val center = size / 2f

        // Sombra
        paint.color = Color.argb(100, 0, 0, 0)
        canvas.drawCircle(center + 2, center + 2, 20f, paint)

        // Color basado en estado y velocidad
        val vehicleColor = when (state) {
            VehicleState.IN_SAFE_ZONE -> Color.BLUE
            VehicleState.OUTSIDE_SAFE_ZONE -> Color.RED
            VehicleState.NORMAL -> when {
                speed < 1 -> Color.GRAY
                speed < 10 -> Color.YELLOW
                speed < 30 -> Color.GREEN
                else -> Color.CYAN
            }
        }

        // Círculo principal
        paint.color = vehicleColor
        canvas.drawCircle(center, center, 18f, paint)

        // Borde blanco
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.WHITE
        canvas.drawCircle(center, center, 18f, paint)

        // Flecha de dirección
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE

        canvas.save()
        canvas.rotate(bearing.toFloat(), center, center)

        val path = Path().apply {
            moveTo(center, center - 12)
            lineTo(center - 6, center + 6)
            lineTo(center + 6, center + 6)
            close()
        }
        canvas.drawPath(path, paint)
        canvas.restore()

        return BitmapDrawable(resources, bitmap)
    }


    // ============ MOVIMIENTO SUAVE DEL MAPA ============
    private fun smoothMoveMapToPosition(position: GeoPoint, bearing: Double) {
        if (!isFollowingVehicle.get()) return

        try {
            // Mover el centro del mapa suavemente
            map?.controller?.animateTo(position, FOLLOW_ZOOM_LEVEL, 800L)

            // Rotar mapa según dirección del vehículo (si está habilitado)
            if (autoRotateMap && Math.abs(bearing - lastBearing) > 15) {
                rotateMapSmooth(bearing)
                lastBearing = bearing
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error moving map: ${e.message}")
        }
    }


    private fun rotateMapSmooth(newBearing: Double) {
        mapRotationAnimator?.cancel()

        val currentRotation = map?.mapOrientation ?: 0f
        var targetRotation = -newBearing.toFloat()

        // Normalizar ángulos
        while (targetRotation - currentRotation > 180) targetRotation -= 360
        while (targetRotation - currentRotation < -180) targetRotation += 360

        mapRotationAnimator = ValueAnimator.ofFloat(currentRotation, targetRotation).apply {
            duration = 1500
            interpolator = AccelerateDecelerateInterpolator()

            addUpdateListener { animation ->
                val rotation = animation.animatedValue as Float
                map?.mapOrientation = rotation
                map?.invalidate()
            }

            start()
        }

        Log.d(TAG, "🧭 Map rotating to bearing: ${String.format("%.1f", newBearing)}°")
    }

    // ============ ACTUALIZACIÓN DEL RASTRO DEL VEHÍCULO ============
    private fun updateVehicleTrack(newPoint: GeoPoint) {
    }

    private fun updateTrackOverlay() {
    }


    // ============ CONFIGURACIÓN DE UI MEJORADA ============
    // ============ CONFIGURACIÓN DE UI MEJORADA ============
    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun setupUI() = withContext(Dispatchers.Main) {
        binding.buttonLogout.setOnClickListener { logout() }
        setupEnhancedButtons()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun setupEnhancedButtons() {
        with(binding) {
            // ✅ BOTÓN PRINCIPAL DE SEGUIMIENTO (existente)
            buttonMyLocation.apply {
                setOnClickListener {
                    toggleVehicleFollowing()
                }

                setOnLongClickListener {
                    val opciones = arrayOf(
                        "📍 Ir a mi ubicación",
                        "🧹 Limpiar Cache",
                        "🔄 Reconectar WebSocket",
                        "🛡️ Modo Seguridad" // ✅ NUEVA OPCIÓN
                    )
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Acciones rápidas")
                        .setItems(opciones) { _, which ->
                            when (which) {
                                0 -> centerOnMyLocation()
                                1 -> clearOldGPSData()
                                2 -> forceReconnectWebSocket()
                                3 -> toggleSecurityMode() // ✅ NUEVA FUNCIÓN
                            }
                        }
                        .show()
                    true
                }
            }
            // ✅ BOTÓN DE ZONA SEGURA MEJORADO
            buttonZonaSegura.setOnLongClickListener {
                if (isSecurityModeActive) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("🛡️ Opciones de Seguridad")
                        .setItems(arrayOf(
                            "🔇 Modo Silencioso",
                            "📊 Estado de Seguridad",
                            "🛑 Desactivar Seguridad"
                        )) { _, which ->
                            when (which) {
                                0 -> enableSilentSecurityMode()
                                1 -> showSecurityStatus()
                                2 -> stopSecurityMode()
                            }
                        }
                        .show()
                } else {
                    handleSafeZoneButton()
                }
                true
            }

            // ✅ RESTO DE BOTONES (mantener configuración existente)
            buttonZonaSeguraMain.setOnClickListener { handleSafeZoneButton() }
            buttonAssociateDevice.setOnClickListener { showAssociateDeviceDialog() }

            buttonDeviceStatus.apply {
                setOnClickListener {
                    checkDeviceStatus()
                }

                setOnLongClickListener {
                    val opciones = arrayOf(
                        "🧹 Limpiar Cache GPS",
                        "🗑️ Borrar posiciones antiguas",
                        "🔄 Reconectar WebSocket"
                    )
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Acciones de Estado")
                        .setItems(opciones) { _, which ->
                            when (which) {
                                0 -> clearOldGPSData()
                                1 -> clearOldPositionsDialog()
                                2 -> forceReconnectWebSocket() // ✅ NUEVA OPCIÓN
                            }
                        }
                        .show()
                    true
                }
            }

            buttonShowUbicacion.setOnClickListener { showShareLocationDialog() }
            buttonTestWS.setOnClickListener { toggleAutoFollow() }

            // ✅ OCULTAR ELEMENTOS NO NECESARIOS
            buttonDescargarOffline.visibility = View.GONE
            progressBarDownload.visibility = View.GONE

            updateFollowButtonText()
        }
    }
    private fun showSecurityStatus() {
        // ✅ ESTA FUNCIÓN SE PODRÍA EXPANDIR PARA MOSTRAR ESTADÍSTICAS DETALLADAS
        val message = if (isSecurityModeActive) {
            "🛡️ Modo Seguridad: ACTIVO\n" +
                    "📡 Monitoreo: 24/7 en segundo plano\n" +
                    "🎯 Zona Segura: Configurada\n" +
                    "⚡ Estado: Funcionando correctamente"
        } else {
            "🛡️ Modo Seguridad: INACTIVO\n" +
                    "ℹ️ No hay monitoreo activo"
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("📊 Estado de Seguridad")
            .setMessage(message)
            .setPositiveButton("✅ OK", null)
            .show()
    }

    // ============ NUEVA FUNCIONALIDAD: COMPARTIR UBICACIÓN ============
    @RequiresApi(Build.VERSION_CODES.O)
    private fun showShareLocationDialog() {
        Log.d(TAG, "🌍 Share dialog requested")

        // Verificar estado
        if (isCurrentlySharingLocation) {
            showStopSharingDialog()
            return
        }

        // ✅ USAR AlertDialog ESTÁNDAR
        val options = arrayOf(
            "📍 Compartir por 10 minutos",
            "📍 Compartir por 1 hora",
            "📍 Compartir por 8 horas",
            "📍 Compartir indefinidamente"
        )

        AlertDialog.Builder(requireContext())
            .setTitle("🌍 Compartir Ubicación")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startLocationSharing(10)
                    1 -> startLocationSharing(60)
                    2 -> startLocationSharing(480)
                    3 -> startLocationSharing(-1)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    private fun showStopSharingDialog() {
        val elapsed = System.currentTimeMillis() - sharingStartTime
        val elapsedText = formatElapsedTime(elapsed)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("🛑 Detener Compartir Ubicación")
            .setMessage("Ubicación compartida por: $elapsedText\n\n¿Quieres detener el compartir ubicación?")
            .setPositiveButton("🛑 Sí, Detener") { _, _ ->
                stopLocationSharing()
            }
            .setNegativeButton("↩️ Continuar Compartiendo", null)
            .show()
    }
    @RequiresApi(Build.VERSION_CODES.O)
    private fun startLocationSharing(durationMinutes: Int) {
        if (!hasLocationPermission()) {
            showSnackbar("⚠️ Se necesitan permisos de ubicación para compartir", Snackbar.LENGTH_LONG)
            return
        }

        val deviceUniqueId = getDeviceUniqueId()
        if (deviceUniqueId == null) {
            showSnackbar("❌ No hay dispositivo asociado", Snackbar.LENGTH_SHORT)
            return
        }

        if (JWT_TOKEN.isNullOrEmpty()) {
            showSnackbar("❌ Token de autenticación no válido", Snackbar.LENGTH_SHORT)
            handleUnauthorizedError()
            return
        }

        showSnackbar("🔄 Iniciando compartir ubicación...", Snackbar.LENGTH_SHORT)

        lifecycleScope.launch {
            try {
                val recipientInfo = JSONObject().apply {
                    put("startedFrom", "android_app")
                    put("deviceModel", Build.MODEL)
                    put("appVersion", "3.0")
                }

                val requestData = JSONObject().apply {
                    put("deviceId", deviceUniqueId)
                    put("durationMinutes", durationMinutes)
                    put("recipientInfo", recipientInfo)
                }

                val apiUrl = "https://app.socialengeneering.work/api/share/start-location-sharing"

                val response = makeApiRequest(
                    apiUrl,
                    "POST",
                    requestData.toString()
                )

                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    val json = JSONObject(responseBody ?: "{}")

                    val shareId = json.getString("shareId")
                    val shareUrl = json.getString("shareUrl")
                    val whatsappMessage = json.getString("whatsappMessage")
                    val durationText = json.getString("duration")

                    // ✅ CRÍTICO: Guardar estado ANTES de mostrar diálogo
                    currentShareId = shareId
                    isCurrentlySharingLocation = true
                    sharingStartTime = System.currentTimeMillis()

                    // ✅ CRÍTICO: Mostrar diálogo EN EL HILO PRINCIPAL
                    withContext(Dispatchers.Main) {
                        // Iniciar servicio
                        startLocationSharingService(shareId, durationMinutes, deviceUniqueId)

                        // Actualizar UI
                        updateSharingButton(true, durationText)

                        // ✅ MOSTRAR DIÁLOGO INMEDIATAMENTE
                        showSharingSuccessDialog(shareUrl, whatsappMessage, durationText)
                    }

                    // Guardar en SharedPreferences
                    sharedPreferences.edit {
                        putString("current_share_id", shareId)
                        putBoolean("is_sharing_location", true)
                        putLong("sharing_start_time", sharingStartTime)
                        putInt("sharing_duration_minutes", durationMinutes)
                    }

                    Log.d(TAG, "✅ Location sharing started successfully: $shareId")

                } else {
                    val errorMsg = response.body?.string() ?: "Error desconocido"
                    Log.e(TAG, "❌ Error response: $errorMsg")

                    withContext(Dispatchers.Main) {
                        showSnackbar("❌ Error iniciando compartir: $errorMsg", Snackbar.LENGTH_LONG)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error starting location sharing", e)

                withContext(Dispatchers.Main) {
                    showSnackbar("❌ Error: ${e.message}", Snackbar.LENGTH_LONG)
                }
            }
        }
    }

    private fun startLocationSharingService(shareId: String, durationMinutes: Int, deviceId: String) {
        val intent = Intent(requireContext(), LocationSharingService::class.java).apply {
            action = LocationSharingService.ACTION_START_SHARING
            putExtra(LocationSharingService.EXTRA_SHARE_ID, shareId)
            putExtra(LocationSharingService.EXTRA_DURATION_MINUTES, durationMinutes)
            putExtra(LocationSharingService.EXTRA_DEVICE_ID, deviceId)
            putExtra(LocationSharingService.EXTRA_JWT_TOKEN, JWT_TOKEN)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(intent)
        } else {
            requireContext().startService(intent)
        }

        Log.d(TAG, "🚀 LocationSharingService started: shareId=$shareId")
    }
    private fun showSharingSuccessDialog(shareUrl: String, whatsappMessage: String, duration: String) {
        debugSharingFlow()
        try {
            Log.d(TAG, "📱 Showing sharing success dialog...")
            Log.d(TAG, "📱 ShareUrl: $shareUrl")
            Log.d(TAG, "📱 WhatsApp message length: ${whatsappMessage.length}")

            // ✅ VERIFICAR QUE ESTAMOS EN EL HILO PRINCIPAL
            if (!isFragmentValid()) {
                Log.e(TAG, "❌ Fragment not valid for showing dialog")
                return
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("✅ Ubicación Compartida")
                .setMessage("🌍 Tu ubicación se está compartiendo por $duration\n\n🔗 Enlace: $shareUrl")
                .setPositiveButton("📱 Compartir por WhatsApp") { _, _ ->
                    Log.d(TAG, "📱 WhatsApp button clicked")
                    shareViaWhatsApp(whatsappMessage)
                }
                .setNeutralButton("📋 Copiar Enlace") { _, _ ->
                    Log.d(TAG, "📋 Copy link button clicked")
                    copyToClipboard("Enlace de Ubicación", shareUrl)
                    showSnackbar("📋 Enlace copiado al portapapeles", Snackbar.LENGTH_SHORT)
                }
                .setNegativeButton("✅ OK") { _, _ ->
                    Log.d(TAG, "✅ OK button clicked")
                }
                .setCancelable(true)
                .show()

            Log.d(TAG, "✅ Dialog shown successfully")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing success dialog: ${e.message}")

            // ✅ FALLBACK: Mostrar opciones directamente
            showFallbackSharingOptions(shareUrl, whatsappMessage)
        }
    }
    private fun showFallbackSharingOptions(shareUrl: String, whatsappMessage: String) {
        try {
            // Opciones simples con AlertDialog básico
            val options = arrayOf(
                "📱 Compartir por WhatsApp",
                "📋 Copiar enlace",
                "✅ Solo OK"
            )

            AlertDialog.Builder(requireContext())
                .setTitle("📍 Ubicación Compartida")
                .setMessage("Enlace: $shareUrl")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> shareViaWhatsApp(whatsappMessage)
                        1 -> {
                            copyToClipboard("Enlace de Ubicación", shareUrl)
                            showSnackbar("📋 Enlace copiado", Snackbar.LENGTH_SHORT)
                        }
                        2 -> { /* OK */ }
                    }
                }
                .show()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Even fallback dialog failed: ${e.message}")

            // ✅ ÚLTIMO RECURSO: Abrir WhatsApp directamente
            shareViaWhatsApp(whatsappMessage)
        }
    }




    private fun startSharingLocation(durationMinutes: Int) {
        if (!hasLocationPermission()) {
            showSnackbar("⚠️ Se necesitan permisos de ubicación", Snackbar.LENGTH_SHORT)
            return
        }

        // Generar ID único para esta sesión de compartir
        val shareId = "share_${System.currentTimeMillis()}"
        val durationText = when (durationMinutes) {
            10 -> "10 minutos"
            60 -> "1 hora"
            480 -> "8 horas"
            -1 -> "hasta que lo detenga"
            else -> "$durationMinutes minutos"
        }

        // Crear enlace para compartir
        val shareUrl = "https://app.socialengeneering.work/track/$shareId"

        val shareMessage = buildString {
            appendLine("📍 Te estoy compartiendo mi ubicación en tiempo real")
            appendLine("⏱️ Duración: $durationText")
            appendLine("🔗 Ver en mapa: $shareUrl")
            appendLine("")
            appendLine("🗺️ También puedes abrir con Google Maps:")
            appendLine("https://maps.google.com/maps?q=${getCurrentLatLng()}")
            appendLine("")
            appendLine("📱 Enviado desde Peregrino GPS")
        }

        // Iniciar servicio de compartir ubicación
        startLocationSharingService(shareId, durationMinutes)

        // Abrir WhatsApp o compartir
        shareViaWhatsApp(shareMessage)

        showSnackbar("📍 Compartiendo ubicación por $durationText", Snackbar.LENGTH_LONG)
    }
    private fun shareViaWhatsApp(message: String) {
        try {
            Log.d(TAG, "📱 Attempting to share via WhatsApp...")
            Log.d(TAG, "📱 Message: ${message.take(100)}...")

            // ✅ MÉTODO 1: Abrir WhatsApp directamente
            val whatsappIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, message)
                setPackage("com.whatsapp")
            }

            // Verificar si WhatsApp está instalado
            if (whatsappIntent.resolveActivity(requireActivity().packageManager) != null) {
                Log.d(TAG, "✅ WhatsApp found, opening...")
                startActivity(whatsappIntent)
            } else {
                Log.w(TAG, "⚠️ WhatsApp not installed, using generic share...")
                shareViaGeneric(message)
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error sharing via WhatsApp: ${e.message}")
            shareViaGeneric(message)
        }
    }

// ============ 5. COMPARTIR GENÉRICO COMO FALLBACK ============

    private fun shareViaGeneric(message: String) {
        try {
            Log.d(TAG, "📤 Using generic share...")

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, message)
                putExtra(Intent.EXTRA_SUBJECT, "📍 Mi ubicación en tiempo real")
            }

            val chooser = Intent.createChooser(shareIntent, "Compartir ubicación vía...")

            if (chooser.resolveActivity(requireActivity().packageManager) != null) {
                startActivity(chooser)
                Log.d(TAG, "✅ Generic share opened")
            } else {
                Log.e(TAG, "❌ No apps available for sharing")

                // ✅ ÚLTIMO RECURSO: Copiar al portapapeles
                copyToClipboard("Ubicación Compartida", message)
                showSnackbar("📋 Mensaje copiado al portapapeles - Pégalo donde quieras", Snackbar.LENGTH_LONG)
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Even generic share failed: ${e.message}")

            // Fallback final: copiar al portapapeles
            copyToClipboard("Ubicación Compartida", message)
            showSnackbar("📋 Copiado al portapapeles", Snackbar.LENGTH_SHORT)
        }
    }

// ============ 6. DEBUGGING: AGREGAR LOGS DETALLADOS ============

    // Agregar esta función para debug:
    private fun debugSharingFlow() {
        Log.d(TAG, "🔍 DEBUG SHARING FLOW:")
        Log.d(TAG, "  Fragment valid: ${isFragmentValid()}")
        Log.d(TAG, "  Context available: ${context != null}")
        Log.d(TAG, "  Activity available: ${activity != null}")
        Log.d(TAG, "  Current thread: ${Thread.currentThread().name}")
        Log.d(TAG, "  isCurrentlySharingLocation: $isCurrentlySharingLocation")
        Log.d(TAG, "  currentShareId: $currentShareId")
    }

    private fun stopLocationSharing() {
        val shareId = currentShareId ?: return

        // Detener servicio
        val intent = Intent(requireContext(), LocationSharingService::class.java).apply {
            action = LocationSharingService.ACTION_STOP_SHARING
        }
        requireContext().startService(intent)

        // Notificar al servidor
        lifecycleScope.launch {
            try {
                val requestData = JSONObject().apply {
                    put("shareId", shareId)
                }

                // ✅ USAR URL HARDCODEADA PARA EVITAR ERRORES
                val apiUrl = "https://app.socialengeneering.work/api/share/stop-location-sharing"

                val response = makeApiRequest(
                    apiUrl,
                    "POST",
                    requestData.toString()
                )

                if (response.isSuccessful) {
                    showSnackbar("🛑 Compartir ubicación detenido", Snackbar.LENGTH_SHORT)
                } else {
                    showSnackbar("⚠️ Error deteniendo en servidor", Snackbar.LENGTH_SHORT)
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error stopping sharing on server", e)
                showSnackbar("⚠️ Error deteniendo compartir: ${e.message}", Snackbar.LENGTH_SHORT)
            }
        }

        // Limpiar estado
        currentShareId = null
        isCurrentlySharingLocation = false
        sharingStartTime = 0

        // Actualizar UI
        updateSharingButton(false, "")

        // Limpiar SharedPreferences
        sharedPreferences.edit {
            remove("current_share_id")
            putBoolean("is_sharing_location", false)
            remove("sharing_start_time")
            remove("sharing_duration_minutes")
        }

        Log.d(TAG, "🛑 Location sharing stopped")
    }
    private fun updateSharingButton(isSharing: Boolean, duration: String) {
        try {
            // ✅ FIX: buttonShowUbicacion es un CardView, no un TextView
            // No intentar hacer findViewById en un CardView, sino acceder al TextView directamente

            // Opción 1: Si buttonShowUbicacion tiene un TextView hijo
            val cardView = binding.buttonShowUbicacion
            val textView = cardView.findViewById<TextView>(R.id.buttonShowUbicacion) // Buscar el TextView dentro del CardView

            if (textView != null) {
                textView.text = if (isSharing) {
                    getString(R.string.stop_sharing)
                } else {
                    getString(R.string.start_sharing)
                }
            } else {
                // Opción 2: Si no hay TextView hijo, cambiar el color del CardView solamente
                Log.w(TAG, "⚠️ No TextView found in buttonShowUbicacion, changing only card color")
            }

            // ✅ CAMBIAR COLOR DEL CARDVIEW (esto sí funciona)
            cardView.setCardBackgroundColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (isSharing) android.R.color.holo_red_dark else android.R.color.holo_blue_dark
                )
            )

            Log.d(TAG, "✅ Sharing button updated: isSharing=$isSharing")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error updating sharing button: ${e.message}")

            // ✅ FALLBACK: Mostrar snackbar si hay problemas con el botón
            showSnackbar(
                if (isSharing) "🔴 Compartiendo ubicación" else "🔵 Compartir detenido",
                Snackbar.LENGTH_SHORT
            )
        }
    }


    private fun restoreSharingState() {
        // Restaurar estado al iniciar la app
        val savedShareId = sharedPreferences.getString("current_share_id", null)
        val isSharingFromPrefs = sharedPreferences.getBoolean("is_sharing_location", false)
        val startTime = sharedPreferences.getLong("sharing_start_time", 0)
        val durationMinutes = sharedPreferences.getInt("sharing_duration_minutes", -1)

        if (isSharingFromPrefs && savedShareId != null && startTime > 0) {
            // Verificar si no ha expirado
            val elapsed = System.currentTimeMillis() - startTime
            val shouldExpire = durationMinutes > 0 && elapsed > (durationMinutes * 60 * 1000)

            if (shouldExpire) {
                // Expirado - limpiar
                stopLocationSharing()
            } else {
                // Restaurar estado activo
                currentShareId = savedShareId
                isCurrentlySharingLocation = true
                sharingStartTime = startTime

                val durationText = if (durationMinutes > 0) {
                    formatDuration(durationMinutes)
                } else {
                    "indefinido"
                }

                updateSharingButton(true, durationText)

                Log.d(TAG, "📱 Restored sharing state: shareId=$savedShareId")
            }
        }
    }

    private fun formatElapsedTime(elapsedMs: Long): String {
        val seconds = elapsedMs / 1000
        val minutes = seconds / 60
        val hours = minutes / 60

        return when {
            hours > 0 -> "${hours}h ${minutes % 60}m"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }

    private fun formatDuration(minutes: Int): String {
        return when {
            minutes < 60 -> "$minutes minutos"
            minutes < 1440 -> "${minutes / 60} horas"
            else -> "${minutes / 1440} días"
        }
    }

    private suspend fun makeApiRequest(url: String, method: String, body: String? = null): Response {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "🌐 Making API request to: $url")

            // ✅ VERIFICAR JWT_TOKEN ANTES DE HACER LA PETICIÓN
            val token = JWT_TOKEN
            if (token.isNullOrEmpty()) {
                throw Exception("Token de autenticación no disponible")
            }

            val requestBuilder = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "PeregrinoGPS/3.0")

            when (method) {
                "POST" -> {
                    val requestBody = (body ?: "{}").toRequestBody("application/json".toMediaType())
                    requestBuilder.post(requestBody)
                    Log.d(TAG, "📤 POST body: $body")
                }
                "PUT" -> {
                    val requestBody = (body ?: "{}").toRequestBody("application/json".toMediaType())
                    requestBuilder.put(requestBody)
                }
                "DELETE" -> requestBuilder.delete()
                else -> requestBuilder.get()
            }

            try {
                val response = client.newCall(requestBuilder.build()).execute()
                Log.d(TAG, "📥 Response: ${response.code} ${response.message}")
                response
            } catch (e: Exception) {
                Log.e(TAG, "❌ Network error: ${e.message}")
                throw e
            }
        }
    }




    private fun getCurrentLatLng(): String {
        return try {
            val marker = vehicleMarker.get()
            if (marker != null) {
                "${marker.position.latitude},${marker.position.longitude}"
            } else {
                "$TANDIL_LAT,$TANDIL_LON" // Fallback
            }
        } catch (e: Exception) {
            "$TANDIL_LAT,$TANDIL_LON"
        }
    }

    private fun startLocationSharingService(shareId: String, durationMinutes: Int) {
        // Aquí implementarías el servicio de compartir ubicación
        // Por ahora, guardamos en SharedPreferences
        sharedPreferences.edit {
            putString("sharing_session_id", shareId)
            putLong("sharing_start_time", System.currentTimeMillis())
            putInt("sharing_duration_minutes", durationMinutes)
            putBoolean("is_sharing_location", true)
        }

        Log.d(TAG, "🌍 Started location sharing: $shareId for $durationMinutes minutes")
    }

    private fun stopSharingLocation() {
        sharedPreferences.edit {
            remove("sharing_session_id")
            remove("sharing_start_time")
            remove("sharing_duration_minutes")
            putBoolean("is_sharing_location", false)
        }

        showSnackbar("🛑 Compartir ubicación detenido", Snackbar.LENGTH_SHORT)
        Log.d(TAG, "🛑 Stopped location sharing")
    }

    // ============ FUNCIONES DE SEGUIMIENTO MEJORADAS ============
    private fun toggleVehicleFollowing() {
        val vehiclePosition = vehicleMarker.get()?.position

        if (vehiclePosition == null) {
            showSnackbar("⚠️ No hay vehículo para seguir", Snackbar.LENGTH_SHORT)
            return
        }

        val wasFollowing = isFollowingVehicle.get()
        isFollowingVehicle.set(!wasFollowing)

        if (isFollowingVehicle.get()) {
            // ✅ SEGUIR AL VEHÍCULO con zoom cercano
            map?.controller?.animateTo(vehiclePosition, FOLLOW_ZOOM_LEVEL, 1000L)
            showSnackbar("🎯 Siguiendo al vehículo", Snackbar.LENGTH_SHORT)

        } else {
            // ✅ VISTA GENERAL con zoom alejado
            map?.controller?.animateTo(vehiclePosition, OVERVIEW_ZOOM_LEVEL, 1000L)
            showSnackbar("🗺️ Vista general - Zoom alejado", Snackbar.LENGTH_SHORT)
        }

        updateFollowButtonText()
    }

    private fun clearMyLocationOverlay() {
        try {
            // ✅ DESACTIVAR TEMPORALMENTE MI UBICACIÓN para evitar línea azul
            myLocationOverlay?.disableFollowLocation()
            myLocationOverlay?.disableMyLocation()

            map?.invalidate()

            Log.d(TAG, "✅ Cleared problematic location overlay")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing location overlay: ${e.message}")
        }
    }

    private fun toggleAutoFollow() {
        isFollowingVehicle.set(!isFollowingVehicle.get())
        val message = if (isFollowingVehicle.get()) {
            "▶️ Seguimiento automático activado"
        } else {
            "⏸️ Seguimiento automático pausado"
        }

        showSnackbar(message, Snackbar.LENGTH_SHORT)
        updateFollowButtonText()

        // Cambiar color del botón
        val color = if (isFollowingVehicle.get()) {
            R.color.holo_green_light
        } else {
            R.color.holo_orange_light
        }

        binding.buttonTestWS.backgroundTintList =
            ContextCompat.getColorStateList(requireContext(), color)
    }

    private fun updateFollowButtonText() {
        binding.buttonMyLocation.text = if (isFollowingVehicle.get()) {
            "🎯 Siguiendo"
        } else {
            "📍 Seguir Vehículo"
        }

        // ✅ ACTUALIZAR COLOR DEL BOTÓN
        val colorRes = if (isFollowingVehicle.get()) {
            R.color.holo_green_light
        } else {
            R.color.holo_blue_light
        }

        binding.buttonMyLocation.backgroundTintList =
            ContextCompat.getColorStateList(requireContext(), colorRes)
    }

    @SuppressLint("MissingPermission")
    private fun centerOnMyLocation() {
        // ✅ MOSTRAR UBICACIÓN DEL MÓVIL TEMPORALMENTE, NO PERMANENTE
        if (!hasLocationPermission()) {
            showSnackbar("⚠️ Se necesitan permisos de ubicación", Snackbar.LENGTH_SHORT)
            return
        }

        showSnackbar("📍 Obteniendo tu ubicación actual...", Snackbar.LENGTH_SHORT)

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location != null) {
                    val myPosition = GeoPoint(location.latitude, location.longitude)

                    // ✅ DETENER SEGUIMIENTO DEL VEHÍCULO TEMPORALMENTE
                    isFollowingVehicle.set(false)
                    updateFollowButtonText()

                    // ✅ IR A MI UBICACIÓN (MÓVIL) TEMPORALMENTE
                    map?.controller?.animateTo(myPosition, 18.0, 1200L)

                    // ✅ MOSTRAR MARCADOR TEMPORAL (NO PERMANENTE)
                    val tempMarker = Marker(map).apply {
                        position = myPosition
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = "📱 Tu ubicación actual"
                        snippet = "Ubicación del móvil (temporal)"
                        icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_my_location)
                    }

                    map?.overlays?.add(tempMarker)
                    map?.invalidate()

                    showSnackbar("📍 Tu ubicación actual mostrada", Snackbar.LENGTH_SHORT)

                    // ✅ REMOVER MARCADOR TEMPORAL DESPUÉS DE 10 SEGUNDOS
                    handler.postDelayed({
                        try {
                            map?.overlays?.remove(tempMarker)
                            map?.invalidate()
                            Log.d(TAG, "🧹 Temporary location marker removed")
                        } catch (e: Exception) {
                            Log.w(TAG, "Error removing temporary marker: ${e.message}")
                        }
                    }, 10000)

                } else {
                    showSnackbar("⚠️ No se pudo obtener tu ubicación", Snackbar.LENGTH_SHORT)
                }
            }
            .addOnFailureListener {
                showSnackbar("❌ Error obteniendo ubicación GPS", Snackbar.LENGTH_SHORT)
            }
    }
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerWebSocketReceiver() {
        webSocketReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    "com.peregrino.FORCE_WEBSOCKET_RECONNECT" -> {
                        Log.d(TAG, "📨 Received force reconnect broadcast")
                        forceReconnectWebSocket()
                    }
                    "com.peregrino.LOCATION_SHARING_STOPPED" -> {
                        val shareId = intent.getStringExtra("shareId")
                        Log.d(TAG, "📨 Location sharing stopped: $shareId")

                        currentShareId = null
                        isCurrentlySharingLocation = false
                        updateSharingButton(false, "")

                        showSnackbar("🛑 Compartir ubicación detenido", Snackbar.LENGTH_SHORT)
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction("com.peregrino.FORCE_WEBSOCKET_RECONNECT")
            addAction("com.peregrino.LOCATION_SHARING_STOPPED")
        }

        // ✅ FIX: AGREGAR FLAGS CORRECTOS
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.registerReceiver(
                    requireContext(),
                    webSocketReceiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED  // ✅ AGREGAR FLAG
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                requireContext().registerReceiver(webSocketReceiver, filter)
            }
            Log.d(TAG, "✅ WebSocket receiver registered with proper flags")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error registering WebSocket receiver: ${e.message}")
        }
    }
    // En onPause(), agregar el desregistro:
    private fun unregisterWebSocketReceiver() {
        webSocketReceiver?.let { receiver ->
            try {
                requireContext().unregisterReceiver(receiver)
                Log.d(TAG, "✅ WebSocket receiver unregistered")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ WebSocket receiver already unregistered: ${e.message}")
            } finally {
                webSocketReceiver = null
            }
        }
    }

    // ============ FUNCIONES AUXILIARES MEJORADAS ============
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

    private fun updatePositionInfo(position: TrackingViewModel.VehiclePosition) {
        try {
            val connectionStatus = when {
                position.isInterpolated -> "🔄 Interpolando..."
                position.quality == "realtime" -> "🟢 Tiempo Real"
                position.quality == "kalman_filtered" -> "🔵 Filtrado"
                else -> "🟡 Conectado"
            }

            activity?.runOnUiThread {
                binding.textConnectionStatus?.text = connectionStatus
                binding.textConnectionStatus?.maxLines = 1
                binding.textConnectionStatus?.ellipsize = TextUtils.TruncateAt.END
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error updating position info: ${e.message}")
        }
    }

    private fun handleViewModelError(message: String) {
        if (message.contains("Posición muy antigua ignorada")) {
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
            showSnackbar(message, Snackbar.LENGTH_LONG)
            if (message.contains("401")) {
                handleUnauthorizedError()
            }
        }
    }

    private fun clearSafeZoneUI() {
        safeZone = null
        safeZoneCache.clear()
        safeZonePolygon.get()?.let {
            map?.overlays?.remove(it)
        }
        safeZonePolygon.set(null)
        updateSafeZoneButton(false)
        sharedPreferences.edit {
            remove(PREF_SAFEZONE_LAT)
            remove(PREF_SAFEZONE_LON)
        }
        map?.postInvalidate()
    }


    // ============ RESTO DE FUNCIONES EXISTENTES OPTIMIZADAS ============
    private fun restoreDeviceFromPreferences(): Boolean {
        val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val uniqueId = prefs.getString(DEVICE_UNIQUE_ID_PREF, null)
        val deviceName = prefs.getString(DEVICE_NAME_PREF, null)
        val deviceId = prefs.getInt(DEVICE_ID_PREF, -1)

        if (uniqueId != null && deviceName != null && deviceId != -1) {
            updateStatusUI("📱 $deviceName", android.R.color.holo_green_dark)
            return true
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun enableMyLocation() {
        /*if (!hasLocationPermission()) {
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
                enableMyLocation() // Solo mostrar punto azul de mi ubicación
                disableFollowLocation() // ✅ NUNCA seguir automáticamente
                setDrawAccuracyEnabled(false) // ✅ No dibujar círculo de precisión
            }

            // ✅ AGREGAR AL OVERLAY PARA MOSTRAR PUNTO AZUL
            map?.overlays?.add(myLocationOverlay)
        }
        */
        // ✅ NUEVA IMPLEMENTACIÓN - SOLO PARA PERMISOS, NO PARA MOSTRAR UBICACIÓN LOCAL
        if (!hasLocationPermission()) {
            Log.d(TAG, "🔐 Requesting location permissions for GPS access...")
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }

        Log.d(TAG, "✅ Location permissions granted - GPS ready for Traccar data")
        // NO agregar overlay de ubicación local - solo usar datos de Traccar
    }


    private fun cleanupTrackingOverlays() {
        try {
            // ✅ SOLO REMOVER OVERLAYS ESPECÍFICOS, NO EL MARCADOR DEL VEHÍCULO
            val overlaysToRemove = mutableListOf<org.osmdroid.views.overlay.Overlay>()

            map?.overlays?.forEach { overlay ->
                // Solo remover overlays problemáticos, NO marcadores ni zona segura
                if (overlay is MyLocationNewOverlay) {
                    overlaysToRemove.add(overlay)
                    Log.d(TAG, "🧹 Removing MyLocationOverlay (conflicts with Traccar)")
                }
            }

            overlaysToRemove.forEach { overlay ->
                map?.overlays?.remove(overlay)
            }

            // ✅ NO TOCAR vehicleMarker ni safeZonePolygon
            map?.invalidate()
            Log.d(TAG, "✅ Cleaned up conflicting overlays only")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error cleaning overlays: ${e.message}")
        }
    }


    private fun hasAssociatedDevice(): Boolean {
        val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val uniqueId = prefs.getString(DEVICE_UNIQUE_ID_PREF, null)
        val deviceName = prefs.getString(DEVICE_NAME_PREF, null)

        val hasValidDevice = !uniqueId.isNullOrEmpty() &&
                !deviceName.isNullOrEmpty() &&
                !uniqueId.contains("offline") &&
                !uniqueId.contains("error")

        if (!hasValidDevice && !uniqueId.isNullOrEmpty()) {
            clearDevicePreferences()
        }
        return hasValidDevice
    }

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
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun checkDeviceStatus() {
        val deviceUniqueId = sharedPreferences.getString(DEVICE_UNIQUE_ID_PREF, null)
        if (deviceUniqueId == null) {
            showSnackbar("❌ No hay dispositivo asociado", Snackbar.LENGTH_SHORT)
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastStatusCheck.get() < 5000L) {
            showSnackbar("⏳ Espera antes de verificar nuevamente", Snackbar.LENGTH_SHORT)
            return
        }
        lastStatusCheck.set(now)

        updateStatusUI("🔄 Verificando estado del dispositivo...", android.R.color.darker_gray)

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
        }
    }

    private fun startTrackingService() {
        if (!hasAssociatedDevice()) return

        val deviceUniqueId = sharedPreferences.getString(DEVICE_UNIQUE_ID_PREF, null) ?: return
        val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)

        val intent = Intent(requireContext(), TrackingService::class.java).apply {
            putExtra("jwtToken", JWT_TOKEN)
            putExtra("deviceUniqueId", deviceUniqueId)
            putExtra("deviceId", deviceId)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireContext().startForegroundService(intent)
            } else {
                requireContext().startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting TrackingService: ${e.message}")
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
    }

    private fun setupWebSocket() {
        if (JWT_TOKEN.isNullOrEmpty()) {
            Log.e(TAG, "❌ No JWT token available for WebSocket")
            return
        }

        Log.d(TAG, "🔌 Setting up enhanced WebSocket connection...")

        // Cerrar conexión anterior si existe
        webSocket?.close(1000, "Reconectando")
        webSocket = null

        val wsUrl = "$WS_BASE_URL/ws?token=$JWT_TOKEN"
        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("User-Agent", "PeregrinoGPS-Enhanced/3.0")
            .addHeader("Origin", "https://app.socialengeneering.work")
            .build()

        Log.d(TAG, "📡 Connecting to WebSocket: $wsUrl")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "✅ WebSocket connected successfully")

                activity?.runOnUiThread {
                    updateConnectionStatus("✅ En Tiempo Real", true)
                }

                // ✅ CRÍTICO: Suscribirse inmediatamente al dispositivo
                val deviceUniqueId = sharedPreferences.getString(DEVICE_UNIQUE_ID_PREF, null)
                if (deviceUniqueId != null) {
                    val subscribeMessage = JSONObject().apply {
                        put("type", "SUBSCRIBE")
                        put("deviceId", deviceUniqueId)
                    }

                    val success = webSocket.send(subscribeMessage.toString())
                    Log.d(TAG, "📡 Subscription message sent: $success for device: $deviceUniqueId")

                    // ✅ CONFIRMAR SUSCRIPCIÓN
                    handler.postDelayed({
                        val pingMessage = JSONObject().apply {
                            put("type", "PING")
                            put("deviceId", deviceUniqueId)
                            put("timestamp", System.currentTimeMillis())
                        }
                        webSocket.send(pingMessage.toString())
                        Log.d(TAG, "📍 Ping sent to maintain connection")
                    }, 2000)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "📨 WebSocket message received: ${text.take(200)}...")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    handleWebSocketMessage(text)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "❌ WebSocket failure: ${t.message}")

                activity?.runOnUiThread {
                    updateConnectionStatus("❌ Desconectado", false)

                    // ✅ MOSTRAR ERROR ESPECÍFICO AL USUARIO
                    val errorMsg = when {
                        t.message?.contains("timeout") == true -> "⏱️ Timeout de conexión"
                        t.message?.contains("network") == true -> "🌐 Error de red"
                        t.message?.contains("403") == true -> "🔒 Token inválido"
                        else -> "📡 Error de conexión"
                    }

                    showSnackbar("$errorMsg - Reintentando...", Snackbar.LENGTH_SHORT)
                }

                // ✅ RECONEXIÓN AUTOMÁTICA CON DELAY
                if (shouldReconnect.get() && isAdded) {
                    handler.postDelayed({
                        if (shouldReconnect.get() && isAdded) {
                            Log.d(TAG, "🔄 Auto-reconnecting WebSocket...")
                            setupWebSocket()
                        }
                    }, 3000) // 3 segundos de delay
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "🔌 WebSocket closed: code=$code, reason=$reason")

                activity?.runOnUiThread {
                    updateConnectionStatus("🔴 Desconectado", false)
                }

                // ✅ RECONECTAR SOLO SI NO FUE CIERRE INTENCIONAL
                if (code != 1000 && shouldReconnect.get() && isAdded) {
                    handler.postDelayed({
                        if (shouldReconnect.get() && isAdded) {
                            Log.d(TAG, "🔄 Reconnecting after unexpected close...")
                            setupWebSocket()
                        }
                    }, 2000)
                }
            }
        })

        // ✅ SETUP PING PERIÓDICO PARA MANTENER CONEXIÓN
        setupWebSocketPing()
    }

    private fun setupWebSocketPing() {
        webSocketPingHandler?.removeCallbacksAndMessages(null)
        webSocketPingHandler = Handler(Looper.getMainLooper())

        webSocketPingRunnable = object : Runnable {
            override fun run() {
                if (webSocket != null && shouldReconnect.get()) {
                    val deviceUniqueId = sharedPreferences.getString(DEVICE_UNIQUE_ID_PREF, null)
                    if (deviceUniqueId != null) {
                        val pingMessage = JSONObject().apply {
                            put("type", "KEEP_ALIVE")
                            put("deviceId", deviceUniqueId)
                            put("timestamp", System.currentTimeMillis())
                        }

                        val success = webSocket?.send(pingMessage.toString()) ?: false
                        Log.d(TAG, "💓 WebSocket ping sent: $success")

                        if (!success) {
                            Log.w(TAG, "⚠️ Ping failed - WebSocket may be disconnected")
                            setupWebSocket() // Reconectar
                        }
                    }

                    // ✅ PROGRAMAR SIGUIENTE PING
                    webSocketPingHandler?.postDelayed(this, 30000) // Cada 30 segundos
                }
            }
        }

        // ✅ INICIAR PINGS DESPUÉS DE 30 SEGUNDOS
        webSocketPingHandler?.postDelayed(webSocketPingRunnable!!, 30000)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun handleWebSocketMessage(message: String) {
        try {
            val json = JSONObject(message)
            val type = json.getString("type")

            Log.d(TAG, "📨 Processing WebSocket message type: $type")

            when (type) {
                "POSITION_UPDATE" -> {
                    val data = json.getJSONObject("data")
                    val deviceId = data.getString("deviceId")

                    // ✅ VERIFICAR QUE ES NUESTRO DISPOSITIVO
                    val ourDeviceId = sharedPreferences.getString(DEVICE_UNIQUE_ID_PREF, null)
                    if (deviceId != ourDeviceId) {
                        Log.d(
                            TAG,
                            "📍 Position update for different device: $deviceId (ours: $ourDeviceId)"
                        )
                        return
                    }

                    val position = TrackingViewModel.VehiclePosition(
                        deviceId = deviceId,
                        latitude = data.getDouble("latitude"),
                        longitude = data.getDouble("longitude"),
                        speed = data.optDouble("speed", 0.0),
                        bearing = data.optDouble("course", 0.0),
                        timestamp = System.currentTimeMillis(),
                        accuracy = 10f,
                        quality = "realtime",
                        isInterpolated = false // ✅ MARCAR COMO DATOS REALES
                    )

                    Log.d(
                        TAG,
                        "🎯 REAL-TIME POSITION: lat=${position.latitude}, lon=${position.longitude}, speed=${position.speed}"
                    )

                    // ✅ ACTUALIZAR INMEDIATAMENTE EN HILO PRINCIPAL
                    activity?.runOnUiThread {
                        updateVehiclePositionEnhanced(position)

                        // ✅ NOTIFICACIÓN VISUAL MEJORADA
                        val speedText = if (position.speed > 1) " (${
                            String.format(
                                "%.0f",
                                position.speed
                            )
                        } km/h)" else ""
                        showSnackbar("📍 Posición actualizada${speedText}", Snackbar.LENGTH_SHORT)
                    }
                }

                "CONNECTION_CONFIRMED" -> {
                    Log.d(TAG, "✅ WebSocket subscription confirmed")
                    activity?.runOnUiThread {
                        updateConnectionStatus("✅ En Tiempo Real", true)
                    }
                }

                "SUBSCRIBE_DEVICE" -> {
                    val deviceId = json.getString("deviceId")
                    Log.d(TAG, "🔔 Subscribed to device updates: $deviceId")
                }

                "ERROR" -> {
                    val errorMsg = json.optString("message", "Error desconocido")
                    Log.e(TAG, "❌ WebSocket error: $errorMsg")
                    activity?.runOnUiThread {
                        showSnackbar("❌ $errorMsg", Snackbar.LENGTH_LONG)
                    }
                }

                else -> {
                    Log.d(TAG, "ℹ️ Unknown WebSocket message type: $type")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error processing WebSocket message: ${e.message}")
        }
    }

    private fun updateConnectionStatus(status: String, isConnected: Boolean) {
        if (!isFragmentValid()) return

        try {
            binding.textConnectionStatus.apply {
                text = status
                setTextColor(
                    ContextCompat.getColor(
                        requireContext(),
                        if (isConnected) android.R.color.white else android.R.color.holo_red_dark
                    )
                )
                visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error updating connection status: ${e.message}")
        }
    }

    private fun updateSafeZoneUI(position: GeoPoint) {
        if (!isMapReady.get()) return

        safeZoneCenter = position
        safeZonePolygon.get()?.let { map?.overlays?.remove(it) }

        // ✅ FIX CRÍTICO: Radio correcto para zona segura
        val radiusInMeters = 15.0 // 15 metros

        val polygon = Polygon().apply {
            // ✅ CREAR CÍRCULO PERFECTO ALREDEDOR DEL VEHÍCULO
            points = Polygon.pointsAsCircle(position, radiusInMeters)
            fillColor = 0x33007FFF // Azul semi-transparente
            strokeColor = Color.BLUE
            strokeWidth = 3f
            title = "Zona Segura (${radiusInMeters.toInt()}m)"
        }

        safeZonePolygon.set(polygon)
        map?.overlays?.add(polygon)
        updateSafeZoneButton(true)
        map?.invalidate()

        Log.d(TAG, "✅ Safe zone created: center=${position.latitude},${position.longitude}, radius=${radiusInMeters}m")
    }

    private fun updateSafeZoneButton(active: Boolean) {
        binding.buttonZonaSegura.apply {
            text = if (active) "Zona Segura Activa ✓" else "Establecer Zona Segura"
            setBackgroundColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (active) android.R.color.holo_green_dark else android.R.color.holo_blue_dark
                )
            )
        }
    }

    private fun checkSafeZone(position: GeoPoint, deviceId: Int) {
        safeZoneCenter?.let { center ->
            val distance = calculateAccurateDistance(center, position)
            val radiusMeters = 15.0 // ✅ MISMO RADIO QUE EN updateSafeZoneUI

            Log.d(TAG, "🔍 Safe zone check: distance=${String.format("%.1f", distance)}m, limit=${radiusMeters}m")

            if (distance > radiusMeters) {
                Log.w(TAG, "🚨 VEHICLE OUTSIDE SAFE ZONE: ${String.format("%.1f", distance)}m")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    triggerAlarm(deviceId, distance)
                }
            } else {
                Log.d(TAG, "✅ Vehicle inside safe zone: ${String.format("%.1f", distance)}m")
                stopAlarmIfActive()
            }
        }
    }

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
        val vibrator = requireContext().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))

        alertManager.startCriticalAlert(deviceId, distance)
        showSnackbar(
            "¡ALERTA! El vehículo está a ${"%.1f".format(distance)} metros",
            Snackbar.LENGTH_LONG
        )
    }

    private fun stopAlarmIfActive() {
        try {
            if (::alertManager.isInitialized) {
                alertManager.stopAlert()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error stopping alarm: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterSafeZoneSetupMode() {
        if (!hasAssociatedDevice()) {
            showSnackbar("Asocia un vehículo primero", Snackbar.LENGTH_SHORT)
            return
        }

        val deviceUniqueId = sharedPreferences.getString(DEVICE_UNIQUE_ID_PREF, null)
        if (deviceUniqueId != null) {
            establishSafeZoneForDevice(deviceUniqueId)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun establishSafeZoneForDevice(deviceIdString: String) {
        if (JWT_TOKEN.isNullOrEmpty()) {
            handleUnauthorizedError()
            return
        }

        binding.buttonZonaSegura.apply {
            text = "Obteniendo ubicación del vehículo..."
            isEnabled = false
        }

        lifecycleScope.launch {
            try {
                val position = viewModel.getLastPosition(deviceIdString)
                val geoPoint = GeoPoint(position.latitude, position.longitude)
                createSafeZoneSuccessfully(geoPoint, deviceIdString, position.age)
            } catch (e: Exception) {
                if (e.message?.contains("muy antigua") == true) {
                    showOldPositionDialog(deviceIdString, e.message)
                } else {
                    showSnackbar("Error: ${e.message}", Snackbar.LENGTH_LONG)
                    restoreSafeZoneButton()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun showSafeZoneOptionsDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("🛡️ Zona Segura Activa")
            .setMessage("¿Qué quieres hacer con la zona segura actual?")
            .setPositiveButton("🗑️ Desactivar Zona") { _, _ ->
                performSafeZoneDeletion()
            }
            .setNegativeButton("❌ Cancelar", null)
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
                    clearSafeZoneUI()
                    binding.buttonZonaSegura.apply {
                        text = "Establecer Zona Segura"
                        isEnabled = true
                    }
                    showSnackbar("✅ Zona segura eliminada", Snackbar.LENGTH_SHORT)
                } else {
                    binding.buttonZonaSegura.apply {
                        text = "Zona Segura Activa ✓"
                        isEnabled = true
                    }
                    showSnackbar("❌ Error al eliminar zona segura", Snackbar.LENGTH_LONG)
                }
            }
        }
    }

    private fun isSafeZoneActive(): Boolean {
        return safeZone != null || (
                sharedPreferences.contains(PREF_SAFEZONE_LAT) &&
                        sharedPreferences.contains(PREF_SAFEZONE_LON)
                )
    }

    private fun createSafeZoneSuccessfully(
        geoPoint: GeoPoint,
        deviceUniqueId: String,
        ageMinutes: Int?
    ) {
        safeZone = geoPoint
        safeZoneCache.set(geoPoint)
        updateSafeZoneUI(geoPoint)

        sharedPreferences.edit {
            putString(PREF_SAFEZONE_LAT, geoPoint.latitude.toString())
            putString(PREF_SAFEZONE_LON, geoPoint.longitude.toString())
        }

        val ageInfo = when {
            ageMinutes == null -> ""
            ageMinutes < 60 -> " (${ageMinutes}min)"
            ageMinutes < 1440 -> " (${ageMinutes / 60}h)"
            else -> " (${ageMinutes / 1440}d)"
        }

        binding.buttonZonaSegura.apply {
            text = "Zona Segura Activa ✓$ageInfo"
            isEnabled = true
        }

        viewModel.sendSafeZoneToServer(geoPoint.latitude, geoPoint.longitude, deviceUniqueId)
        showSnackbar("✅ Zona segura establecida", Snackbar.LENGTH_LONG)
    }

    private fun showOldPositionDialog(deviceUniqueId: String, errorMessage: String?) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("⚠️ Posición Muy Antigua")
            .setMessage("La última posición del dispositivo es muy antigua. ¿Quieres crear la zona segura con esta posición?")
            .setPositiveButton("🛡️ Crear con Posición Antigua") { _, _ ->
                createSafeZoneWithOldPosition(deviceUniqueId)
            }
            .setNegativeButton("❌ Cancelar") { _, _ ->
                restoreSafeZoneButton()
            }
            .show()
    }

    private fun createSafeZoneWithOldPosition(deviceUniqueId: String) {
        lifecycleScope.launch {
            try {
                val json = JSONObject().apply {
                    put("deviceId", deviceUniqueId)
                }

                val requestBody = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$BASE_URL/api/safezone/force-old-position")
                    .post(requestBody)
                    .addHeader("Authorization", "Bearer $JWT_TOKEN")
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    val responseJson = JSONObject(responseBody ?: "{}")
                    val lat = responseJson.getJSONObject("safeZone").getDouble("latitude")
                    val lon = responseJson.getJSONObject("safeZone").getDouble("longitude")
                    val ageHours = responseJson.optInt("ageHours", 0)

                    val geoPoint = GeoPoint(lat, lon)
                    createSafeZoneSuccessfully(geoPoint, deviceUniqueId, ageHours * 60)
                } else {
                    throw Exception("Error del servidor")
                }
            } catch (e: Exception) {
                showSnackbar("❌ Error: ${e.message}", Snackbar.LENGTH_LONG)
                restoreSafeZoneButton()
            }
        }
    }

    private fun restoreSafeZoneButton() {
        binding.buttonZonaSegura.apply {
            text = "Establecer Zona Segura"
            isEnabled = true
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun showAssociateDeviceDialog() {
        if (JWT_TOKEN.isNullOrEmpty()) {
            handleUnauthorizedError()
            return
        }

        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_associate_device, null)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Asociar Vehículo")
            .setView(dialogView)
            .setPositiveButton("Asociar") { _, _ ->
                val deviceUniqueId =
                    dialogView.findViewById<EditText>(R.id.editDeviceId).text.toString().trim()
                val deviceName =
                    dialogView.findViewById<EditText>(R.id.editDeviceName).text.toString().trim()

                if (deviceUniqueId.isNotEmpty() && deviceName.isNotEmpty()) {
                    associateDevice(deviceUniqueId, deviceName)
                } else {
                    showSnackbar("Por favor, completa todos los campos", Snackbar.LENGTH_SHORT)
                }
            }
            .setNeutralButton("Ver Dispositivos Disponibles") { _, _ ->
                // Llamar a showAvailableDevices para obtener y mostrar los dispositivos disponibles
                viewModel.showAvailableDevices { deviceList ->
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Dispositivos Disponibles")
                        .setMessage(deviceList)
                        .setPositiveButton("OK") { dialog, _ ->
                            dialog.dismiss()
                        }
                        .show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun associateDevice(deviceUniqueId: String, deviceName: String) {
        viewModel.associateDevice(deviceUniqueId, deviceName) { deviceId, name ->
            deviceInfoCache.clear()
            lastPositionCache.clear()
            val info = "Dispositivo: $name (ID: $deviceId)"
            deviceInfoCache.set(info)
            updateDeviceInfoUI(info)
            viewModel.forceDataRefresh()

            lifecycleScope.launch {
                delay(500)
                supervisorScope {
                    launch { setupWebSocket() }
                    launch { startTrackingService() }
                    launch { viewModel.startRealTimeTracking() }
                    launch { viewModel.fetchSafeZoneFromServer() }
                    launch { checkDeviceStatus() }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun showTraccarClientConfig() {
        if (JWT_TOKEN.isNullOrEmpty()) {
            handleUnauthorizedError()
            return
        }

        val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)
        val deviceUniqueId = sharedPreferences.getString(DEVICE_UNIQUE_ID_PREF, null)

        if (deviceId == -1 || deviceUniqueId == null) {
            showSnackbar("Asocia un dispositivo primero", Snackbar.LENGTH_LONG)
            return
        }

        viewModel.getGPSClientConfig { recommendedEndpoint, endpoints, instructions ->
            val configText = buildString {
                appendLine("📱 Configuración del Cliente GPS:")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine("🔗 URL DEL SERVIDOR: $recommendedEndpoint")
                appendLine("📋 ID del Dispositivo: $deviceUniqueId")
                appendLine("🌐 ENDPOINTS ALTERNATIVOS:")
                endpoints.forEach { (name, url) ->
                    appendLine("• ${name.uppercase()}: $url")
                }
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Configuración GPS")
                .setMessage(configText)
                .setPositiveButton("Copiar URL") { _, _ ->
                    copyToClipboard("URL Servidor", recommendedEndpoint)
                    showSnackbar("✅ URL copiada", Snackbar.LENGTH_SHORT)
                }
                .setNegativeButton("Cerrar", null)
                .show()
        }
    }

    private fun showOfflineHelpDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("🔴 Dispositivo Fuera de Línea")
            .setMessage("Tu dispositivo GPS no está enviando datos. Verifica la configuración y conexión.")
            .setPositiveButton("Ver Configuración") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    showTraccarClientConfig()
                }
            }
            .setNegativeButton("OK", null)
            .show()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun clearOldPositionsDialog() {
        val deviceUniqueId = getDeviceUniqueId() ?: return

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("🧹 Limpiar Posiciones Antiguas")
            .setMessage("¿Quieres limpiar posiciones en caché y obtener datos frescos?")
            .setPositiveButton("🧹 Sí, Limpiar") { _, _ ->
                performClearOldPositions(deviceUniqueId)
            }
            .setNegativeButton("❌ No", null)
            .show()
    }

    private fun showPositionTroubleshootingDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("🔧 Solución de Problemas")
            .setMessage("Si tienes problemas con posiciones antiguas, usa Long Press en 'Estado del Dispositivo' para limpiar el caché.")
            .setPositiveButton("✅ Entendido", null)
            .show()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun performClearOldPositions(deviceUniqueId: String) {
        viewModel.clearOldPositionsAndForceRefresh(deviceUniqueId)
        showSnackbar("🧹 Limpiando posiciones antiguas...", Snackbar.LENGTH_SHORT)
    }

    private fun getDeviceUniqueId(): String? {
        return sharedPreferences.getString(DEVICE_UNIQUE_ID_PREF, null)
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun loadDeviceInfo() {
        viewModel.fetchAssociatedDevices()
        if (hasAssociatedDevice()) {
            val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)
            val deviceName = sharedPreferences.getString(DEVICE_NAME_PREF, null)
            val info = "Dispositivo: $deviceName (ID: $deviceId)"
            updateDeviceInfoUI(info)
        }
    }

    private fun loadSafeZone() {
        safeZoneCache.get()?.let {
            safeZone = it
            updateSafeZoneUI(it)
            return
        }

        val lat = sharedPreferences.getString(PREF_SAFEZONE_LAT, null)?.toDoubleOrNull()
        val lon = sharedPreferences.getString(PREF_SAFEZONE_LON, null)?.toDoubleOrNull()

        if (lat != null && lon != null) {
            val geoPoint = GeoPoint(lat, lon)
            safeZone = geoPoint
            safeZoneCache.set(geoPoint)
            updateSafeZoneUI(geoPoint)
        }
    }

    private fun startPeriodicStatusCheck() {
        if (!hasAssociatedDevice()) return

        statusCheckRunnable = object : Runnable {
            override fun run() {
                if (isAdded && hasAssociatedDevice()) {
                    val deviceUniqueId = sharedPreferences.getString(DEVICE_UNIQUE_ID_PREF, null)
                    if (!deviceUniqueId.isNullOrEmpty()) {
                        viewModel.checkDeviceStatus(deviceUniqueId) { isOnline, message ->
                            val statusIcon = if (isOnline) "🟢" else "🔴"
                            val displayMessage = "$statusIcon ${message.substringAfter(" ")}"
                            updateStatusUI(
                                displayMessage,
                                if (isOnline) android.R.color.holo_green_dark else android.R.color.holo_red_dark
                            )
                        }
                    }
                }
                handler.postDelayed(this, STATUS_CHECK_INTERVAL)
            }
        }
        handler.post(statusCheckRunnable!!)
    }

    private fun cancelStatusCheck() {
        statusCheckRunnable?.let { handler.removeCallbacks(it) }
        statusCheckRunnable = null
    }

    private fun updateDeviceInfoUI(info: String) {
        binding.textDeviceInfo.apply {
            text = info
            visibility = View.VISIBLE
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }

    private fun handleUnauthorizedError() {
        refreshJWTToken()
    }

    private fun refreshJWTToken() {
        val userEmail = args.userEmail
        val userPassword = sharedPreferences.getString("user_password", null)

        if (userPassword == null) {
            forceLogout()
            return
        }

        lifecycleScope.launch {
            try {
                val json = JSONObject().apply {
                    put("email", userEmail)
                    put("password", userPassword)
                }

                val requestBody = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$BASE_URL/api/auth/login")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    val responseJson = JSONObject(responseBody ?: "")
                    val newToken = responseJson.getString("token")

                    JWT_TOKEN = newToken
                    sharedPreferences.edit {
                        putString("jwt_token", newToken)
                    }

                    showSnackbar("✅ Token renovado automáticamente", Snackbar.LENGTH_SHORT)
                    setupWebSocket()
                } else {
                    forceLogout()
                }
            } catch (e: Exception) {
                forceLogout()
            }
        }
    }

    private fun logout() {
        try {
            showLogoutConfirmation()
        } catch (e: Exception) {
            forceLogout()
        }
    }

    private fun showLogoutConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle("Cerrar Sesión")
            .setMessage("¿Estás seguro que deseas cerrar la sesión?")
            .setPositiveButton("Cerrar Sesión") { _, _ ->
                performLogout()
            }
            .setNegativeButton("Cancelar") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun performLogout() {
        try {
            clearUserSession()
            stopWebSocket()
            Toast.makeText(requireContext(), "Sesión cerrada", Toast.LENGTH_SHORT).show()
            navigateToFirstFragment()
        } catch (e: Exception) {
            forceLogout()
        }
    }

    private fun clearUserSession() {
        with(sharedPreferences.edit()) {
            remove("jwt_token")
            remove("user_email")
            remove("user_password")
            clear()
            apply()
        }
    }

    private fun stopWebSocket() {
        webSocket?.close(1000, "Logout")
        webSocket = null
    }

    private fun navigateToFirstFragment() {
        try {
            if (isAdded && !isDetached && _binding != null) {
                val navController = findNavController()
                if (navController.currentDestination?.id == R.id.SecondFragment) {
                    navController.popBackStack(R.id.FirstFragment, false)
                }
            } else {
                forceLogout()
            }
        } catch (e: Exception) {
            forceLogout()
        }
    }

    private fun forceLogout() {
        try {
            clearUserSession()
            val intent = requireActivity().intent
            requireActivity().finish()
            startActivity(intent)
        } catch (e: Exception) {
            requireActivity().finishAffinity()
        }
    }

    private fun setupLogoutButton() {
        binding.buttonLogout.setOnClickListener {
            logout()
        }
    }

    private fun isFragmentValid(): Boolean {
        return isAdded && !isDetached && _binding != null && isResumed
    }

    private fun updateStatusUI(message: String, colorResId: Int? = null) {
        if (!isFragmentValid()) return

        try {
            binding.textDeviceStatus.text = message
            binding.textDeviceStatus.visibility = View.VISIBLE

            colorResId?.let {
                binding.textDeviceStatus.setTextColor(
                    ContextCompat.getColor(requireContext(), it)
                )
            }

            if (!message.contains("❌") && !message.contains("🔄")) {
                val timeString = getCurrentTime()
                binding.textDeviceStatus.text = "$message ($timeString)"
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error updating status UI: ${e.message}")
        }
    }

    private fun getCurrentTime(): String {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date())
    }

    private fun showSnackbar(message: String, duration: Int) {
        if (!isFragmentValid()) {
            try {
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error showing toast: ${e.message}")
            }
            return
        }

        try {
            val snackbar = Snackbar.make(
                requireActivity().findViewById(R.id.coordinator_layout),
                message,
                duration
            )

            snackbar.view.elevation = 50f
            snackbar.view.setBackgroundResource(R.drawable.snackbar_background)

            val textView =
                snackbar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
            textView.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
            textView.textSize = 14f
            textView.maxLines = 3

            val params = snackbar.view.layoutParams as CoordinatorLayout.LayoutParams
            params.setMargins(16, 16, 16, 200)
            params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            snackbar.view.layoutParams = params

            snackbar.show()
        } catch (e: Exception) {
            try {
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            } catch (toastError: Exception) {
                Log.e(TAG, "❌ Error showing toast: ${toastError.message}")
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun startEnhancedServices() = withContext(Dispatchers.IO) {
        Log.d(TAG, "🚀 Starting enhanced services...")

        try {
            // ✅ SECUENCIA CORRECTA Y CRÍTICA
            launch {
                Log.d(TAG, "📱 Step 1: Starting tracking service...")
                startTrackingService()
            }

            delay(1000) // Esperar que el servicio se inicie

            launch {
                Log.d(TAG, "🔌 Step 2: Setting up WebSocket...")
                setupWebSocket()
            }

            delay(1500) // Esperar WebSocket

            launch {
                Log.d(TAG, "📍 Step 3: Getting initial position...")
                // ✅ CRÍTICO: Cargar posición inicial INMEDIATAMENTE
                getDeviceUniqueId()?.let { uniqueId ->
                    try {
                        // Intentar posición reciente primero
                        val position = viewModel.getLastPosition(uniqueId)
                        val geoPoint = GeoPoint(position.latitude, position.longitude)

                        withContext(Dispatchers.Main) {
                            updateVehiclePositionEnhanced(
                                TrackingViewModel.VehiclePosition(
                                    deviceId = uniqueId,
                                    latitude = position.latitude,
                                    longitude = position.longitude,
                                    speed = position.speed,
                                    bearing = position.course,
                                    timestamp = System.currentTimeMillis(),
                                    accuracy = 10f,
                                    quality = "initial_load"
                                )
                            )
                            showSnackbar("📍 Vehículo cargado en el mapa", Snackbar.LENGTH_SHORT)
                        }

                        Log.d(TAG, "✅ Initial position loaded and displayed")

                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error loading initial position: ${e.message}")

                        // ✅ FALLBACK: Usar posición antigua si no hay reciente
                        try {
                            val oldPosition = viewModel.getLastPosition(
                                uniqueId,
                                allowOldPositions = true,
                                maxAgeMinutes = 1440
                            )
                            val geoPoint = GeoPoint(oldPosition.latitude, oldPosition.longitude)

                            withContext(Dispatchers.Main) {
                                updateVehiclePositionEnhanced(
                                    TrackingViewModel.VehiclePosition(
                                        deviceId = uniqueId,
                                        latitude = oldPosition.latitude,
                                        longitude = oldPosition.longitude,
                                        speed = 0.0,
                                        bearing = 0.0,
                                        timestamp = System.currentTimeMillis(),
                                        accuracy = 50f,
                                        quality = "old_fallback"
                                    )
                                )
                                showSnackbar(
                                    "📍 Posición anterior cargada (${oldPosition.age}min)",
                                    Snackbar.LENGTH_LONG
                                )
                            }

                            Log.d(TAG, "✅ Fallback position loaded: ${oldPosition.age} minutes old")

                        } catch (fallbackError: Exception) {
                            Log.e(TAG, "❌ Even fallback failed: ${fallbackError.message}")
                            withContext(Dispatchers.Main) {
                                showSnackbar(
                                    "⚠️ No se pudo cargar posición del vehículo",
                                    Snackbar.LENGTH_LONG
                                )
                            }
                        }
                    }
                }
            }

            delay(2000) // Esperar carga inicial

            launch {
                Log.d(TAG, "🎯 Step 4: Starting real-time tracking...")
                viewModel.startRealTimeTracking()
            }

            launch {
                Log.d(TAG, "⏰ Step 5: Starting periodic checks...")
                schedulePeriodicSync()
                startPeriodicStatusCheck()
            }

            Log.d(TAG, "✅ All enhanced services started successfully")

        } catch (error: Exception) {
            Log.e(TAG, "❌ Error starting enhanced services: ${error.message}")
            withContext(Dispatchers.Main) {
                showSnackbar("❌ Error iniciando servicios: ${error.message}", Snackbar.LENGTH_LONG)
            }
        }
    }

    // 2. AGREGAR función para forzar carga inicial:
// ============ MEJORAR CARGA INICIAL SIN POSICIONES HARDCODEADAS ============
    @RequiresApi(Build.VERSION_CODES.O)
    private fun forceLoadInitialPosition() {
        val deviceUniqueId = getDeviceUniqueId()
        if (deviceUniqueId == null) {
            showSnackbar("❌ No hay dispositivo asociado", Snackbar.LENGTH_SHORT)
            return
        }

        Log.d(TAG, "🔄 Force loading initial position for: $deviceUniqueId")

        binding.textConnectionStatus?.text = "🔄 Cargando vehículo..."

        lifecycleScope.launch {
            try {
                // ✅ ESTRATEGIA MEJORADA: SOLO POSICIONES VÁLIDAS

                // Intento 1: Posición muy reciente (preferida)
                try {
                    val recentPosition = viewModel.getLastPosition(
                        deviceUniqueId,
                        allowOldPositions = false,
                        maxAgeMinutes = 15
                    )
                    if (isValidPosition(
                            GeoPoint(
                                recentPosition.latitude,
                                recentPosition.longitude
                            )
                        )
                    ) {
                        displayPositionOnMap(recentPosition, "recent")
                        return@launch
                    } else {
                        Log.d(TAG, "⚠️ Recent position is invalid/hardcoded, skipping")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "ℹ️ No recent valid position found: ${e.message}")
                }

                // Intento 2: Posición extendida válida (1 hora)
                try {
                    val extendedPosition = viewModel.getLastPosition(
                        deviceUniqueId,
                        allowOldPositions = true,
                        maxAgeMinutes = 60
                    )
                    if (isValidPosition(
                            GeoPoint(
                                extendedPosition.latitude,
                                extendedPosition.longitude
                            )
                        )
                    ) {
                        displayPositionOnMap(extendedPosition, "extended")
                        return@launch
                    } else {
                        Log.d(TAG, "⚠️ Extended position is invalid/hardcoded, skipping")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "ℹ️ No extended valid position found: ${e.message}")
                }

                // Intento 3: Buscar posición válida en las últimas 24 horas
                try {
                    val anyPosition = viewModel.getLastPosition(
                        deviceUniqueId,
                        allowOldPositions = true,
                        maxAgeMinutes = 1440
                    )
                    if (isValidPosition(GeoPoint(anyPosition.latitude, anyPosition.longitude))) {
                        displayPositionOnMap(anyPosition, "fallback")
                        return@launch
                    } else {
                        Log.d(TAG, "⚠️ All positions are invalid/hardcoded")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ No valid position found at all: ${e.message}")
                }

                // ✅ Si no hay posiciones válidas, mostrar mensaje y centrar en vista general
                withContext(Dispatchers.Main) {
                    // ✅ NO CENTRAR EN TANDIL, MANTENER VISTA ACTUAL
                    map?.controller?.setZoom(OVERVIEW_ZOOM_LEVEL.toDouble())
                    showSnackbar(
                        "⚠️ No hay posiciones GPS válidas. Configura tu dispositivo GPS.",
                        Snackbar.LENGTH_LONG
                    )
                    binding.textConnectionStatus?.text = "⚠️ Sin datos GPS válidos"
                }

            } catch (error: Exception) {
                Log.e(TAG, "❌ Error in force load: ${error.message}")
                withContext(Dispatchers.Main) {
                    showSnackbar(
                        "❌ Error cargando posición: ${error.message}",
                        Snackbar.LENGTH_LONG
                    )
                    binding.textConnectionStatus?.text = "❌ Error de carga"
                }
            }
        }
    }

    // ============ FUNCIÓN AUXILIAR MEJORADA PARA MOSTRAR POSICIÓN ============
    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun displayPositionOnMap(
        position: TrackingViewModel.LastPositionResponse,
        strategy: String
    ) {
        withContext(Dispatchers.Main) {
            val vehiclePos = TrackingViewModel.VehiclePosition(
                deviceId = position.deviceId,
                latitude = position.latitude,
                longitude = position.longitude,
                speed = position.speed,
                bearing = position.course,
                timestamp = System.currentTimeMillis(),
                accuracy = when (strategy) {
                    "recent" -> 10f
                    "extended" -> 25f
                    else -> 50f
                },
                quality = strategy,
                isInterpolated = false // ✅ DATOS REALES
            )

            updateVehiclePositionEnhanced(vehiclePos)

            val ageInfo = when {
                position.age == null -> ""
                position.age < 60 -> "(${position.age}min)"
                position.age < 1440 -> "(${position.age / 60}h)"
                else -> "(${position.age / 1440}d)"
            }

            val message = when (strategy) {
                "recent" -> "✅ Vehículo encontrado $ageInfo"
                "extended" -> "📍 Posición anterior cargada $ageInfo"
                else -> "⚠️ Posición antigua cargada $ageInfo"
            }

            showSnackbar(message, Snackbar.LENGTH_SHORT)
            binding.textConnectionStatus?.text = "✅ Vehículo en mapa"

            Log.d(TAG, "✅ Position displayed: $strategy, age: ${position.age}min")
        }
    }

    // ============ BROADCAST RECEIVER PARA ZONA SEGURA ============
    private val safeZoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.peregrino.SAFEZONE_DISABLED" -> {
                    clearSafeZoneUI()
                    showSnackbar("🛡️ Zona segura desactivada", Snackbar.LENGTH_LONG)
                }

                "com.peregrino.SAFEZONE_ALERT" -> {
                    val distance = intent.getDoubleExtra("distance", 0.0)
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
    // ✅ NUEVA FUNCIÓN: FORZAR RECONEXIÓN WEBSOCKET
    private fun forceReconnectWebSocket() {
        Log.d(TAG, "🔄 Force reconnecting WebSocket from UI...")
        viewModel.forceReconnectWebSocket()
        showSnackbar("🔄 Reconectando en tiempo real...", Snackbar.LENGTH_SHORT)
    }

    // Llamar este método cuando detectes posiciones incorrectas
    private fun clearOldGPSData() {
        lifecycleScope.launch {
            try {
                val deviceUniqueId = getDeviceUniqueId()
                if (deviceUniqueId != null) {
                    // Llamar al endpoint del servidor para limpiar
                    val json = JSONObject().apply {
                        put("deviceId", deviceUniqueId)
                        put("hoursBack", 4) // Limpiar últimas 4 horas
                    }

                    val requestBody =
                        json.toString().toRequestBody("application/json".toMediaType())
                    val request = Request.Builder()
                        .url("$BASE_URL/api/clear-old-positions")
                        .post(requestBody)
                        .addHeader("Authorization", "Bearer $JWT_TOKEN")
                        .build()

                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        showSnackbar(
                            "🧹 Cache GPS limpiado - Obteniendo datos frescos",
                            Snackbar.LENGTH_LONG
                        )
                        // Forzar actualización
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            viewModel.forceDataRefresh()
                        }
                    } else {
                        showSnackbar("⚠️ Error limpiando cache GPS", Snackbar.LENGTH_SHORT)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing GPS cache: ${e.message}")
            }
        }
    }
    // ✅ FUNCIÓN PARA REGISTRAR SAFE ZONE RECEIVER
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerSafeZoneReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction("com.peregrino.SAFEZONE_DISABLED")
                addAction("com.peregrino.SAFEZONE_ALERT")
            }

            // ✅ FIX: AGREGAR FLAGS CORRECTOS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.registerReceiver(
                    requireContext(),
                    safeZoneReceiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED  // ✅ AGREGAR FLAG
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                requireContext().registerReceiver(safeZoneReceiver, filter)
            }
            Log.d(TAG, "✅ SafeZone receiver registered with proper flags")
        } catch (e: Exception) {
            Log.w(TAG, "Error registering safe zone receiver: ${e.message}")
        }
    }

    // ✅ FUNCIÓN PARA DESREGISTRAR SAFE ZONE RECEIVER
    private fun unregisterSafeZoneReceiver() {
        try {
            requireContext().unregisterReceiver(safeZoneReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Receiver already unregistered: ${e.message}")
        }
    }

    // ✅ FUNCIÓN PARA TOGGLE DEL MODO SEGURIDAD (agregar a setupEnhancedButtons)
    private fun toggleSecurityMode() {
        if (isSecurityModeActive) {
            stopSecurityMode()
        } else {
            showSecurityModeDialog()
        }
    }

    private fun showSecurityModeDialog() {
        // ✅ VERIFICAR REQUISITOS
        if (safeZone == null) {
            showSnackbar("⚠️ Primero establece una zona segura", Snackbar.LENGTH_LONG)
            return
        }

        val deviceUniqueId = getDeviceUniqueId()
        if (deviceUniqueId == null) {
            showSnackbar("⚠️ No hay dispositivo asociado", Snackbar.LENGTH_SHORT)
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("🛡️ Modo Seguridad")
            .setMessage("""
            El Modo Seguridad activará:
            
            ✅ Monitoreo 24/7 en segundo plano
            ✅ Alarma crítica si sale de zona segura
            ✅ Funciona aunque cierres la app
            ✅ Resiste reinicios del sistema
            
            ⚠️ Consumirá algo de batería para máxima seguridad
            
            ¿Activar Modo Seguridad?
        """.trimIndent())
            .setPositiveButton("🛡️ Activar Seguridad") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startSecurityMode(deviceUniqueId)
                }
            }
            .setNegativeButton("❌ Cancelar", null)
            .show()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startSecurityMode(deviceUniqueId: String) {
        val safeZonePosition = safeZone ?: run {
            showSnackbar("❌ Error: No hay zona segura configurada", Snackbar.LENGTH_LONG)
            return
        }

        Log.d(TAG, "🛡️ Starting security mode for device: $deviceUniqueId")

        try {
            val intent = Intent(requireContext(), BackgroundSecurityService::class.java).apply {
                action = BackgroundSecurityService.ACTION_START_SECURITY
                putExtra(BackgroundSecurityService.EXTRA_DEVICE_ID, deviceUniqueId)
                putExtra(BackgroundSecurityService.EXTRA_JWT_TOKEN, JWT_TOKEN)
                putExtra(BackgroundSecurityService.EXTRA_SAFE_ZONE_LAT, safeZonePosition.latitude)
                putExtra(BackgroundSecurityService.EXTRA_SAFE_ZONE_LON, safeZonePosition.longitude)
                putExtra(BackgroundSecurityService.EXTRA_SAFE_ZONE_RADIUS, 15.0) // 15 metros
            }

            requireContext().startForegroundService(intent)

            isSecurityModeActive = true
            updateSecurityButtonUI(true)

            // ✅ GUARDAR ESTADO EN PREFERENCES
            sharedPreferences.edit {
                putBoolean("security_mode_active", true)
                putString("security_device_id", deviceUniqueId)
            }

            showSnackbar("🛡️ Modo Seguridad ACTIVADO - Vigilancia 24/7", Snackbar.LENGTH_LONG)
            Log.d(TAG, "✅ Security mode started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting security mode: ${e.message}")
            showSnackbar("❌ Error activando seguridad: ${e.message}", Snackbar.LENGTH_LONG)
        }
    }

    private fun stopSecurityMode() {
        Log.d(TAG, "🛑 Stopping security mode")

        try {
            val intent = Intent(requireContext(), BackgroundSecurityService::class.java).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    action = BackgroundSecurityService.ACTION_STOP_SECURITY
                }
            }
            requireContext().startService(intent)

            isSecurityModeActive = false
            updateSecurityButtonUI(false)

            // ✅ LIMPIAR ESTADO EN PREFERENCES
            sharedPreferences.edit {
                putBoolean("security_mode_active", false)
                remove("security_device_id")
            }

            showSnackbar("🛑 Modo Seguridad DESACTIVADO", Snackbar.LENGTH_SHORT)
            Log.d(TAG, "✅ Security mode stopped")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error stopping security mode: ${e.message}")
            showSnackbar("❌ Error desactivando seguridad: ${e.message}", Snackbar.LENGTH_SHORT)
        }
    }

    private fun updateSecurityButtonUI(isActive: Boolean) {
        // ✅ ACTUALIZAR BOTÓN DE ZONA SEGURA PARA MOSTRAR ESTADO DE SEGURIDAD
        binding.buttonZonaSegura.apply {
            if (isActive) {
                text = "🛡️ SEGURIDAD ACTIVA"
                setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
            } else {
                text = if (safeZone != null) "Zona Segura Activa ✓" else "Establecer Zona Segura"
                setBackgroundColor(
                    ContextCompat.getColor(
                        requireContext(),
                        if (safeZone != null) android.R.color.holo_green_dark else android.R.color.holo_blue_dark
                    )
                )
            }
        }
    }

    // ✅ FUNCIÓN PARA REGISTRAR RECEIVER DE SEGURIDAD
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerSecurityReceiver() {
        securityReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    "com.peregrino.SECURITY_ALERT" -> {
                        val distance = intent.getDoubleExtra("distance", 0.0)
                        val deviceId = intent.getStringExtra("deviceId")
                        val timestamp = intent.getLongExtra("timestamp", System.currentTimeMillis())

                        Log.w(TAG, "🚨 Security alert received: distance=${distance}m, device=$deviceId")

                        lifecycleScope.launch(Dispatchers.Main) {
                            showSnackbar(
                                "🚨 ALERTA: Vehículo fuera de zona (${distance.toInt()}m)",
                                Snackbar.LENGTH_LONG
                            )

                            val vibrator = requireContext().getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                vibrator?.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
                            }
                        }
                    }

                    "com.peregrino.SAFEZONE_DISABLED" -> {
                        Log.d(TAG, "🛡️ Safe zone disabled from notification")
                        lifecycleScope.launch(Dispatchers.Main) {
                            clearSafeZoneUI()
                            stopSecurityMode()
                            showSnackbar("🛡️ Zona segura desactivada por alerta", Snackbar.LENGTH_LONG)
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction("com.peregrino.SECURITY_ALERT")
            addAction("com.peregrino.SAFEZONE_DISABLED")
        }

        // ✅ FIX: AGREGAR FLAGS CORRECTOS
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.registerReceiver(
                    requireContext(),
                    securityReceiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED  // ✅ AGREGAR FLAG
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                requireContext().registerReceiver(securityReceiver, filter)
            }
            Log.d(TAG, "✅ Security receiver registered with proper flags")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error registering security receiver: ${e.message}")
        }
    }

    private fun unregisterSecurityReceiver() {
        securityReceiver?.let { receiver ->
            try {
                requireContext().unregisterReceiver(receiver)
                Log.d(TAG, "✅ Security receiver unregistered")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Security receiver already unregistered: ${e.message}")
            } finally {
                securityReceiver = null
            }
        }
    }

    // ✅ FUNCIÓN PARA RESTAURAR ESTADO DE SEGURIDAD
    private fun restoreSecurityState() {
        isSecurityModeActive = sharedPreferences.getBoolean("security_mode_active", false)
        if (isSecurityModeActive) {
            updateSecurityButtonUI(true)
            Log.d(TAG, "🛡️ Security mode state restored: ACTIVE")
        }
    }

    // ✅ MODIFICAR LA FUNCIÓN handleSafeZoneButton EXISTENTE
    @RequiresApi(Build.VERSION_CODES.O)
    private fun handleSafeZoneButton() {
        when {
            isSecurityModeActive -> {
                // ✅ SI SEGURIDAD ESTÁ ACTIVA, MOSTRAR OPCIONES DE SEGURIDAD
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("🛡️ Modo Seguridad Activo")
                    .setMessage("El modo seguridad está monitoreando tu vehículo 24/7")
                    .setPositiveButton("🛑 Desactivar Seguridad") { _, _ ->
                        stopSecurityMode()
                    }
                    .setNeutralButton("🔇 Modo Silencioso") { _, _ ->
                        enableSilentSecurityMode()
                    }
                    .setNegativeButton("❌ Cancelar", null)
                    .show()
            }

            safeZone == null || !isSafeZoneActive() -> {
                // ✅ ESTABLECER NUEVA ZONA SEGURA
                enterSafeZoneSetupMode()
            }

            else -> {
                // ✅ ZONA SEGURA EXISTE, MOSTRAR OPCIONES
                showSafeZoneOptionsDialog()
            }
        }
    }

    private fun enableSilentSecurityMode() {
        val intent = Intent(requireContext(), BackgroundSecurityService::class.java).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                action = BackgroundSecurityService.ACTION_SILENT_MODE
            }
        }
        requireContext().startService(intent)
        showSnackbar("🔇 Modo seguridad silencioso activado", Snackbar.LENGTH_SHORT)
    }

    // ============ LIFECYCLE METHODS ============
    override fun onResume() {
        super.onResume()

        Log.d(TAG, "▶️ SecondFragment onResume")

        isFragmentResumed = true
        isFragmentVisible = true

        map?.onResume()
        shouldReconnect.set(true)
        // ✅ REGISTRAR RECEIVERS
        registerSafeZoneReceiver()
        registerWebSocketReceiver() // ✅ NUEVO


        // ✅ REACTIVAR WEBSOCKET
        if (hasAssociatedDevice()) {
            handler.postDelayed({
                Log.d(TAG, "🔌 Reactivating WebSocket on resume...")
                viewModel.resumeWebSocket()

                if (vehicleMarker.get() == null) {
                    Log.d(TAG, "🎯 No vehicle visible on resume, loading...")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        forceLoadInitialPosition()
                    }
                }
            }, 1000)
        }

        // ✅ LIMPIAR OVERLAYS PROBLEMÁTICOS
        handler.postDelayed({
            cleanupTrackingOverlays()
        }, 2000)

        // ✅ REGISTRAR RECEIVER
        registerSafeZoneReceiver()

        // ✅ VERIFICAR SESIÓN
        val token = sharedPreferences.getString("jwt_token", null)
        if (token == null) {
            navigateToFirstFragment()
        }

        // ✅ VERIFICAR ESTADO DE COMPARTIR
        restoreSharingState()
        registerSecurityReceiver()
        restoreSecurityState()
    }

    override fun onPause() {
        super.onPause()

        Log.d(TAG, "⏸️ SecondFragment onPause")

        isFragmentResumed = false
        // ✅ NO MARCAR COMO NO VISIBLE para mantener updates en background

        map?.onPause()

        // ✅ CRÍTICO: NO CERRAR WEBSOCKET, SOLO PAUSAR
        Log.d(TAG, "⏸️ Pausing WebSocket (keeping alive)...")
        viewModel.pauseWebSocket()

        // ✅ PAUSAR ANIMACIONES
        vehicleAnimator?.pause()
        mapRotationAnimator?.pause()

        cancelStatusCheck()

        // ✅ DESREGISTRAR RECEIVER
        unregisterSafeZoneReceiver()
        unregisterWebSocketReceiver() // ✅ NUEVO
        unregisterSecurityReceiver()
    }

    override fun onStop() {
        super.onStop()

        Log.d(TAG, "⏹️ SecondFragment onStop")

        // ✅ MARCAR COMO NO VISIBLE SOLO EN onStop
        isFragmentVisible = false

        // ✅ MANTENER WEBSOCKET VIVO PERO PAUSADO
        viewModel.keepWebSocketAlive()
    }

    override fun onStart() {
        super.onStart()

        Log.d(TAG, "▶️ SecondFragment onStart")

        // ✅ MARCAR COMO VISIBLE
        isFragmentVisible = true

        // ✅ REACTIVAR WEBSOCKET SI ES NECESARIO
        if (hasAssociatedDevice()) {
            Log.d(TAG, "🔌 Reactivating WebSocket on start...")
            viewModel.resumeWebSocket()
        }
    }


    override fun onDestroyView() {
        super.onDestroyView()

        Log.d(TAG, "🗑️ SecondFragment onDestroyView")

        // ✅ MARCAR COMO NO VISIBLE
        isFragmentVisible = false
        isFragmentResumed = false

        // ✅ DETENER SOLO SI LA ACTIVIDAD SE ESTÁ CERRANDO
        if (requireActivity().isFinishing) {
            Log.d(TAG, "🛑 Activity finishing, stopping tracking completely")
            viewModel.stopRealTimeTracking()
            requireContext().stopService(Intent(requireContext(), TrackingService::class.java))
        } else {
            Log.d(TAG, "🔄 Fragment destroyed but activity continuing, keeping WebSocket alive")
            viewModel.keepWebSocketAlive()
        }

        // ✅ LIMPIAR HANDLERS
        cancelStatusCheck()
        handler.removeCallbacksAndMessages(null)

        // ✅ LIMPIAR MAPA
        myLocationOverlay?.disableMyLocation()
        myLocationOverlay?.disableFollowLocation()
        map?.overlays?.clear()
        map?.onDetach()

        // ✅ CANCELAR ANIMACIONES
        vehicleAnimator?.cancel()
        mapRotationAnimator?.cancel()

        // ✅ LIMPIAR REFERENCIAS
        vehicleMarker.set(null)
        safeZonePolygon.set(null)
        myLocationOverlay = null
        map = null

        // ✅ LIMPIAR CACHES
        deviceInfoCache.clear()
        safeZoneCache.clear()
        lastPositionCache.clear()

        // ✅ GUARDAR ESTADO DE COMPARTIR SI ES NECESARIO
        if (isCurrentlySharingLocation && currentShareId != null) {
            sharedPreferences.edit {
                putString("current_share_id", currentShareId)
                putBoolean("is_sharing_location", true)
                putLong("sharing_start_time", sharingStartTime)
            }
            Log.d(TAG, "💾 Sharing state saved on destroy")
        }

        _binding = null
        Log.d(TAG, "🧹 SecondFragment cleanup completed")
    }
}