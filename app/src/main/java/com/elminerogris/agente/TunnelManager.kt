package com.elminerogris.agente

import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.min

class TunnelManager(
    private val scope: CoroutineScope,
    private val onOrdenRecibida: suspend (JSONArray) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS) // Heartbeat nativo cada 25s (mantiene vivo el túnel en Render)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Sin timeout de lectura para WebSocket
        .build()

    private var webSocket: WebSocket? = null
    private var activo = false
    private var reconectarJob: Job? = null

    private val reconnectMinMs = 3_000L
    private val reconnectMaxMs = 60_000L
    private var delayActualMs = reconnectMinMs
    private var conectadoDesdeMs = 0L

    fun iniciar(apiKey: String, agentId: String) {
        if (activo || apiKey.isEmpty()) return
        activo = true
        delayActualMs = reconnectMinMs
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
                conectadoDesdeMs = System.currentTimeMillis()
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
        // Si el túnel duró más de 60s estable, reseteamos el backoff a 3s
        if (conectadoDesdeMs > 0L && (System.currentTimeMillis() - conectadoDesdeMs) > 60_000L) {
            delayActualMs = reconnectMinMs
        }
        conectadoDesdeMs = 0L

        val espera = delayActualMs
        delayActualMs = min(delayActualMs * 2L, reconnectMaxMs)

        reconectarJob?.cancel()
        reconectarJob = scope.launch {
            delay(espera)
            if (activo) conectar(apiKey, agentId)
        }
    }
}
