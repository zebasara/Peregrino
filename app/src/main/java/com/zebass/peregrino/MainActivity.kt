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
        Log.d(TAG, "MainActivity created")
        sharedPreferences = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkAndRequestNotificationPermission()
        initializeWorkManager()
        scheduleSyncWork()
        // ✅ CONFIGURAR NAVEGACIÓN SEGURA
        setupNavigation()


    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            NOTIFICATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Permiso de notificaciones concedido")
                } else {
                    Log.w(TAG, "Permiso de notificaciones denegado")
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
                    Log.d(TAG, "Permiso de notificaciones ya concedido")
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
            Log.d(TAG, "WorkManager ya está inicializado")
        } catch (e: IllegalStateException) {
            val config = Configuration.Builder()
                .setMinimumLoggingLevel(Log.INFO)
                .build()
            WorkManager.initialize(this, config)
            Log.d(TAG, "WorkManager inicializado")
        } catch (e: Exception) {
            Log.e(TAG, "Error inicializando WorkManager", e)
        }
    }

    private fun scheduleSyncWork() {
        lifecycleScope.launch {
            try {
                SyncWorker.schedulePeriodicSync(applicationContext, 15)
                Log.d(TAG, "Sincronización periódica programada")
            } catch (e: Exception) {
                Log.e(TAG, "Error programando trabajo de sincronización", e)
            }
        }
    }
    // ✅ CONFIGURAR NAVEGACIÓN
    private fun setupNavigation() {
        try {
            val navController = findNavController(R.id.nav_host_fragment_content_main)

            // ✅ LISTENER PARA CAMBIOS DE DESTINO
            navController.addOnDestinationChangedListener { _, destination, _ ->
                Log.d(TAG, "Navigation destination changed to: ${destination.id}")

                when (destination.id) {
                    R.id.FirstFragment -> {
                        Log.d(TAG, "Now in FirstFragment (Login)")
                        // Ocultar action bar si es necesario
                        supportActionBar?.hide()
                    }
                    R.id.SecondFragment -> {
                        Log.d(TAG, "Now in SecondFragment (Main)")
                        // Mostrar action bar si es necesario
                        supportActionBar?.show()
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up navigation", e)
        }
    }

    // ✅ MANEJAR BACK BUTTON EN ACTIVITY
    override fun onBackPressed() {
        try {
            val navController = findNavController(R.id.nav_host_fragment_content_main)

            when (navController.currentDestination?.id) {
                R.id.FirstFragment -> {
                    // En login, salir de la app
                    Log.d(TAG, "Back pressed in FirstFragment - exiting app")
                    finishAffinity()
                }
                R.id.SecondFragment -> {
                    // En main, hacer logout
                    Log.d(TAG, "Back pressed in SecondFragment - logging out")
                    performLogout()
                }
                else -> {
                    // Default behavior
                    super.onBackPressed()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling back press", e)
            super.onBackPressed()
        }
    }

    // ✅ FUNCIÓN DE LOGOUT DESDE ACTIVITY
    private fun performLogout() {
        try {
            Log.d(TAG, "Performing logout from MainActivity")

            // Limpiar sesión
            with(sharedPreferences.edit()) {
                clear()
                apply()
            }

            // Navegar a FirstFragment
            val navController = findNavController(R.id.nav_host_fragment_content_main)
            navController.popBackStack(R.id.FirstFragment, false)

        } catch (e: Exception) {
            Log.e(TAG, "Error performing logout", e)

            // Reiniciar activity
            recreate()
        }
    }
    // ✅ VERIFICAR SESIÓN AL RESUMIR ACTIVITY
    override fun onResume() {
        super.onResume()

        try {
            val token = sharedPreferences.getString("jwt_token", null)
            val navController = findNavController(R.id.nav_host_fragment_content_main)

            Log.d(TAG, "MainActivity resumed - Token exists: ${token != null}, Current: ${navController.currentDestination?.id}")

            // ✅ VERIFICAR CONSISTENCIA DE NAVEGACIÓN
            if (token == null && navController.currentDestination?.id == R.id.SecondFragment) {
                Log.w(TAG, "No token but in SecondFragment - redirecting to login")
                navController.popBackStack(R.id.FirstFragment, false)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in onResume", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MainActivity destruida")
    }
}