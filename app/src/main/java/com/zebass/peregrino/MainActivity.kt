package com.zebass.peregrino

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.work.Configuration
import androidx.work.WorkManager
import com.zebass.peregrino.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPreferences: SharedPreferences

    companion object {
        private const val TAG = "MainActivity"
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "🚀 MainActivity created")

        sharedPreferences = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkAndRequestNotificationPermission()
        initializeWorkManager()
        scheduleSyncWork()
        setupNavigation()
        requestLocationPermissions()
    }

    private fun requestLocationPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permissions = arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            )

            requestPermissions(permissions, 1001)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                Log.d(TAG, "✅ Todos los permisos de ubicación concedidos")
            } else {
                Log.e(TAG, "❌ Permisos de ubicación denegados - GPS no funcionará")
            }
        }
        when (requestCode) {
            NOTIFICATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "✅ Permiso de notificaciones concedido")
                } else {
                    Log.w(TAG, "❌ Permiso de notificaciones denegado")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (!shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                            showPermissionSettingsDialog()
                        }
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    Log.d(TAG, "✅ Permiso de notificaciones ya concedido")
                }

                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    showPermissionExplanationDialog()
                }

                else -> {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        NOTIFICATION_PERMISSION_REQUEST_CODE
                    )
                }
            }
        }
    }

    private fun showPermissionExplanationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permiso necesario")
            .setMessage("Este permiso es necesario para mostrarte notificaciones importantes sobre el estado del rastreo GPS.")
            .setPositiveButton("Entendido") { _, _ ->
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE
                )
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showPermissionSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permiso requerido")
            .setMessage("Has denegado el permiso permanentemente. Por favor, habilítalo manualmente en Configuración -> Aplicaciones -> [Esta app] -> Permisos")
            .setPositiveButton("Abrir configuración") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun initializeWorkManager() {
        try {
            WorkManager.getInstance(this)
            Log.d(TAG, "✅ WorkManager ya está inicializado")
        } catch (e: IllegalStateException) {
            val config = Configuration.Builder()
                .setMinimumLoggingLevel(Log.INFO)
                .build()
            WorkManager.initialize(this, config)
            Log.d(TAG, "✅ WorkManager inicializado")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error inicializando WorkManager", e)
        }
    }

    private fun scheduleSyncWork() {
        lifecycleScope.launch {
            try {
                SyncWorker.schedulePeriodicSync(applicationContext, 15)
                Log.d(TAG, "✅ Sincronización periódica programada")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error programando trabajo de sincronización", e)
            }
        }
    }

    private fun setupNavigation() {
        try {
            val navController = findNavController(R.id.nav_host_fragment_content_main)

            navController.addOnDestinationChangedListener { _, destination, _ ->
                Log.d(TAG, "📍 Navigation destination changed to: ${destination.id}")

                when (destination.id) {
                    R.id.FirstFragment -> {
                        Log.d(TAG, "📍 Now in FirstFragment (Login)")
                        supportActionBar?.hide()
                    }
                    R.id.SecondFragment -> {
                        Log.d(TAG, "📍 Now in SecondFragment (Main)")
                        supportActionBar?.show()
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error setting up navigation", e)
        }
    }

    // ✅ MEJORAR MANEJO DE BACK BUTTON PARA NO CERRAR WEBSOCKET
    override fun onBackPressed() {
        try {
            val navController = findNavController(R.id.nav_host_fragment_content_main)

            when (navController.currentDestination?.id) {
                R.id.FirstFragment -> {
                    Log.d(TAG, "⬅️ Back pressed in FirstFragment - exiting app")
                    finishAffinity()
                }
                R.id.SecondFragment -> {
                    Log.d(TAG, "⬅️ Back pressed in SecondFragment - showing logout confirmation")
                    showLogoutConfirmation()
                }
                else -> {
                    super.onBackPressed()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling back press", e)
            super.onBackPressed()
        }
    }

    // ✅ NUEVA FUNCIÓN: MOSTRAR CONFIRMACIÓN DE LOGOUT SIN CERRAR WEBSOCKET
    private fun showLogoutConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Cerrar Sesión")
            .setMessage("¿Estás seguro que deseas cerrar la sesión?\n\nNota: El tracking GPS se mantendrá en segundo plano si está activo.")
            .setPositiveButton("Cerrar Sesión") { _, _ ->
                performLogout()
            }
            .setNegativeButton("Cancelar") { dialog, _ ->
                dialog.dismiss()
            }
            .setNeutralButton("Minimizar App") { _, _ ->
                moveTaskToBack(true)
            }
            .show()
    }

    private fun performLogout() {
        try {
            Log.d(TAG, "🚪 Performing logout from MainActivity")

            // ✅ LIMPIAR SESIÓN PERO MANTENER WEBSOCKET SI HAY TRACKING ACTIVO
            with(sharedPreferences.edit()) {
                // ✅ MANTENER ALGUNOS DATOS CRÍTICOS PARA EL SERVICIO
                val hasActiveTracking = sharedPreferences.getBoolean("is_sharing_location", false)
                val deviceId = sharedPreferences.getString("associated_device_unique_id", null)

                clear()

                // ✅ RESTAURAR DATOS CRÍTICOS SI HAY TRACKING ACTIVO
                if (hasActiveTracking && !deviceId.isNullOrEmpty()) {
                    putBoolean("is_sharing_location", true)
                    putString("associated_device_unique_id", deviceId)
                    Log.d(TAG, "📱 Keeping essential data for active location sharing")
                }

                apply()
            }

            // ✅ NAVEGAR A LOGIN
            val navController = findNavController(R.id.nav_host_fragment_content_main)
            navController.popBackStack(R.id.FirstFragment, false)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error performing logout", e)
            recreate()
        }
    }

    // ✅ MEJORAR onResume PARA MANTENER CONTINUIDAD DEL WEBSOCKET
    override fun onResume() {
        super.onResume()

        try {
            val token = sharedPreferences.getString("jwt_token", null)
            val navController = findNavController(R.id.nav_host_fragment_content_main)

            Log.d(TAG, "▶️ MainActivity resumed - Token exists: ${token != null}, Current: ${navController.currentDestination?.id}")

            // ✅ VERIFICAR CONSISTENCIA DE NAVEGACIÓN
            if (token == null && navController.currentDestination?.id == R.id.SecondFragment) {
                Log.w(TAG, "⚠️ No token but in SecondFragment - redirecting to login")
                navController.popBackStack(R.id.FirstFragment, false)
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in onResume", e)
        }
    }

    // ✅ NUEVA FUNCIÓN: MANEJO DE onPause PARA WEBSOCKET PERSISTENTE
    override fun onPause() {
        super.onPause()
        Log.d(TAG, "⏸️ MainActivity paused")

        // ✅ NO hacer nada especial aquí para mantener WebSocket vivo
        // El WebSocket debe continuar funcionando en background
    }

    // ✅ NUEVA FUNCIÓN: MANEJO DE onStop PARA WEBSOCKET PERSISTENTE
    override fun onStop() {
        super.onStop()
        Log.d(TAG, "⏹️ MainActivity stopped")

        // ✅ App va a background pero WebSocket debe mantenerse
        // Solo log para debug, no cerrar nada
    }

    // ✅ NUEVA FUNCIÓN: MANEJO DE onDestroy PARA CLEANUP FINAL
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "🗑️ MainActivity destroyed")

        // ✅ SOLO hacer cleanup final si la app se está cerrando completamente
        if (isFinishing) {
            Log.d(TAG, "🛑 App finishing - final cleanup")

            // ✅ Aquí se podría hacer cleanup final si es necesario
            // Pero generalmente el WebSocket debe sobrevivir para servicios en background
        }
    }

    // ✅ FUNCIÓN AUXILIAR PARA VERIFICAR SI HAY SERVICIOS ACTIVOS
    private fun hasActiveBackgroundServices(): Boolean {
        val hasLocationSharing = sharedPreferences.getBoolean("is_sharing_location", false)
        val hasActiveTracking = sharedPreferences.contains("associated_device_unique_id")

        return hasLocationSharing || hasActiveTracking
    }

    // ✅ FUNCIÓN PARA MOSTRAR ESTADO DE SERVICIOS EN BACKGROUND
    private fun showBackgroundServicesStatus() {
        val hasLocationSharing = sharedPreferences.getBoolean("is_sharing_location", false)
        val deviceId = sharedPreferences.getString("associated_device_unique_id", null)

        val statusMessage = buildString {
            appendLine("📱 Estado de Servicios en Background:")
            appendLine("")

            if (hasLocationSharing) {
                appendLine("🌍 ✅ Compartir ubicación: ACTIVO")
            } else {
                appendLine("🌍 ⭕ Compartir ubicación: INACTIVO")
            }

            if (!deviceId.isNullOrEmpty()) {
                appendLine("📍 ✅ Tracking GPS: ACTIVO")
                appendLine("   📱 Dispositivo: $deviceId")
            } else {
                appendLine("📍 ⭕ Tracking GPS: INACTIVO")
            }

            appendLine("")
            if (hasLocationSharing || !deviceId.isNullOrEmpty()) {
                appendLine("ℹ️ Los servicios continuarán funcionando en segundo plano")
            } else {
                appendLine("ℹ️ No hay servicios activos en segundo plano")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Estado de Servicios")
            .setMessage(statusMessage)
            .setPositiveButton("Entendido", null)
            .show()
    }

    // ✅ AGREGAR MENÚ DE OPCIONES SI ES NECESARIO
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_services_status -> {
                showBackgroundServicesStatus()
                true
            }
            R.id.action_force_reconnect -> {
                // ✅ Enviar broadcast para forzar reconexión WebSocket
                val intent = Intent("com.peregrino.FORCE_WEBSOCKET_RECONNECT")
                sendBroadcast(intent)

                Toast.makeText(this, "🔄 Forzando reconexión WebSocket...", Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}