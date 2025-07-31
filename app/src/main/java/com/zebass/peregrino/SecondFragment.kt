package com.zebass.peregrino

    import android.os.Bundle
    import android.view.LayoutInflater
    import android.view.View
    import android.view.ViewGroup
    import android.widget.Toast
    import org.json.JSONObject
    import org.json.JSONArray
    import androidx.fragment.app.Fragment
    import com.zebass.peregrino.databinding.FragmentSecondBinding
    import kotlinx.coroutines.*
    import okhttp3.OkHttpClient
    import okhttp3.Request
    import org.osmdroid.events.MapListener
    import org.osmdroid.events.ScrollEvent
    import org.osmdroid.events.ZoomEvent
    import org.osmdroid.config.Configuration
    import org.osmdroid.tileprovider.cachemanager.CacheManager
    import org.osmdroid.tileprovider.tilesource.TileSourceFactory
    import org.osmdroid.util.BoundingBox
    import org.osmdroid.util.GeoPoint
    import org.osmdroid.views.MapView
    import org.osmdroid.views.overlay.Marker
    import android.Manifest
    import android.annotation.SuppressLint
    import android.app.AlertDialog
    import android.content.Context
    import android.content.SharedPreferences
    import android.content.pm.PackageManager
    import android.graphics.Bitmap
    import android.graphics.BitmapFactory
    import android.graphics.Canvas
    import android.graphics.Color
    import android.graphics.drawable.BitmapDrawable
    import android.location.LocationListener
    import android.location.LocationManager
    import android.os.Build
    import android.os.Handler
    import android.os.Looper
    import android.os.VibrationEffect
    import android.os.Vibrator
    import android.util.Log
    import android.widget.EditText
    import androidx.annotation.RequiresApi
    import androidx.core.app.ActivityCompat
    import androidx.core.content.ContextCompat
    import androidx.navigation.fragment.findNavController
    import androidx.navigation.fragment.navArgs
    import okhttp3.FormBody
    import okhttp3.MediaType.Companion.toMediaTypeOrNull
    import okhttp3.RequestBody.Companion.toRequestBody
    import org.java_websocket.client.WebSocketClient
    import org.java_websocket.handshake.ServerHandshake
    import org.osmdroid.views.overlay.Polygon
    import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
    import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
    import java.net.URI

class SecondFragment : Fragment() {

    private var _binding: FragmentSecondBinding? = null
    private val binding get() = _binding!!
    private lateinit var map: MapView
    private val client = OkHttpClient()
    private lateinit var locationManager: LocationManager
    private var locationListener: LocationListener? = null
    private var myLocationOverlay: MyLocationNewOverlay? = null
    private var vehicleMarker: Marker? = null
    private lateinit var sharedPreferences: SharedPreferences
    private val args: SecondFragmentArgs by navArgs()
    private val updateJob = SupervisorJob()
    private var webSocketClient: WebSocketClient? = null
    private var shouldReconnect = true
    private val reconnectDelay = 5000L
    private val handler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = object : Runnable {
        override fun run() {
            if (shouldReconnect) {
                initWebSocket()
            }
        }
    }

    companion object {
        var JWT_TOKEN: String? = ""
        private var safeZone: GeoPoint? = null
        private const val PREF_SAFEZONE_LAT = "safezone_lat"
        private const val PREF_SAFEZONE_LON = "safezone_lon"
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val MIN_TIME_BETWEEN_UPDATES = 5000L
        private const val MIN_DISTANCE_CHANGE_FOR_UPDATES = 10f
        private const val DEVICE_ID_PREF = "associated_device_id"
        private const val DEVICE_NAME_PREF = "associated_device_name"
        private const val DEVICE_UNIQUE_ID_PREF = "associated_device_unique_id" // NUEVO
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Configuration.getInstance().load(
            requireContext(),
            requireContext().getSharedPreferences("osmdroid", 0)
        )
        _binding = FragmentSecondBinding.inflate(inflater, container, false)
        sharedPreferences =
            requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val userEmail = args.userEmail
        JWT_TOKEN = args.jwtToken

        binding.textUser.text = "Bienvenido: $userEmail"
        binding.buttonLogout.setOnClickListener {
            with(sharedPreferences.edit()) {
                remove("jwt_token")
                remove("user_email")
                apply()
            }
            findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment)
        }

