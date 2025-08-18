package com.zebass.peregrino.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Vibrator
import android.util.Log
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.zebass.peregrino.MainActivity
import com.zebass.peregrino.R
import java.text.SimpleDateFormat
import java.util.*

@RequiresApi(Build.VERSION_CODES.O)
class AlertActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AlertActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alert)

        // ✅ MOSTRAR SOBRE PANTALLA BLOQUEADA
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
        val distance = intent.getDoubleExtra("alert_distance", 0.0)
        val alertTime = intent.getLongExtra("alert_time", System.currentTimeMillis())

        // ✅ CONFIGURAR TEXTOS
        findViewById<MaterialTextView>(R.id.alertTitle)?.text = "🚨 ALERTA DE SEGURIDAD"

        findViewById<MaterialTextView>(R.id.alertMessage)?.text = buildString {
            appendLine("¡Tu vehículo ha salido de la zona segura!")
            appendLine()
            if (distance > 0) {
                appendLine("📍 Distancia: ${distance.toInt()} metros")
            }
            appendLine("⏰ Hora: ${formatTime(alertTime)}")
            appendLine()
            appendLine("⚠️ Selecciona una acción:")
        }

        // ✅ BOTONES DE ACCIÓN
        findViewById<MaterialButton>(R.id.buttonStopAlert)?.apply {
            text = "🔇 SILENCIAR ALARMA"
            setOnClickListener { stopAlert() }
            setOnLongClickListener {
                emergencyStopAll()
                true
            }
        }

        findViewById<MaterialButton>(R.id.buttonOpenApp)?.apply {
            text = "📱 ABRIR APP"
            setOnClickListener { openMainApp() }
        }

        findViewById<MaterialButton>(R.id.buttonDisableZone)?.apply {
            text = "🛡️ DESACTIVAR ZONA"
            setOnClickListener { disableSafeZone() }
        }
    }

    private fun stopAlert() {
        Log.d(TAG, "🔇 AlertActivity: Stopping alert")

        // ✅ MÚLTIPLES MÉTODOS PARA ASEGURAR QUE SE DETENGA
        try {
            // Método 1: Broadcast específico
            val stopIntent = Intent("com.peregrino.STOP_ALARM_BROADCAST")
            sendBroadcast(stopIntent)

            // Método 2: Intent directo al servicio
            val serviceIntent = Intent(this, BackgroundSecurityService::class.java)
            serviceIntent.action = "STOP_ALARM"
            startService(serviceIntent)

            // Método 3: Cancelar notificaciones
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(9998) // CRITICAL_ALERT_ID

        } catch (e: Exception) {
            Log.w(TAG, "Error stopping alert: ${e.message}")
        }

        finish()
    }

    private fun emergencyStopAll() {
        Log.e(TAG, "🚨 EMERGENCY STOP: Stopping all alarms")

        // ✅ PARADA DE EMERGENCIA TOTAL
        try {
            // Detener vibración
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            vibrator?.cancel()

            // Cancelar todas las notificaciones
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancelAll()

            // Broadcast de emergencia
            val emergencyIntent = Intent("com.peregrino.EMERGENCY_STOP_ALL")
            sendBroadcast(emergencyIntent)

            // Detener servicio de seguridad
            val stopServiceIntent = Intent(this, BackgroundSecurityService::class.java)
            stopServiceIntent.action = BackgroundSecurityService.ACTION_STOP_SECURITY
            startService(stopServiceIntent)

        } catch (e: Exception) {
            Log.e(TAG, "Error in emergency stop: ${e.message}")
        }

        finishAffinity()
    }

    private fun openMainApp() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("OPEN_SECURITY_TAB", true)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening main app: ${e.message}")
        }
        finish()
    }

    private fun disableSafeZone() {
        Log.d(TAG, "🛡️ Disabling safe zone from alert")

        try {
            // ✅ ENVIAR BROADCAST PARA DESACTIVAR ZONA
            val disableIntent = Intent("com.peregrino.DISABLE_SAFEZONE_BROADCAST")
            sendBroadcast(disableIntent)

            // ✅ TAMBIÉN ENVIAR AL SERVICIO DIRECTAMENTE
            val serviceIntent = Intent(this, BackgroundSecurityService::class.java)
            serviceIntent.action = "DISABLE_SAFEZONE"
            startService(serviceIntent)

        } catch (e: Exception) {
            Log.e(TAG, "Error disabling safe zone: ${e.message}")
        }

        finish()
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }

    override fun onBackPressed() {
        // ✅ NO PERMITIR CERRAR CON BACK - FORZAR ACCIÓN
        stopAlert()
        super.onBackPressed()
    }
}