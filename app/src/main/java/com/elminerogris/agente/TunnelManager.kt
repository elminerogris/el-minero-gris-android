package com.elminerogris.agente

import android.util.Base64
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
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

    // Cliente exclusivo para el Proxy Web Local (Antminers + Routers con SSL autofirmado)
    private val proxyClient: OkHttpClient by lazy { crearProxyClient() }

    private val credencialesDigest = listOf(
        "root" to "root",
        "admin" to "admin",
        "root" to "admin"
    )

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
                        if (msg.optString("tipo") == "http_req") {
                            launch(Dispatchers.IO) {
                                manejarPeticionProxy(ws, msg)
                            }
                            return@launch
                        }

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

    private fun esIpLocalPrivada(ip: String): Boolean {
        val partes = ip.trim().split(".")
        if (partes.size != 4) return false
        val nums = partes.map { it.toIntOrNull() ?: return false }
        if (nums.any { it !in 0..255 }) return false
        return when {
            nums[0] == 10 -> true
            nums[0] == 192 && nums[1] == 168 -> true
            nums[0] == 172 && nums[1] in 16..31 -> true
            else -> false
        }
    }

    private fun crearProxyClient(): OkHttpClient {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAll, SecureRandom())

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private fun manejarPeticionProxy(ws: WebSocket, reqJson: JSONObject) {
        val reqId = reqJson.optString("req_id")
        val ip = reqJson.optString("ip").trim()
        val scheme = if (reqJson.optString("scheme") == "https") "https" else "http"
        val method = reqJson.optString("method", "GET").uppercase()
        val path = reqJson.optString("path", "/")
        val headersIn = reqJson.optJSONObject("headers")
        val bodyB64 = reqJson.optString("body_b64", "")

        if (!esIpLocalPrivada(ip)) {
            enviarErrorProxy(ws, reqId, "IP local no permitida: $ip")
            return
        }

        try {
            val url = "$scheme://$ip$path"
            val bodyBytes = if (bodyB64.isNotEmpty()) Base64.decode(bodyB64, Base64.DEFAULT) else ByteArray(0)
            val contentType = headersIn?.optString("Content-Type", "")?.takeIf { it.isNotBlank() }

            fun construirRequest(authHeader: String? = null): Request {
                val rb = Request.Builder().url(url)
                if (headersIn != null) {
                    val keys = headersIn.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (authHeader != null && k.equals("Authorization", ignoreCase = true)) continue
                        val v = headersIn.optString(k, "")
                        if (v.isNotEmpty()) rb.header(k, v)
                    }
                }
                if (authHeader != null) rb.header("Authorization", authHeader)

                val reqBody = if (method in listOf("POST", "PUT", "PATCH", "DELETE")) {
                    bodyBytes.toRequestBody(contentType?.toMediaTypeOrNull())
                } else null
                return rb.method(method, reqBody).build()
            }

            var response = proxyClient.newCall(construirRequest()).execute()

            // Auto-login HTTP Digest para Antminers si el equipo pide autenticación Digest
            if (response.code == 401 && headersIn?.optString("Authorization", "").isNullOrEmpty()) {
                val wwwAuth = response.header("WWW-Authenticate") ?: ""
                if (wwwAuth.startsWith("Digest", ignoreCase = true)) {
                    for ((usr, pwd) in credencialesDigest) {
                        val digestHeader = construirDigestHeader(wwwAuth, method, path, usr, pwd) ?: break
                        response.close()
                        response = proxyClient.newCall(construirRequest(digestHeader)).execute()
                        if (response.code != 401) break
                    }
                }
            }

            response.use { r ->
                val rawResp = r.body?.bytes() ?: ByteArray(0)
                if (rawResp.size > 4 * 1024 * 1024) {
                    enviarErrorProxy(ws, reqId, "Respuesta demasiado grande (> 4 MB) para el túnel.")
                    return
                }

                val respHeaders = JSONObject()
                val setCookies = r.headers("Set-Cookie")
                for (i in 0 until r.headers.size) {
                    val name = r.headers.name(i)
                    if (name.equals("Set-Cookie", ignoreCase = true)) continue
                    respHeaders.put(name, r.headers.value(i))
                }
                if (setCookies.isNotEmpty()) {
                    val arr = JSONArray()
                    setCookies.forEach { arr.put(it) }
                    respHeaders.put("Set-Cookie", arr)
                }

                val out = JSONObject()
                    .put("tipo", "http_resp")
                    .put("req_id", reqId)
                    .put("status", r.code)
                    .put("headers", respHeaders)
                    .put("body_b64", Base64.encodeToString(rawResp, Base64.NO_WRAP))

                ws.send(out.toString())
            }
        } catch (e: Exception) {
            enviarErrorProxy(ws, reqId, "No se pudo conectar a $scheme://$ip ($path): ${e.message}")
        }
    }

    private fun enviarErrorProxy(ws: WebSocket, reqId: String, msg: String) {
        try {
            ws.send(
                JSONObject()
                    .put("tipo", "http_resp")
                    .put("req_id", reqId)
                    .put("error", msg)
                    .toString()
            )
        } catch (_: Exception) {}
    }

    private fun construirDigestHeader(wwwAuth: String, method: String, uri: String, user: String, pass: String): String? {
        fun extraer(campo: String): String? {
            val regex = Regex("""$campo="([^"]+)"""")
            return regex.find(wwwAuth)?.groupValues?.getOrNull(1)
        }
        val realm = extraer("realm") ?: return null
        val nonce = extraer("nonce") ?: return null
        val qop = extraer("qop")
        val opaque = extraer("opaque")

        fun md5(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        val ha1 = md5("$user:$realm:$pass")
        val ha2 = md5("$method:$uri")
        val cnonce = "emg${System.currentTimeMillis() % 100000}"
        val nc = "00000001"

        val respHash = if (qop != null && qop.contains("auth")) {
            md5("$ha1:$nonce:$nc:$cnonce:auth:$ha2")
        } else {
            md5("$ha1:$nonce:$ha2")
        }

        val sb = StringBuilder("""Digest username="$user", realm="$realm", nonce="$nonce", uri="$uri", response="$respHash"""")
        if (qop != null && qop.contains("auth")) {
            sb.append(""", qop=auth, nc=$nc, cnonce="$cnonce"""")
        }
        if (opaque != null) {
            sb.append(""", opaque="$opaque"""")
        }
        return sb.toString()
    }
}
