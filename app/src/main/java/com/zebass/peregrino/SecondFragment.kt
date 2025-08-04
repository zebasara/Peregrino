package com.zebass.peregrino

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
            showSnackbar("Location permission denied", Snackbar.LENGTH_LONG)
            Log.e(TAG, "Location permission denied")
        }
    }

    // ============ LIFECYCLE OPTIMIZADO ============

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        Configuration.getInstance().load(
            requireContext(),
            requireContext().getSharedPreferences("osmdroid", 0)
        )
        _binding = FragmentSecondBinding.inflate(inflater, container, false)
        sharedPreferences = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        viewModel.setContext(requireContext())
        return binding.root
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val userEmail = args.userEmail
        JWT_TOKEN = args.jwtToken
        Log.d(TAG, "onViewCreated: userEmail=$userEmail")

        // UI Setup inmediato
        binding.textUser.text = "Welcome: $userEmail"

        // Inicialización paralela
        lifecycleScope.launch {
            supervisorScope {
                launch { setupUI() }
                launch { setupMap() }
                launch { observeViewModel() }
                launch {
                    if (hasAssociatedDevice()) {
                        loadDeviceInfo()
                        delay(100) // Pequeño delay para evitar congestión inicial
                        startServices()
                    }
                }
            }
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

    @RequiresApi(Build.VERSION_CODES.O)
    private fun setupButtons() {
        with(binding) {
            buttonMyLocation.setOnClickListener { enableMyLocation() }
            buttonZonaSegura.setOnClickListener { handleSafeZoneButton() }
            buttonAssociateDevice.setOnClickListener { showAssociateDeviceDialog() }
            buttonDeviceStatus.setOnClickListener { checkDeviceStatus() }
            buttonShowConfig.setOnClickListener { showTraccarClientConfig() }

            // Ocultar elementos no necesarios
            buttonDescargarOffline.visibility = View.GONE
            progressBarDownload.visibility = View.GONE
        }
        Log.d(TAG, "Buttons initialized")
    }

    private suspend fun setupMap() = withContext(Dispatchers.Main) {
        map = binding.mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(12.0)
            controller.setCenter(GeoPoint(-37.32167, -59.13316))

            // Optimizaciones de rendimiento
            isTilesScaledToDpi = true
            setUseDataConnection(true)
        }

        isMapReady.set(true)
        Log.d(TAG, "Map initialized")

        // Cargar zona segura después de inicializar mapa
        loadSafeZone()
        if (hasAssociatedDevice()) {
            viewModel.fetchSafeZoneFromServer()
        }

        if (hasLocationPermission()) {
            enableMyLocation()
        }
    }

    private fun observeViewModel() {
        // Usar collectLatest para evitar procesamiento de valores antiguos
        lifecycleScope.launch {
            viewModel.vehiclePosition.collectLatest { position ->
                position?.let {
                    if (shouldUpdatePosition()) {
                        updateVehiclePosition(it.first, GeoPoint(it.second.latitude, it.second.longitude))
                        lastPositionUpdate.set(System.currentTimeMillis())
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.safeZone.collectLatest { zone ->
                zone?.let {
                    val geoPoint = GeoPoint(it.latitude, it.longitude)
                    safeZone = geoPoint
                    safeZoneCache.set(geoPoint)
                    updateSafeZoneUI(geoPoint)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.error.collectLatest { error ->
                error?.let { showSnackbar(it, Snackbar.LENGTH_LONG) }
            }
        }

        lifecycleScope.launch {
            viewModel.deviceInfo.collectLatest { info ->
                info?.let {
                    deviceInfoCache.set(it)
                    updateDeviceInfoUI(it)
                }
            }
        }
    }

    // ============ SERVICIOS OPTIMIZADOS ============

    private suspend fun startServices() = withContext(Dispatchers.IO) {
        launch { startTrackingService() }
        launch { setupWebSocket() }
        launch { schedulePeriodicSync() }
        launch { fetchInitialPosition() }
        launch { startPeriodicStatusCheck() }
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
        Log.d(TAG, "TrackingService started")
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
        Log.d(TAG, "Periodic sync scheduled")
    }

    // ============ WEBSOCKET ULTRA-OPTIMIZADO ============

    private fun setupWebSocket() {
        val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)
        if (deviceId == -1) {
            Log.e(TAG, "No device for WebSocket")
            return
        }

        cancelReconnect()

        val wsUrl = "wss://carefully-arriving-shepherd.ngrok-free.app/ws?token=$JWT_TOKEN"
        val request = Request.Builder()
            .url(wsUrl)
            .header("Origin", "https://carefully-arriving-shepherd.ngrok-free.app")
            .build()

        webSocket = client.newWebSocket(request, createWebSocketListener(deviceId))
    }

    private fun createWebSocketListener(deviceId: Int) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket connected")
            val message = JSONObject().apply {
                put("type", "SUBSCRIBE_DEVICE")
                put("deviceId", deviceId)
                put("token", JWT_TOKEN)
            }
            webSocket.send(message.toString())

            lifecycleScope.launch(Dispatchers.Main) {
                showSnackbar("Connected - Receiving locations", Snackbar.LENGTH_SHORT)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val json = JSONObject(text)
                when (json.getString("type")) {
                    "POSITION_UPDATE" -> handlePositionUpdate(json)
                    "CONNECTION_CONFIRMED" -> Log.d(TAG, "Connection confirmed")
                    "SUBSCRIPTION_CONFIRMED" -> Log.d(TAG, "Subscription confirmed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "WebSocket message error: ${e.message}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure: ${t.message}")
            if (shouldReconnect.get() && isAdded) {
                scheduleReconnect()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: $code - $reason")
            if (shouldReconnect.get() && isAdded && code != 1000) {
                scheduleReconnect()
            }
        }
    }

    private fun handlePositionUpdate(json: JSONObject) {
        val data = json.getJSONObject("data")
        val deviceId = data.getInt("deviceId")
        val lat = data.getDouble("latitude")
        val lon = data.getDouble("longitude")

        lifecycleScope.launch(Dispatchers.Main) {
            if (shouldUpdatePosition()) {
                viewModel.updateVehiclePosition(deviceId, GeoPoint(lat, lon))
            }
        }
    }

    private fun scheduleReconnect() {
        cancelReconnect()
        reconnectRunnable = Runnable {
            if (shouldReconnect.get() && isAdded) {
                Log.d(TAG, "Reconnecting WebSocket...")
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

        val storedDeviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)
        if (deviceId != storedDeviceId) {
            Log.d(TAG, "Ignoring position for device $deviceId (expected $storedDeviceId)")
            return
        }

        lastPositionCache.set(position)

        // Crear o actualizar marcador
        var marker = vehicleMarker.get()
        if (marker == null) {
            marker = createVehicleMarker(deviceId, position)
            vehicleMarker.set(marker)
            map?.overlays?.add(marker)
        } else {
            updateMarkerPosition(marker, deviceId, position)
        }

        // Verificar zona segura
        checkSafeZone(position, deviceId)

        // Animar mapa suavemente
        map?.controller?.animateTo(position, map?.zoomLevelDouble ?: 16.0, 500L)
        map?.postInvalidate() // Más eficiente que invalidate()
    }

    private fun createVehicleMarker(deviceId: Int, position: GeoPoint): Marker {
        return Marker(map).apply {
            this.position = position
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_vehicle)
            title = formatMarkerTitle(deviceId, position)
            isDraggable = false
            setInfoWindow(null) // Desactivar infowindow para mejor rendimiento
        }
    }

    private fun updateMarkerPosition(marker: Marker, deviceId: Int, position: GeoPoint) {
        marker.position = position
        marker.title = formatMarkerTitle(deviceId, position)
    }

    private fun formatMarkerTitle(deviceId: Int, position: GeoPoint): String {
        return "Vehicle ID: $deviceId\nLat: ${"%.6f".format(position.latitude)}\nLon: ${"%.6f".format(position.longitude)}"
    }

    private fun checkSafeZone(position: GeoPoint, deviceId: Int) {
        safeZone?.let { zone ->
            val distance = zone.distanceToAsDouble(position)
            val marker = vehicleMarker.get()

            if (distance > GEOFENCE_RADIUS) {
                marker?.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_vehicle_alert)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    triggerAlarm(deviceId, distance)
                }
            } else {
                marker?.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_vehicle)
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
            "ALERT! Vehicle $deviceId is ${"%.1f".format(distance)} meters away",
            Snackbar.LENGTH_LONG
        )
    }

    private fun updateSafeZoneUI(position: GeoPoint) {
        if (!isMapReady.get()) return

        // Remover polígono anterior
        safeZonePolygon.get()?.let { map?.overlays?.remove(it) }

        // Crear nuevo polígono
        val polygon = Polygon().apply {
            points = Polygon.pointsAsCircle(position, GEOFENCE_RADIUS)
            fillColor = 0x22FF0000
            strokeColor = android.graphics.Color.RED
            strokeWidth = 2f
        }

        safeZonePolygon.set(polygon)
        map?.overlays?.add(polygon)

        // Actualizar botón
        updateSafeZoneButton(true)

        // Animar a la zona
        map?.controller?.animateTo(position, 16.0, 300L)
        map?.postInvalidate()
    }

    private fun updateSafeZoneButton(active: Boolean) {
        binding.buttonZonaSegura.apply {
            text = if (active) "Safe Zone Active ✓" else "Set Safe Zone"
            setBackgroundColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (active) android.R.color.holo_green_dark
                    else android.R.color.holo_blue_dark
                )
            )
        }
    }

    private fun updateDeviceInfoUI(info: String) {
        binding.textDeviceInfo.apply {
            text = info
            visibility = View.VISIBLE
        }
    }

    // ============ FUNCIONES DE DISPOSITIVO OPTIMIZADAS ============

    private fun loadDeviceInfo() {
        // Primero intentar del cache
        deviceInfoCache.get()?.let {
            updateDeviceInfoUI(it)
            return
        }

        // Si no, cargar de preferencias
        if (hasAssociatedDevice()) {
            val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)
            val deviceName = sharedPreferences.getString(DEVICE_NAME_PREF, null)
            val info = "Device: $deviceName (ID: $deviceId)"
            deviceInfoCache.set(info)
            updateDeviceInfoUI(info)

            // Iniciar servicios
            lifecycleScope.launch {
                setupWebSocket()
                fetchInitialPosition()
            }
        } else {
            updateDeviceInfoUI("No devices associated")
        }
    }

    private fun fetchInitialPosition() {
        if (!hasAssociatedDevice()) return

        val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)

        // Check cache primero
        lastPositionCache.get()?.let { cached ->
            updateVehiclePosition(deviceId, cached)
            return
        }

        lifecycleScope.launch {
            try {
                val position = viewModel.getLastPosition(deviceId)
                val geoPoint = GeoPoint(position.latitude, position.longitude)
                updateVehiclePosition(deviceId, geoPoint)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch initial position", e)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun checkDeviceStatus() {
        val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)
        if (deviceId == -1) {
            showSnackbar("No device associated", Snackbar.LENGTH_SHORT)
            return
        }

        // Throttling para evitar spam
        val now = System.currentTimeMillis()
        if (now - lastStatusCheck.get() < 5000L) {
            showSnackbar("Please wait before checking again", Snackbar.LENGTH_SHORT)
            return
        }
        lastStatusCheck.set(now)

        // UI feedback inmediato
        updateStatusUI("🔄 Checking device status...", android.R.color.darker_gray)

        viewModel.checkDeviceStatus(deviceId) { isOnline, message ->
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
                    val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)
                    if (deviceId != -1) {
                        viewModel.checkDeviceStatus(deviceId) { isOnline, message ->
                            val statusIcon = if (isOnline) "🟢" else "🔴"
                            updateStatusUI(
                                "$statusIcon ${message.substringAfter(" ")}",
                                if (isOnline) android.R.color.holo_green_dark
                                else android.R.color.holo_red_dark
                            )
                        }
                    }
                }
                handler.postDelayed(this, STATUS_CHECK_INTERVAL)
            }
        }

        handler.postDelayed(statusCheckRunnable!!, 10000) // Start after 10s
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
            showSnackbar("Please associate a vehicle first", Snackbar.LENGTH_SHORT)
            return
        }

        val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)
        if (deviceId != -1) {
            establishSafeZoneForDevice(deviceId)
        } else {
            viewModel.showDeviceSelectionForSafeZone { selectedDeviceId ->
                establishSafeZoneForDevice(selectedDeviceId)
            }
        }
    }

    private fun establishSafeZoneForDevice(deviceId: Int) {
        binding.buttonZonaSegura.apply {
            text = "Fetching vehicle location..."
            isEnabled = false
        }

        lifecycleScope.launch {
            try {
                val position = viewModel.getLastPosition(deviceId)
                val geoPoint = GeoPoint(position.latitude, position.longitude)

                // Guardar zona segura
                safeZone = geoPoint
                safeZoneCache.set(geoPoint)
                updateSafeZoneUI(geoPoint)

                // Guardar en preferencias
                sharedPreferences.edit {
                    putString(PREF_SAFEZONE_LAT, position.latitude.toString())
                    putString(PREF_SAFEZONE_LON, position.longitude.toString())
                }

                // Enviar al servidor
                viewModel.sendSafeZoneToServer(position.latitude, position.longitude, deviceId)

                binding.buttonZonaSegura.isEnabled = true
            } catch (e: Exception) {
                showSnackbar("Failed to set safe zone: ${e.message}", Snackbar.LENGTH_LONG)
                binding.buttonZonaSegura.apply {
                    text = "Set Safe Zone"
                    isEnabled = true
                }
            }
        }
    }

    private fun toggleSafeZone() {
        // Limpiar UI inmediatamente
        safeZone = null
        safeZoneCache.clear()
        safeZonePolygon.get()?.let { map?.overlays?.remove(it) }
        safeZonePolygon.set(null)

        // Limpiar preferencias
        sharedPreferences.edit {
            remove(PREF_SAFEZONE_LAT)
            remove(PREF_SAFEZONE_LON)
        }

        updateSafeZoneButton(false)

        // Eliminar del servidor
        binding.buttonZonaSegura.apply {
            text = "Deleting..."
            isEnabled = false
        }

        viewModel.deleteSafeZoneFromServer()

        lifecycleScope.launch {
            delay(1500)
            if (isAdded) {
                binding.buttonZonaSegura.apply {
                    text = "Set Safe Zone"
                    isEnabled = true
                }
            }
        }

        map?.postInvalidate()
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

    private fun showAssociateDeviceDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_associate_device, null)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Associate Vehicle")
            .setMessage("Enter the GPS device Unique ID")
            .setView(dialogView)
            .setPositiveButton("Associate") { _, _ ->
                val deviceUniqueId = dialogView.findViewById<EditText>(R.id.editDeviceId)
                    .text.toString().trim()
                val deviceName = dialogView.findViewById<EditText>(R.id.editDeviceName)
                    .text.toString().trim()

                if (deviceUniqueId.isNotEmpty() && deviceName.isNotEmpty()) {
                    associateDevice(deviceUniqueId, deviceName)
                } else {
                    showSnackbar("Enter a valid Unique ID and name", Snackbar.LENGTH_SHORT)
                }
            }
            .setNeutralButton("View Devices") { _, _ ->
                viewModel.showAvailableDevices { devices ->
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Available Devices")
                        .setMessage(devices)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun associateDevice(deviceUniqueId: String, name: String) {
        Log.d(TAG, "Associating device: uniqueId=$deviceUniqueId, name=$name")

        viewModel.associateDevice(deviceUniqueId, name) { deviceId, deviceName ->
            // Actualizar UI inmediatamente
            val info = "Device: $deviceName (ID: $deviceId)"
            deviceInfoCache.set(info)
            updateDeviceInfoUI(info)

            // Iniciar servicios
            lifecycleScope.launch {
                supervisorScope {
                    launch { setupWebSocket() }
                    launch { startTrackingService() }
                    launch { fetchInitialPosition() }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun showTraccarClientConfig() {
        val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)
        val deviceUniqueId = sharedPreferences.getString(DEVICE_UNIQUE_ID_PREF, null)

        if (deviceId == -1 || deviceUniqueId == null) {
            showSnackbar("Associate a device first", Snackbar.LENGTH_SHORT)
            return
        }

        viewModel.getGPSClientConfig { recommendedEndpoint, endpoints, instructions ->
            val configText = buildString {
                appendLine("📱 GPS Client Configuration:")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("🔗 RECOMMENDED SERVER URL:")
                appendLine("$recommendedEndpoint")
                appendLine()
                appendLine("📋 DEVICE SETTINGS:")
                appendLine("Device ID: $deviceUniqueId")
                appendLine("Protocol: ${instructions["protocol"] ?: "HTTP GET/POST"}")
                appendLine("Parameters: ${instructions["parameters"] ?: "id, lat, lon, timestamp, speed"}")
                appendLine("Frequency: 5 seconds")
                appendLine("Distance: 10 meters")
                appendLine()
                appendLine("🌐 ALTERNATIVE ENDPOINTS:")
                endpoints.forEach { (name, url) ->
                    appendLine("• ${name.uppercase()}: $url")
                }
                appendLine()
                appendLine("📱 FOR TRACCAR CLIENT APP:")
                appendLine("1. Install Traccar Client from Play Store")
                appendLine("2. Server URL: $recommendedEndpoint")
                appendLine("3. Device ID: $deviceUniqueId")
                appendLine("4. Enable location permissions")
                appendLine("5. Start the service")
                appendLine()
                appendLine("🔧 FOR CUSTOM GPS DEVICE:")
                appendLine("Send HTTP GET/POST requests to:")
                appendLine("$recommendedEndpoint")
                appendLine("Parameters: ${instructions["parameters"] ?: "id, lat, lon, timestamp, speed"}")
                appendLine()
                appendLine("📡 EXAMPLE REQUEST:")
                appendLine(instructions["example"] ?: "GET $recommendedEndpoint?id=$deviceUniqueId&lat=-37.32167&lon=-59.13316&timestamp=${java.time.Instant.now()}&speed=0")
                appendLine()
                appendLine("✅ VERIFY CONNECTION:")
                appendLine("Use 'Device Status' button to check if data is being received")
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("GPS Device Setup Guide")
                .setMessage(configText)
                .setPositiveButton("Copy Server URL") { _, _ ->
                    copyToClipboard("Server URL", recommendedEndpoint)
                    showSnackbar("✅ Server URL copied!", Snackbar.LENGTH_SHORT)
                }
                .setNeutralButton("Test URL") { _, _ ->
                    val testUrl = instructions["example"] ?:
                    "$recommendedEndpoint?id=$deviceUniqueId&lat=-37.32167&lon=-59.13316&timestamp=${java.time.Instant.now()}&speed=0"
                    openInBrowser(testUrl)
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun showOfflineHelpDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("🔴 Device Offline - Troubleshooting")
            .setMessage(buildString {
                appendLine("Your GPS device is not sending data. Here's how to fix it:")
                appendLine()
                appendLine("✅ CHECK THESE ITEMS:")
                appendLine("1. GPS device/app is running")
                appendLine("2. Location permissions are enabled")
                appendLine("3. Internet connection is working")
                appendLine("4. Correct server URL is configured")
                appendLine("5. Device ID matches exactly")
                appendLine()
                appendLine("🔧 NEXT STEPS:")
                appendLine("• Use 'Show GPS Client Config' for setup")
                appendLine("• Test the URL in your browser")
                appendLine("• Check GPS app settings")
                appendLine("• Restart the GPS tracking service")
            })
            .setPositiveButton("Show Config") { _, _ ->
                showTraccarClientConfig()
            }
            .setNegativeButton("OK", null)
            .show()
    }

    // ============ LOCATION Y PERMISOS OPTIMIZADOS ============

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
                enableFollowLocation()
            }
            map?.overlays?.add(myLocationOverlay)
        }

        map?.controller?.setZoom(16.0)
        map?.postInvalidate()
        Log.d(TAG, "My location enabled")
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

    private fun hasAssociatedDevice(): Boolean {
        val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)
        val deviceName = sharedPreferences.getString(DEVICE_NAME_PREF, null)
        val deviceUniqueId = sharedPreferences.getString(DEVICE_UNIQUE_ID_PREF, null)

        return deviceId != -1 &&
                !deviceName.isNullOrEmpty() &&
                !deviceUniqueId.isNullOrEmpty()
    }

    private fun shouldUpdatePosition(): Boolean {
        val now = System.currentTimeMillis()
        return (now - lastPositionUpdate.get()) >= POSITION_UPDATE_THROTTLE
    }

    private fun showSnackbar(message: String, duration: Int) {
        if (isAdded && view != null) {
            Snackbar.make(binding.root, message, duration).show()
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
            showSnackbar("🌐 Opening test URL in browser", Snackbar.LENGTH_SHORT)
        } catch (e: Exception) {
            showSnackbar("❌ No browser available", Snackbar.LENGTH_SHORT)
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
        Log.d(TAG, "User logged out")
    }

    // ============ LIFECYCLE OPTIMIZADO ============

    override fun onResume() {
        super.onResume()
        shouldReconnect.set(true)
        map?.onResume()

        if (hasAssociatedDevice()) {
            lifecycleScope.launch {
                supervisorScope {
                    launch { setupWebSocket() }
                    launch { startPeriodicStatusCheck() }
                    launch {
                        // Actualizar posición si es necesario
                        if (System.currentTimeMillis() - lastPositionUpdate.get() > 30000L) {
                            fetchInitialPosition()
                        }
                    }
                }
            }
        }
        Log.d(TAG, "Fragment resumed")
    }

    override fun onPause() {
        super.onPause()
        shouldReconnect.set(false)

        // Cancelar tareas
        cancelStatusCheck()
        cancelReconnect()
        handler.removeCallbacksAndMessages(null)

        // Cerrar WebSocket limpiamente
        webSocket?.close(1000, "Fragment paused")
        webSocket = null

        map?.onPause()
        Log.d(TAG, "Fragment paused")
    }

    override fun onDestroyView() {
        super.onDestroyView()

        // Limpieza completa
        shouldReconnect.set(false)
        cancelStatusCheck()
        cancelReconnect()
        handler.removeCallbacksAndMessages(null)

        // Cerrar WebSocket
        webSocket?.close(1000, "Fragment destroyed")
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
        Log.d(TAG, "Fragment view destroyed")
    }
}