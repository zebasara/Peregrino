package com.zebass.peregrino

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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

class SecondFragment : Fragment() {

    private var _binding: FragmentSecondBinding? = null
    private val binding get() = _binding!!
    private lateinit var sharedPreferences: SharedPreferences
    private var map: MapView? = null
    private var vehicleMarker: Marker? = null
    private var safeZonePolygon: Polygon? = null
    private var myLocationOverlay: MyLocationNewOverlay? = null
    private val args: SecondFragmentArgs by navArgs()
    private var webSocket: WebSocket? = null
    private val viewModel: TrackingViewModel by viewModels()
    private val client: OkHttpClient by lazy { OkHttpClient() }
    private var shouldReconnect = true
    private val handler = Handler(Looper.getMainLooper())

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
            Snackbar.make(binding.root, "Location permission denied", Snackbar.LENGTH_LONG).show()
            Log.e(TAG, "Location permission denied")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        Configuration.getInstance().load(requireContext(), requireContext().getSharedPreferences("osmdroid", 0))
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
        Log.d(TAG, "onViewCreated: userEmail=$userEmail, jwtToken=$JWT_TOKEN")

        binding.textUser.text = "Welcome: $userEmail"
        binding.buttonLogout.setOnClickListener {
            sharedPreferences.edit().remove("jwt_token").remove("user_email").apply()
            findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment)
            Log.d(TAG, "User logged out")
        }

        setupMap()
        setupButtons()
        viewModel.fetchAssociatedDevices()
        displayDeviceInfo()
        startTrackingService()
        schedulePeriodicSync()
        observeViewModel()
        fetchInitialPosition()
        setupWebSocket()
        startPeriodicStatusCheck()
    }

    private fun setupWebSocket() {
        val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)
        if (deviceId == -1) {
            Log.e(TAG, "No hay dispositivo asociado para WebSocket")
            return
        }

        val wsUrl = "wss://carefully-arriving-shepherd.ngrok-free.app/ws?token=$JWT_TOKEN"
        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket conectado")
                val message = JSONObject().apply {
                    put("type", "SUBSCRIBE_DEVICE")
                    put("deviceId", deviceId)
                    put("token", JWT_TOKEN)
                }
                webSocket.send(message.toString())
                lifecycleScope.launch(Dispatchers.Main) {
                    Snackbar.make(binding.root, "Connected - Receiving locations", Snackbar.LENGTH_SHORT).show()
                }
            }

            // En SecondFragment.kt - Actualizar el WebSocket onMessage

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.getString("type")) {
                        "POSITION_UPDATE" -> {
                            val data = json.getJSONObject("data")
                            val receivedDeviceId = data.getInt("deviceId")
                            val lat = data.getDouble("lat")
                            val lon = data.getDouble("lon")
                            Log.d(TAG, "WebSocket position update: deviceId=$receivedDeviceId, lat=$lat, lon=$lon")

                            // USAR EL VIEWMODEL en lugar de llamar directamente updateVehiclePosition
                            lifecycleScope.launch(Dispatchers.Main) {
                                viewModel.updateVehiclePosition(receivedDeviceId, GeoPoint(lat, lon))
                            }
                        }
                        "CONNECTION_CONFIRMED" -> Log.d(TAG, "Conexión WebSocket confirmada")
                        "SUBSCRIPTION_CONFIRMED" -> Log.d(TAG, "Suscripción confirmada para deviceId=$deviceId")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error procesando mensaje WebSocket: ${e.message}")
                    viewModel.postError("Error parsing WebSocket message: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket cerrándose: code=$code, reason=$reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Fallo en WebSocket: ${t.message}")
                lifecycleScope.launch(Dispatchers.Main) {
                    Snackbar.make(binding.root, "Disconnected from server: ${t.message}", Snackbar.LENGTH_SHORT).show()
                    if (shouldReconnect) {
                        delay(RECONNECT_DELAY)
                        if (isAdded) setupWebSocket()
                    }
                }
            }
        })
    }

    private fun setupMap() {
        map = binding.mapView
        map?.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(12.0)
            controller.setCenter(GeoPoint(-37.32167, -59.13316)) // Default center
            Log.d(TAG, "Map initialized with zoom=12.0, center=(-37.32167, -59.13316)")
        }

        loadSafeZone()
        viewModel.fetchSafeZoneFromServer()

        if (hasLocationPermission()) enableMyLocation()
    }
    @RequiresApi(Build.VERSION_CODES.O)
    private fun setupButtons() {
        binding.buttonMyLocation.setOnClickListener { enableMyLocation() }
        binding.buttonZonaSegura.setOnClickListener {
            if (safeZone == null) enterSafeZoneSetupMode() else toggleSafeZone()
        }
        binding.buttonAssociateDevice.setOnClickListener { showAssociateDeviceDialog() }

        // Add new button for device status
        binding.buttonDeviceStatus.setOnClickListener { checkDeviceStatus() }

        // Add button to show Traccar Client configuration
        binding.buttonShowConfig.setOnClickListener { showTraccarClientConfig() }

        binding.buttonDescargarOffline.visibility = View.GONE
        binding.progressBarDownload.visibility = View.GONE
        Log.d(TAG, "Buttons initialized")
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.vehiclePosition.collect { position ->
                position?.let {
                    Log.d(TAG, "ViewModel position update: deviceId=${it.first}, lat=${it.second.latitude}, lon=${it.second.longitude}")
                    updateVehiclePosition(it.first, GeoPoint(it.second.latitude, it.second.longitude))
                }
            }
        }
        lifecycleScope.launch {
            viewModel.safeZone.collect { zone ->
                zone?.let {
                    safeZone = GeoPoint(it.latitude, it.longitude)
                    updateSafeZoneUI(safeZone!!)
                    Log.d(TAG, "Safe zone updated: lat=${it.latitude}, lon=${it.longitude}")
                }
            }
        }
        lifecycleScope.launch {
            viewModel.error.collect { error ->
                error?.let {
                    Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
                    Log.e(TAG, "Error from ViewModel: $error")
                }
            }
        }
        lifecycleScope.launch {
            viewModel.deviceInfo.collect { info ->
                binding.textDeviceInfo.text = info ?: "No devices associated"
                binding.textDeviceInfo.visibility = View.VISIBLE
                Log.d(TAG, "Device info updated: $info")
            }
        }
    }

    private fun startTrackingService() {
        if (hasAssociatedDevice()) {
            val intent = Intent(requireContext(), TrackingService::class.java).apply {
                putExtra("jwtToken", JWT_TOKEN)
                putExtra("deviceId", sharedPreferences.getInt(DEVICE_ID_PREF, -1))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireContext().startForegroundService(intent)
            } else {
                requireContext().startService(intent)
            }
            Log.d(TAG, "Started TrackingService for deviceId=${sharedPreferences.getInt(DEVICE_ID_PREF, -1)}")
        } else {
            Log.d(TAG, "No associated device, skipping TrackingService start")
        }
    }

    private fun schedulePeriodicSync() {
        val syncWork = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(requireContext())
            .enqueueUniquePeriodicWork("sync_work", ExistingPeriodicWorkPolicy.KEEP, syncWork)
        Log.d(TAG, "Scheduled periodic sync")
    }

    private fun updateVehiclePosition(deviceId: Int, position: GeoPoint) {
        val storedDeviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)
        if (deviceId != storedDeviceId) {
            Log.d(TAG, "Ignoring position update: received deviceId=$deviceId, expected=$storedDeviceId")
            return
        }

        if (vehicleMarker == null) {
            vehicleMarker = Marker(map).apply {
                this.position = position
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_vehicle)
                title = "Vehículo ID: $deviceId\nLat: ${"%.6f".format(position.latitude)}\nLon: ${"%.6f".format(position.longitude)}"
            }
            map?.overlays?.add(vehicleMarker)
            Log.d(TAG, "Creado marcador en lat=${position.latitude}, lon=${position.longitude}")
        } else {
            vehicleMarker?.position = position
            vehicleMarker?.title = "Vehículo ID: $deviceId\nLat: ${"%.6f".format(position.latitude)}\nLon: ${"%.6f".format(position.longitude)}"
            Log.d(TAG, "Actualizado marcador a lat=${position.latitude}, lon=${position.longitude}")
        }

        safeZone?.let { zone ->
            val distance = zone.distanceToAsDouble(position)
            if (distance > GEOFENCE_RADIUS) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) triggerAlarm(deviceId, distance)
                vehicleMarker?.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_vehicle_alert)
            } else {
                vehicleMarker?.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_vehicle)
            }
        }

        map?.controller?.animateTo(position, 16.0, 500L) // Animación suave de 500ms
        map?.invalidate()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun triggerAlarm(deviceId: Int, distance: Double) {
        val vibrator = requireContext().getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        vibrator.vibrate(android.os.VibrationEffect.createOneShot(1000, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        Snackbar.make(binding.root, "ALERT! Vehicle $deviceId is ${"%.1f".format(distance)} meters away", Snackbar.LENGTH_LONG).show()
        Log.d(TAG, "Geofence alert triggered: deviceId=$deviceId, distance=$distance")
    }

    private fun fetchInitialPosition() {
        if (hasAssociatedDevice()) {
            val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)
            Log.d(TAG, "Fetching initial position for deviceId=$deviceId")
            lifecycleScope.launch {
                try {
                    val position = viewModel.getLastPosition(deviceId)
                    Log.d(TAG, "Initial position fetched: lat=${position.latitude}, lon=${position.longitude}")
                    updateVehiclePosition(deviceId, GeoPoint(position.latitude, position.longitude))
                } catch (e: Exception) {
                    viewModel.postError("Failed to fetch initial position: ${e.message}")
                    Log.e(TAG, "Failed to fetch initial position for deviceId=$deviceId", e)
                }
            }
        } else {
            Log.d(TAG, "No associated device, skipping initial position fetch")
        }
    }

    private fun associateDevice(deviceUniqueId: String, name: String) {
        Log.d(TAG, "Associating device: uniqueId=$deviceUniqueId, name=$name")
        viewModel.associateDevice(deviceUniqueId, name) { deviceId, deviceName ->
            binding.textDeviceInfo.text = "Device: $deviceName (ID: $deviceId)"
            binding.textDeviceInfo.visibility = View.VISIBLE
            setupWebSocket()
            startTrackingService()
            fetchInitialPosition()
            Log.d(TAG, "Device associated: id=$deviceId, name=$deviceName")
        }
    }

    private fun displayDeviceInfo() {
        if (hasAssociatedDevice()) {
            val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)
            val deviceName = sharedPreferences.getString(DEVICE_NAME_PREF, null)
            binding.textDeviceInfo.text = "Device: $deviceName (ID: $deviceId)"
            binding.textDeviceInfo.visibility = View.VISIBLE
            setupWebSocket()
            fetchInitialPosition()
            Log.d(TAG, "Displaying device info: name=$deviceName, id=$deviceId")
        } else {
            binding.textDeviceInfo.text = "No devices associated"
            binding.textDeviceInfo.visibility = View.VISIBLE
            Log.d(TAG, "No associated devices")
        }
    }

    private fun showAssociateDeviceDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_associate_device, null)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Associate Vehicle")
            .setMessage("Enter the GPS device Unique ID")
            .setView(dialogView)
            .setPositiveButton("Associate") { _, _ ->
                val deviceUniqueId = dialogView.findViewById<EditText>(R.id.editDeviceId).text.toString().trim()
                val deviceName = dialogView.findViewById<EditText>(R.id.editDeviceName).text.toString().trim()
                if (deviceUniqueId.isNotEmpty() && deviceName.isNotEmpty()) {
                    associateDevice(deviceUniqueId, deviceName)
                } else {
                    Snackbar.make(binding.root, "Enter a valid Unique ID and name", Snackbar.LENGTH_SHORT).show()
                    Log.d(TAG, "Invalid device ID or name entered")
                }
            }
            .setNeutralButton("View Devices") { _, _ ->
                viewModel.showAvailableDevices { devices ->
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Available Devices")
                        .setMessage(devices)
                        .setPositiveButton("OK", null)
                        .show()
                    Log.d(TAG, "Showing available devices: $devices")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadSafeZone() {
        val lat = sharedPreferences.getString(PREF_SAFEZONE_LAT, null)?.toDoubleOrNull()
        val lon = sharedPreferences.getString(PREF_SAFEZONE_LON, null)?.toDoubleOrNull()
        if (lat != null && lon != null) {
            safeZone = GeoPoint(lat, lon)
            updateSafeZoneUI(safeZone!!)
            Log.d(TAG, "Loaded safe zone: lat=$lat, lon=$lon")
        }
    }

    private fun updateSafeZoneUI(position: GeoPoint) {
        safeZonePolygon?.let { map?.overlays?.remove(it) }
        safeZonePolygon = Polygon().apply {
            points = Polygon.pointsAsCircle(position, GEOFENCE_RADIUS)
            fillColor = 0x22FF0000
            strokeColor = android.graphics.Color.RED
            strokeWidth = 2f
        }
        map?.overlays?.add(safeZonePolygon)
        binding.buttonZonaSegura.text = "Safe Zone Active ✓"
        binding.buttonZonaSegura.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark))
        map?.controller?.animateTo(position)
        map?.controller?.setZoom(16.0)
        map?.invalidate()
        Log.d(TAG, "Updated safe zone UI at lat=${position.latitude}, lon=${position.longitude}")
    }

    private fun toggleSafeZone() {
        safeZone = null
        safeZonePolygon?.let { map?.overlays?.remove(it) }
        safeZonePolygon = null
        sharedPreferences.edit().remove(PREF_SAFEZONE_LAT).remove(PREF_SAFEZONE_LON).apply()
        binding.buttonZonaSegura.text = "Set Safe Zone"
        binding.buttonZonaSegura.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_blue_dark))
        viewModel.deleteSafeZoneFromServer()
        Log.d(TAG, "Safe zone toggled off")
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
        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(requireContext()), map).apply {
            enableMyLocation()
            enableFollowLocation()
            map?.overlays?.add(this)
        }
        map?.controller?.setZoom(16.0)
        map?.invalidate()
        Log.d(TAG, "My location enabled")
    }

    private fun enterSafeZoneSetupMode() {
        if (!hasAssociatedDevice()) {
            Snackbar.make(binding.root, "Please associate a vehicle first", Snackbar.LENGTH_SHORT).show()
            Log.d(TAG, "Cannot set safe zone: no associated device")
            return
        }
        viewModel.showDeviceSelectionForSafeZone { deviceId ->
            establishSafeZoneForDevice(deviceId)
        }
    }

    private fun establishSafeZoneForDevice(deviceId: Int) {
        binding.buttonZonaSegura.text = "Fetching vehicle location..."
        binding.buttonZonaSegura.isEnabled = false
        lifecycleScope.launch {
            try {
                val position = viewModel.getLastPosition(deviceId)
                safeZone = GeoPoint(position.latitude, position.longitude)
                updateSafeZoneUI(safeZone!!)
                sharedPreferences.edit()
                    .putString(PREF_SAFEZONE_LAT, position.latitude.toString())
                    .putString(PREF_SAFEZONE_LON, position.longitude.toString())
                    .apply()
                viewModel.sendSafeZoneToServer(position.latitude, position.longitude, deviceId)
                binding.buttonZonaSegura.isEnabled = true
                Log.d(TAG, "Safe zone set for deviceId=$deviceId at lat=${position.latitude}, lon=${position.longitude}")
            } catch (e: Exception) {
                viewModel.postError("Failed to set safe zone: ${e.message}")
                binding.buttonZonaSegura.text = "Set Safe Zone"
                binding.buttonZonaSegura.isEnabled = true
                Log.e(TAG, "Failed to set safe zone for deviceId=$deviceId", e)
            }
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasAssociatedDevice(): Boolean {
        val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)
        val deviceName = sharedPreferences.getString(DEVICE_NAME_PREF, null)
        val deviceUniqueId = sharedPreferences.getString(DEVICE_UNIQUE_ID_PREF, null)
        val hasDevice = deviceId != -1 && !deviceName.isNullOrEmpty() && !deviceUniqueId.isNullOrEmpty()
        Log.d(TAG, "hasAssociatedDevice: $hasDevice, deviceId=$deviceId, deviceName=$deviceName, deviceUniqueId=$deviceUniqueId")
        return hasDevice
    }
    // Actualizar en SecondFragment.kt

    @RequiresApi(Build.VERSION_CODES.O)
    private fun showTraccarClientConfig() {
        val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)
        val deviceUniqueId = sharedPreferences.getString(DEVICE_UNIQUE_ID_PREF, null)

        if (deviceId == -1 || deviceUniqueId == null) {
            Snackbar.make(binding.root, "Associate a device first", Snackbar.LENGTH_SHORT).show()
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
                appendLine("Protocol: ${instructions?.optString("protocol", "HTTP GET/POST")}")
                appendLine("Parameters: ${instructions?.optString("parameters", "id, lat, lon, timestamp, speed")}")
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
                appendLine("Parameters: ${instructions?.optString("parameters", "id, lat, lon, timestamp, speed")}")
                appendLine()
                appendLine("📡 EXAMPLE REQUEST:")
                appendLine(instructions?.optString("example", "GET $recommendedEndpoint?id=$deviceUniqueId&lat=-37.32167&lon=-59.13316&timestamp=${java.time.Instant.now()}&speed=0"))
                appendLine()
                appendLine("✅ VERIFY CONNECTION:")
                appendLine("Use 'Device Status' button to check if data is being received")
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("GPS Device Setup Guide")
                .setMessage(configText)
                .setPositiveButton("Copy Server URL") { _, _ ->
                    val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Server URL", recommendedEndpoint)
                    clipboard.setPrimaryClip(clip)
                    Snackbar.make(binding.root, "✅ Server URL copied to clipboard!", Snackbar.LENGTH_SHORT).show()
                }
                .setNeutralButton("Test URL") { _, _ ->
                    // Usar el ejemplo de la respuesta del servidor si está disponible
                    val testUrl = instructions?.optString("example") ?: "$recommendedEndpoint?id=$deviceUniqueId&lat=-37.32167&lon=-59.13316&timestamp=${java.time.Instant.now()}&speed=0"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(testUrl))
                    try {
                        startActivity(intent)
                        Snackbar.make(binding.root, "🌐 Opening test URL in browser", Snackbar.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Snackbar.make(binding.root, "❌ No browser available", Snackbar.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun checkDeviceStatus() {
        val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)
        if (deviceId == -1) {
            Snackbar.make(binding.root, "No device associated", Snackbar.LENGTH_SHORT).show()
            return
        }

        // Show loading
        binding.textDeviceStatus.text = "🔄 Checking device status..."
        binding.textDeviceStatus.visibility = View.VISIBLE
        binding.textDeviceStatus.setTextColor(
            ContextCompat.getColor(requireContext(), android.R.color.darker_gray)
        )

        viewModel.checkDeviceStatus(deviceId) { isOnline, message ->
            val statusIcon = if (isOnline) "🟢" else "🔴"
            val displayMessage = "$statusIcon $message"

            Snackbar.make(binding.root, displayMessage,
                if (isOnline) Snackbar.LENGTH_SHORT else Snackbar.LENGTH_LONG).show()

            // Update UI
            binding.textDeviceStatus.text = displayMessage
            binding.textDeviceStatus.visibility = View.VISIBLE
            binding.textDeviceStatus.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (isOnline) android.R.color.holo_green_dark
                    else android.R.color.holo_red_dark
                )
            )

            // If offline, show help dialog
            if (!isOnline) {
                Handler(Looper.getMainLooper()).postDelayed({
                    showOfflineHelpDialog()
                }, 2000)
            }
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

    // Add periodic status checking
    private fun startPeriodicStatusCheck() {
        if (hasAssociatedDevice()) {
            handler.postDelayed(object : Runnable {
                override fun run() {
                    val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)
                    if (deviceId != -1 && isAdded) {
                        viewModel.checkDeviceStatus(deviceId) { isOnline, message ->
                            // Update status silently (no snackbar)
                            val statusIcon = if (isOnline) "🟢" else "🔴"
                            binding.textDeviceStatus.text = "$statusIcon ${message.substringAfter(" ")}"
                            binding.textDeviceStatus.visibility = View.VISIBLE
                            binding.textDeviceStatus.setTextColor(
                                ContextCompat.getColor(
                                    requireContext(),
                                    if (isOnline) android.R.color.holo_green_dark
                                    else android.R.color.holo_red_dark
                                )
                            )
                        }
                    }
                    handler.postDelayed(this, 30000) // Check every 30 seconds
                }
            }, 10000) // Start after 10 seconds
        }
    }

    override fun onResume() {
        super.onResume()
        shouldReconnect = true
        map?.onResume()
        if (hasAssociatedDevice()) {
            setupWebSocket()
            startPeriodicStatusCheck() // Agregar esta línea
        }
        Log.d(TAG, "Fragment resumed")
    }

    override fun onPause() {
        super.onPause()
        shouldReconnect = false
        handler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "Fragment paused")
        webSocket = null
        map?.onPause()
        Log.d(TAG, "Fragment paused")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        webSocket?.close(1000, "Fragment destruido")
        webSocket = null
        requireContext().stopService(Intent(requireContext(), TrackingService::class.java))
        myLocationOverlay?.disableMyLocation()
        map?.overlays?.clear()
        map?.onDetach()
        _binding = null
        Log.d(TAG, "Fragment view destroyed")
    }
}