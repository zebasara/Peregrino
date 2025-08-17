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
import kotlinx.coroutines.flow.collect

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
        const val GEOFENCE_RADIUS = 5.0
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
        sharedPreferences = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        viewModel.setContext(requireContext())
        return binding.root
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Inicializar servicios
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        alertManager = AlertManager(requireContext())

        val userEmail = args.userEmail
        JWT_TOKEN = args.jwtToken
        Log.d(TAG, "🚀 onViewCreated: userEmail=$userEmail, hasToken=${!JWT_TOKEN.isNullOrEmpty()}")

        binding.textUser.text = "Bienvenido: $userEmail"

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
                    Log.d(TAG, "✅ Device found, starting enhanced services...")
                    viewModel.forceDataRefresh()
                    startEnhancedServices()
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

            showSnackbar("⏸️ Seguimiento pausado - Toca 'Siguiendo' para reactivar", Snackbar.LENGTH_SHORT)
        }
    }

    // ============ OBSERVADORES MEJORADOS ============
    @RequiresApi(Build.VERSION_CODES.O)
    private fun observeViewModel() {
        // Observar posición del vehículo con manejo mejorado
        lifecycleScope.launch {
            viewModel.vehiclePosition.collectLatest { position ->
                if (position != null) {
                    updateVehiclePositionEnhanced(position)
                } else {
                    // ✅ Si no hay posición después de 10 segundos, forzar carga
                    handler.postDelayed({
                        if (vehicleMarker.get() == null) {
                            Log.w(TAG, "⚠️ No vehicle position after timeout, forcing load...")
                            forceLoadInitialPosition()
                        }
                    }, 10000)
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
        if (!isMapReady.get()) return

        val currentTime = System.currentTimeMillis()

        // ✅ THROTTLE MÁS INTELIGENTE
        val shouldUpdate = when {
            position.isInterpolated -> currentTime - lastMapUpdate > 33L // 30 FPS para interpolación
            else -> currentTime - lastMapUpdate > 16L // 60 FPS para datos reales
        }

        if (!shouldUpdate) return

        lastMapUpdate = currentTime
        val newPoint = GeoPoint(position.latitude, position.longitude)

        // ✅ ACTUALIZAR MARCADOR SOLO SI HAY CAMBIO SIGNIFICATIVO
        val lastMarkerPos = vehicleMarker.get()?.position
        val needsMarkerUpdate = lastMarkerPos == null ||
                lastMarkerPos.distanceToAsDouble(newPoint) > 1.0 || // 1 metro
                !position.isInterpolated // Siempre actualizar datos reales

        if (needsMarkerUpdate) {
            val newState = determineVehicleState(newPoint)
            updateVehicleMarkerSmooth(newPoint, position.bearing, position.speed, newState)

            // ✅ NO LLAMAR updateVehicleTrack - SIN LÍNEAS AZULES
            // updateVehicleTrack(newPoint) // ← ELIMINADO
        }

        // ✅ SEGUIMIENTO AUTOMÁTICO SOLO CON ROTACIÓN
        if (isFollowingVehicle.get() && needsMarkerUpdate) {
            smoothMoveMapToPosition(newPoint, position.bearing)
        }

        // ✅ VERIFICAR ZONA SEGURA SOLO CON DATOS REALES
        if (!position.isInterpolated) {
            checkSafeZone(newPoint, position.deviceId.hashCode())
        }

        updatePositionInfo(position)
    }


    // ============ ACTUALIZACIÓN SUAVE DEL MARCADOR ============
    private fun updateVehicleMarkerSmooth(position: GeoPoint, bearing: Double, speed: Double, state: VehicleState) {
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


    private fun createEnhancedVehicleMarker(position: GeoPoint, bearing: Double, speed: Double, state: VehicleState): Marker {
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

    private fun animateVehicleMarker(marker: Marker, newPosition: GeoPoint, bearing: Double, speed: Double, state: VehicleState) {
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
    private fun createDynamicVehicleIcon(bearing: Double, speed: Double, state: VehicleState): android.graphics.drawable.Drawable {
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
            // Botón mejorado de seguimiento
            // ✅ BOTÓN PRINCIPAL DE SEGUIMIENTO
            buttonMyLocation.setOnClickListener {
                toggleVehicleFollowing() // Siempre alternar seguimiento del vehículo
            }
            // ✅ LONG PRESS para ir a MI ubicación GPS
            buttonMyLocation.setOnLongClickListener {
                centerOnMyLocation()
                true
            }



            // Botones de zona segura
            buttonZonaSegura.setOnClickListener { handleSafeZoneButton() }
            buttonZonaSeguraMain.setOnClickListener { handleSafeZoneButton() }

            // Botón de asociar dispositivo
            buttonAssociateDevice.setOnClickListener { showAssociateDeviceDialog() }

            // Botón de estado mejorado
            buttonDeviceStatus.setOnClickListener { checkDeviceStatus() }
            buttonDeviceStatus.setOnLongClickListener {
                clearOldPositionsDialog()
                true
            }
            // ✅ NUEVO: Doble tap para recargar todo
            buttonDeviceStatus.setOnLongClickListener {
                Log.d(TAG, "🔄 Force refresh triggered by user")
                lifecycleScope.launch {
                    try {
                        viewModel.forceDataRefresh()
                        delay(1000)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            forceLoadInitialPosition()
                        }
                        showSnackbar("🔄 Datos actualizados", Snackbar.LENGTH_SHORT)
                    } catch (e: Exception) {
                        showSnackbar("❌ Error actualizando: ${e.message}", Snackbar.LENGTH_LONG)
                    }
                }
                true
            }

            // Configuración GPS
            buttonShowConfig.setOnClickListener { showTraccarClientConfig() }
            buttonShowConfig.setOnLongClickListener {
                showPositionTroubleshootingDialog()
                true
            }

            // Botón de test WebSocket
            buttonTestWS.setOnClickListener { toggleAutoFollow() }

            // Ocultar elementos no necesarios
            buttonDescargarOffline.visibility = View.GONE
            progressBarDownload.visibility = View.GONE

            updateFollowButtonText()
        }
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

        binding.buttonTestWS.backgroundTintList = ContextCompat.getColorStateList(requireContext(), color)
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
        if (!hasLocationPermission()) {
            showSnackbar("⚠️ Se necesitan permisos de ubicación", Snackbar.LENGTH_SHORT)
            return
        }

        showSnackbar("📍 Obteniendo tu ubicación...", Snackbar.LENGTH_SHORT)

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location != null) {
                    val myPosition = GeoPoint(location.latitude, location.longitude)

                    // ✅ DETENER SEGUIMIENTO DEL VEHÍCULO TEMPORALMENTE
                    isFollowingVehicle.set(false)
                    updateFollowButtonText()

                    // ✅ IR A MI UBICACIÓN
                    map?.controller?.animateTo(myPosition, 18.0, 1200L)
                    showSnackbar("📍 Tu ubicación GPS", Snackbar.LENGTH_SHORT)

                } else {
                    showSnackbar("⚠️ No se pudo obtener tu ubicación", Snackbar.LENGTH_SHORT)
                }
            }
            .addOnFailureListener {
                showSnackbar("❌ Error obteniendo ubicación GPS", Snackbar.LENGTH_SHORT)
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
                enableMyLocation() // Solo mostrar punto azul de mi ubicación
                disableFollowLocation() // ✅ NUNCA seguir automáticamente
                setDrawAccuracyEnabled(false) // ✅ No dibujar círculo de precisión
            }

            // ✅ AGREGAR AL OVERLAY PARA MOSTRAR PUNTO AZUL
            map?.overlays?.add(myLocationOverlay)
        }

        map?.invalidate()
        Log.d(TAG, "✅ My location enabled - solo punto azul")
    }


    private fun cleanupTrackingOverlays() {
        try {

            // ✅ REMOVER TODOS LOS OVERLAYS EXCEPTO MARCADORES Y RASTRO DEL VEHÍCULO
            map?.overlays?.removeAll { overlay ->
                overlay !is Marker  && overlay != safeZonePolygon.get()
            }
            map?.invalidate()

            Log.d(TAG, "✅ Cleaned up tracking overlays")

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

            showSnackbar(displayMessage, if (isOnline) Snackbar.LENGTH_SHORT else Snackbar.LENGTH_LONG)
            updateStatusUI(displayMessage, if (isOnline) android.R.color.holo_green_dark else android.R.color.holo_red_dark)

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
                        Log.d(TAG, "📍 Position update for different device: $deviceId (ours: $ourDeviceId)")
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
                        quality = "realtime"
                    )

                    Log.d(TAG, "🎯 Real-time position: lat=${position.latitude}, lon=${position.longitude}, speed=${position.speed}")

                    lifecycleScope.launch(Dispatchers.Main) {
                        updateVehiclePositionEnhanced(position)

                        // ✅ MOSTRAR NOTIFICACIÓN VISUAL DE ACTUALIZACIÓN
                        showSnackbar("📍 Posición actualizada en tiempo real", Snackbar.LENGTH_SHORT)
                    }
                }

                "CONNECTION_CONFIRMED" -> {
                    Log.d(TAG, "✅ WebSocket subscription confirmed")
                    activity?.runOnUiThread {
                        updateConnectionStatus("✅ Suscrito", true)
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

        val polygon = Polygon().apply {
            points = Polygon.pointsAsCircle(position, GEOFENCE_RADIUS)
            fillColor = 0x33007FFF
            strokeColor = Color.BLUE
            strokeWidth = 4f
        }

        safeZonePolygon.set(polygon)
        map?.overlays?.add(polygon)
        updateSafeZoneButton(true)
        map?.invalidate()
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

            if (distance > GEOFENCE_RADIUS) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    triggerAlarm(deviceId, distance)
                }
            } else {
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
    private fun handleSafeZoneButton() {
        if (safeZone == null || !isSafeZoneActive()) {
            enterSafeZoneSetupMode()
        } else {
            showSafeZoneOptionsDialog()
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

    private fun createSafeZoneSuccessfully(geoPoint: GeoPoint, deviceUniqueId: String, ageMinutes: Int?) {
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
            ageMinutes < 1440 -> " (${ageMinutes/60}h)"
            else -> " (${ageMinutes/1440}d)"
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
                val deviceUniqueId = dialogView.findViewById<EditText>(R.id.editDeviceId).text.toString().trim()
                val deviceName = dialogView.findViewById<EditText>(R.id.editDeviceName).text.toString().trim()

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
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
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

            val textView = snackbar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
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
                            updateVehiclePositionEnhanced(TrackingViewModel.VehiclePosition(
                                deviceId = uniqueId,
                                latitude = position.latitude,
                                longitude = position.longitude,
                                speed = position.speed,
                                bearing = position.course,
                                timestamp = System.currentTimeMillis(),
                                accuracy = 10f,
                                quality = "initial_load"
                            ))
                            showSnackbar("📍 Vehículo cargado en el mapa", Snackbar.LENGTH_SHORT)
                        }

                        Log.d(TAG, "✅ Initial position loaded and displayed")

                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error loading initial position: ${e.message}")

                        // ✅ FALLBACK: Usar posición antigua si no hay reciente
                        try {
                            val oldPosition = viewModel.getLastPosition(uniqueId, allowOldPositions = true, maxAgeMinutes = 1440)
                            val geoPoint = GeoPoint(oldPosition.latitude, oldPosition.longitude)

                            withContext(Dispatchers.Main) {
                                updateVehiclePositionEnhanced(TrackingViewModel.VehiclePosition(
                                    deviceId = uniqueId,
                                    latitude = oldPosition.latitude,
                                    longitude = oldPosition.longitude,
                                    speed = 0.0,
                                    bearing = 0.0,
                                    timestamp = System.currentTimeMillis(),
                                    accuracy = 50f,
                                    quality = "old_fallback"
                                ))
                                showSnackbar("📍 Posición anterior cargada (${oldPosition.age}min)", Snackbar.LENGTH_LONG)
                            }

                            Log.d(TAG, "✅ Fallback position loaded: ${oldPosition.age} minutes old")

                        } catch (fallbackError: Exception) {
                            Log.e(TAG, "❌ Even fallback failed: ${fallbackError.message}")
                            withContext(Dispatchers.Main) {
                                showSnackbar("⚠️ No se pudo cargar posición del vehículo", Snackbar.LENGTH_LONG)
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
                // ✅ ESTRATEGIA MÚLTIPLE para encontrar posición

                // Intento 1: Posición reciente (preferida)
                try {
                    val recentPosition = viewModel.getLastPosition(deviceUniqueId, allowOldPositions = false, maxAgeMinutes = 30)
                    displayPositionOnMap(recentPosition, "recent")
                    return@launch
                } catch (e: Exception) {
                    Log.d(TAG, "ℹ️ No recent position found: ${e.message}")
                }

                // Intento 2: Posición extendida (1 hora)
                try {
                    val extendedPosition = viewModel.getLastPosition(deviceUniqueId, allowOldPositions = true, maxAgeMinutes = 60)
                    displayPositionOnMap(extendedPosition, "extended")
                    return@launch
                } catch (e: Exception) {
                    Log.d(TAG, "ℹ️ No extended position found: ${e.message}")
                }

                // Intento 3: Cualquier posición disponible (24 horas)
                try {
                    val anyPosition = viewModel.getLastPosition(deviceUniqueId, allowOldPositions = true, maxAgeMinutes = 1440)
                    displayPositionOnMap(anyPosition, "fallback")
                    return@launch
                } catch (e: Exception) {
                    Log.e(TAG, "❌ No position found at all: ${e.message}")
                }

                // Si todo falla, mostrar Tandil como referencia
                withContext(Dispatchers.Main) {
                    val tandilPosition = GeoPoint(TANDIL_LAT, TANDIL_LON)
                    map?.controller?.animateTo(tandilPosition, OVERVIEW_ZOOM_LEVEL, 1000L)
                    showSnackbar("❌ No se encontró posición del vehículo. Envía datos GPS primero.", Snackbar.LENGTH_LONG)
                    binding.textConnectionStatus?.text = "❌ Sin posición GPS"
                }

            } catch (error: Exception) {
                Log.e(TAG, "❌ Error in force load: ${error.message}")
                withContext(Dispatchers.Main) {
                    showSnackbar("❌ Error cargando posición: ${error.message}", Snackbar.LENGTH_LONG)
                    binding.textConnectionStatus?.text = "❌ Error de carga"
                }
            }
        }
    }

// 3. FUNCIÓN AUXILIAR para mostrar posición:

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun displayPositionOnMap(position: TrackingViewModel.LastPositionResponse, strategy: String) {
        withContext(Dispatchers.Main) {
            val vehiclePos = TrackingViewModel.VehiclePosition(
                deviceId = position.deviceId,
                latitude = position.latitude,
                longitude = position.longitude,
                speed = position.speed,
                bearing = position.course,
                timestamp = System.currentTimeMillis(),
                accuracy = when(strategy) {
                    "recent" -> 10f
                    "extended" -> 25f
                    else -> 50f
                },
                quality = strategy
            )

            updateVehiclePositionEnhanced(vehiclePos)

            val ageInfo = when {
                position.age == null -> ""
                position.age < 60 -> "(${position.age}min)"
                position.age < 1440 -> "(${position.age/60}h)"
                else -> "(${position.age/1440}d)"
            }

            val message = when(strategy) {
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

    // ============ LIFECYCLE METHODS ============
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onResume() {
        super.onResume()

        map?.onResume()
        shouldReconnect.set(true)
        // ✅ LIMPIAR OVERLAYS PROBLEMÁTICOS AL RESUMIR
        handler.postDelayed({
            cleanupTrackingOverlays()

            // Cargar vehículo si no está visible
            if (vehicleMarker.get() == null && hasAssociatedDevice()) {
                Log.d(TAG, "🎯 No vehicle visible on resume, loading...")
                forceLoadInitialPosition()
            }
        }, 2000)
        // ✅ RECONECTAR WEBSOCKET INMEDIATAMENTE
        if (hasAssociatedDevice() && JWT_TOKEN != null) {
            handler.postDelayed({
                Log.d(TAG, "🔄 Reconnecting WebSocket on resume...")
                setupWebSocket()
            }, 1000) // 1 segundo de delay
        }

        // Registrar receiver
        val filter = IntentFilter().apply {
            addAction("com.peregrino.SAFEZONE_DISABLED")
            addAction("com.peregrino.SAFEZONE_ALERT")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(
                requireContext(),
                safeZoneReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } else {
            requireContext().registerReceiver(safeZoneReceiver, filter)
        }

        // Verificar sesión
        val token = sharedPreferences.getString("jwt_token", null)
        if (token == null) {
            navigateToFirstFragment()
        }

        // Reanudar tracking en tiempo real
        if (hasAssociatedDevice() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            viewModel.startRealTimeTracking()
        }
    }

    override fun onPause() {
        super.onPause()

        map?.onPause()
        shouldReconnect.set(false)
        cancelStatusCheck()

        // Pausar animaciones
        vehicleAnimator?.pause()
        mapRotationAnimator?.pause()
        // ✅ LIMPIAR WEBSOCKET Y PINGS
        webSocketPingHandler?.removeCallbacksAndMessages(null)
        webSocketPingRunnable = null

        // Cerrar WebSocket
        webSocket?.close(1000, "Fragment pausado")
        webSocket = null

        // Desregistrar receiver
        try {
            requireContext().unregisterReceiver(safeZoneReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Receiver ya desregistrado: ${e.message}")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()

        // Detener tracking en tiempo real
        viewModel.stopRealTimeTracking()

        // Limpiar estado
        shouldReconnect.set(false)
        isFollowingVehicle.set(false)
        safeZoneCenter = null
        currentVehicleState = VehicleState.NORMAL

        // Cancelar handlers
        cancelStatusCheck()
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

        // Cancelar animaciones
        vehicleAnimator?.cancel()
        mapRotationAnimator?.cancel()

        // Limpiar referencias
        vehicleMarker.set(null)
        safeZonePolygon.set(null)
        myLocationOverlay = null
        map = null

        // Limpiar caches
        deviceInfoCache.clear()
        safeZoneCache.clear()
        lastPositionCache.clear()

        // Solo detener alarma si la app se está cerrando
        if (requireActivity().isFinishing) {
            stopAlarmIfActive()
        }

        _binding = null
        Log.d(TAG, "🧹 SecondFragment cleanup completed")
    }
}