package com.elminerogris.agente

import android.util.Base64
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
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
    private val onOrdenRecibida: suspend (JSONArray) -> Unit,
    private val onEstadoCambio: ((Boolean) -> Unit)? = null
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val proxyClient: OkHttpClient by lazy { crearProxyClient() }

    private val credencialesDigest = listOf(
        "root" to "root",
        "admin" to "admin",
        "root" to "admin"
    )

    private val headersBloqueados = setOf(
        "host",
        "connection",
        "content-length",
        "accept-encoding",
        "upgrade",
        "sec-websocket-key"
    )

    private var webSocket: WebSocket? = null
    private var activo = false
    private var conectadoWs = false
    private var reconectarJob: Job? = null
    private var ultimaApiKey = ""
    private var ultimoAgentId = ""

    private val reconnectMinMs = 3_000L
    private val reconnectMaxMs = 60_000L
    private var delayActualMs = reconnectMinMs
    private var conectadoDesdeMs = 0L

    fun estaConectado(): Boolean = conectadoWs

    private fun actualizarEstado(nuevoEstado: Boolean) {
        if (conectadoWs != nuevoEstado) {
            conectadoWs = nuevoEstado
            try { onEstadoCambio?.invoke(nuevoEstado) } catch (_: Exception) {}
        }
    }

    fun iniciar(apiKey: String, agentId: String) {
        if (apiKey.isEmpty()) return
        ultimaApiKey = apiKey
        ultimoAgentId = agentId
        if (activo) return
        activo = true
        delayActualMs = reconnectMinMs
        conectar(apiKey, agentId)
    }

    fun forzarReconexion() {
        if (!activo || ultimaApiKey.isEmpty()) return
        reconectarJob?.cancel()
        try { webSocket?.cancel() } catch (_: Exception) {}
        webSocket = null
        actualizarEstado(false)
        delayActualMs = reconnectMinMs
        reconectarJob = scope.launch {
            delay(800L)
            if (activo) conectar(ultimaApiKey, ultimoAgentId)
        }
    }

    fun detener() {
        activo = false
        actualizarEstado(false)
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
                actualizarEstado(true)
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

                        if (msg.optString("tipo") == "check_lan_ip") {
                            launch(Dispatchers.IO) {
                                manejarCheckLanIp(ws, msg)
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
                actualizarEstado(false)
                programarReconexion(apiKey, agentId)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                actualizarEstado(false)
                programarReconexion(apiKey, agentId)
            }
        })
    }

    private fun programarReconexion(apiKey: String, agentId: String) {
        if (!activo) return
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
                        val kl = k.lowercase()
                        if (kl in headersBloqueados) continue
                        if (authHeader != null && kl == "authorization") continue
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

    private fun manejarCheckLanIp(ws: WebSocket, msg: JSONObject) {
        val reqId = msg.optString("req_id")
        val ip = msg.optString("ip").trim()
        if (!esIpLocalPrivada(ip)) {
            try {
                ws.send(JSONObject().put("tipo", "check_lan_ip_resp").put("req_id", reqId).put("ocupada", false).toString())
            } catch (_: Exception) {}
            return
        }

        var ocupada = false
        var tipoDisp = "celular_pc"
        var nomDisp = "Dispositivo de Red"

        // 1. Probar puertos clave en la red local
        val puertos = listOf(
            Pair(4028, "Minero ASIC"),
            Pair(80, "Dispositivo Web / Router"),
            Pair(443, "Dispositivo Seguro HTTPS"),
            Pair(554, "Cámara de Seguridad (RTSP)"),
            Pair(8000, "Cámara IP / Hikvision"),
            Pair(37777, "Cámara CCTV / Dahua"),
            Pair(8008, "Smart TV / Google Cast"),
            Pair(8060, "Roku TV"),
            Pair(3000, "Smart TV LG"),
            Pair(7000, "Apple AirPlay"),
            Pair(9080, "Samsung Smart TV"),
            Pair(62078, "iPhone / Apple Sync"),
            Pair(22, "Equipo Linux / SSH"),
            Pair(445, "Computadora Windows (SMB)")
        )

        for ((port, label) in puertos) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(ip, port), 250)
                    ocupada = true
                    nomDisp = label
                    tipoDisp = when {
                        port == 4028 -> "minero"
                        port in listOf(554, 8000, 37777) -> "camara"
                        port in listOf(8008, 8060, 3000, 7000, 9080) -> "smart_tv"
                        else -> "celular_pc"
                    }
                }
                if (ocupada) break
            } catch (e: Exception) {
                val err = (e.message ?: "").lowercase()
                if ("refused" in err || "rechazada" in err || "econnrefused" in err || "reset" in err) {
                    ocupada = true
                    nomDisp = "iPhone / Celular / PC Activo"
                    tipoDisp = "celular_pc"
                    break
                }
            }
        }

        // 2. Probar ping ICMP / Reachable
        if (!ocupada) {
            try {
                val inet = InetAddress.getByName(ip)
                if (inet.isReachable(350)) {
                    ocupada = true
                    nomDisp = "iPhone / Celular / PC en Línea"
                    tipoDisp = "celular_pc"
                }
            } catch (_: Exception) {}
        }

        // 3. Forzar resolución ARP mediante datagrama UDP y verificar /proc/net/arp
        if (!ocupada) {
            try {
                DatagramSocket().use { ds ->
                    val buf = ByteArray(1)
                    ds.send(DatagramPacket(buf, 1, InetAddress.getByName(ip), 9))
                }
                Thread.sleep(100)
                val fArp = File("/proc/net/arp")
                if (fArp.exists()) {
                    fArp.forEachLine { line ->
                        val parts = line.split("\\s+".toRegex())
                        if (parts.size >= 4 && parts[0] == ip && parts[3] !in listOf("00:00:00:00:00:00", "incomplete")) {
                            ocupada = true
                            nomDisp = "Dispositivo LAN (iPhone / Celular / PC)"
                            tipoDisp = "celular_pc"
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        val resp = JSONObject()
            .put("tipo", "check_lan_ip_resp")
            .put("req_id", reqId)
            .put("ocupada", ocupada)
            .put("tipo_disp", tipoDisp)
            .put("nom_disp", nomDisp)

        try { ws.send(resp.toString()) } catch (_: Exception) {}
    }
}
