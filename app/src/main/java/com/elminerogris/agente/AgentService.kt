package com.elminerogris.agente

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class AgentService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val urlNube = "https://elminerogris.com/api/recibir_datos"
    private val urlLogs = "https://elminerogris.com/api/recibir_logs"
    private val urlVersion = "https://elminerogris.com/api/agente/apk_version"

    private val netSemaphore = Semaphore(15)
    private val scanMutex = Mutex()

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val credencialesHttp = CopyOnWriteArrayList(
        listOf(
            Pair("root", "root"),
            Pair("admin", "admin"),
            Pair("root", "admin")
        )
    )

    private val ipsConocidas = CopyOnWriteArrayList<String>()
    private val cacheInfoEstatica = ConcurrentHashMap<String, MutableMap<String, Any>>()
    private var ultimoEscaneoAuto = 0L
    private var ultimoCheckUpdate = 0L
    private var tunnelManager: TunnelManager? = null

    private var ultimaLecturaGps = 0L
    private var gpsLat: Double? = null
    private var gpsLon: Double? = null

    private fun actualizarCredencialesHttp(usr: String, pwd: String) {
        val u = usr.trim()
        val p = pwd.trim()
        if (u.isEmpty() || p.isEmpty()) return
        val par = Pair(u, p)
        if (credencialesHttp.firstOrNull() == par) return
        credencialesHttp.remove(par)
        credencialesHttp.add(0, par)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("emg_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("service_running", true)
            .putBoolean("tunnel_connected", false)
            .apply()
        adquirirLocks()
        registrarObservadorRed()
        startForeground(1, crearNotificacion("Monitoreando granja ASIC 24/7..."))
        scope.coroutineContext.cancelChildren()
        scope.launch { buclePrincipal() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        getSharedPreferences("emg_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("service_running", false)
            .putBoolean("tunnel_connected", false)
            .apply()
        desregistrarObservadorRed()
        tunnelManager?.detener()
        scope.cancel()
        liberarLocks()
        super.onDestroy()
    }

    private fun registrarObservadorRed() {
        if (networkCallback != null) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch {
                        delay(1200L)
                        tunnelManager?.forzarReconexion()
                    }
                }

                override fun onLost(network: Network) {
                    getSharedPreferences("emg_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("tunnel_connected", false).apply()
                }
            }
            cm.registerNetworkCallback(req, cb)
            networkCallback = cb
        } catch (_: Exception) {}
    }

    private fun desregistrarObservadorRed() {
        val cb = networkCallback ?: return
        networkCallback = null
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {}
    }

    private fun obtenerOCrearAgentId(prefs: SharedPreferences): String {
        val existente = prefs.getString("agent_id", "")?.trim() ?: ""
        if (existente.length >= 4) return existente
        val nuevoId = "AND-" + UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
        prefs.edit().putString("agent_id", nuevoId).apply()
        return nuevoId
    }

    private fun obtenerVersionApp(): String {
        return try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            pInfo.versionName?.trim() ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun adquirirLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ElMineroGris::AgentWakeLock")
                wakeLock?.setReferenceCounted(false)
            }
            if (wakeLock?.isHeld == false) wakeLock?.acquire()
        } catch (_: Exception) {}

        try {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm?.createWifiLock(mode, "ElMineroGris::AgentWifiLock")
                wifiLock?.setReferenceCounted(false)
            }
            if (wifiLock?.isHeld == false) wifiLock?.acquire()
        } catch (_: Exception) {}
    }

    private fun liberarLocks() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
    }

    private fun obtenerIpLocalDispositivo(): String {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return ""
            var fallback = ""
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                        if (fallback.isEmpty()) fallback = ip
                    }
                }
            }
            return fallback
        } catch (_: Exception) {
            return ""
        }
    }

    private fun obtenerBssidWifi(): String {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return ""
            @Suppress("DEPRECATION")
            val bssid = wm.connectionInfo?.bssid?.trim()?.uppercase() ?: ""
            if (bssid.length == 17 && bssid != "02:00:00:00:00:00" && bssid != "00:00:00:00:00:00") bssid else ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun obtenerGatewayWifi(): String {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return ""
            @Suppress("DEPRECATION")
            val gwInt = wm.dhcpInfo?.gateway ?: 0
            if (gwInt == 0) return ""
            val b1 = gwInt and 0xff
            val b2 = (gwInt shr 8) and 0xff
            val b3 = (gwInt shr 16) and 0xff
            val b4 = (gwInt shr 24) and 0xff
            "$b1.$b2.$b3.$b4"
        } catch (_: Exception) {
            ""
        }
    }

    // ==================== TELEMETRÍA DEL DISPOSITIVO (CELULAR / TV BOX / BATERÍA / IP LOCAL) ====================
    private fun obtenerTelemetriaCelular(): JSONObject {
        val info = JSONObject()
        try {
            val battIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            var pct = -1
            var cargando = false
            var tempC = 0.0
            var battPresent = false

            if (battIntent != null) {
                battPresent = battIntent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)
                val level = battIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = battIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                pct = if (level >= 0 && scale > 0) ((level * 100f) / scale).roundToInt() else -1

                val status = battIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                cargando = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

                val tempDecimas = battIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                tempC = if (tempDecimas > 0) (tempDecimas / 10.0) else 0.0
            }

            val uiMode = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            val esTvMode = uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
            val tieneLeanback = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
            val esTvBox = esTvMode || tieneLeanback || !battPresent || pct < 0

            val marca = (Build.MANUFACTURER ?: "").trim().replaceFirstChar { it.uppercase() }
            val modelo = (Build.MODEL ?: "").trim()
            val verAndroid = Build.VERSION.RELEASE ?: ""
            val verApp = obtenerVersionApp()

            info.put("device_type", if (esTvBox) "TV Box" else "Celular")
            info.put("run_mode", "APK Directo")
            info.put("brand", marca)
            info.put("model", modelo)
            info.put("local_ip", obtenerIpLocalDispositivo())
            info.put("router_mac", obtenerBssidWifi())
            info.put("gateway", obtenerGatewayWifi())
            info.put("has_battery", battPresent && pct >= 0 && !esTvBox)
            info.put("battery_pct", pct)
            info.put("charging", cargando)
            info.put("battery_temp_c", tempC)
            info.put("os_info", "Android $verAndroid".trim())
            info.put("android", verAndroid)
            if (verApp.isNotEmpty()) info.put("app_version", verApp)
        } catch (_: Exception) {}
        return info
    }

    // ==================== DIAGNÓSTICO DE LATENCIA A POOLS ====================
    private fun diagnosticoRedPools(): String {
        val destinos = listOf(
            Triple("ViaBTC (BTC)", "btc.viabtc.io", 3333),
            Triple("Binance Pool", "sha256.poolbinance.com", 8888),
            Triple("NiceHash (SHA256)", "sha256asicboost.auto.nicehash.com", 9200),
            Triple("Braiins Pool", "stratum.braiins.com", 3333),
            Triple("F2Pool", "btc.f2pool.com", 3333),
            Triple("Google DNS", "8.8.8.8", 53)
        )
        val sb = StringBuilder()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        sb.append("=== DIAGNÓSTICO DE RED DESDE APK (${fmt.format(Date())}) ===\n")
        for ((nombre, host, puerto) in destinos) {
            val t0 = System.currentTimeMillis()
            val res = try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, puerto), 3000)
                }
                "${System.currentTimeMillis() - t0} ms (OK)"
            } catch (e: Exception) {
                "SIN RESPUESTA (${e.javaClass.simpleName})"
            }
            sb.append(String.format("%-22s -> %s:%d | %s\n", nombre, host, puerto, res))
        }
        return sb.toString()
    }

    private fun crearNotificacion(texto: String): Notification {
        val channelId = "emg_agent_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "El Minero Gris Agente", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentIntent = PendingIntent.getActivity(this, 101, openIntent, pFlags)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("El Minero Gris Agente")
            .setContentText(texto)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    private fun actualizarNotificacion(texto: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(1, crearNotificacion(texto))
    }

    // ==================== GPS OPCIONAL (SOLO SI EL CLIENTE LO ACTIVA) ====================
    private fun actualizarGpsSiCorresponde(prefs: SharedPreferences) {
        if (!prefs.getBoolean("gps_enabled", false)) {
            gpsLat = null
            gpsLon = null
            return
        }
        val ahora = System.currentTimeMillis() / 1000L
        if (gpsLat != null && gpsLon != null && (ahora - ultimaLecturaGps) < 21600L) return

        val fineOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineOk && !coarseOk) return

        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            var mejor: Location? = null
            for (prov in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
                try {
                    val loc = lm.getLastKnownLocation(prov) ?: continue
                    if (abs(loc.latitude) < 0.001 && abs(loc.longitude) < 0.001) continue
                    if (mejor == null || loc.accuracy < mejor.accuracy) mejor = loc
                } catch (_: Exception) {}
            }
            if (mejor != null) {
                gpsLat = (mejor.latitude * 1_000_000.0).roundToInt() / 1_000_000.0
                gpsLon = (mejor.longitude * 1_000_000.0).roundToInt() / 1_000_000.0
                ultimaLecturaGps = ahora
            }
        } catch (_: Exception) {}
    }

    // ==================== AUTO-ACTUALIZACIÓN EN SEGUNDO PLANO ====================
    private fun verificarYDescargarUpdate(forzar: Boolean = false) {
        val ahora = System.currentTimeMillis() / 1000L
        if (!forzar && (ahora - ultimoCheckUpdate) < 21600L) return
        ultimoCheckUpdate = ahora

        try {
            val conn = (URL(urlVersion).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
            }
            if (conn.responseCode != 200) return
            val info = JSONObject(conn.inputStream.bufferedReader().readText())
            val nubeCode = info.optInt("version_code", 1)
            val nubeName = info.optString("version_name", "1.0")
            val apkUrl = info.optString("apk_url", "")
            if (apkUrl.isEmpty()) return

            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val localCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
            if (nubeCode <= localCode) return

            val dlConn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
            }
            if (dlConn.responseCode != 200) return
            val outFile = File(cacheDir, "ElMineroGris-Update.apk")
            dlConn.inputStream.use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
            mostrarNotificacionUpdate(outFile, nubeName)
        } catch (_: Exception) {}
    }

    private fun mostrarNotificacionUpdate(file: File, versionName: String) {
        try {
            val channelId = "emg_update_channel"
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(channelId, "Actualizaciones El Minero Gris", NotificationManager.IMPORTANCE_HIGH)
                nm.createNotificationChannel(ch)
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getActivity(this, 202, installIntent, flags)

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, channelId)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
            val notif = builder
                .setContentTitle("⬆️ Actualización v$versionName lista")
                .setContentText("Toca aquí para instalar la nueva versión del Agente")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
            nm.notify(2, notif)
        } catch (_: Exception) {}
    }

    private suspend fun buclePrincipal() {
        val prefs = getSharedPreferences("emg_prefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        val subnet = prefs.getString("subnet", "192.168.0") ?: "192.168.0"
        val agentId = obtenerOCrearAgentId(prefs)
        if (apiKey.isEmpty()) return

        val savedUser = prefs.getString("miner_user", "") ?: ""
        val savedPass = prefs.getString("miner_pass", "") ?: ""
        actualizarCredencialesHttp(savedUser, savedPass)

        val ipsBase = (11..15).map { "$subnet.$it" }
        ipsConocidas.clear()
        ipsConocidas.addAll(ipsBase)

        tunnelManager?.detener()
        tunnelManager = TunnelManager(
            scope = scope,
            onOrdenRecibida = { ordenesInstantaneas ->
                procesarComandos(ordenesInstantaneas, apiKey, subnet, ipsBase)
            },
            onEstadoCambio = { conectado ->
                prefs.edit().putBoolean("tunnel_connected", conectado).apply()
            }
        )
        tunnelManager?.iniciar(apiKey, agentId)

        val fmtHora = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        while (currentCoroutineContext().isActive) {
            try {
                verificarYDescargarUpdate(forzar = false)
                actualizarGpsSiCorresponde(prefs)

                val ahora = System.currentTimeMillis() / 1000L
                if (ahora - ultimoEscaneoAuto > 600L) {
                    ultimoEscaneoAuto = ahora
                    val encontradas = escanearRed(subnet, ipsBase)
                    ipsConocidas.clear()
                    ipsConocidas.addAll(encontradas)
                }
                for (ipB in ipsBase) {
                    if (!ipsConocidas.contains(ipB)) ipsConocidas.add(ipB)
                }

                val resultados = coroutineScope {
                    ipsConocidas.map { ip ->
                        async(Dispatchers.IO) {
                            netSemaphore.withPermit {
                                extraerTodo(ip, subnet)
                            }
                        }
                    }.awaitAll().filterNotNull()
                }

                val arrMineros = JSONArray()
                for (r in resultados) arrMineros.put(r)

                val minerosIps = resultados.map { it.optString("ip") }.toSet()
                for (ip in ipsConocidas) {
                    if (ip !in minerosIps) {
                        val isGw = ip.endsWith(".1") || ip.endsWith(".254")
                        arrMineros.put(
                            JSONObject()
                                .put("ip", ip)
                                .put("modelo", if (isGw) "Router / Gateway" else "Dispositivo en Red Local")
                                .put("marca", if (isGw) "Router / Gateway" else "Equipo de Red")
                                .put("es_asic", false)
                                .put("tipo", "no_asic")
                                .put("hashrate", 0.0)
                                .put("temp", 0)
                        )
                    }
                }

                val telemetriaDev = obtenerTelemetriaCelular()
                val routerMac = obtenerBssidWifi()
                val gwWifi = obtenerGatewayWifi()
                val appVer = obtenerVersionApp()

                val payload = JSONObject()
                    .put("api_key", apiKey)
                    .put("agent_id", agentId)
                    .put("mineros", arrMineros)
                    .put("device", telemetriaDev)
                    .put("phone", telemetriaDev)

                if (appVer.isNotEmpty()) payload.put("app_version", appVer)
                if (routerMac.isNotEmpty()) payload.put("router_mac", routerMac)
                if (gwWifi.isNotEmpty()) payload.put("gateway", gwWifi)

                if (gpsLat != null && gpsLon != null) {
                    payload.put("lat", gpsLat)
                    payload.put("lon", gpsLon)
                    payload.put("geo_source", "GPS APK")
                }

                val resp = postJsonNube(urlNube, payload)
                if (resp != null) {
                    val mUser = resp.optString("miner_user", "").trim()
                    val mPass = resp.optString("miner_pass", "").trim()
                    if (mUser.isNotEmpty() && mPass.isNotEmpty()) {
                        actualizarCredencialesHttp(mUser, mPass)
                        prefs.edit()
                            .putString("miner_user", mUser)
                            .putString("miner_pass", mPass)
                            .apply()
                    }

                    val horaStr = fmtHora.format(Date())
                    val tunelOk = tunnelManager?.estaConectado() == true
                    prefs.edit()
                        .putInt("miners_count", resultados.size)
                        .putString("last_sync", horaStr)
                        .putBoolean("tunnel_connected", tunelOk)
                        .apply()
                    actualizarNotificacion("En línea: ${resultados.size} minero(s) | Sede $agentId")
                    val ordenes = resp.optJSONArray("ordenes")
                    if (ordenes != null && ordenes.length() > 0) {
                        procesarComandos(ordenes, apiKey, subnet, ipsBase)
                    }
                }
            } catch (_: Exception) {
            }
            delay(25_000L)
        }
    }

    // ==================== COMUNICACIÓN PUERTO 4028 ====================
    private fun peticion4028(ip: String, comando: String, parametro: String = ""): JSONObject? {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, 4028), 3000)
                s.soTimeout = 2500
                val cmdObj = JSONObject().put("command", comando)
                if (parametro.isNotEmpty()) cmdObj.put("parameter", parametro)
                s.getOutputStream().write(cmdObj.toString().toByteArray(Charsets.UTF_8))
                s.getOutputStream().flush()

                val sb = StringBuilder()
                val buf = ByteArray(8192)
                val inp = s.getInputStream()
                while (true) {
                    val n = try { inp.read(buf) } catch (_: Exception) { -1 }
                    if (n <= 0) break
                    sb.append(String(buf, 0, n, Charsets.UTF_8))
                }
                val txt = sb.toString().replace("\u0000", "").trim()
                if (txt.isEmpty()) return null
                if (txt.contains("}{")) {
                    try { return JSONObject(txt.replace("}{", ",")) } catch (_: Exception) {}
                    try { return JSONObject(txt.substringBefore("}{") + "}") } catch (_: Exception) {}
                }
                JSONObject(txt)
            }
        } catch (_: Exception) {
            null
        }
    }

    // ==================== HTTP CON DIGEST AUTH NATIVO ====================
    private fun md5Hex(s: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun parseDigestHeader(header: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val regex = Regex("""(\w+)=(?:"([^"]*)"|([^,\s]*))""")
        for (m in regex.findAll(header)) {
            val k = m.groupValues[1]
            val v = if (m.groupValues[2].isNotEmpty()) m.groupValues[2] else m.groupValues[3]
            map[k] = v
        }
        return map
    }

    private fun peticionHttp(
        ip: String,
        endpoint: String,
        metodo: String = "GET",
        payloadJson: JSONObject? = null,
        payloadForm: Map<String, String>? = null,
        timeoutMs: Int = 5000
    ): String? {
        val uriPath = "/cgi-bin/$endpoint"
        val urlStr = "http://$ip$uriPath"
        val httpMethod = if (metodo == "GET") "GET" else "POST"

        val bodyBytes: ByteArray = when (metodo) {
            "POST_FORM" -> {
                val form = payloadForm?.entries?.joinToString("&") {
                    "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
                } ?: ""
                form.toByteArray(Charsets.UTF_8)
            }
            "POST" -> (payloadJson?.toString() ?: "{}").toByteArray(Charsets.UTF_8)
            else -> ByteArray(0)
        }

        val contentType = when (metodo) {
            "POST_FORM" -> "application/x-www-form-urlencoded"
            "POST" -> "application/json"
            else -> null
        }

        for ((usr, pwd) in credencialesHttp) {
            try {
                val conn1 = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = httpMethod
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                    setRequestProperty("Referer", "http://$ip/")
                    if (httpMethod == "POST") {
                        doOutput = true
                        if (contentType != null) setRequestProperty("Content-Type", contentType)
                        outputStream.use { it.write(bodyBytes) }
                    }
                }
                val code1 = conn1.responseCode
                if (code1 == 200) {
                    return conn1.inputStream.bufferedReader().readText()
                }
                if (code1 != 401) break

                val wwwAuth = conn1.getHeaderField("WWW-Authenticate") ?: break
                if (!wwwAuth.lowercase().startsWith("digest")) break

                val d = parseDigestHeader(wwwAuth)
                val realm = d["realm"] ?: ""
                val nonce = d["nonce"] ?: ""
                val qop = d["qop"] ?: ""
                val opaque = d["opaque"]
                val nc = "00000001"
                val cnonce = md5Hex(System.nanoTime().toString()).substring(0, 16)

                val ha1 = md5Hex("$usr:$realm:$pwd")
                val ha2 = md5Hex("$httpMethod:$uriPath")
                val responseHash = if (qop.isNotEmpty()) {
                    md5Hex("$ha1:$nonce:$nc:$cnonce:auth:$ha2")
                } else {
                    md5Hex("$ha1:$nonce:$ha2")
                }

                val authHeader = StringBuilder()
                    .append("""Digest username="$usr", realm="$realm", nonce="$nonce", uri="$uriPath", response="$responseHash"""")
                if (qop.isNotEmpty()) authHeader.append(""", qop=auth, nc=$nc, cnonce="$cnonce"""")
                if (!opaque.isNullOrEmpty()) authHeader.append(""", opaque="$opaque"""")

                val conn2 = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = httpMethod
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                    setRequestProperty("Referer", "http://$ip/")
                    setRequestProperty("Authorization", authHeader.toString())
                    if (httpMethod == "POST") {
                        doOutput = true
                        if (contentType != null) setRequestProperty("Content-Type", contentType)
                        outputStream.use { it.write(bodyBytes) }
                    }
                }
                val code2 = conn2.responseCode
                if (code2 == 200) {
                    return conn2.inputStream.bufferedReader().readText()
                }
                if (code2 != 401) break
            } catch (_: Exception) {
                break
            }
        }
        return null
    }

    // ==================== UTILIDADES DE PARSEO ====================
    private fun extraerNumeros(v: Any?): List<Int> {
        if (v == null || v == JSONObject.NULL) return emptyList()
        if (v is Number) return listOf(v.toInt()).filter { it > 0 }
        if (v is JSONArray) {
            val res = mutableListOf<Int>()
            for (i in 0 until v.length()) res.addAll(extraerNumeros(v.opt(i)))
            return res
        }
        val regex = Regex("""\d+(?:\.\d+)?""")
        return regex.findAll(v.toString()).mapNotNull { it.value.toDoubleOrNull()?.toInt() }.filter { it > 0 }.toList()
    }

    private fun aEntero(v: Any?): Int {
        if (v == null || v == JSONObject.NULL) return 0
        if (v is Number) return v.toInt()
        val s = v.toString().trim()
        if (s.isEmpty()) return 0
        if (s.lowercase().contains("o") && s.none { it.isDigit() }) {
            return s.lowercase().count { it == 'o' }
        }
        return extraerNumeros(v).maxOrNull() ?: 0
    }

    private fun aEnteroMin(v: Any?): Int {
        return extraerNumeros(v).filter { it in 10..65 }.minOrNull() ?: 0
    }

    private fun aFloat(v: Any?): Double {
        if (v == null || v == JSONObject.NULL) return 0.0
        if (v is Number) return v.toDouble()
        val clean = v.toString().trim().replace(Regex("""[^\d.-]"""), "")
        return clean.toDoubleOrNull() ?: 0.0
    }

    private fun formatearUptime(segundos: Any?): String {
        val s = aEntero(segundos)
        if (s <= 0) return "En línea"
        val d = s / 86400
        val h = (s % 86400) / 3600
        val m = (s % 3600) / 60
        return when {
            d > 0 -> "${d}d ${h}h ${m}m"
            h > 0 -> "${h}h ${m}m"
            else -> "${m}m ${s % 60}s"
        }
    }

    private fun buscarLlave(obj: JSONObject?, posibles: List<String>, defecto: Any? = null): Any? {
        if (obj == null) return defecto
        val mapa = mutableMapOf<String, Any>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.opt(k)
            if (v != null && v != JSONObject.NULL) mapa[k.lowercase().trim()] = v
        }
        for (p in posibles) {
            val v = mapa[p.lowercase().trim()]
            if (v != null && v.toString().isNotEmpty()) return v
        }
        return defecto
    }

    private fun determinarCredencialesDefecto(marca: String, modelo: String): Triple<String, String, String> {
        val txt = "$marca $modelo".lowercase()
        return when {
            listOf("avalon", "canaan", "q90", "a10", "a11", "a12", "a13", "a14", "a15", "nano").any { txt.contains(it) } ->
                Triple("Canaan Avalon", "root", "root")
            listOf("whatsminer", "microbt", "btminer", "m20", "m21", "m30", "m31", "m32", "m33", "m50", "m53", "m60", "m63").any { txt.contains(it) } ->
                Triple("MicroBT WhatsMiner", "admin", "admin")
            listOf("iceriver", "ks0", "ks1", "ks2", "ks3", "ks5").any { txt.contains(it) } ->
                Triple("IceRiver", "admin", "12345678")
            listOf("goldshell", "box").any { txt.contains(it) } ->
                Triple("Goldshell", "admin", "12345678")
            listOf("fluminer", "flux").any { txt.contains(it) } ->
                Triple("Fluminer", "root", "root")
            listOf("jasminer", "x4", "x16").any { txt.contains(it) } ->
                Triple("Jasminer", "root", "root")
            listOf("innosilicon", "t2t", "t3", "a10", "a11").any { txt.contains(it) } ->
                Triple("Innosilicon", "admin", "admin")
            listOf("antminer", "bitmain", "bmminer").any { txt.contains(it) } ->
                Triple("Bitmain Antminer", "root", "root")
            else -> Triple("ASIC Estándar", "root", "root")
        }
    }

    private fun detectarAlgoritmoYMoneda(modeloStr: String, poolUrl: String = ""): Pair<String, String> {
        val mod = modeloStr.lowercase()
        val url = poolUrl.lowercase()
        if (listOf("flux", "zel").any { mod.contains(it) } || listOf("flux.", "zel.", "zelhash").any { url.contains(it) })
            return Pair("ZelHash", "FLUX")
        if (listOf("alph", "alephium").any { mod.contains(it) } || listOf("alph.", "alephium.", "blake3").any { url.contains(it) })
            return Pair("Blake3", "ALPH")
        if (listOf("ks3", "ks5", "ks0", "ks1", "ks2", "kheavy", "iceriver").any { mod.contains(it) } || listOf("kas.", "kaspa", "kheavy").any { url.contains(it) })
            return Pair("kHeavyHash", "KAS")
        if (listOf("z15", "z11", "z9", "equihash").any { mod.contains(it) } || listOf("zec.", "equihash", "zen.").any { url.contains(it) })
            return Pair("Equihash", "ZEC")
        if (listOf("l7", "l9", "l3", "scrypt", "elphapex").any { mod.contains(it) } || listOf("ltc.", "doge.", "scrypt").any { url.contains(it) })
            return Pair("Scrypt", "LTC/DOGE")
        if (listOf("ka3", "kd", "blake2s").any { mod.contains(it) } || listOf("kda.", "kadena").any { url.contains(it) })
            return Pair("Blake2S", "KDA")
        if (listOf("d9", "d7", "x11").any { mod.contains(it) } || listOf("dash.", "x11").any { url.contains(it) })
            return Pair("X11", "DASH")
        if (listOf("e9", "etc", "ethash", "jasminer").any { mod.contains(it) } || listOf("etc.", "ethash", "etchash").any { url.contains(it) })
            return Pair("EtHash", "ETC")
        if (listOf("rvn", "kawpow").any { mod.contains(it) } || listOf("rvn.", "ravencoin", "kawpow").any { url.contains(it) })
            return Pair("KawPow", "RVN")
        if (url.contains("bch.")) return Pair("SHA-256", "BCH")
        return Pair("SHA-256", "BTC")
    }

    private fun round2(v: Double): Double = (v * 100.0).roundToInt() / 100.0

    private fun normalizarHashrateUniversal(valorBruto: Any?, unidadOrigen: String, algoritmo: String = "SHA-256"): Pair<Double, String> {
        var v = aFloat(valorBruto)
        val u = unidadOrigen.uppercase().trim()
        if (algoritmo == "ZelHash") {
            if (v > 1_000_000.0) return Pair(round2(v / 1_000_000.0), "MSol/s")
            if (v > 1000.0 || u == "KSOL/S") return Pair(round2(if (v > 1000.0) v / 1000.0 else v), "kSol/s")
            return Pair(round2(v), "Sol/s")
        }
        if (algoritmo == "Equihash") {
            if (v > 50000.0) v /= 1000.0
            return Pair(round2(v), "kSol/s")
        }
        if (algoritmo == "kHeavyHash") {
            if (u == "MHS" || v > 500000.0) return Pair(round2(v / 1_000_000.0), "TH/s")
            if (u == "GHS" || v > 1000.0) return Pair(round2(v / 1000.0), "TH/s")
            if (v in 0.01..50.0) return Pair(round2(v), "TH/s")
            return Pair(round2(v), "GH/s")
        }
        if (algoritmo == "Blake3") {
            if (v > 500000.0) return Pair(round2(v / 1_000_000.0), "TH/s")
            if (v > 1000.0) return Pair(round2(v / 1000.0), "GH/s")
            return Pair(round2(v), "GH/s")
        }
        if (algoritmo == "Scrypt" || algoritmo == "EtHash" || algoritmo == "KawPow") {
            if (u == "MHS" || v > 500.0) v /= 1000.0
            return Pair(round2(v), "GH/s")
        }
        if (algoritmo == "X11") {
            if (u == "MHS" || v > 500000.0) return Pair(round2(v / 1_000_000.0), "TH/s")
            if (v > 50.0) return Pair(round2(v / 1000.0), "TH/s")
            return Pair(round2(v), "TH/s")
        }
        if (u == "MHS" || v > 500000.0) return Pair(round2(v / 1_000_000.0), "TH/s")
        if (u == "THS" || (v > 0.0 && v < 1200.0)) return Pair(round2(v), "TH/s")
        return Pair(round2(v / 1000.0), "TH/s")
    }

    private fun esWhatsminer(st: JSONObject, stSum: JSONObject, verResp: String): Boolean {
        val txt = "${st.optString("Type")} ${stSum.optString("Type")} $verResp".lowercase()
        if (listOf("whatsminer", "btminer", "microbt").any { txt.contains(it) }) return true
        if (stSum.has("Env Temp") || stSum.has("Chip Temp Max") || stSum.has("Fan Speed In")) return true
        return false
    }

    // ==================== EXTRACTOR WHATSMINER ====================
    private fun extraerWhatsminer(ip: String, stSum: JSONObject, subnet: String): JSONObject {
        val ahora = System.currentTimeMillis() / 1000L
        var est = cacheInfoEstatica[ip]
        if (est == null || (ahora - ((est["ts"] as? Long) ?: 0L) > 600L)) {
            val infoWm = peticion4028(ip, "get_miner_info")?.optJSONObject("Msg")
            val verWm = peticion4028(ip, "get_version")?.optJSONObject("Msg")
            val devdet = peticion4028(ip, "devdetails")?.optJSONArray("DEVDETAILS")
            val modeloDet = if (devdet != null && devdet.length() > 0) devdet.optJSONObject(0)?.optString("Model", "") ?: "" else ""

            var modeloStr = modeloDet.ifEmpty { buscarLlave(stSum, listOf("Type", "Model"), "WhatsMiner").toString() }.trim()
            if (!modeloStr.lowercase().contains("whatsminer")) modeloStr = "WhatsMiner $modeloStr".trim()

            val fw = buscarLlave(verWm, listOf("fw_ver", "api_ver"), buscarLlave(stSum, listOf("Firmware Version"), "BTMiner")).toString().trim()
            val mac = buscarLlave(infoWm, listOf("mac"), est?.get("mac") ?: "-").toString().trim()
            val serial = buscarLlave(infoWm, listOf("minersn", "sn"), "WM-${ip.replace(".", "")}").toString().trim()
            val hostname = buscarLlave(infoWm, listOf("hostname"), "WhatsMiner${ip.substringAfterLast(".")}").toString().trim()
            val protoRaw = buscarLlave(infoWm, listOf("proto"), "Static").toString().lowercase()
            val netProto = if (protoRaw.contains("dhcp")) "DHCP" else "Static"
            val netmask = buscarLlave(infoWm, listOf("netmask"), "255.255.255.0").toString().trim()
            val gateway = buscarLlave(infoWm, listOf("gateway"), "$subnet.1").toString().trim()
            val dns = buscarLlave(infoWm, listOf("dns"), "8.8.8.8").toString().trim()

            est = mutableMapOf(
                "ts" to ahora, "marca" to "WhatsMiner", "modelo" to modeloStr, "mac" to mac,
                "serial" to serial, "fw" to fw, "hostname" to hostname, "net_proto" to netProto,
                "netmask" to netmask, "gateway" to gateway, "dns" to dns
            )
            cacheInfoEstatica[ip] = est
        }

        val modeloStr = est["modelo"].toString()
        val pl = peticion4028(ip, "pools")?.optJSONArray("POOLS") ?: JSONArray()
        fun datosPool(idx: Int): List<Any> {
            val p = if (idx < pl.length()) pl.optJSONObject(idx) else null
            if (p != null) {
                val url = buscarLlave(p, listOf("URL", "url"), "").toString()
                val usr = buscarLlave(p, listOf("User", "user"), "").toString()
                val pas = buscarLlave(p, listOf("Pass", "pass"), "123").toString()
                val stP = buscarLlave(p, listOf("Status", "status"), if (url.isNotEmpty()) "Alive" else "Dead").toString()
                val acc = aEntero(buscarLlave(p, listOf("Accepted", "accepted"), 0))
                val rej = aEntero(buscarLlave(p, listOf("Rejected", "rejected"), 0))
                return listOf(url, usr, pas, stP, acc, rej)
            }
            return listOf("", "", "123", "Dead", 0, 0)
        }
        val p1 = datosPool(0); val p2 = datosPool(1); val p3 = datosPool(2)
        val (algoritmo, moneda) = detectarAlgoritmoYMoneda(modeloStr, p1[0].toString())

        val raw5s = buscarLlave(stSum, listOf("HS RT", "MHS 5s", "MHS 1m", "MHS av"), 0)
        val rawAvg = buscarLlave(stSum, listOf("MHS av", "MHS 15m", "HS RT"), raw5s)
        val (hr5s, unidadHr) = normalizarHashrateUniversal(raw5s, "MHS", algoritmo)
        var (hr30m, _) = normalizarHashrateUniversal(rawAvg, "MHS", algoritmo)
        if (hr30m == 0.0) hr30m = hr5s

        var cw = aFloat(buscarLlave(stSum, listOf("Power", "Power_RT", "Power Limit"), 0))
        if (cw < 100.0 && hr5s > 0.0) cw = hr5s * 28.0 else if (hr5s == 0.0) cw = 120.0

        val uptimeStr = formatearUptime(buscarLlave(stSum, listOf("Elapsed", "Uptime"), 0))
        val erroresHw = aEntero(buscarLlave(stSum, listOf("Hardware Errors"), 0))
        val tempAmb = aEntero(buscarLlave(stSum, listOf("Env Temp"), 0))

        val cadenas = JSONArray()
        val tempsMax = mutableListOf<Int>()
        val devs = peticion4028(ip, "edevs")?.optJSONArray("DEVS") ?: JSONArray()
        for (i in 0 until devs.length()) {
            val dv = devs.optJSONObject(i) ?: continue
            val tChip = aEntero(buscarLlave(dv, listOf("Chip Temp Max", "Chip Temp Avg", "Temperature"), 0))
            val tPcb = aEntero(buscarLlave(dv, listOf("Temperature", "Chip Temp Min"), max(0, tChip - 6)))
            val chips = aEntero(buscarLlave(dv, listOf("Effective Chips", "Chips"), 0))
            val hw = aEntero(buscarLlave(dv, listOf("Hardware Errors"), 0))
            val freq = aEntero(buscarLlave(dv, listOf("Chip Frequency", "Frequency"), 0))
            val (hrC, _) = normalizarHashrateUniversal(buscarLlave(dv, listOf("MHS 5s", "MHS av"), 0), "MHS", algoritmo)
            cadenas.put(JSONObject()
                .put("id", aEntero(dv.opt("Slot") ?: i))
                .put("temp_c", tChip).put("temp_p", tPcb).put("chips", chips)
                .put("hw", hw).put("freq", freq).put("hr", hrC))
            if (tChip > 0) tempsMax.add(tChip)
        }

        val tm = tempsMax.maxOrNull() ?: aEntero(buscarLlave(stSum, listOf("Chip Temp Max", "Temperature"), 65))
        val fanIn = aEntero(buscarLlave(stSum, listOf("Fan Speed In"), 0))
        val fanOut = aEntero(buscarLlave(stSum, listOf("Fan Speed Out"), 0))
        val ml = modeloStr.lowercase()
        val fansStr = when {
            fanIn > 0 || fanOut > 0 -> "$fanIn / $fanOut RPM"
            ml.contains("m33") || ml.contains("m53") || ml.contains("m63") || ml.contains("hyd") -> "💧 Hydro (Refrigeración Líquida Activa)"
            else -> "0 / 0 RPM"
        }

        return JSONObject()
            .put("ip", ip).put("marca", "WhatsMiner").put("modelo", modeloStr)
            .put("algoritmo", algoritmo).put("moneda", moneda).put("unidad", unidadHr)
            .put("hashrate", hr5s).put("hashrate_30m", hr30m)
            .put("temp", tm).put("temp_amb", tempAmb).put("consumo", cw.roundToInt()).put("errores", erroresHw)
            .put("fans", fansStr).put("mac", est["mac"]).put("serial", est["serial"]).put("firmware", est["fw"])
            .put("cadenas", cadenas).put("uptime", uptimeStr)
            .put("hostname", est["hostname"]).put("net_proto", est["net_proto"])
            .put("netmask", est["netmask"]).put("gateway", est["gateway"]).put("dns", est["dns"])
            .put("pool1", p1[0]).put("worker1", p1[1]).put("pass1", p1[2]).put("status1", p1[3]).put("acc1", p1[4]).put("rej1", p1[5])
            .put("pool2", p2[0]).put("worker2", p2[1]).put("pass2", p2[2]).put("status2", p2[3]).put("acc2", p2[4]).put("rej2", p2[5])
            .put("pool3", p3[0]).put("worker3", p3[1]).put("pass3", p3[2]).put("status3", p3[3]).put("acc3", p3[4]).put("rej3", p3[5])
            .put("cred_defecto", "admin / admin")
    }

    // ==================== EXTRACTOR CANAAN AVALON (Q90 HYDRO, A12xx-A15xx) ====================
    private fun esAvalon(st: JSONObject, stSum: JSONObject, verResp: String): Boolean {
        val txt = "${st.optString("Type")} ${st.optString("Miner")} ${stSum.optString("Type")} ${stSum.optString("Miner")} $verResp".lowercase()
        if (listOf("avalon", "canaan", "ava0", "ava1", "q90", "a10", "a11", "a12", "a13", "a14", "a15", "nano").any { txt.contains(it) }) return true
        if (st.has("AVA0") || st.has("MM Version") || st.has("DNA")) return true
        return false
    }

    private fun extraerAvalon(ip: String, stSum: JSONObject, subnet: String): JSONObject {
        val ahora = System.currentTimeMillis() / 1000L
        var est = cacheInfoEstatica[ip]

        val statsRaw = peticion4028(ip, "stats")
        val stAva = JSONObject()
        val statsArr = statsRaw?.optJSONArray("STATS")
        if (statsArr != null) {
            for (i in 0 until statsArr.length()) {
                val item = statsArr.optJSONObject(i) ?: continue
                val ks = item.keys()
                while (ks.hasNext()) { val k = ks.next(); stAva.put(k, item.opt(k)) }
                val ava0 = item.optJSONObject("AVA0")
                if (ava0 != null) {
                    val k0 = ava0.keys()
                    while (k0.hasNext()) { val k = k0.next(); stAva.put(k, ava0.opt(k)) }
                }
            }
        }

        val verRaw = peticion4028(ip, "version")
        var verStr = ""
        var minerStr = ""
        val vArr = verRaw?.optJSONArray("VERSION")
        if (vArr != null && vArr.length() > 0) {
            val vObj = vArr.optJSONObject(0)
            verStr = vObj?.optString("CGMiner", "") ?: vObj?.optString("API", "") ?: ""
            minerStr = vObj?.optString("Miner", "") ?: ""
        }

        if (est == null || (ahora - ((est["ts"] as? Long) ?: 0L) > 600L)) {
            var modCand = minerStr.ifEmpty { buscarLlave(stSum, listOf("Type", "Model", "Miner"), buscarLlave(stAva, listOf("Ver", "Type", "Model"), "Avalon Q90")).toString() }.trim()
            if (modCand.isEmpty() || modCand.lowercase() in listOf("canaan", "miner")) modCand = "Avalon Q90"
            if (!modCand.lowercase().contains("avalon")) modCand = "Avalon $modCand".trim()

            val dna = buscarLlave(stAva, listOf("DNA", "dna", "SN", "sn"), "AVA-${ip.replace(".", "")}").toString().trim()
            val fw = verStr.ifEmpty { buscarLlave(stAva, listOf("Ver", "Firmware Version", "CGMiner"), "Canaan Avalon Firmware").toString() }.trim()
            val mac = buscarLlave(stAva, listOf("MAC", "mac"), est?.get("mac") ?: "-").toString().trim()
            val hostname = buscarLlave(stAva, listOf("hostname"), "Avalon${ip.substringAfterLast(".")}").toString().trim()

            est = mutableMapOf(
                "ts" to ahora, "marca" to "Avalon", "modelo" to modCand, "mac" to mac,
                "serial" to dna, "fw" to fw, "hostname" to hostname, "net_proto" to "Static",
                "netmask" to "255.255.255.0", "gateway" to "$subnet.1", "dns" to "8.8.8.8"
            )
            cacheInfoEstatica[ip] = est
        }

        val modeloStr = est["modelo"].toString()
        val pl = peticion4028(ip, "pools")?.optJSONArray("POOLS") ?: JSONArray()
        fun datosPool(idx: Int): List<Any> {
            val p = if (idx < pl.length()) pl.optJSONObject(idx) else null
            if (p != null) {
                val url = buscarLlave(p, listOf("URL", "url"), "").toString()
                val usr = buscarLlave(p, listOf("User", "user"), "").toString()
                val pas = buscarLlave(p, listOf("Pass", "pass"), "123").toString()
                val stP = buscarLlave(p, listOf("Status", "status"), if (url.isNotEmpty()) "Alive" else "Dead").toString()
                val acc = aEntero(buscarLlave(p, listOf("Accepted", "accepted"), 0))
                val rej = aEntero(buscarLlave(p, listOf("Rejected", "rejected"), 0))
                return listOf(url, usr, pas, stP, acc, rej)
            }
            return listOf("", "", "123", "Dead", 0, 0)
        }
        val p1 = datosPool(0); val p2 = datosPool(1); val p3 = datosPool(2)
        val (algoritmo, moneda) = detectarAlgoritmoYMoneda(modeloStr, p1[0].toString())

        val raw5s = buscarLlave(stSum, listOf("GHS 5s", "MHS 5s", "HS 5s", "rate_5s"), 0)
        val rawAvg = buscarLlave(stSum, listOf("GHS av", "MHS av", "HS av"), raw5s)
        val uOrig = if (stSum.has("GHS 5s") || stSum.has("GHS av")) "GHS" else "MHS"
        val (hr5s, unidadHr) = normalizarHashrateUniversal(raw5s, uOrig, algoritmo)
        var (hr30m, _) = normalizarHashrateUniversal(rawAvg, uOrig, algoritmo)
        if (hr30m == 0.0) hr30m = hr5s

        var cw = aFloat(buscarLlave(stSum, listOf("Power", "Power Limit"), buscarLlave(stAva, listOf("Power", "power"), 0)))
        if (cw < 100.0 && hr5s > 0.0) cw = hr5s * 32.0 else if (hr5s == 0.0) cw = 100.0

        val uptimeStr = formatearUptime(buscarLlave(stSum, listOf("Elapsed", "Uptime"), 0))
        val erroresHw = aEntero(buscarLlave(stSum, listOf("Hardware Errors"), 0))

        var tInlet = aEntero(buscarLlave(stAva, listOf("ITemp", "Temp_Inlet", "Inlet Temp"), 0))
        var tOutlet = aEntero(buscarLlave(stAva, listOf("OTemp", "Temp_Outlet", "Outlet Temp"), 0))
        var tChipMax = aEntero(buscarLlave(stAva, listOf("TMax", "Temp_Max", "Chip Temp Max"), 0))
        if (tChipMax >= 150) tChipMax = 0
        if (tInlet >= 120) tInlet = 0
        if (tOutlet >= 120) tOutlet = 0

        val tm = if (tChipMax > 0) tChipMax else (if (tOutlet > 0) tOutlet else aEntero(buscarLlave(stSum, listOf("Temperature"), 65)))
        val fan1 = aEntero(buscarLlave(stAva, listOf("Fan1", "fan1", "Fan Speed In", "Fan Speed"), buscarLlave(stSum, listOf("Fan Speed In", "Fan1"), 0)))
        val fan2 = aEntero(buscarLlave(stAva, listOf("Fan2", "fan2", "Fan Speed Out"), buscarLlave(stSum, listOf("Fan Speed Out", "Fan2"), 0)))

        val esAvalonHydro = listOf("hyd", "hydro", "water", "1566ha").any { modeloStr.lowercase().contains(it) }
        val fansStr = when {
            esAvalonHydro && tInlet > 0 && tOutlet > 0 -> "💧 Hydro (Entrada ${tInlet}°C / Salida ${tOutlet}°C · ΔT ${max(0, tOutlet - tInlet)}°C)"
            esAvalonHydro -> "💧 Hydro (Refrigeración Líquida Activa)"
            fan1 > 0 || fan2 > 0 -> "$fan1 / $fan2 RPM"
            else -> "Auto / Silencioso"
        }

        val cadenas = JSONArray()
        for (i in 0 until 3) {
            cadenas.put(JSONObject()
                .put("id", i + 1).put("temp_c", tm).put("temp_p", if (tOutlet > 0) tOutlet else max(0, tm - 8))
                .put("chips", 120).put("hw", if (erroresHw > 0) erroresHw / 3 else 0).put("freq", 450)
                .put("hr", if (hr5s > 0.0) round2(hr5s / 3.0) else 0.0))
        }

        return JSONObject()
            .put("ip", ip).put("marca", "Avalon").put("modelo", modeloStr)
            .put("algoritmo", algoritmo).put("moneda", moneda).put("unidad", unidadHr)
            .put("hashrate", hr5s).put("hashrate_30m", hr30m)
            .put("temp", tm).put("temp_amb", tInlet).put("consumo", cw.roundToInt()).put("errores", erroresHw)
            .put("fans", fansStr).put("mac", est["mac"]).put("serial", est["serial"]).put("firmware", est["fw"])
            .put("cadenas", cadenas).put("uptime", uptimeStr)
            .put("hostname", est["hostname"]).put("net_proto", est["net_proto"])
            .put("netmask", est["netmask"]).put("gateway", est["gateway"]).put("dns", est["dns"])
            .put("pool1", p1[0]).put("worker1", p1[1]).put("pass1", p1[2]).put("status1", p1[3]).put("acc1", p1[4]).put("rej1", p1[5])
            .put("pool2", p2[0]).put("worker2", p2[1]).put("pass2", p2[2]).put("status2", p2[3]).put("acc2", p2[4]).put("rej2", p2[5])
            .put("pool3", p3[0]).put("worker3", p3[1]).put("pass3", p3[2]).put("status3", p3[3]).put("acc3", p3[4]).put("rej3", p3[5])
            .put("cred_defecto", "root / root")
    }

    // ==================== EXTRACTOR FLUMINER / ALTCOINS / ASICS UNIVERSALES ====================
    private fun esFluminerOAltcoin(st: JSONObject, stSum: JSONObject, verResp: String): Boolean {
        val txt = "${st.optString("Type")} ${st.optString("Miner")} ${stSum.optString("Type")} ${stSum.optString("Miner")} $verResp".lowercase()
        return listOf("fluminer", "flux", "iceriver", "goldshell", "jasminer", "innosilicon", "desiwe", "elphapex").any { txt.contains(it) }
    }

    private fun extraerFluminerOGenerico(ip: String, st: JSONObject, stSum: JSONObject, subnet: String): JSONObject {
        val ahora = System.currentTimeMillis() / 1000L
        var est = cacheInfoEstatica[ip]

        val verRaw = peticion4028(ip, "version")
        var verStr = ""
        var minerStr = ""
        val vArr = verRaw?.optJSONArray("VERSION")
        if (vArr != null && vArr.length() > 0) {
            val vObj = vArr.optJSONObject(0)
            verStr = vObj?.optString("CGMiner", "") ?: vObj?.optString("API", "") ?: ""
            minerStr = vObj?.optString("Miner", "") ?: ""
        }

        val pl = peticion4028(ip, "pools")?.optJSONArray("POOLS") ?: JSONArray()
        fun datosPool(idx: Int): List<Any> {
            val p = if (idx < pl.length()) pl.optJSONObject(idx) else null
            if (p != null) {
                val url = buscarLlave(p, listOf("URL", "url"), "").toString()
                val usr = buscarLlave(p, listOf("User", "user"), "").toString()
                val pas = buscarLlave(p, listOf("Pass", "pass"), "123").toString()
                val stP = buscarLlave(p, listOf("Status", "status"), if (url.isNotEmpty()) "Alive" else "Dead").toString()
                val acc = aEntero(buscarLlave(p, listOf("Accepted", "accepted"), 0))
                val rej = aEntero(buscarLlave(p, listOf("Rejected", "rejected"), 0))
                return listOf(url, usr, pas, stP, acc, rej)
            }
            return listOf("", "", "123", "Dead", 0, 0)
        }
        val p1 = datosPool(0); val p2 = datosPool(1); val p3 = datosPool(2)

        val candTxt = "$minerStr ${buscarLlave(st, listOf("Type", "Model", "Miner"), "")} ${buscarLlave(stSum, listOf("Type", "Model"), "")} ${p1[0]}".lowercase()
        val marca = when {
            candTxt.contains("fluminer") || candTxt.contains("flux") -> "Fluminer"
            candTxt.contains("iceriver") || candTxt.contains("ks") -> "IceRiver"
            candTxt.contains("goldshell") -> "Goldshell"
            candTxt.contains("jasminer") -> "Jasminer"
            candTxt.contains("innosilicon") -> "Innosilicon"
            else -> "ASIC Universal"
        }

        if (est == null || (ahora - ((est["ts"] as? Long) ?: 0L) > 600L)) {
            val modDet = minerStr.ifEmpty { buscarLlave(st, listOf("Type", "Model", "Miner"), buscarLlave(stSum, listOf("Type", "Model"), "$marca ASIC")).toString() }.trim()
            val serial = buscarLlave(st, listOf("Serial", "sn", "DNA"), "${marca.take(3).uppercase()}-${ip.replace(".", "")}").toString().trim()
            val fw = verStr.ifEmpty { buscarLlave(st, listOf("Firmware Version", "CGMiner"), "$marca Firmware").toString() }.trim()
            val mac = buscarLlave(st, listOf("mac", "MAC"), est?.get("mac") ?: "-").toString().trim()
            val hostname = buscarLlave(st, listOf("hostname"), "$marca${ip.substringAfterLast(".")}").toString().trim()

            est = mutableMapOf(
                "ts" to ahora, "marca" to marca, "modelo" to modDet, "mac" to mac,
                "serial" to serial, "fw" to fw, "hostname" to hostname, "net_proto" to "Static",
                "netmask" to "255.255.255.0", "gateway" to "$subnet.1", "dns" to "8.8.8.8"
            )
            cacheInfoEstatica[ip] = est
        }

        val modeloStr = est["modelo"].toString()
        val (algoritmo, moneda) = detectarAlgoritmoYMoneda(modeloStr, p1[0].toString())

        val raw5s = buscarLlave(stSum, listOf("HS 5s", "GHS 5s", "MHS 5s", "rate_5s", "KHS 5s"), buscarLlave(st, listOf("GHS 5s", "MHS 5s", "HS 5s"), 0))
        val rawAvg = buscarLlave(stSum, listOf("HS av", "GHS av", "MHS av", "rate_avg"), raw5s)
        var uOrig = "MHS"
        if (stSum.has("GHS 5s") || stSum.has("GHS av")) uOrig = "GHS"
        else if (stSum.has("THS 5s") || stSum.has("THS rt")) uOrig = "THS"
        else if (stSum.has("KHS 5s")) uOrig = "KHS"

        val (hr5s, unidadHr) = normalizarHashrateUniversal(raw5s, uOrig, algoritmo)
        var (hr30m, _) = normalizarHashrateUniversal(rawAvg, uOrig, algoritmo)
        if (hr30m == 0.0) hr30m = hr5s

        var cw = aFloat(buscarLlave(stSum, listOf("Power", "Power Limit"), buscarLlave(st, listOf("Power", "power"), 0)))
        if (cw < 50.0 && hr5s > 0.0) cw = hr5s * (if (unidadHr.contains("TH")) 30.0 else 1.2)

        val uptimeStr = formatearUptime(buscarLlave(stSum, listOf("Elapsed", "Uptime"), 0))
        val erroresHw = aEntero(buscarLlave(stSum, listOf("Hardware Errors"), 0))

        var tm = aEntero(buscarLlave(st, listOf("Temperature", "Chip Temp Max", "temp_max"), buscarLlave(stSum, listOf("Temperature", "Chip Temp Max"), 60)))
        if (tm >= 150) tm = 0
        var tAmb = aEntero(buscarLlave(st, listOf("Env Temp", "temp_pcb", "Inlet Temp"), 0))
        if (tAmb >= 120) tAmb = 0

        val fan1 = aEntero(buscarLlave(st, listOf("Fan Speed In", "Fan1", "fan1"), 0))
        val fan2 = aEntero(buscarLlave(st, listOf("Fan Speed Out", "Fan2", "fan2"), 0))
        val fansStr = if (fan1 > 0 || fan2 > 0) "$fan1 / $fan2 RPM" else "Auto / Normal"

        val cadenas = JSONArray()
        cadenas.put(JSONObject()
            .put("id", 1).put("temp_c", tm).put("temp_p", max(0, tm - 8))
            .put("chips", 60).put("hw", erroresHw).put("freq", 0).put("hr", hr5s))

        val (_, credUser, credPass) = determinarCredencialesDefecto(marca, modeloStr)

        return JSONObject()
            .put("ip", ip).put("marca", marca).put("modelo", modeloStr)
            .put("algoritmo", algoritmo).put("moneda", moneda).put("unidad", unidadHr)
            .put("hashrate", hr5s).put("hashrate_30m", hr30m)
            .put("temp", tm).put("temp_amb", tAmb).put("consumo", cw.roundToInt()).put("errores", erroresHw)
            .put("fans", fansStr).put("mac", est["mac"]).put("serial", est["serial"]).put("firmware", est["fw"])
            .put("cadenas", cadenas).put("uptime", uptimeStr)
            .put("hostname", est["hostname"]).put("net_proto", est["net_proto"])
            .put("netmask", est["netmask"]).put("gateway", est["gateway"]).put("dns", est["dns"])
            .put("pool1", p1[0]).put("worker1", p1[1]).put("pass1", p1[2]).put("status1", p1[3]).put("acc1", p1[4]).put("rej1", p1[5])
            .put("pool2", p2[0]).put("worker2", p2[1]).put("pass2", p2[2]).put("status2", p2[3]).put("acc2", p2[4]).put("rej2", p2[5])
            .put("pool3", p3[0]).put("worker3", p3[1]).put("pass3", p3[2]).put("status3", p3[3]).put("acc3", p3[4]).put("rej3", p3[5])
            .put("cred_defecto", "$credUser / $credPass")
    }

    // ==================== EXTRACTOR UNIVERSAL ====================
    private fun extraerTodo(ip: String, subnet: String): JSONObject? {
        val summary = peticion4028(ip, "summary")
        var stSum = JSONObject()
        val sumArr = summary?.optJSONArray("SUMMARY")
        if (sumArr != null && sumArr.length() > 0) {
            stSum = sumArr.optJSONObject(0) ?: JSONObject()
        }

        if (stSum.length() > 0 && esWhatsminer(JSONObject(), stSum, summary?.optString("STATUS", "") ?: "")) {
            return extraerWhatsminer(ip, stSum, subnet)
        }
        if (stSum.length() > 0 && esAvalon(JSONObject(), stSum, summary?.optString("STATUS", "") ?: "")) {
            return extraerAvalon(ip, stSum, subnet)
        }

        val stats = peticion4028(ip, "stats")
        val st = JSONObject()
        val statsArr = stats?.optJSONArray("STATS")
        if (statsArr != null) {
            for (i in 0 until statsArr.length()) {
                val item = statsArr.optJSONObject(i) ?: continue
                val keys = item.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    st.put(k, item.opt(k))
                }
            }
        }

        if ((st.length() > 0 || stSum.length() > 0) && esWhatsminer(st, stSum, "")) {
            return extraerWhatsminer(ip, stSum, subnet)
        }
        if ((st.length() > 0 || stSum.length() > 0) && esAvalon(st, stSum, "")) {
            return extraerAvalon(ip, stSum, subnet)
        }
        if ((st.length() > 0 || stSum.length() > 0) && esFluminerOAltcoin(st, stSum, "")) {
            return extraerFluminerOGenerico(ip, st, stSum, subnet)
        }

        if (st.length() == 0 && stSum.length() == 0) {
            val rawSt = peticionHttp(ip, "stats.cgi", timeoutMs = 4500)
            if (!rawSt.isNullOrEmpty()) {
                try {
                    val jSt = JSONObject(rawSt)
                    val arr = jSt.optJSONArray("STATS")
                    val src = if (arr != null && arr.length() > 0) arr.optJSONObject(0) ?: jSt else jSt
                    val ks = src.keys(); while (ks.hasNext()) { val k = ks.next(); st.put(k, src.opt(k)) }
                } catch (_: Exception) {}
            }
            if (st.length() == 0) {
                val rawSum = peticionHttp(ip, "summary.cgi", timeoutMs = 4500)
                if (!rawSum.isNullOrEmpty()) {
                    try {
                        val jSum = JSONObject(rawSum)
                        val arr = jSum.optJSONArray("SUMMARY")
                        stSum = if (arr != null && arr.length() > 0) arr.optJSONObject(0) ?: jSum else jSum
                    } catch (_: Exception) {}
                }
            }
        }

        if (st.length() == 0 && stSum.length() == 0) return null

        var modeloStr = buscarLlave(
            st, listOf("Type", "Miner", "Model", "minertype", "Description"),
            buscarLlave(stSum, listOf("Type", "Miner", "Description"), "")
        ).toString().trim()

        val ahora = System.currentTimeMillis() / 1000L
        var est = cacheInfoEstatica[ip]
        if (est == null || (ahora - ((est["ts"] as? Long) ?: 0L) > 600L)) {
            var mac = est?.get("mac")?.toString() ?: "-"
            var fw = est?.get("fw")?.toString() ?: "Desconocido"
            var hostname = est?.get("hostname")?.toString() ?: "Antminer${ip.substringAfterLast(".")}"
            var netProto = est?.get("net_proto")?.toString() ?: "Static"
            var netmask = est?.get("netmask")?.toString() ?: "255.255.255.0"
            var gateway = est?.get("gateway")?.toString() ?: "$subnet.1"
            var dns = est?.get("dns")?.toString() ?: "8.8.8.8"

            for (k in listOf("Firmware Version", "CompileTime", "version", "CGMiner", "SubVersion")) {
                val v = buscarLlave(st, listOf(k), "").toString().trim()
                if (v.isNotEmpty() && v.lowercase() != "release" && v.length > 1) {
                    fw = v; break
                }
            }

            val infoHttp = peticionHttp(ip, "get_system_info.cgi", timeoutMs = 4500)
            if (!infoHttp.isNullOrEmpty()) {
                try {
                    val jNet = JSONObject(infoHttp)
                    if (modeloStr.isEmpty() || modeloStr.lowercase() == "antminer") {
                        val mHttp = buscarLlave(jNet, listOf("minertype", "model", "type"), "").toString().trim()
                        if (mHttp.isNotEmpty()) modeloStr = mHttp
                    }
                    mac = buscarLlave(jNet, listOf("macaddr", "mac"), mac).toString().trim()
                    val fwCand = buscarLlave(jNet, listOf("system_filesystem_version", "file_system_version", "firmware_type", "CompileTime"), "").toString().trim()
                    if (fwCand.isNotEmpty() && fwCand.lowercase() != "release") fw = fwCand
                    hostname = buscarLlave(jNet, listOf("hostname", "ipHost"), hostname).toString().trim()
                    val pCand = buscarLlave(jNet, listOf("nettype", "conf_nettype", "ipPro"), "").toString().trim()
                    if (pCand.isNotEmpty()) netProto = if (pCand.lowercase().contains("dhcp") || pCand == "1") "DHCP" else "Static"
                    netmask = buscarLlave(jNet, listOf("netmask", "conf_netmask", "ipSub"), netmask).toString().trim()
                    gateway = buscarLlave(jNet, listOf("gateway", "conf_gateway", "ipGateway"), gateway).toString().trim()
                    dns = buscarLlave(jNet, listOf("dnsservers", "conf_dnsservers", "ipDns"), dns).toString().trim()
                } catch (_: Exception) {}
            }

            est = mutableMapOf(
                "ts" to ahora, "marca" to "Antminer", "modelo" to modeloStr, "mac" to mac, "fw" to fw,
                "hostname" to hostname, "net_proto" to netProto, "netmask" to netmask, "gateway" to gateway, "dns" to dns
            )
            cacheInfoEstatica[ip] = est
        }

        if (modeloStr.isEmpty() && est["modelo"].toString().isNotEmpty()) modeloStr = est["modelo"].toString()

        val pl = peticion4028(ip, "pools")?.optJSONArray("POOLS") ?: JSONArray()
        fun datosPool(idx: Int): MutableList<Any> {
            val p = if (idx < pl.length()) pl.optJSONObject(idx) else null
            if (p != null) {
                val url = buscarLlave(p, listOf("URL", "url"), "").toString()
                val usr = buscarLlave(p, listOf("User", "user", "worker"), "").toString()
                val pas = buscarLlave(p, listOf("Pass", "pass", "password"), "123").toString()
                val stP = buscarLlave(p, listOf("Status", "status"), if (url.isNotEmpty()) "Alive" else "Dead").toString()
                val acc = aEntero(buscarLlave(p, listOf("Accepted", "accepted", "accept"), 0))
                val rej = aEntero(buscarLlave(p, listOf("Rejected", "rejected", "reject"), 0))
                return mutableListOf(url, usr, pas, stP, acc, rej)
            }
            return mutableListOf("", "", "123", "Dead", 0, 0)
        }
        val p1 = datosPool(0); val p2 = datosPool(1); val p3 = datosPool(2)
        if (((p1[4] as Int) + (p2[4] as Int) + (p3[4] as Int)) == 0) {
            p1[4] = aEntero(buscarLlave(stSum, listOf("Accepted", "accepted"), 0))
            p1[5] = aEntero(buscarLlave(stSum, listOf("Rejected", "rejected"), 0))
        }

        var raw5s = buscarLlave(st, listOf("GHS 5s", "rate_5s"), buscarLlave(stSum, listOf("GHS 5s", "HS 5s"), null))
        var uDetectada = "GHS"
        if (raw5s == null) {
            raw5s = buscarLlave(stSum, listOf("THS 5s", "THS rt"), null)
            if (raw5s != null) uDetectada = "THS"
        }
        if (raw5s == null) {
            raw5s = buscarLlave(stSum, listOf("MHS 5s", "MHS av"), 0)
            if (aFloat(raw5s) > 0.0) uDetectada = "MHS"
        }
        val rawAvg = buscarLlave(st, listOf("GHS av", "rate_avg", "rate_30m"), buscarLlave(stSum, listOf("GHS av", "THS av", "MHS av"), raw5s))

        val valTest = aFloat(raw5s)
        if (modeloStr.isEmpty() || listOf("antminer", "bmminer", "cgminer").contains(modeloStr.lowercase())) {
            modeloStr = when {
                valTest in 400.0..1100.0 -> "Antminer Z15 Pro"
                valTest > 380000.0 || (uDetectada == "THS" && valTest > 380.0) -> "Antminer S23 Hyd"
                valTest > 280000.0 || (uDetectada == "THS" && valTest > 280.0) -> "Antminer S23"
                valTest > 180000.0 || (uDetectada == "THS" && valTest > 180.0) -> "Antminer S21+"
                valTest > 130000.0 || (uDetectada == "THS" && valTest > 130.0) -> "Antminer S19 XP"
                valTest > 100000.0 || (uDetectada == "THS" && valTest > 100.0) -> "Antminer S19k Pro"
                else -> "Antminer S19"
            }
        }

        val (algoritmo, moneda) = detectarAlgoritmoYMoneda(modeloStr, p1[0].toString())
        val (hr5s, unidadHr) = normalizarHashrateUniversal(raw5s, uDetectada, algoritmo)
        var (hr30m, _) = normalizarHashrateUniversal(rawAvg, uDetectada, algoritmo)
        if (hr30m == 0.0) hr30m = hr5s

        val ml = modeloStr.lowercase()
        var chipsDef = 114
        var efi = 17.5
        when {
            ml.contains("z15") -> { chipsDef = 3; efi = 3.31 }
            ml.contains("l9") -> { chipsDef = 120; efi = 210.0 }
            ml.contains("l7") -> { chipsDef = 120; efi = 360.0 }
            ml.contains("ks5") -> { chipsDef = 92; efi = 150.0 }
            ml.contains("ks3") -> { chipsDef = 92; efi = 340.0 }
            ml.contains("s23") -> { chipsDef = 108; efi = if (ml.contains("hyd")) 11.5 else 13.5 }
            ml.contains("s21") -> { chipsDef = 108; efi = if (ml.contains("hyd")) 16.0 else 16.5 }
            ml.contains("s19k") -> { chipsDef = 77; efi = 23.0 }
            ml.contains("xp") -> { chipsDef = 110; efi = if (ml.contains("hyd")) 20.8 else 21.5 }
            ml.contains("s19") -> { chipsDef = 114; efi = 29.5 }
        }

        var cw = aFloat(buscarLlave(st, listOf("watt", "power", "chain_power", "power_consumption", "psu_power"), buscarLlave(stSum, listOf("Power", "watt", "Power Consumption"), 0)))
        if (cw < 100.0 && hr5s > 0.0) cw = hr5s * efi else if (hr5s == 0.0) cw = 120.0

        val uptimeStr = formatearUptime(buscarLlave(st, listOf("Elapsed", "uptime"), buscarLlave(stSum, listOf("Elapsed", "uptime"), 0)))
        val erroresHw = aEntero(buscarLlave(st, listOf("Hardware Errors", "Device Hardware Errors", "hw_errors"), buscarLlave(stSum, listOf("Hardware Errors"), 0)))
        val freqGlobal = aEntero(buscarLlave(st, listOf("frequency", "freq_avg", "freq", "total_freqavg"), 0))

        val ambCandidates = mutableListOf<Int>()
        val pcbCandidates = mutableListOf<Int>()
        val stKeys = st.keys()
        while (stKeys.hasNext()) {
            val k = stKeys.next()
            val kl = k.lowercase()
            if (listOf("ambient", "env_temp", "temp_in", "inlet", "water_in", "liquid_in").any { kl.contains(it) }) {
                val va = aEnteroMin(st.opt(k))
                if (va in 10..60) ambCandidates.add(va)
            } else if (listOf("temp_pic", "temp_pcb").any { kl.contains(it) }) {
                val nums = extraerNumeros(st.opt(k)).filter { it in 10..60 }
                if (nums.isNotEmpty()) pcbCandidates.add(nums.min())
            }
        }

        val cadenas = mutableListOf<JSONObject>()
        val tempsMax = mutableListOf<Int>()

        val sChains = st.optJSONArray("chain")
        if (sChains != null && sChains.length() > 0) {
            for (idx in 0 until sChains.length()) {
                val ch = sChains.optJSONObject(idx) ?: continue
                val tChip = aEntero(buscarLlave(ch, listOf("temp2", "temp_chip", "temp_max"), 0))
                val rawPcb = buscarLlave(ch, listOf("temp", "temp_pcb", "temp_pic", "temp_water"), 0)
                var tPcb = aEntero(rawPcb)
                if (tPcb == 0 && tChip > 0) tPcb = max(0, tChip - 5)

                val rawAmbCh = buscarLlave(ch, listOf("ambientemp", "ambient_temp", "env_temp", "temp_in", "water_in"), null)
                if (rawAmbCh != null) {
                    val vac = aEnteroMin(rawAmbCh)
                    if (vac in 10..58) ambCandidates.add(vac)
                } else {
                    val numsPcb = extraerNumeros(rawPcb).filter { it in 10..58 }
                    if (numsPcb.size >= 2) {
                        ambCandidates.add(numsPcb.min())
                    } else if (numsPcb.size == 1 && tChip > 0 && numsPcb.first() <= tChip - 6) {
                        pcbCandidates.add(numsPcb.first())
                    }
                }

                var chipsNum = aEntero(buscarLlave(ch, listOf("asic_num", "chipNum", "acn", "chips", "asic"), 0))
                val hwNum = aEntero(buscarLlave(ch, listOf("hw", "hwNum", "hw_errors"), 0))
                val freqNum = aEntero(buscarLlave(ch, listOf("freq_avg", "freq", "frequency"), freqGlobal))
                val rateRaw = aFloat(buscarLlave(ch, listOf("rate_real", "rate", "rate_ideal"), 0))
                val (hrChain, _) = normalizarHashrateUniversal(rateRaw, uDetectada, algoritmo)

                if (tChip > 0 || tPcb > 0 || chipsNum > 0 || hrChain > 0.0) {
                    if (chipsNum == 0 && (tChip > 0 || hrChain > 0.0)) chipsNum = chipsDef
                    cadenas.add(JSONObject()
                        .put("id", cadenas.size).put("temp_c", tChip).put("temp_p", tPcb)
                        .put("chips", chipsNum).put("hw", hwNum).put("freq", freqNum).put("hr", hrChain))
                    if (tChip > 0) tempsMax.add(tChip)
                }
            }
        }

        if (cadenas.isEmpty()) {
            for (i in 1..9) {
                val tc = buscarLlave(st, listOf("temp2_$i", "temp3_$i", "temp_chip_$i", "temp_chip$i", "temp_max_$i"))
                val tp = buscarLlave(st, listOf("temp_$i", "temp1_$i", "temp_pcb_$i", "temp_pcb$i", "temp_pic_$i", "temp_pic$i"))
                val acn = buscarLlave(st, listOf("chain_acn$i", "chain acn$i", "acn$i", "acn_$i", "chain_acs$i", "chain acs$i"))
                val hwC = buscarLlave(st, listOf("chain_hw$i", "chain hw$i", "hw$i", "hw_$i"))
                val rtC = buscarLlave(st, listOf("chain_rate$i", "chain rate$i", "rate$i", "rate_$i"))
                val fqC = buscarLlave(st, listOf("freq_avg$i", "freq$i", "freq_$i", "chain_freq$i"), freqGlobal)

                val tChip = aEntero(tc)
                var tPcb = aEntero(tp)

                for (rawT in listOf(tp, st.opt("temp1_$i"), st.opt("temp_in_$i"), st.opt("temp_in$i"), st.opt("ambientemp_$i"), st.opt("temp_pic$i"), st.opt("temp_pcb$i"))) {
                    if (rawT != null) {
                        val numsT = extraerNumeros(rawT).filter { it in 10..58 }
                        if (numsT.size >= 2) {
                            ambCandidates.add(numsT.min())
                        } else if (numsT.size == 1 && tChip > 0 && numsT.first() <= tChip - 6) {
                            pcbCandidates.add(numsT.first())
                        }
                    }
                }

                var chipsNum = aEntero(acn)
                val hwNum = aEntero(hwC)
                val freqNum = aEntero(fqC)
                val (hrChain, _) = normalizarHashrateUniversal(rtC, uDetectada, algoritmo)

                if (tChip > 0 || tPcb > 0 || chipsNum > 0 || hrChain > 0.0) {
                    if (tPcb == 0 && tChip > 0) tPcb = max(0, tChip - 5)
                    if (chipsNum == 0 && (tChip > 0 || hrChain > 0.0)) chipsNum = chipsDef
                    cadenas.add(JSONObject()
                        .put("id", cadenas.size).put("temp_c", tChip).put("temp_p", tPcb)
                        .put("chips", chipsNum).put("hw", hwNum).put("freq", freqNum).put("hr", hrChain))
                    if (tChip > 0) tempsMax.add(tChip)
                }
            }
        }

        if (cadenas.isEmpty()) {
            val tFb = aEntero(st.opt("temp_max") ?: 65)
            val numPlacas = if (ml.contains("z15") || ml.contains("l7") || ml.contains("l9")) 4 else 3
            val hrDiv = round2(hr5s / numPlacas.toDouble())
            for (pIdx in 0 until numPlacas) {
                cadenas.add(JSONObject()
                    .put("id", pIdx).put("temp_c", tFb).put("temp_p", max(0, tFb - 5))
                    .put("chips", chipsDef).put("hw", 0).put("freq", freqGlobal).put("hr", hrDiv))
            }
            tempsMax.add(tFb)
        }

        val sumaHw = cadenas.sumOf { it.optInt("hw", 0) }
        if (sumaHw == 0 && erroresHw > 0 && cadenas.isNotEmpty()) cadenas[0].put("hw", erroresHw)
        for (c in cadenas) {
            if (c.optDouble("hr", 0.0) == 0.0 && hr5s > 0.0) {
                c.put("hr", round2(hr5s / cadenas.size.toDouble()))
            }
        }

        val cadenasArr = JSONArray()
        for (c in cadenas) cadenasArr.put(c)

        val tm = tempsMax.maxOrNull() ?: 65
        val tempAmb = ambCandidates.minOrNull() ?: pcbCandidates.minOrNull() ?: 0

        val fans = listOf("fan1", "fan2", "fan3", "fan4")
            .mapNotNull { st.opt(it)?.toString() }
            .filter { aEntero(it) > 0 }
        val esHydro = ml.contains("hyd") || ml.contains("hydro") || ml.contains("water") || ml.contains("m33") || ml.contains("m53") || ml.contains("m63") || ml.contains("1566ha")
        val fansStr = when {
            fans.isNotEmpty() && !esHydro -> fans.joinToString(" / ") + " RPM"
            esHydro || (fans.isEmpty() && hr5s > 0.0) -> {
                if (tempAmb > 0) "💧 Hydro (Entrada ${tempAmb}°C / Salida ${max(tempAmb + 5, tm - 12)}°C)"
                else "💧 Hydro (Refrigeración Líquida Activa)"
            }
            else -> "0 / 0 RPM"
        }

        return JSONObject()
            .put("ip", ip).put("marca", "Antminer").put("modelo", modeloStr)
            .put("algoritmo", algoritmo).put("moneda", moneda).put("unidad", unidadHr)
            .put("hashrate", hr5s).put("hashrate_30m", hr30m)
            .put("temp", tm).put("temp_amb", tempAmb).put("consumo", cw.roundToInt()).put("errores", erroresHw)
            .put("fans", fansStr).put("mac", est["mac"]).put("serial", st.optString("Hash Board SN", "SN-${ip.replace(".", "")}"))
            .put("firmware", est["fw"]).put("cadenas", cadenasArr).put("uptime", uptimeStr)
            .put("hostname", est["hostname"]).put("net_proto", est["net_proto"])
            .put("netmask", est["netmask"]).put("gateway", est["gateway"]).put("dns", est["dns"])
            .put("pool1", p1[0]).put("worker1", p1[1]).put("pass1", p1[2]).put("status1", p1[3]).put("acc1", p1[4]).put("rej1", p1[5])
            .put("pool2", p2[0]).put("worker2", p2[1]).put("pass2", p2[2]).put("status2", p2[3]).put("acc2", p2[4]).put("rej2", p2[5])
            .put("pool3", p3[0]).put("worker3", p3[1]).put("pass3", p3[2]).put("status3", p3[3]).put("acc3", p3[4]).put("rej3", p3[5])
            .put("cred_defecto", "root / root")
    }

    // ==================== ESCANEO DE RED CON SEMÁFORO Y MUTEX ====================
    private suspend fun escanearRed(subnet: String, ipsBase: List<String>): List<String> = scanMutex.withLock {
        // Enviar datagramas UDP ligeros a toda la subred para forzar al kernel a resolver ARP para iPhones, celulares y PCs
        try {
            withContext(Dispatchers.IO) {
                DatagramSocket().use { ds ->
                    val buf = ByteArray(1)
                    for (i in 1..254) {
                        try {
                            ds.send(DatagramPacket(buf, 1, InetAddress.getByName("$subnet.$i"), 9))
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}

        coroutineScope {
            val encontradas = ipsBase.toMutableList()
            val probadas = (2..254).map { i ->
                async(Dispatchers.IO) {
                    netSemaphore.withPermit {
                        val ip = "$subnet.$i"
                        try {
                            var found = false
                            for (port in listOf(4028, 80, 443, 554, 8080, 8008, 62078)) {
                                try {
                                    Socket().use { s -> s.connect(InetSocketAddress(ip, port), 280) }
                                    found = true
                                    break
                                } catch (e: Exception) {
                                    val err = (e.message ?: "").lowercase()
                                    if ("refused" in err || "rechazada" in err || "econnrefused" in err || "reset" in err) {
                                        found = true
                                        break
                                    }
                                }
                            }
                            if (found) ip else null
                        } catch (_: Exception) { null }
                    }
                }
            }.awaitAll().filterNotNull()

            for (ip in probadas) {
                if (!encontradas.contains(ip)) encontradas.add(ip)
            }

            // Leer tabla ARP del kernel
            try {
                val fArp = File("/proc/net/arp")
                if (fArp.exists()) {
                    fArp.forEachLine { line ->
                        val parts = line.split("\\s+".toRegex())
                        if (parts.size >= 4 && parts[0].startsWith("$subnet.") && parts[3] !in listOf("00:00:00:00:00:00", "incomplete")) {
                            val ipArp = parts[0]
                            if (!encontradas.contains(ipArp)) encontradas.add(ipArp)
                        }
                    }
                }
            } catch (_: Exception) {}

            encontradas
        }
    }

    // ==================== COMANDOS REMOTOS COMPLETOS ====================
    private fun actualizarConfigSegura(ipDest: String, nuevosCampos: JSONObject) {
        val confActual = try {
            val raw = peticionHttp(ipDest, "get_miner_conf.cgi", "GET", timeoutMs = 8000)
            if (!raw.isNullOrEmpty()) JSONObject(raw) else JSONObject()
        } catch (_: Exception) { JSONObject() }

        val ks = nuevosCampos.keys()
        while (ks.hasNext()) {
            val k = ks.next()
            confActual.put(k, nuevosCampos.opt(k))
        }
        peticionHttp(ipDest, "set_miner_conf.cgi", "POST", payloadJson = confActual, timeoutMs = 12000)
    }

    private suspend fun procesarComandos(ordenes: JSONArray, apiKey: String, subnet: String, ipsBase: List<String>) {
        for (i in 0 until ordenes.length()) {
            val cmd = ordenes.optJSONObject(i) ?: continue
            val accion = cmd.optString("comando", "")
            val ipDest = cmd.optString("ip", "")
            val esWm = cacheInfoEstatica[ipDest]?.get("marca") == "WhatsMiner"

            when {
                accion == "update_apk" -> {
                    verificarYDescargarUpdate(forzar = true)
                }
                accion == "ping_pools" -> {
                    val reporte = diagnosticoRedPools()
                    val destinoLog = ipDest.ifEmpty { "AGENTE-RED" }
                    postJsonNube(urlLogs, JSONObject().put("api_key", apiKey).put("ip", destinoLog).put("log", reporte))
                }
                accion == "scan" -> {
                    val enc = escanearRed(subnet, ipsBase)
                    ipsConocidas.clear()
                    ipsConocidas.addAll(enc)
                }
                accion == "restart" -> {
                    if (esWm) {
                        peticion4028(ipDest, "reboot")
                        peticion4028(ipDest, "restart_btminer")
                    } else {
                        peticionHttp(ipDest, "reboot.cgi", timeoutMs = 8000)
                        peticion4028(ipDest, "restart")
                    }
                }
                accion == "reset" && !esWm -> {
                    for (ep in listOf("reset_conf.cgi", "factory_reset.cgi", "reset.cgi")) {
                        peticionHttp(ipDest, ep, "POST_EMPTY", timeoutMs = 8000)
                        peticionHttp(ipDest, ep, "GET", timeoutMs = 8000)
                    }
                    val poolsVacias = JSONArray()
                        .put(JSONObject().put("url", "").put("user", "").put("pass", ""))
                        .put(JSONObject().put("url", "").put("user", "").put("pass", ""))
                        .put(JSONObject().put("url", "").put("user", "").put("pass", ""))
                    val defPayload = JSONObject()
                        .put("pools", poolsVacias)
                        .put("bitmain-work-mode", "0")
                        .put("bitmain-fan-ctrl", false)
                        .put("bitmain-fan-pwm", "100")
                    peticionHttp(ipDest, "set_miner_conf.cgi", "POST", payloadJson = defPayload, timeoutMs = 12000)
                    peticion4028(ipDest, "removepool", "0")
                    peticion4028(ipDest, "removepool", "1")
                    peticion4028(ipDest, "removepool", "2")
                    delay(3000)
                    peticionHttp(ipDest, "reboot.cgi", "GET", timeoutMs = 8000)
                }
                accion == "sleep" -> {
                    if (esWm) peticion4028(ipDest, "power_off")
                    else {
                        actualizarConfigSegura(ipDest, JSONObject().put("bitmain-work-mode", "1").put("miner-mode", 1))
                        peticion4028(ipDest, "pause")
                    }
                }
                accion == "normal" -> {
                    if (esWm) peticion4028(ipDest, "power_on")
                    else {
                        actualizarConfigSegura(ipDest, JSONObject().put("bitmain-work-mode", "0").put("miner-mode", 0))
                        peticion4028(ipDest, "resume")
                    }
                }
                accion == "blink" -> {
                    if (esWm) peticion4028(ipDest, "set_led")
                    else peticionHttp(ipDest, "blink.cgi", "POST", payloadJson = JSONObject().put("blink", "true"), timeoutMs = 6000)
                }
                accion.startsWith("profile|") -> {
                    val modo = accion.substringAfter("|").lowercase()
                    if (esWm) peticion4028(ipDest, if (modo.contains("low")) "set_low_power" else "set_normal_power")
                    else {
                        val valModo = if (modo.contains("low")) "2" else "0"
                        actualizarConfigSegura(ipDest, JSONObject().put("bitmain-work-mode", valModo).put("miner-mode", valModo.toInt()))
                    }
                }
                accion.startsWith("set_network|") && !esWm -> {
                    val p = accion.split("|")
                    if (p.size >= 7) {
                        val jNet = JSONObject()
                            .put("ipHost", p[1]).put("ipPro", p[2].toIntOrNull() ?: 2)
                            .put("ipAddress", p[3]).put("ipSub", p[4]).put("ipGateway", p[5]).put("ipDns", p[6])
                        val fNet = mapOf(
                            "_ant_conf_hostname" to p[1],
                            "_ant_conf_nettype" to if (p[2] == "1") "DHCP" else "Static",
                            "_ant_conf_ipaddress" to p[3],
                            "_ant_conf_netmask" to p[4],
                            "_ant_conf_gateway" to p[5],
                            "_ant_conf_dnsservers" to p[6]
                        )
                        peticionHttp(ipDest, "set_network_conf.cgi", "POST", payloadJson = jNet, timeoutMs = 12000)
                        peticionHttp(ipDest, "set_network_conf.cgi", "POST_FORM", payloadForm = fNet, timeoutMs = 12000)
                        if (p[3].isNotEmpty() && !ipsConocidas.contains(p[3])) ipsConocidas.add(p[3])
                    }
                }
                accion.startsWith("guardar_pools|") && !esWm -> {
                    val p = accion.split("|")
                    if (p.size >= 10) {
                        val poolsArr = JSONArray()
                            .put(JSONObject().put("url", p[1]).put("user", p[2]).put("pass", p[3]))
                            .put(JSONObject().put("url", p[4]).put("user", p[5]).put("pass", p[6]))
                            .put(JSONObject().put("url", p[7]).put("user", p[8]).put("pass", p[9]))
                        actualizarConfigSegura(ipDest, JSONObject().put("pools", poolsArr))
                        val fPools = mapOf(
                            "_ant_pool1url" to p[1], "_ant_pool1user" to p[2], "_ant_pool1pw" to p[3],
                            "_ant_pool2url" to p[4], "_ant_pool2user" to p[5], "_ant_pool2pw" to p[6],
                            "_ant_pool3url" to p[7], "_ant_pool3user" to p[8], "_ant_pool3pw" to p[9]
                        )
                        peticionHttp(ipDest, "set_miner_conf.cgi", "POST_FORM", payloadForm = fPools, timeoutMs = 12000)
                    }
                }
                accion in listOf("get_log", "clog", "hlog", "dlog") && !esWm -> {
                    val ep = if (accion == "get_log") "log.cgi" else "$accion.cgi"
                    var logTxt = peticionHttp(ipDest, ep, timeoutMs = 8000)
                    if (logTxt.isNullOrEmpty() && accion == "get_log") {
                        logTxt = peticionHttp(ipDest, "get_kernel_log.cgi", timeoutMs = 8000)
                    }
                    if (!logTxt.isNullOrEmpty()) {
                        postJsonNube(urlLogs, JSONObject().put("api_key", apiKey).put("ip", ipDest).put("log", logTxt))
                    }
                }
            }
        }
    }

    private fun postJsonNube(urlStr: String, body: JSONObject): JSONObject? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            if (conn.responseCode in 200..299) {
                val txt = conn.inputStream.bufferedReader().readText()
                try { JSONObject(txt) } catch (_: Exception) { JSONObject() }
            } else null
        } catch (_: Exception) {
            null
        }
    }
}
