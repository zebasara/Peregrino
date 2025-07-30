
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
    import android.view.MotionEvent
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
    import androidx.annotation.RequiresPermission
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
    private var safeZoneMarker: Marker? = null
    private var isSettingSafeZone = false
    private var vehicleMarker: Marker? = null
    private lateinit var sharedPreferences: SharedPreferences
    private val args: SecondFragmentArgs by navArgs()
    private val updateJob = SupervisorJob()
    private var webSocketClient: WebSocketClient? = null
    private var shouldReconnect = true
    private val reconnectDelay = 5000L // 5 segundos
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
        private const val MIN_TIME_BETWEEN_UPDATES = 5000L // 5 segundos
        private const val MIN_DISTANCE_CHANGE_FOR_UPDATES = 10f // 10 metros
        private const val DEVICE_ID_PREF = "associated_device_id"
        private const val DEVICE_NAME_PREF = "associated_device_name"
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

        // Obtener argumentos usando Safe Args
        val userEmail = args.userEmail
        JWT_TOKEN = args.jwtToken

        binding.textUser.text = "Bienvenido: $userEmail"
        binding.buttonLogout.setOnClickListener {
            // Limpiar preferencias
            with(sharedPreferences.edit()) {
                remove("jwt_token")
                remove("user_email")
                apply()
            }

            // Navegar de vuelta al login
            findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment)
        }


        map = binding.mapView
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)

        // Cargar primero la zona local
        loadSafeZone()

        // Luego verificar con el servidor (que puede sobrescribir la local si es diferente)
        fetchSafeZoneFromServer()

        // Replace your current map.setOnClickListener with this:
        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                return false
            }
        })

        map.setOnTouchListener { _, event ->
            if (isSettingSafeZone && event.action == MotionEvent.ACTION_UP) {
                val geoPoint =
                    map.projection.fromPixels(event.x.toInt(), event.y.toInt()) as GeoPoint
                safeZoneMarker?.position = geoPoint
                safeZone = geoPoint
                map.invalidate()
            }
            false
        }

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
        binding.buttonMyLocation.setOnClickListener {
            enableMyLocation()
        }

        binding.buttonZonaSegura.setOnClickListener {
            if (!isSettingSafeZone) {
                enterSafeZoneSetupMode()
            } else {
                confirmSafeZone()
            }
        }
        binding.mapContainer.layoutParams.height = 600
        binding.mapContainer.setOnClickListener {
            // Expande el mapa
            binding.mapContainer.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            binding.mapContainer.requestLayout()
        }
        binding.buttonDescargarOffline.setOnClickListener {
            val bbox = map.boundingBox
            val zoomMin = 10
            val zoomMax = 16

            CoroutineScope(Dispatchers.IO).launch {
                val tileDownloader = CacheManager(map)
                tileDownloader.downloadAreaAsync(
                    requireContext(), bbox, zoomMin, zoomMax,
                    object : CacheManager.CacheManagerCallback {
                        override fun setPossibleTilesInArea(total: Int) {
                            // Información sobre el total de tiles a descargar
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
                                val prefs =
                                    requireContext().getSharedPreferences("offline_zone", 0).edit()
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


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            initWebSocket()
        }
        // Agrega un botón para asociar dispositivos
        binding.buttonAssociateDevice.setOnClickListener {
            showAssociateDeviceDialog()
        }

        // Cargar dispositivos asociados
        fetchAssociatedDevices()

        // Mostrar información del dispositivo si está guardada localmente
        val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)
        val deviceName = sharedPreferences.getString(DEVICE_NAME_PREF, null)

        if (deviceId != -1 && deviceName != null) {
            binding.textDeviceInfo.text = "Dispositivo: $deviceName (ID: $deviceId)"
        }
    }

    private fun initWebSocket() {
        val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)
        if (deviceId == -1) {
            activity?.runOnUiThread {
                Toast.makeText(context, "No hay dispositivo asociado", Toast.LENGTH_SHORT).show()
            }
            return
        }

        closeWebSocket() // Cerrar conexión existente si la hay

        webSocketClient = object : WebSocketClient(URI("wss://carefully-arriving-shepherd.ngrok-free.app/ws")) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                activity?.runOnUiThread {
                    Toast.makeText(context, "Conectado al servidor", Toast.LENGTH_SHORT).show()
                }

                // Enviar mensaje de suscripción
                val subscribeMsg = JSONObject().apply {
                    put("type", "SUBSCRIBE_DEVICE")
                    put("deviceId", deviceId)
                    put("token", JWT_TOKEN)
                }
                send(subscribeMsg.toString())
            }

            override fun onMessage(message: String?) {
                try {
                    val data = JSONObject(message ?: return)
                    if (data.getString("type") == "POSITION_UPDATE") {
                        val pos = data.getJSONObject("data")
                        activity?.runOnUiThread {
                            updateVehiclePosition(
                                deviceId,
                                GeoPoint(pos.getDouble("lat"), pos.getDouble("lon")))
                        }
                    }
                } catch (e: Exception) {
                    Log.e("WebSocket", "Error parsing message", e)
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                activity?.runOnUiThread {
                    Toast.makeText(context, "Desconectado del servidor", Toast.LENGTH_SHORT).show()
                }
                scheduleReconnect()
            }

            override fun onError(ex: Exception?) {
                Log.e("WebSocket", "Error", ex)
                scheduleReconnect()
            }
        }.apply {
            connect()
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

    private fun cleanupResources() {
        // 1. Cerrar WebSocket
        closeWebSocket()

        // 2. Detener actualizaciones de ubicación
        locationListener?.let {
            try {
                locationManager.removeUpdates(it)
            } catch (e: Exception) {
                Log.e("Location", "Error removing location updates", e)
            }
        }
        locationListener = null

        // 3. Desactivar overlay de ubicación
        try {
            myLocationOverlay?.disableMyLocation()
        } catch (e: Exception) {
            Log.e("Location", "Error disabling location overlay", e)
        }
        myLocationOverlay = null

        // 4. Cancelar corrutinas
        updateJob.cancel()
    }

    private fun updateVehiclePosition(deviceId: Int, position: GeoPoint) {
        // Actualizar o crear marcador del vehículo
        vehicleMarker?.let {
            it.position = position
            it.title =
                "Vehículo $deviceId (${"%.1f".format(position.latitude)}, ${"%.1f".format(position.longitude)})"
        } ?: run {
            vehicleMarker = Marker(map).apply {
                this.position = position
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_vehicle)
                title = "Vehículo $deviceId"
                map.overlays.add(this)
            }
        }

        map.invalidate()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @RequiresPermission(Manifest.permission.VIBRATE)
    private fun triggerAlarm(deviceId: Int, distance: Double) {
        // Personaliza esta función para tu lógica de alarma
        val alarmMsg = "¡ALARMA! Vehículo $deviceId se alejó ${"%.1f".format(distance)} metros"
        Toast.makeText(requireContext(), alarmMsg, Toast.LENGTH_LONG).show()

        // Opcional: Vibrar o reproducir sonido
        val vibrator = context?.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // Método para asociar un dispositivo
    private fun associateDevice(deviceId: Int, name: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("name", name)
                }

                val requestBody = json.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

                val request = Request.Builder()
                    .url("https://carefully-arriving-shepherd.ngrok-free.app/api/user/devices")
                    .post(requestBody)
                    .addHeader("Authorization", "Bearer $JWT_TOKEN")
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    // Guardar localmente
                    sharedPreferences.edit().apply {
                        putInt(DEVICE_ID_PREF, deviceId)
                        putString(DEVICE_NAME_PREF, name)
                        apply()
                    }

                    requireActivity().runOnUiThread {
                        Toast.makeText(
                            requireContext(),
                            "Dispositivo $name asociado correctamente",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    requireActivity().runOnUiThread {
                        Toast.makeText(
                            requireContext(),
                            "Error al asociar dispositivo: ${response.code}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
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

    // Método para obtener dispositivos asociados
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
                        val jsonArray = JSONArray(responseBody)

                        requireActivity().runOnUiThread {
                            if (jsonArray.length() > 0) {
                                val firstDevice = jsonArray.getJSONObject(0)
                                val deviceId = firstDevice.getInt("device_id")
                                val deviceName = firstDevice.getString("name")

                                binding.textDeviceInfo.text =
                                    "Vehículo: $deviceName (ID: $deviceId)"

                                sharedPreferences.edit().apply {
                                    putInt(DEVICE_ID_PREF, deviceId)
                                    putString(DEVICE_NAME_PREF, deviceName)
                                    apply()
                                }
                            } else {
                                binding.textDeviceInfo.text = "No hay vehículos asociados"
                            }
                        }
                    }
                } else {
                    requireActivity().runOnUiThread {
                        Toast.makeText(
                            requireContext(),
                            "Error al obtener dispositivos: ${response.code}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
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
            .setView(dialogView)
            .setPositiveButton("Asociar") { _, _ ->
                val deviceId = dialogView.findViewById<EditText>(R.id.editDeviceId).text.toString()
                    .toIntOrNull()
                val deviceName =
                    dialogView.findViewById<EditText>(R.id.editDeviceName).text.toString()

                if (deviceId != null && deviceName.isNotEmpty()) {
                    associateDevice(deviceId, deviceName)
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Ingresa un ID válido y un nombre",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .create()

        dialog.show()
    }

    private fun enterSafeZoneSetupMode() {
        isSettingSafeZone = true
        binding.buttonZonaSegura.text = "Fijar Zona Segura"
        binding.buttonZonaSegura.setBackgroundColor(
            ContextCompat.getColor(
                requireContext(),
                R.color.orange
            )
        )

        // Configurar el marcador de zona segura en el centro del mapa
        setupSafeZoneMarker(map.mapCenter as GeoPoint)
    }

    private fun setupSafeZoneMarker(initialPosition: GeoPoint) {
        // Eliminar marcadores anteriores
        safeZoneMarker?.let { map.overlays.remove(it) }
        vehicleMarker?.let { map.overlays.remove(it) }

        // Crear nuevo marcador
        safeZoneMarker = Marker(map).apply {
            position = initialPosition
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_drop_marker_red)
            isDraggable = true // Permitir que el marcador sea arrastrable
            title = "Arrastra para mover la zona segura"

            setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                override fun onMarkerDragStart(marker: Marker) {
                    // Opcional: puedes hacer algo cuando empieza el arrastre
                }

                override fun onMarkerDrag(marker: Marker) {
                    // Opcional: puedes hacer algo durante el arrastre
                }

                override fun onMarkerDragEnd(marker: Marker) {
                    // Actualizar la posición cuando termina el arrastre
                    safeZone = marker.position
                    marker.title = "Zona segura - Posición actualizada"
                    map.invalidate()
                }
            })
        }

        map.overlays.add(safeZoneMarker)
        map.controller.animateTo(safeZoneMarker?.position)

        // Mostrar instrucciones al usuario
        Toast.makeText(
            requireContext(),
            "Arrastra el marcador para mover la zona segura",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun confirmSafeZone() {
        if (safeZoneMarker == null) return

        isSettingSafeZone = false
        binding.buttonZonaSegura.text = "Zona Segura Establecida"
        binding.buttonZonaSegura.setBackgroundColor(
            ContextCompat.getColor(
                requireContext(),
                R.color.green
            )
        )

        safeZone = safeZoneMarker?.position

        // Guardar localmente
        sharedPreferences.edit().apply {
            putString(PREF_SAFEZONE_LAT, safeZone?.latitude.toString())
            putString(PREF_SAFEZONE_LON, safeZone?.longitude.toString())
            apply()
        }

        safeZoneMarker?.let {
            map.overlays.remove(it)
            safeZoneMarker = null
        }

        Toast.makeText(
            requireContext(),
            "Zona segura establecida en: ${safeZone?.latitude}, ${safeZone?.longitude}",
            Toast.LENGTH_SHORT
        ).show()

        safeZone?.let { zone ->
            sendSafeZoneToServer(zone.latitude, zone.longitude)
            addVehicleMarker(zone)
        }

        map.invalidate()
        // Añadir visualización del radio
        Polygon().apply {
            points = Polygon.pointsAsCircle(safeZone!!, 15.0)
            fillColor = 0x22FF0000  // Rojo semitransparente
            strokeColor = Color.RED
            strokeWidth = 2f
            map.overlays.add(this)
        }
    }

    private fun loadSafeZone() {
        val lat = sharedPreferences.getString(PREF_SAFEZONE_LAT, null)
        val lon = sharedPreferences.getString(PREF_SAFEZONE_LON, null)

        if (lat != null && lon != null) {
            safeZone = GeoPoint(lat.toDouble(), lon.toDouble())
            addVehicleMarker(safeZone!!)
            binding.buttonZonaSegura.text = "Zona Segura Establecida"
            binding.buttonZonaSegura.setBackgroundColor(
                ContextCompat.getColor(
                    requireContext(),
                    R.color.green
                )
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

                        // Guardar localmente
                        sharedPreferences.edit().apply {
                            putString(PREF_SAFEZONE_LAT, lat.toString())
                            putString(PREF_SAFEZONE_LON, lon.toString())
                            apply()
                        }

                        requireActivity().runOnUiThread {
                            safeZone = GeoPoint(lat, lon)
                            addVehicleMarker(safeZone!!)
                            binding.buttonZonaSegura.text = "Zona Segura Establecida"
                            binding.buttonZonaSegura.setBackgroundColor(
                                ContextCompat.getColor(requireContext(), R.color.green)
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                // Silenciar errores, usaremos la zona local si hay problemas
                // Podrías agregar un Toast si quieres notificar al usuario
                // requireActivity().runOnUiThread {
                //     Toast.makeText(requireContext(), "No se pudo cargar zona del servidor", Toast.LENGTH_SHORT).show()
                // }
            }
        }
    }

    private fun sendSafeZoneToServer(latitude: Double, longitude: Double) {
        val deviceId = sharedPreferences.getInt(DEVICE_ID_PREF, -1)
        if (deviceId == -1) {
            Toast.makeText(
                requireContext(),
                "Primero asocia un vehículo",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
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

                if (!response.isSuccessful) {
                    requireActivity().runOnUiThread {
                        Toast.makeText(
                            requireContext(),
                            "Error al guardar zona segura en el servidor",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                requireActivity().runOnUiThread {
                    Toast.makeText(
                        requireContext(),
                        "Error de conexión al guardar zona: ${e.localizedMessage}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun addVehicleMarker(position: GeoPoint) {
        vehicleMarker?.let { map.overlays.remove(it) }

        vehicleMarker = Marker(map).apply {
            this.position = position
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_vehicle)
            title = "Vehículo estacionado"
        }
        map.overlays.add(vehicleMarker)
        map.invalidate()
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

        myLocationOverlay =
            MyLocationNewOverlay(GpsMyLocationProvider(requireContext()), map).apply {
                enableMyLocation()
                enableFollowLocation()

                // Configurar icono con manejo seguro
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_my_location)
                    ?.let { drawable ->
                        val bitmap = if (drawable is BitmapDrawable) {
                            drawable.bitmap
                        } else {
                            // Convertir otros tipos de Drawable a Bitmap
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

                        // Escalar el bitmap si es necesario (48x48 dp)
                        val desiredSize = (48 * resources.displayMetrics.density).toInt()
                        val scaledBitmap = Bitmap.createScaledBitmap(
                            bitmap,
                            desiredSize,
                            desiredSize,
                            true
                        )

                        setPersonIcon(scaledBitmap)
                    } ?: run {
                    // Usar icono por defecto si no se encuentra el drawable
                    setPersonIcon(
                        BitmapFactory.decodeResource(
                            resources,
                            org.osmdroid.library.R.drawable.ic_menu_mylocation
                        )
                    )
                }

                map.overlays.add(this)
            }

        locationManager =
            requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
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

    override fun onResume() {
        super.onResume()
        shouldReconnect = true
        initWebSocket()
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