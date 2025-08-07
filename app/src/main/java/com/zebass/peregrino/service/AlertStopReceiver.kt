package com.zebass.peregrino.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
/**
 * ✅ RECEIVER PARA DETENER ALARMA DESDE NOTIFICACIÓN
 */
class AlertStopReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlertStopReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d(TAG, "🔇 Deteniendo alarma desde notificación")

        try {
            // ✅ ENVIAR BROADCAST AL SERVICIO
            val stopIntent = Intent("com.peregrino.STOP_ALARM_BROADCAST")
            context?.sendBroadcast(stopIntent)

            // ✅ TOAST DE CONFIRMACIÓN
            Toast.makeText(context, "🔇 Alarma silenciada", Toast.LENGTH_SHORT).show()

            Log.d(TAG, "✅ Broadcast enviado para detener alarma")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error deteniendo alarma: ${e.message}")
            Toast.makeText(context, "❌ Error deteniendo alarma", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * ✅ RECEIVER PARA DESACTIVAR ZONA SEGURA DESDE NOTIFICACIÓN
 */
class AlertDisableSafeZoneReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlertDisableSafeZoneReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d(TAG, "🛡️ Solicitud para desactivar zona segura desde notificación")

        if (context == null) {
            Log.e(TAG, "❌ Context es null")
            return
        }

        try {
            // ✅ ENVIAR BROADCAST INMEDIATAMENTE PARA DESACTIVAR
            val disableIntent = Intent("com.peregrino.DISABLE_SAFEZONE_BROADCAST")
            context.sendBroadcast(disableIntent)

            // ✅ CONFIRMACIÓN AL USUARIO
            Toast.makeText(
                context,
                "🛡️ Zona segura DESACTIVADA - Alarma detenida",
                Toast.LENGTH_LONG
            ).show()

            Log.d(TAG, "✅ Zona segura desactivada exitosamente")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error desactivando zona segura: ${e.message}")
            Toast.makeText(context, "❌ Error desactivando zona", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * ✅ RECEIVER PARA ACCIONES DESDE LA APP (OPCIONAL)
 */
class AppActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AppActionReceiver"
        const val ACTION_SILENCE_TEMPORARILY = "com.peregrino.SILENCE_TEMPORARILY"
        const val ACTION_FORCE_REFRESH = "com.peregrino.FORCE_REFRESH"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            ACTION_SILENCE_TEMPORARILY -> {
                Log.d(TAG, "🔇 Silenciando temporalmente desde la app")

                val silenceIntent = Intent("com.peregrino.SILENCE_ALERTS_TEMPORARILY")
                context?.sendBroadcast(silenceIntent)

                Toast.makeText(context, "🔇 Alertas silenciadas por 1 hora", Toast.LENGTH_SHORT).show()
            }

            ACTION_FORCE_REFRESH -> {
                Log.d(TAG, "🔄 Forzando refresh del servicio")

                val refreshIntent = Intent("com.peregrino.FORCE_REFRESH_BROADCAST")
                context?.sendBroadcast(refreshIntent)

                Toast.makeText(context, "🔄 Servicio actualizado", Toast.LENGTH_SHORT).show()
            }

            else -> {
                Log.d(TAG, "❓ Acción desconocida: ${intent?.action}")
            }
        }
    }
}