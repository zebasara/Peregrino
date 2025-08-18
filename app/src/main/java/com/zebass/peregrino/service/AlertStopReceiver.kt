package com.zebass.peregrino.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

// ✅ MANTENER SOLO ESTOS 2 RECEIVERS:
class AlertStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val stopIntent = Intent("com.peregrino.STOP_ALARM_BROADCAST")
        context?.sendBroadcast(stopIntent)
        Toast.makeText(context, "🔇 Alarma silenciada", Toast.LENGTH_SHORT).show()
    }
}
class AlertDisableSafeZoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val disableIntent = Intent("com.peregrino.DISABLE_SAFEZONE_BROADCAST")
        context?.sendBroadcast(disableIntent)
        Toast.makeText(context, "🛡️ Zona segura desactivada", Toast.LENGTH_LONG).show()
    }
}