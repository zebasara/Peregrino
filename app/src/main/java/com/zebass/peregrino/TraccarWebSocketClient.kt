package com.zebass.peregrino

import android.content.Context
import android.util.Log
import android.widget.Toast
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI

// Crea una clase WebSocketClient
class TraccarWebSocketClient(
    private val context: Context,
    private val onPositionReceived: (deviceId: Int, lat: Double, lon: Double) -> Unit
) : WebSocketClient(URI("wss://tu-servidor-traccar/ws")) {

    override fun onOpen(handshakedata: ServerHandshake?) {
        Toast.makeText(context, "Conectado a Traccar", Toast.LENGTH_SHORT).show()
    }

    override fun onMessage(message: String?) {
        try {
            val json = JSONObject(message)
            if (json.has("positions")) {
                val positions = json.getJSONArray("positions")
                for (i in 0 until positions.length()) {
                    val pos = positions.getJSONObject(i)
                    onPositionReceived(
                        pos.getInt("deviceId"),
                        pos.getDouble("latitude"),
                        pos.getDouble("longitude")
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("WebSocket", "Error parsing message", e)
        }
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        Toast.makeText(context, "Desconectado de Traccar", Toast.LENGTH_SHORT).show()
    }

    override fun onError(ex: Exception?) {
        Log.e("WebSocket", "Error", ex)
    }
}