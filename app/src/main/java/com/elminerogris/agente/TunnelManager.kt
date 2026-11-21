package com.elminerogris.agente

import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class TunnelManager(
    private val scope: CoroutineScope,
    private val onOrdenRecibida: suspend (JSONArray) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS) // Mantiene vivo el túnel en Render
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Sin timeout de lectura para WebSocket
        .build()

    private var webSocket: WebSocket? = null
    private var activo = false
    private var reconectarJob: Job? = null

    fun iniciar(apiKey: String, agentId: String) {
        if (activo || apiKey.isEmpty()) return
        activo = true
        conectar(apiKey, agentId)
    }

    fun detener() {
        activo = false
        reconectarJob?.cancel()
        try { webSocket?.close(1000, "Agente detenido") } catch (_: Exception) {}
        webSocket = null
    }

    private fun conectar(apiKey: String, agentId: String) {
        if (!activo) return
        val kEnc = URLEncoder.encode(apiKey, "UTF-8")
        val aEnc = URLEncoder.encode(agentId, "UTF-8")
        val urlWs = "wss://elminerogris.com/ws/agente?api_key=$kEnc&agent_id=$aEnc"

        val request = Request.Builder().url(urlWs).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                // Confirmar conexión al servidor
                ws.send(JSONObject().put("tipo", "hello").put("agent_id", agentId).toString())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                scope.launch {
                    try {
                        val msg = JSONObject(text)
                        val ordenes = when {
                            msg.has("ordenes") -> msg.optJSONArray("ordenes")
                            msg.has("comando") -> JSONArray().put(msg)
                            else -> null
                        }
                        if (ordenes != null && ordenes.length() > 0) {
                            onOrdenRecibida(ordenes)
                            // Avisar al servidor que la orden fue ejecutada
                            ws.send(JSONObject().put("tipo", "ack").put("ok", true).toString())
                        }
                    } catch (_: Exception) {}
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                programarReconexion(apiKey, agentId)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                programarReconexion(apiKey, agentId)
            }
        })
    }

    private fun programarReconexion(apiKey: String, agentId: String) {
        if (!activo) return
        reconectarJob?.cancel()
        reconectarJob = scope.launch {
            delay(5000L)
            if (activo) conectar(apiKey, agentId)
        }
    }
}
