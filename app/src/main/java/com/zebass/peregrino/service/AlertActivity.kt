package com.zebass.peregrino.service

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.zebass.peregrino.R
import java.text.SimpleDateFormat
import java.util.*

class AlertActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ USAR LAYOUT SIMPLE EN LUGAR DE MATERIAL
        setContentView(R.layout.activity_alert)

        // ✅ CONFIGURAR VENTANA PARA MOSTRAR SOBRE PANTALLA BLOQUEADA
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        setupUI()
    }

    private fun setupUI() {
        // ✅ OBTENER DATOS DEL INTENT
        val distance = intent.getDoubleExtra("alert_distance", 0.0)
        val vehicleId = intent.getIntExtra("vehicle_id", 0)

        // ✅ CONFIGURAR TEXTOS
        findViewById<TextView>(R.id.alertTitle).text = "🚨 VEHÍCULO FUERA DE ZONA"
        findViewById<TextView>(R.id.alertMessage).text = buildString {
            appendLine("Tu vehículo ha salido de la zona segura!")
            appendLine()
            if (distance > 0) {
                appendLine("Distancia: ${distance.toInt()}m")
            }
            appendLine("Hora: ${getCurrentTime()}")
            appendLine()
            appendLine("⚠️ Toca una opción para continuar")
        }

        // ✅ BOTÓN PARA SILENCIAR
        findViewById<Button>(R.id.buttonStopAlert).setOnClickListener {
            stopAlert()
        }

        // ✅ BOTÓN PARA ABRIR APP
        findViewById<Button>(R.id.buttonOpenApp).setOnClickListener {
            openMainApp()
        }

        // ✅ BOTÓN PARA DESACTIVAR ZONA (OPCIONAL)
        findViewById<Button>(R.id.buttonDisableZone).setOnClickListener {
            disableSafeZone()
        }
    }

    private fun stopAlert() {
        // ✅ ENVIAR BROADCAST PARA DETENER ALARMA
        val stopIntent = Intent("com.peregrino.STOP_ALARM_BROADCAST")
        sendBroadcast(stopIntent)

        finish()
    }

    private fun openMainApp() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback si no puede abrir la app
        }
        finish()
    }

    private fun disableSafeZone() {
        // ✅ ENVIAR BROADCAST PARA DESACTIVAR ZONA
        val disableIntent = Intent("com.peregrino.DISABLE_SAFEZONE_BROADCAST")
        sendBroadcast(disableIntent)

        finish()
    }

    private fun getCurrentTime(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    override fun onBackPressed() {
        super.onBackPressed()
        // ✅ PERMITIR CERRAR PERO DETENER ALARMA
        stopAlert()
    }
}