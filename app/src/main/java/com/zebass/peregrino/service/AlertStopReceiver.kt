package com.zebass.peregrino.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AlertStopReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlertStopReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received stop alert broadcast")

        try {
            // Detener la alerta usando AlertManager
            val alertManager = AlertManager(context)
            alertManager.stopAlert()

            Log.d(TAG, "Alert stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping alert", e)
        }
    }
}