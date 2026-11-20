
package com.elminerogris.agente

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

class AgentService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val urlNube = "https://antminer-panel.onrender.com/api/recibir_datos"
    private var ipsConocidas = mutableSetOf<String>()
    private var ultimoEscaneo = 0L

    override fun onCreate() {
        super.onCreate()
        crearCanalNotificacion()
        startForeground(101, construirNotificacion("Iniciando monitoreo LAN..."))
        scope.launch { buclePrincipal() }
    }

    private suspend fun buclePrincipal() {
        val prefs = getSharedPreferences("emg_prefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        val subnet = prefs.getString("subnet", "192.168.0") ?: "192.168.0"

        while (currentCoroutineContext().isActive && apiKey.isNotEmpty()) {
            try {
                val ahora = System.currentTimeMillis()
                if (ipsConocidas.isEmpty() || ahora - ultimoEscaneo > 600_000L) {
                    ultimoEscaneo = ahora
                    actualizarNotificacion("Escaneando subred $subnet.x...")
                    ipsConocidas = escanearRed(subnet)
                }

                val listaMineros = JSONArray()
                val jobs = ipsConocidas.map { ip ->
                    scope.async { leerMinero4028(ip) }
                }
                jobs.awaitAll().filterNotNull().forEach { listaMineros.put(it) }

                val payload = JSONObject().apply {
                    put("api_key", apiKey)
                    put("mineros", listaMineros)
                }

                val resp = enviarPostJson(urlNube, payload.toString())
                if (resp != null) {
                    actualizarNotificacion("En línea: ${listaMineros.length()} minero(s) activos")
                    val ordenes = resp.optJSONArray("ordenes")
                    if (ordenes != null) procesarOrdenes(ordenes, subnet)
                } else {
                    actualizarNotificacion("Reintentando conexión con la nube...")
                }
            } catch (_: Exception) {}
            delay(25_000L)
        }
    }

    private suspend fun escanearRed(subnet: String): MutableSet<String> = coroutineScope {
        val encontradas = mutableSetOf<String>()
        val tareas = (2..254).map { i ->
            async(Dispatchers.IO) {
                val ip = "$subnet.$i"
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(ip, 4028), 350)
                        ip
                    }
                } catch (_: Exception) { null }
            }
        }
        tareas.awaitAll().filterNotNull().forEach { encontradas.add(it) }
        encontradas
    }

    private fun peticion4028(ip: String, comando: String): JSONObject? {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, 4028), 2000)
                s.soTimeout = 2000
                s.getOutputStream().write("{\"command\":\"$comando\"}".toByteArray())
                val txt = s.getInputStream().bufferedReader().readText().replace("\u0000", "").trim()
                if (txt.isEmpty()) null else JSONObject(txt.replace("}{", ","))
            }
        } catch (_: Exception) { null }
    }

    private fun leerMinero4028(ip: String): JSONObject? {
        val sum = peticion4028(ip, "summary") ?: return null
        val stSum = sum.optJSONArray("SUMMARY")?.optJSONObject(0) ?: return null
        val stats = peticion4028(ip, "stats")
        val stStats = stats?.optJSONArray("STATS")?.optJSONObject(1)
            ?: stats?.optJSONArray("STATS")?.optJSONObject(0) ?: JSONObject()

        val ghs5s = stSum.optDouble("GHS 5s", stSum.optDouble("MHS 5s", 0.0) / 1000.0)
        val ghsAv = stSum.optDouble("GHS av", stSum.optDouble("MHS av", ghs5s * 1000.0) / 1000.0)
        val hrTh = Math.round((ghs5s / 1000.0) * 100.0) / 100.0
        val hr30Th = Math.round((ghsAv / 1000.0) * 100.0) / 100.0

        val tempMax = maxOf(
            stStats.optInt("temp2_1", 0),
            stStats.optInt("temp2_2", 0),
            stStats.optInt("temp2_3", 0),
            stSum.optInt("Chip Temp Max", 65)
        )

        val pools = peticion4028(ip, "pools")?.optJSONArray("POOLS")
        val p1 = pools?.optJSONObject(0)

        return JSONObject().apply {
            put("ip", ip)
            put("modelo", stStats.optString("Type", "ASIC Miner"))
            put("algoritmo", "SHA-256")
            put("moneda", "BTC")
            put("unidad", "TH/s")
            put("hashrate", hrTh)
            put("hashrate_30m", if (hr30Th > 0) hr30Th else hrTh)
            put("temp", tempMax)
            put("temp_amb", stSum.optInt("Env Temp", 0))
            put("consumo", if (hrTh > 0) hrTh * 23.0 else 120.0)
            put("errores", stSum.optInt("Hardware Errors", 0))
            put("pool1", p1?.optString("URL", "") ?: "")
            put("worker1", p1?.optString("User", "") ?: "")
            put("acc1", p1?.optInt("Accepted", 0) ?: 0)
            put("rej1", p1?.optInt("Rejected", 0) ?: 0)
            put("cadenas", JSONArray())
        }
    }

    private suspend fun procesarOrdenes(ordenes: JSONArray, subnet: String) {
        for (i in 0 until ordenes.length()) {
            val o = ordenes.optJSONObject(i) ?: continue
            val ip = o.optString("ip")
            val cmd = o.optString("comando")
            when (cmd) {
                "scan" -> ipsConocidas = escanearRed(subnet)
                "restart" -> peticion4028(ip, "restart")
                "sleep" -> peticion4028(ip, "pause")
                "normal" -> peticion4028(ip, "resume")
            }
        }
    }

    private fun enviarPostJson(urlStr: String, body: String): JSONObject? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 10000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            if (conn.responseCode == 200) {
                JSONObject(conn.inputStream.bufferedReader().readText())
            } else null
        } catch (_: Exception) { null }
    }

    private fun crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel("emg_ch", "El Minero Gris 24/7", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
        }
    }

    private fun construirNotificacion(texto: String): Notification {
        return NotificationCompat.Builder(this, "emg_ch")
            .setContentTitle("El Minero Gris — Agente")
            .setContentText(texto)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun actualizarNotificacion(texto: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(101, construirNotificacion(texto))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
