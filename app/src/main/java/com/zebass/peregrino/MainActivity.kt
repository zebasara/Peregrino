package com.zebass.peregrino

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.work.Configuration
import androidx.work.WorkManager
import com.zebass.peregrino.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inicializar binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Configuración básica del NavController sin ActionBar
        val navController = findNavController(R.id.nav_host_fragment_content_main)

        // Inicializar WorkManager con configuración optimizada
        initializeWorkManager()

        // Programar sincronización periódica
        scheduleSyncWork()

        Log.d(TAG, "MainActivity created")
    }

    private fun initializeWorkManager() {
        try {
            // Verificar si WorkManager ya está inicializado
            val workManager = try {
                WorkManager.getInstance(this)
                Log.d(TAG, "WorkManager already initialized")
                return
            } catch (e: IllegalStateException) {
                // WorkManager no está inicializado, continuar con la inicialización
                null
            }

            // Configuración optimizada de WorkManager
            val config = Configuration.Builder()
                .setMinimumLoggingLevel(Log.INFO)
                .build()

            WorkManager.initialize(this, config)
            Log.d(TAG, "WorkManager initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing WorkManager", e)
        }
    }

    private fun scheduleSyncWork() {
        lifecycleScope.launch {
            try {
                // Programar sincronización periódica cada 15 minutos
                SyncWorker.schedulePeriodicSync(applicationContext, 15)
                Log.d(TAG, "Periodic sync scheduled")
            } catch (e: Exception) {
                Log.e(TAG, "Error scheduling sync work", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MainActivity destroyed")
    }
}