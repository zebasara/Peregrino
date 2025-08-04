package com.zebass.peregrino.service

import android.os.Bundle
import android.os.Build
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.zebass.peregrino.R
import java.text.SimpleDateFormat
import java.util.*

class AlertActivity : AppCompatActivity() {

    private lateinit var alertManager: AlertManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alert)

        // Configurar ventana para mostrar sobre pantalla bloqueada
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

        alertManager = AlertManager(this)

        // Configurar UI
        findViewById<TextView>(R.id.alertTitle).text = "⚠️ VEHICLE ALERT!"
        findViewById<TextView>(R.id.alertMessage).text =
            "Your vehicle has left the safe zone!\n\nTime: ${getCurrentTime()}"

        findViewById<Button>(R.id.buttonStopAlert).setOnClickListener {
            alertManager.stopAlert()
            finish()
        }

        findViewById<Button>(R.id.buttonOpenApp).setOnClickListener {
            // Abrir la app principal
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            startActivity(intent)
            finish()
        }
    }

    private fun getCurrentTime(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    override fun onBackPressed() {
        super.onBackPressed()
        // No permitir cerrar con back, debe usar el botón
    }
}