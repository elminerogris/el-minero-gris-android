package com.elminerogris.agente

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val urlPanel = "https://antminer-panel.onrender.com"
    private val urlLogin = "https://antminer-panel.onrender.com/api/agente/login"
    private val urlVersion = "https://antminer-panel.onrender.com/api/agente/apk_version"
    private var apkUpdateUrl = ""

    private lateinit var txtEstado: TextView
    private lateinit var txtResumenLive: TextView
    private lateinit var btnBateria: Button
    private lateinit var btnActualizar: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("emg_prefs", Context.MODE_PRIVATE)
        val dp = resources.displayMetrics.density
        fun Int.dp(): Int = (this * dp).toInt()

        fun crearFondo(colorHex: String, radioDp: Int = 12, bordeHex: String? = null): GradientDrawable {
            return GradientDrawable().apply {
                setColor(Color.parseColor(colorHex))
                cornerRadius = radioDp.dp().toFloat()
                if (bordeHex != null) setStroke(1.dp(), Color.parseColor(bordeHex))
            }
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0D14"))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 28.dp(), 20.dp(), 28.dp())
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // --- CABECERA CON TU LOGO ---
        val imgLogo = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = crearFondo("#151926", 20, "#2B3553")
            clipToOutline = true
            setPadding(4.dp(), 4.dp(), 4.dp(), 4.dp())
            layoutParams = LinearLayout.LayoutParams(88.dp(), 88.dp()).apply {
                bottomMargin = 12.dp()
            }
        }

        val pInfo = packageManager.getPackageInfo(packageName, 0)
        val versionLocalName = pInfo.versionName ?: "1.0"

        val titulo = TextView(this).apply {
            text = "El Minero Gris"
            textSize = 23f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val subtitulo = TextView(this).apply {
            text = "Agente Local 24/7  •  v$versionLocalName"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, 2.dp(), 0, 18.dp())
        }

        // --- TARJETA DE ESTADO EN VIVO ---
        val cardLive = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = crearFondo("#131724", 12, "#242C44")
            setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16.dp() }
        }

        txtEstado = TextView(this).apply {
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
        }

        txtResumenLive = TextView(this).apply {
            textSize = 12.5f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, 6.dp(), 0, 0)
            typeface = Typeface.MONOSPACE
        }

        cardLive.addView(txtEstado)
        cardLive.addView(txtResumenLive)

        // --- TARJETA DE ACCESO ---
        val cardLogin = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = crearFondo("#131724", 12, "#242C44")
            setPadding(16.dp(), 16.dp(), 16.dp(), 16.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 14.dp() }
        }

        val inpUser = EditText(this).apply {
            hint = "Usuario o correo"
            setText(prefs.getString("username", ""))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            background = crearFondo("#0B0D14", 8, "#29324D")
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10.dp() }
        }

        val inpPass = EditText(this).apply {
            hint = "Contraseña"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            background = crearFondo("#0B0D14", 8, "#29324D")
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 14.dp() }
        }

        val btnIniciar = Button(this).apply {
            text = "▶  Iniciar Agente 24/7"
            isAllCaps = false
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = crearFondo("#2563EB", 10)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                48.dp()
            ).apply { bottomMargin = 10.dp() }
        }

        val btnDetener = Button(this).apply {
            text = "⏹  Detener Agente"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.parseColor("#E2E8F0"))
            background = crearFondo("#1E2436", 10, "#334155")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                44.dp()
            )
        }

        cardLogin.addView(inpUser)
        cardLogin.addView(inpPass)
        cardLogin.addView(btnIniciar)
        cardLogin.addView(btnDetener)

        // --- BOTONES DE UTILIDAD ---
        val btnAbrirWeb = Button(this).apply {
            text = "🌐  Abrir Panel Web"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.parseColor("#93C5FD"))
            background = crearFondo("#131B2E", 10, "#1D4ED8")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                44.dp()
            ).apply { bottomMargin = 10.dp() }
        }

        btnBateria = Button(this).apply {
            text = "🔋  Desactivar ahorro de batería (24/7)"
            isAllCaps = false
            textSize = 13.5f
            setTextColor(Color.parseColor("#FDE68A"))
            background = crearFondo("#241C0F", 10, "#B45309")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                44.dp()
            ).apply { bottomMargin = 10.dp() }
            visibility = View.GONE
        }

        btnActualizar = Button(this).apply {
            text = "🔄  Buscar actualizaciones"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.parseColor("#6EE7B7"))
            background = crearFondo("#0F241D", 10, "#059669")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                44.dp()
            )
        }

        // --- EVENTOS ---
        btnIniciar.setOnClickListener {
            val u = inpUser.text.toString().trim()
            val p = inpPass.text.toString()
            val apiGuardada = prefs.getString("api_key", "") ?: ""

            if (p.isEmpty() && apiGuardada.isNotEmpty() && u == prefs.getString("username", "")) {
                iniciarServicio()
                actualizarEstadoUI()
                return@setOnClickListener
            }

            if (u.isEmpty() || p.isEmpty()) {
                txtEstado.text = "⚠️ Completa usuario y contraseña"
                return@setOnClickListener
            }
            txtEstado.text = "⏳ Conectando con El Minero Gris..."
            scope.launch {
                val res = withContext(Dispatchers.IO) { hacerLogin(u, p) }
                if (res != null && res.optBoolean("ok", false)) {
                    val subWifi = detectarSubredWifi() ?: res.optString("subnet", "192.168.0")
                    prefs.edit()
                        .putString("username", res.optString("username"))
                        .putString("api_key", res.optString("api_key"))
                        .putString("subnet", subWifi)
                        .putBoolean("service_running", true)
                        .apply()
                    iniciarServicio()
                    txtEstado.text = "🟢 Agente activo 24/7"
                    actualizarEstadoUI()
                } else {
                    txtEstado.text = "❌ ${res?.optString("msg") ?: "Error de conexión"}"
                }
            }
        }

        btnDetener.setOnClickListener {
            stopService(Intent(this, AgentService::class.java))
            prefs.edit().putBoolean("service_running", false).apply()
            txtEstado.text = "⏸ Agente detenido"
            actualizarEstadoUI()
        }

        btnAbrirWeb.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(urlPanel)))
        }

        btnBateria.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }

        btnActualizar.setOnClickListener {
            if (apkUpdateUrl.isNotEmpty()) {
                descargarEInstalar()
            } else {
                btnActualizar.text = "⏳ Verificando versión..."
                verificarActualizacion(manual = true)
            }
        }

        root.addView(imgLogo)
        root.addView(titulo)
        root.addView(subtitulo)
        root.addView(cardLive)
        root.addView(cardLogin)
        root.addView(btnAbrirWeb)
        root.addView(btnBateria)
        root.addView(btnActualizar)
        scroll.addView(root)
        setContentView(scroll)

        solicitarPermisosNecesarios()
        actualizarEstadoUI()
        verificarActualizacion(manual = false)
    }

    override fun onResume() {
        super.onResume()
        actualizarEstadoUI()
        verificarBateriaUI()
    }

    private fun solicitarPermisosNecesarios() {
        val permisos = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permisos.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val faltantes = permisos.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (faltantes.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, faltantes.toTypedArray(), 101)
        }
    }

    private fun verificarBateriaUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val ignorando = pm?.isIgnoringBatteryOptimizations(packageName) == true
            btnBateria.visibility = if (ignorando) View.GONE else View.VISIBLE
        }
    }

    private fun detectarSubredWifi(): String? {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val ipInt = wm?.connectionInfo?.ipAddress ?: 0
            if (ipInt == 0) return null
            val b1 = ipInt and 0xff
            val b2 = (ipInt shr 8) and 0xff
            val b3 = (ipInt shr 16) and 0xff
            "$b1.$b2.$b3"
        } catch (_: Exception) {
            null
        }
    }

    private fun actualizarEstadoUI() {
        val prefs = getSharedPreferences("emg_prefs", Context.MODE_PRIVATE)
        val user = prefs.getString("username", "") ?: ""
        val apiKey = prefs.getString("api_key", "") ?: ""
        val subnet = prefs.getString("subnet", "192.168.0") ?: "192.168.0"
        val running = prefs.getBoolean("service_running", false)
        val count = prefs.getInt("miners_count", 0)
        val lastSync = prefs.getString("last_sync", "Sin sincronizar") ?: "Sin sincronizar"
        val gpsStatus = prefs.getString("gps_status", "Esperando GPS...") ?: "Esperando GPS..."

        txtEstado.text = when {
            running && apiKey.isNotEmpty() -> "🟢 Agente activo 24/7 ($user)"
            apiKey.isNotEmpty() -> "🟡 Sesión lista ($user) — Detenido"
            else -> "Inicia sesión para vincular esta granja"
        }
        txtResumenLive.text = "Subred: $subnet.x  •  ASICs: $count\nÚlt. envío: $lastSync\nUbicación: $gpsStatus"
    }

    private fun verificarActualizacion(manual: Boolean) {
        scope.launch {
            val info = withContext(Dispatchers.IO) { obtenerVersionNube() }
            if (info == null) {
                if (manual) btnActualizar.text = "🔄  Buscar actualizaciones"
                return@launch
            }
            val nubeCode = info.optInt("version_code", 1)
            val nubeName = info.optString("version_name", "1.0")
            val apkUrl = info.optString("apk_url", "")

            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val localCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }

            if (nubeCode > localCode && apkUrl.isNotEmpty()) {
                apkUpdateUrl = apkUrl
                btnActualizar.text = "⬆️  Descargar actualización v$nubeName"
            } else if (manual) {
                btnActualizar.text = "✅  App al día (v$nubeName)"
                delay(2500)
                btnActualizar.text = "🔄  Buscar actualizaciones"
            }
        }
    }

    private fun descargarEInstalar() {
        btnActualizar.isEnabled = false
        btnActualizar.text = "⬇️  Descargando APK..."
        scope.launch {
            val archivoApk = withContext(Dispatchers.IO) { descargarApk(apkUpdateUrl) }
            if (archivoApk != null) {
                btnActualizar.text = "✅  Abriendo instalador..."
                instalarApk(archivoApk)
            } else {
                btnActualizar.text = "❌  Error descargando — Reintentar"
            }
            btnActualizar.isEnabled = true
        }
    }

    private fun obtenerVersionNube(): JSONObject? {
        return try {
            val conn = (URL(urlVersion).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
            }
            if (conn.responseCode == 200) JSONObject(conn.inputStream.bufferedReader().readText()) else null
        } catch (_: Exception) { null }
    }

    private fun descargarApk(urlStr: String): File? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
            }
            if (conn.responseCode != 200) return null
            val outFile = File(cacheDir, "ElMineroGris-Update.apk")
            conn.inputStream.use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
            outFile
        } catch (_: Exception) { null }
    }

    private fun instalarApk(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun iniciarServicio() {
        val svc = Intent(this, AgentService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
        else startService(svc)
    }

    private fun hacerLogin(u: String, p: String): JSONObject? {
        return try {
            val conn = (URL(urlLogin).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            val body = JSONObject().put("username", u).put("password", p).toString()
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            JSONObject(stream.bufferedReader().readText())
        } catch (_: Exception) { null }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