        setupMap()
        setupButtons()

        // Cargar dispositivos asociados y inicializar conexión
        fetchAssociatedDevices()

        // Mostrar información del dispositivo si está guardada
        displayDeviceInfo()
    }
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                enableMyLocation()
            } else {
                Toast.makeText(
                    requireContext(),
                    "Permiso de ubicación denegado",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun setupMap() {
        map = binding.mapView
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)

        loadSafeZone()
        fetchSafeZoneFromServer()

        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean = false
            override fun onZoom(event: ZoomEvent?): Boolean = false
        })

        val prefs = requireContext().getSharedPreferences("offline_zone", 0)
        if (prefs.contains("latNorth")) {
            val bbox = BoundingBox(
                prefs.getString("latNorth", "0")!!.toDouble(),
                prefs.getString("lonEast", "0")!!.toDouble(),
                prefs.getString("latSouth", "0")!!.toDouble(),
                prefs.getString("lonWest", "0")!!.toDouble()
            )
            map.zoomToBoundingBox(bbox, true)
        } else {
            val startPoint = GeoPoint(-37.32167, -59.13316)
            map.controller.setZoom(12.0)
            map.controller.setCenter(startPoint)
        }
    }

    private fun setupButtons() {
        binding.buttonMyLocation.setOnClickListener {
            enableMyLocation()
        }

        binding.buttonZonaSegura.setOnClickListener {
            enterSafeZoneSetupMode()
        }

        binding.buttonAssociateDevice.setOnClickListener {
            showAssociateDeviceDialog()
        }

        binding.buttonDescargarOffline.setOnClickListener {
            downloadOfflineMap()
        }

        binding.mapContainer.layoutParams.height = 600
        binding.mapContainer.setOnClickListener {
            binding.mapContainer.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            binding.mapContainer.requestLayout()
        }
    }
    // Método corregido para mostrar información del dispositivo
    private fun displayDeviceInfo() {
        val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)
        val deviceName = sharedPreferences.getString(DEVICE_NAME_PREF, null)
        val deviceUniqueId = sharedPreferences.getString(DEVICE_UNIQUE_ID_PREF, null)

        Log.d("DeviceInfo", "Mostrando info - ID: $deviceId, Name: $deviceName, UniqueID: $deviceUniqueId")

        if (hasAssociatedDevice()) {
            binding.textDeviceInfo.text = "Dispositivo: $deviceName (ID: $deviceId)"
            binding.textDeviceInfo.visibility = View.VISIBLE

            // Inicializar WebSocket solo si hay dispositivo asociado
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                initWebSocket()
            }
        } else {
            binding.textDeviceInfo.text = "No hay dispositivos asociados"
            binding.textDeviceInfo.visibility = View.VISIBLE
        }
    }


    private fun initWebSocket() {
        val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)
        if (deviceId == -1) {
            Log.w("WebSocket", "No hay dispositivo asociado para WebSocket")
            return
        }

        closeWebSocket()

        try {
            webSocketClient = object : WebSocketClient(URI("wss://carefully-arriving-shepherd.ngrok-free.app/ws?token=${JWT_TOKEN}")) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    Log.d("WebSocket", "Conectado al servidor WebSocket")
                    activity?.runOnUiThread {
                        Toast.makeText(context, "Conectado - Recibiendo ubicaciones", Toast.LENGTH_SHORT).show()
                    }

                    // Enviar mensaje de suscripción
                    val subscribeMsg = JSONObject().apply {
                        put("type", "SUBSCRIBE_DEVICE")
                        put("deviceId", deviceId)
                        put("token", JWT_TOKEN)
                    }
                    send(subscribeMsg.toString())
                    Log.d("WebSocket", "Mensaje de suscripción enviado: $subscribeMsg")
                }

                override fun onMessage(message: String?) {
                    Log.d("WebSocket", "Mensaje recibido: $message")
                    try {
                        val data = JSONObject(message ?: return)
                        if (data.getString("type") == "POSITION_UPDATE") {
                            val pos = data.getJSONObject("data")
                            val deviceIdFromMsg = pos.getInt("deviceId")

                            activity?.runOnUiThread {
                                updateVehiclePosition(
                                    deviceIdFromMsg,
                                    GeoPoint(pos.getDouble("lat"), pos.getDouble("lon"))
                                )
                                Log.d("WebSocket", "Posición actualizada: lat=${pos.getDouble("lat")}, lon=${pos.getDouble("lon")}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("WebSocket", "Error parsing message", e)
                    }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.w("WebSocket", "Desconectado: code=$code, reason=$reason")
                    activity?.runOnUiThread {
                        Toast.makeText(context, "Desconectado del servidor", Toast.LENGTH_SHORT).show()
                    }
                    scheduleReconnect()
                }

                override fun onError(ex: Exception?) {
                    Log.e("WebSocket", "Error en WebSocket", ex)
                    scheduleReconnect()
                }
            }

            webSocketClient?.connect()

        } catch (e: Exception) {
            Log.e("WebSocket", "Error creando WebSocket", e)
        }
    }

    private fun scheduleReconnect() {
        if (shouldReconnect) {
            handler.postDelayed(reconnectRunnable, reconnectDelay)
        }
    }

    private fun closeWebSocket() {
        try {
            webSocketClient?.let { client ->
                if (client.isOpen) {
                    client.close()
                }
            }
        } catch (e: Exception) {
            Log.e("WebSocket", "Error closing WebSocket", e)
        } finally {
            webSocketClient = null
        }
    }
    // Método corregido para actualizar posición del vehículo
    private fun updateVehiclePosition(deviceId: Int, position: GeoPoint) {
        Log.d("Position", "Actualizando posición del vehículo $deviceId: ${position.latitude}, ${position.longitude}")

        // Verificar si es el dispositivo activo
        val activeDeviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)
        if (deviceId != activeDeviceId) {
            Log.d("Position", "Posición recibida para dispositivo $deviceId, pero el activo es $activeDeviceId")
            return
        }

        // Actualizar o crear marcador
        vehicleMarker?.let { marker ->
            marker.position = position
            marker.title = "Vehículo ID: $deviceId\nLat: ${"%.6f".format(position.latitude)}\nLon: ${"%.6f".format(position.longitude)}"
            marker.snippet = "Última actualización: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}"
        } ?: run {
            // Crear nuevo marcador si no existe
            addVehicleMarker(position, deviceId)
        }

        // Verificar distancia a zona segura
        safeZone?.let { zone ->
            val distance = zone.distanceToAsDouble(position)
            Log.d("SafeZone", "Distancia a zona segura: ${"%.1f".format(distance)}m")

            if (distance > 15) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    triggerAlarm(deviceId, distance)
                }
                vehicleMarker?.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_vehicle_alert)
            } else {
                vehicleMarker?.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_vehicle)
            }
        }

        // Centrar mapa en la nueva posición del vehículo (opcional, puedes comentar si no quieres que se mueva automáticamente)
        // map.controller.animateTo(position)
        map.invalidate()

        Log.d("Position", "Marcador actualizado correctamente")
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun triggerAlarm(deviceId: Int, distance: Double) {
        val alarmMsg = "¡ALARMA! Vehículo $deviceId se alejó ${"%.1f".format(distance)} metros"
        Toast.makeText(requireContext(), alarmMsg, Toast.LENGTH_LONG).show()

        val vibrator = context?.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun associateDevice(deviceUniqueId: String, name: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("DeviceAssociation", "Iniciando asociación - UniqueID: $deviceUniqueId, Nombre: $name")

                val json = JSONObject().apply {
                    put("deviceId", deviceUniqueId.trim()) // Limpiar espacios
                    put("name", name.trim())
                }

                val BASE_URL = "https://carefully-arriving-shepherd.ngrok-free.app"
                val requestBody = json.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

                val request = Request.Builder()
                    .url("$BASE_URL/api/user/devices")
                    .post(requestBody)
                    .addHeader("Authorization", "Bearer $JWT_TOKEN")
                    .addHeader("Content-Type", "application/json")
                    .build()

                Log.d("DeviceAssociation", "Enviando request: ${request.url}")
                Log.d("DeviceAssociation", "Body: ${json.toString()}")

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                Log.d("DeviceAssociation", "Response code: ${response.code}")
                Log.d("DeviceAssociation", "Response body: $responseBody")

                withContext(Dispatchers.Main) {
                    when {
                        response.isSuccessful -> {
                            try {
                                val jsonResponse = JSONObject(responseBody ?: "{}")
                                val deviceId = jsonResponse.getInt("id")
                                val deviceName = jsonResponse.getString("name")
                                val uniqueId = jsonResponse.optString("uniqueId", deviceUniqueId)

                                // Guardar información del dispositivo
                                sharedPreferences.edit().apply {
                                    putInt(DEVICE_ID_PREF, deviceId)
                                    putString(DEVICE_NAME_PREF, deviceName)
                                    putString(DEVICE_UNIQUE_ID_PREF, uniqueId)
                                    apply()
                                }

                                // Actualizar UI
                                binding.textDeviceInfo.text = "Dispositivo: $deviceName (ID: $deviceId)"
                                binding.textDeviceInfo.visibility = View.VISIBLE

                                Toast.makeText(
                                    requireContext(),
                                    "Dispositivo '$deviceName' asociado correctamente",
                                    Toast.LENGTH_LONG
                                ).show()

                                // Inicializar WebSocket
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    initWebSocket()
                                }

                            } catch (e: Exception) {
                                Log.e("DeviceAssociation", "Error parsing success response", e)
                                Toast.makeText(
                                    requireContext(),
                                    "Dispositivo asociado pero error en respuesta",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        response.code == 404 -> {
                            Toast.makeText(
                                requireContext(),
                                "Dispositivo no encontrado. Verifica el Unique ID en Traccar.\n\nRespuesta: $responseBody",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        response.code == 409 -> {
                            Toast.makeText(
                                requireContext(),
                                "Dispositivo ya está asociado a tu cuenta",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        response.code == 401 -> {
                            Toast.makeText(
                                requireContext(),
                                "Token de autenticación inválido. Inicia sesión nuevamente",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        else -> {
                            Toast.makeText(
                                requireContext(),
                                "Error ${response.code}: $responseBody",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("DeviceAssociation", "Error completo", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        "Error de conexión: ${e.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun fetchAssociatedDevices() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder()
                    .url("https://carefully-arriving-shepherd.ngrok-free.app/api/user/devices")
                    .get()
                    .addHeader("Authorization", "Bearer $JWT_TOKEN")
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    response.body?.string()?.let { responseBody ->
                        Log.d("FetchDevices", "Response: $responseBody")
                        val jsonArray = JSONArray(responseBody)

                        requireActivity().runOnUiThread {
                            if (jsonArray.length() > 0) {
                                val firstDevice = jsonArray.getJSONObject(0)
                                val deviceId = firstDevice.getInt("id")
                                val deviceName = firstDevice.getString("name")

                                binding.textDeviceInfo.text = "Vehículo: $deviceName (ID: $deviceId)"
                                binding.textDeviceInfo.visibility = View.VISIBLE

                                sharedPreferences.edit().apply {
                                    putInt(DEVICE_ID_PREF, deviceId)
                                    putString(DEVICE_NAME_PREF, deviceName)
                                    apply()
                                }

                                // Inicializar WebSocket después de cargar dispositivos
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    initWebSocket()
                                }
                            } else {
                                binding.textDeviceInfo.text = "No hay vehículos asociados"
                                binding.textDeviceInfo.visibility = View.VISIBLE
                            }
                        }
                    }
                } else {
                    Log.e("FetchDevices", "Error response: ${response.code}")
                    requireActivity().runOnUiThread {
                        Toast.makeText(
                            requireContext(),
                            "Error al obtener dispositivos: ${response.code}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("FetchDevices", "Error", e)
                requireActivity().runOnUiThread {
                    Toast.makeText(
                        requireContext(),
                        "Error de conexión: ${e.localizedMessage}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun showAssociateDeviceDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_associate_device, null)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Asociar vehículo")
            .setMessage("Ingresa el Unique ID del dispositivo GPS\n(Ejemplo: 123456789012345)")
            .setView(dialogView)
            .setPositiveButton("Asociar") { _, _ ->
                val deviceUniqueId = dialogView.findViewById<EditText>(R.id.editDeviceId).text.toString().trim()
                val deviceName = dialogView.findViewById<EditText>(R.id.editDeviceName).text.toString().trim()

                if (deviceUniqueId.isNotEmpty() && deviceName.isNotEmpty()) {
                    associateDevice(deviceUniqueId, deviceName)
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Ingresa un Unique ID válido y un nombre",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNeutralButton("Ver Dispositivos") { _, _ ->
                // Mostrar dispositivos disponibles
                showAvailableDevices()
            }
            .setNegativeButton("Cancelar", null)
            .create()

        dialog.show()
    }

    private fun showAvailableDevices() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder()
                    .url("https://carefully-arriving-shepherd.ngrok-free.app/api/traccar/devices")
                    .get()
                    .addHeader("Authorization", "Bearer $JWT_TOKEN")
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    response.body?.string()?.let { responseBody ->
                        val jsonObject = JSONObject(responseBody)
                        val devices = jsonObject.getJSONArray("devices")

                        val deviceList = StringBuilder("Dispositivos disponibles:\n\n")
                        for (i in 0 until devices.length()) {
                            val device = devices.getJSONObject(i)
                            deviceList.append("• ${device.getString("name")}\n")
                            deviceList.append("  UniqueID: ${device.getString("uniqueId")}\n")
                            deviceList.append("  Estado: ${device.optString("status", "N/A")}\n\n")
                        }

                        requireActivity().runOnUiThread {
                            AlertDialog.Builder(requireContext())
                                .setTitle("Dispositivos en Traccar")
                                .setMessage(deviceList.toString())
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ShowDevices", "Error", e)
            }
        }
    }

    private fun loadSafeZone() {
        val lat = sharedPreferences.getString(PREF_SAFEZONE_LAT, null)
        val lon = sharedPreferences.getString(PREF_SAFEZONE_LON, null)
        val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1) // Obtener deviceId

        if (lat != null && lon != null) {
            safeZone = GeoPoint(lat.toDouble(), lon.toDouble())

            // Llamar con deviceId
            if (deviceId != -1) {
                addVehicleMarker(safeZone!!, deviceId)
            } else {
                // Si no hay deviceId guardado, usar -1 como fallback
                addVehicleMarker(safeZone!!, -1)
            }

            Polygon().apply {
                points = Polygon.pointsAsCircle(safeZone!!, 15.0)
                fillColor = 0x22FF0000
                strokeColor = Color.RED
                strokeWidth = 2f
                title = "Zona Segura - Radio 15m"
                map.overlays.add(this)
            }

            map.controller.animateTo(safeZone!!)
            map.invalidate()

            binding.buttonZonaSegura.text = "Zona Segura Establecida ✓"
            binding.buttonZonaSegura.setBackgroundColor(
                ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark)
            )
        }
    }

    private fun fetchSafeZoneFromServer() {
        val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)
        if (deviceId == -1) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder()
                    .url("https://carefully-arriving-shepherd.ngrok-free.app/api/safezone?deviceId=$deviceId")
                    .get()
                    .addHeader("Authorization", "Bearer $JWT_TOKEN")
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    response.body?.string()?.let { responseBody ->
                        val jsonObject = JSONObject(responseBody)
                        val lat = jsonObject.getDouble("latitude")
                        val lon = jsonObject.getDouble("longitude")

                        sharedPreferences.edit().apply {
                            putString(PREF_SAFEZONE_LAT, lat.toString())
                            putString(PREF_SAFEZONE_LON, lon.toString())
                            apply()
                        }

                        requireActivity().runOnUiThread {
                            safeZone = GeoPoint(lat, lon)
                            // Pasar deviceId al método
                            addVehicleMarker(safeZone!!, deviceId)

                            binding.buttonZonaSegura.text = "Zona Segura Establecida ✓"
                            binding.buttonZonaSegura.setBackgroundColor(
                                ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark)
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("SafeZone", "Error cargando zona del servidor", e)
            }
        }
    }

    private fun sendSafeZoneToServer(latitude: Double, longitude: Double) {
        val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)
        if (deviceId == -1) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder()
                    .url("https://carefully-arriving-shepherd.ngrok-free.app/api/safezone")
                    .post(
                        FormBody.Builder()
                            .add("latitude", latitude.toString())
                            .add("longitude", longitude.toString())
                            .add("deviceId", deviceId.toString())
                            .build()
                    )
                    .addHeader("Authorization", "Bearer $JWT_TOKEN")
                    .build()

                val response = client.newCall(request).execute()
                Log.d("SafeZone", "Zona enviada al servidor: ${response.code}")
            } catch (e: Exception) {
                Log.e("SafeZone", "Error enviando zona al servidor", e)
            }
        }
    }

    // Método corregido para agregar marcador del vehículo
    private fun addVehicleMarker(position: GeoPoint, deviceId: Int) {
        vehicleMarker?.let { map.overlays.remove(it) }

        vehicleMarker = Marker(map).apply {
            this.position = position
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_vehicle)

            // Manejar caso donde deviceId puede ser -1 (zona segura sin dispositivo específico)
            title = if (deviceId != -1) {
                "Vehículo ID: $deviceId\nLat: ${"%.6f".format(position.latitude)}\nLon: ${"%.6f".format(position.longitude)}"
            } else {
                "Zona Segura\nLat: ${"%.6f".format(position.latitude)}\nLon: ${"%.6f".format(position.longitude)}"
            }

            snippet = if (deviceId != -1) {
                "Última posición conocida"
            } else {
                "Zona segura establecida"
            }

            // Hacer el marcador más visible
            setOnMarkerClickListener { marker, mapView ->
                Toast.makeText(requireContext(), marker.title, Toast.LENGTH_LONG).show()
                true
            }
        }

        map.overlays.add(vehicleMarker)
        map.invalidate()

        Log.d("VehicleMarker", "Marcador agregado en: ${position.latitude}, ${position.longitude} para dispositivo $deviceId")
    }

    private fun downloadOfflineMap() {
        val bbox = map.boundingBox
        val zoomMin = 10
        val zoomMax = 16

        CoroutineScope(Dispatchers.IO).launch {
            val tileDownloader = CacheManager(map)
            tileDownloader.downloadAreaAsync(
                requireContext(), bbox, zoomMin, zoomMax,
                object : CacheManager.CacheManagerCallback {
                    override fun setPossibleTilesInArea(total: Int) {
                        requireActivity().runOnUiThread {
                            Toast.makeText(
                                requireContext(),
                                "Preparando para descargar $total tiles",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    override fun onTaskComplete() {
                        requireActivity().runOnUiThread {
                            Toast.makeText(
                                requireContext(),
                                "Zona guardada para uso offline",
                                Toast.LENGTH_LONG
                            ).show()
                            val prefs = requireContext().getSharedPreferences("offline_zone", 0).edit()
                            prefs.putString("latNorth", bbox.latNorth.toString())
                            prefs.putString("latSouth", bbox.latSouth.toString())
                            prefs.putString("lonEast", bbox.lonEast.toString())
                            prefs.putString("lonWest", bbox.lonWest.toString())
                            prefs.apply()
                        }
                    }

                    override fun onTaskFailed(errors: Int) {
                        requireActivity().runOnUiThread {
                            Toast.makeText(
                                requireContext(),
                                "Error al descargar mapa",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }

                    override fun updateProgress(
                        progress: Int,
                        currentZoomLevel: Int,
                        zoomMin: Int,
                        zoomMax: Int
                    ) {
                        requireActivity().runOnUiThread {
                            binding.progressBarDownload?.let { progressBar ->
                                progressBar.max = 100
                                progressBar.progress = progress
                                progressBar.visibility = View.VISIBLE
                            }
                        }
                    }

                    override fun downloadStarted() {
                        requireActivity().runOnUiThread {
                            Toast.makeText(
                                requireContext(),
                                "Descarga de mapa iniciada",
                                Toast.LENGTH_SHORT
                            ).show()
                            binding.progressBarDownload?.visibility = View.VISIBLE
                        }
                    }
                }
            )
        }
    }

    private fun enableMyLocation() {
        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_REQUEST_CODE
            )
            return
        }

        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(requireContext()), map).apply {
            enableMyLocation()
            enableFollowLocation()

            ContextCompat.getDrawable(requireContext(), R.drawable.ic_my_location)?.let { drawable ->
                val bitmap = if (drawable is BitmapDrawable) {
                    drawable.bitmap
                } else {
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

                val desiredSize = (48 * resources.displayMetrics.density).toInt()
                val scaledBitmap = Bitmap.createScaledBitmap(bitmap, desiredSize, desiredSize, true)
                setPersonIcon(scaledBitmap)
            } ?: run {
                setPersonIcon(
                    BitmapFactory.decodeResource(
                        resources,
                        org.osmdroid.library.R.drawable.ic_menu_mylocation
                    )
                )
            }

            map.overlays.add(this)
        }

        locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationListener = LocationListener { location ->
            val currentLocation = GeoPoint(location.latitude, location.longitude)
            map.controller.animateTo(currentLocation)
        }

        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            MIN_TIME_BETWEEN_UPDATES,
            MIN_DISTANCE_CHANGE_FOR_UPDATES,
            locationListener!!
        )
    }

    private fun cleanupResources() {
        closeWebSocket()

        locationListener?.let {
            try {
                locationManager.removeUpdates(it)
            } catch (e: Exception) {
                Log.e("Location", "Error removing location updates", e)
            }
        }
        locationListener = null

        try {
            myLocationOverlay?.disableMyLocation()
        } catch (e: Exception) {
            Log.e("Location", "Error disabling location overlay", e)
        }
        myLocationOverlay = null

        updateJob.cancel()
    }

    // Método corregido para verificar dispositivo asociado
    private fun hasAssociatedDevice(): Boolean {
        val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)
        val deviceName = sharedPreferences.getString(DEVICE_NAME_PREF, null)
        val deviceUniqueId = sharedPreferences.getString(DEVICE_UNIQUE_ID_PREF, null)

        Log.d("DeviceCheck", "Verificando dispositivo - ID: $deviceId, Name: $deviceName, UniqueID: $deviceUniqueId")

        return deviceId != -1 && !deviceName.isNullOrEmpty() && !deviceUniqueId.isNullOrEmpty()
    }
    // Método corregido para mostrar dispositivos disponibles y permitir selección
    private fun enterSafeZoneSetupMode() {
        if (!hasAssociatedDevice()) {
            Toast.makeText(requireContext(), "Primero debes asociar un vehículo", Toast.LENGTH_SHORT).show()
            return
        }

        // Mostrar diálogo para seleccionar dispositivo
        showDeviceSelectionForSafeZone()
    }

    private fun showDeviceSelectionForSafeZone() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Obtener dispositivos asociados del usuario
                val userDevicesRequest = Request.Builder()
                    .url("https://carefully-arriving-shepherd.ngrok-free.app/api/user/devices")
                    .get()
                    .addHeader("Authorization", "Bearer $JWT_TOKEN")
                    .build()

                val userDevicesResponse = client.newCall(userDevicesRequest).execute()

                if (userDevicesResponse.isSuccessful) {
                    val responseBody = userDevicesResponse.body?.string()
                    val devicesArray = JSONArray(responseBody)

                    if (devicesArray.length() == 0) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "No tienes dispositivos asociados", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }

                    // Crear lista de dispositivos para el diálogo
                    val deviceNames = Array(devicesArray.length()) { i ->
                        val device = devicesArray.getJSONObject(i)
                        "${device.getString("name")} (ID: ${device.getInt("id")})"
                    }

                    val deviceIds = Array(devicesArray.length()) { i ->
                        devicesArray.getJSONObject(i).getInt("id")
                    }

                    withContext(Dispatchers.Main) {
                        AlertDialog.Builder(requireContext())
                            .setTitle("Seleccionar vehículo para zona segura")
                            .setItems(deviceNames) { _, which ->
                                val selectedDeviceId = deviceIds[which]
                                val selectedDeviceName = deviceNames[which]

                                // Guardar el dispositivo seleccionado como activo
                                sharedPreferences.edit().apply {
                                    putInt(DEVICE_ID_PREF, selectedDeviceId)
                                    putString(DEVICE_NAME_PREF, selectedDeviceName.substringBefore(" (ID:"))
                                    apply()
                                }

                                // Proceder a establecer zona segura
                                establishSafeZoneForDevice(selectedDeviceId)
                            }
                            .setNegativeButton("Cancelar", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                Log.e("DeviceSelection", "Error", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error al cargar dispositivos: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun establishSafeZoneForDevice(deviceId: Int) {
        binding.buttonZonaSegura.text = "Obteniendo ubicación del vehículo..."
        binding.buttonZonaSegura.isEnabled = false

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder()
                    .url("https://carefully-arriving-shepherd.ngrok-free.app/api/last-position?deviceId=$deviceId")
                    .get()
                    .addHeader("Authorization", "Bearer $JWT_TOKEN")
                    .build()

                val response = withTimeoutOrNull(15000) {
                    client.newCall(request).execute()
                } ?: run {
                    withContext(Dispatchers.Main) {
                        binding.buttonZonaSegura.text = "Establecer Zona Segura"
                        binding.buttonZonaSegura.isEnabled = true
                        Toast.makeText(
                            requireContext(),
                            "Tiempo de espera agotado al obtener ubicación",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }

                if (response.isSuccessful) {
                    response.body?.string()?.let { responseBody ->
                        Log.d("SafeZone", "Response: $responseBody")
                        val jsonObject = JSONObject(responseBody)
                        val lat = jsonObject.getDouble("latitude")
                        val lon = jsonObject.getDouble("longitude")
                        val vehiclePosition = GeoPoint(lat, lon)

                        withContext(Dispatchers.Main) {
                            safeZone = vehiclePosition

                            // Limpiar overlays anteriores
                            clearPreviousOverlays()

                            // Agregar nuevo marcador del vehículo
                            addVehicleMarker(vehiclePosition, deviceId)

                            // Dibujar zona segura
                            val safeZonePolygon = Polygon().apply {
                                points = Polygon.pointsAsCircle(vehiclePosition, 15.0)
                                fillColor = 0x22FF0000
                                strokeColor = Color.RED
                                strokeWidth = 2f
                                title = "Zona Segura - Radio 15m"
                            }
                            map.overlays.add(safeZonePolygon)

                            // Guardar zona segura
                            sharedPreferences.edit().apply {
                                putString(PREF_SAFEZONE_LAT, lat.toString())
                                putString(PREF_SAFEZONE_LON, lon.toString())
                                apply()
                            }

                            binding.buttonZonaSegura.text = "Zona Segura Establecida ✓"
                            binding.buttonZonaSegura.setBackgroundColor(
                                ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark)
                            )
                            binding.buttonZonaSegura.isEnabled = true

                            map.controller.animateTo(vehiclePosition)
                            map.controller.setZoom(16.0)
                            map.invalidate()

                            Toast.makeText(
                                requireContext(),
                                "Zona segura establecida para vehículo ID: $deviceId\nRadio: 15 metros",
                                Toast.LENGTH_LONG
                            ).show()

                            sendSafeZoneToServer(lat, lon)

                            // Reinicializar WebSocket con el dispositivo correcto
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                initWebSocket()
                            }
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        binding.buttonZonaSegura.text = "Establecer Zona Segura"
                        binding.buttonZonaSegura.isEnabled = true

                        val errorMsg = when (response.code) {
                            404 -> "No se encontraron posiciones para este vehículo.\n¿Está encendido y transmitiendo?"
                            401 -> "Token de autenticación inválido"
                            else -> "Error al obtener ubicación: ${response.code}"
                        }

                        Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("SafeZone", "Error", e)
                withContext(Dispatchers.Main) {
                    binding.buttonZonaSegura.text = "Establecer Zona Segura"
                    binding.buttonZonaSegura.isEnabled = true
                    Toast.makeText(
                        requireContext(),
                        "Error de conexión: ${e.localizedMessage}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun clearPreviousOverlays() {
        // Remover marcadores de vehículos anteriores
        vehicleMarker?.let {
            map.overlays.remove(it)
            vehicleMarker = null
        }

        // Remover polígonos de zona segura anteriores
        map.overlays.removeAll { overlay ->
            overlay is Polygon && overlay.title?.contains("Zona Segura") == true
        }
    }

    override fun onResume() {
        super.onResume()
        shouldReconnect = true
        // Solo inicializar WebSocket si hay dispositivo asociado
        val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)
        if (deviceId != -1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            initWebSocket()
        }
    }

    override fun onPause() {
        super.onPause()
        shouldReconnect = false
        handler.removeCallbacks(reconnectRunnable)
        closeWebSocket()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        shouldReconnect = false
        handler.removeCallbacks(reconnectRunnable)
        cleanupResources()
        _binding = null
    }
}