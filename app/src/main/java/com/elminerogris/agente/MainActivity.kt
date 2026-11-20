package com.elminerogris.agente

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var liveRefreshJob: Job? = null

    private val urlPanel = "https://elminerogris.com"
    private val urlLogin = "https://elminerogris.com/api/agente/login"
    private val urlVersion = "https://elminerogris.com/api/agente/apk_version"
    private var apkUpdateUrl = ""
    private var mostrandoFormularioLogin = false

    // Banner principal de estado
    private lateinit var cardBanner: LinearLayout
    private lateinit var txtBadgeEstado: TextView
    private lateinit var txtDescEstado: TextView

    // Métricas en vivo (2x2)
    private lateinit var valAsics: TextView
    private lateinit var valSync: TextView
    private lateinit var valSede: TextView
    private lateinit var valSubred: TextView

    // Checklist de salud
    private lateinit var txtCheckWifi: TextView
    private lateinit var txtCheckGps: TextView
    private lateinit var txtCheckBateria: TextView

    // Controles de sesión / acción
    private lateinit var cardLoginForm: LinearLayout
    private lateinit var cardAccionRapida: LinearLayout
    private lateinit var txtCuentaGuardada: TextView
    private lateinit var inpUser: EditText
    private lateinit var inpPass: EditText
    private lateinit var txtErrorLogin: TextView
    private lateinit var btnConectarLogin: Button
    private lateinit var btnIniciarRapido: Button
    private lateinit var btnDetener: Button
    private lateinit var btnCambiarCuenta: TextView
    private lateinit var btnActualizar: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("emg_prefs", Context.MODE_PRIVATE)
        obtenerOCrearAgentId(prefs)

        val dp = resources.displayMetrics.density
        fun Int.dp(): Int = (this * dp).toInt()

        fun fondo(colorHex: String, radioDp: Int = 12, bordeHex: String? = null, grosorDp: Int = 1): GradientDrawable {
            return GradientDrawable().apply {
                setColor(Color.parseColor(colorHex))
                cornerRadius = radioDp.dp().toFloat()
                if (bordeHex != null) setStroke(grosorDp.dp(), Color.parseColor(bordeHex))
            }
        }

        fun crearCajaMetrica(etiqueta: String): Pair<LinearLayout, TextView> {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = fondo("#111624", 10, "#232B42")
                setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val lbl = TextView(this).apply {
                text = etiqueta
                textSize = 11f
                setTextColor(Color.parseColor("#94A3B8"))
                setTypeface(null, Typeface.BOLD)
            }
            val valor = TextView(this).apply {
                text = "-"
                textSize = 16f
                setTextColor(Color.WHITE)
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 4.dp(), 0, 0)
            }
            box.addView(lbl)
            box.addView(valor)
            return Pair(box, valor)
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0A0C12"))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp(), 22.dp(), 18.dp(), 28.dp())
        }

        // ==================== 1. CABECERA CON LOGO ====================
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16.dp() }
        }

        val imgLogo = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = fondo("#151926", 14, "#2B3553")
            clipToOutline = true
            setPadding(2.dp(), 2.dp(), 2.dp(), 2.dp())
            layoutParams = LinearLayout.LayoutParams(52.dp(), 52.dp()).apply {
                rightMargin = 12.dp()
            }
        }

        val pInfo = packageManager.getPackageInfo(packageName, 0)
        val versionLocalName = pInfo.versionName ?: "1.0"

        val headerTxt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headerTxt.addView(TextView(this).apply {
            text = "El Minero Gris"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        headerTxt.addView(TextView(this).apply {
            text = "Puente Local de Granja 24/7  •  v$versionLocalName"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
        })

        header.addView(imgLogo)
        header.addView(headerTxt)
        root.addView(header)

        // ==================== 2. BANNER PRINCIPAL DE ESTADO ====================
        cardBanner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 14.dp() }
        }

        txtBadgeEstado = TextView(this).apply {
            textSize = 15.5f
            setTypeface(null, Typeface.BOLD)
        }
        txtDescEstado = TextView(this).apply {
            textSize = 12.5f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, 5.dp(), 0, 0)
        }
        cardBanner.addView(txtBadgeEstado)
        cardBanner.addView(txtDescEstado)
        root.addView(cardBanner)

        // ==================== 3. MÉTRICAS EN VIVO (2x2) ====================
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp() }
        }
        val (boxAsics, vAsics) = crearCajaMetrica("ASICs DETECTADOS")
        val (boxSync, vSync) = crearCajaMetrica("ÚLTIMO ENVÍO")
        valAsics = vAsics
        valSync = vSync
        (boxAsics.layoutParams as LinearLayout.LayoutParams).rightMargin = 8.dp()
        row1.addView(boxAsics)
        row1.addView(boxSync)
        root.addView(row1)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 14.dp() }
        }
        val (boxSede, vSede) = crearCajaMetrica("ID DE SEDE")
        val (boxSubred, vSubred) = crearCajaMetrica("RED LOCAL (WI-FI)")
        valSede = vSede
        valSubred = vSubred
        (boxSede.layoutParams as LinearLayout.LayoutParams).rightMargin = 8.dp()
        row2.addView(boxSede)
        row2.addView(boxSubred)
        root.addView(row2)

        // ==================== 4. CONTROL PRINCIPAL (ACCION RAPIDA / DETENER) ====================
        cardAccionRapida = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fondo("#111624", 12, "#232B42")
            setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 14.dp() }
        }

        txtCuentaGuardada = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, 0, 0, 10.dp())
        }

        btnIniciarRapido = Button(this).apply {
            text = "▶   INICIAR MONITOREO 24/7"
            isAllCaps = false
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = fondo("#2563EB", 10)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                50.dp()
            )
        }

        btnDetener = Button(this).apply {
            text = "⏹   DETENER AGENTE"
            isAllCaps = false
            textSize = 14.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FECACA"))
            background = fondo("#3B1219", 10, "#EF4444")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                48.dp()
            )
        }

        btnCambiarCuenta = TextView(this).apply {
            text = "Cambiar cuenta o contraseña"
            textSize = 12.5f
            setTextColor(Color.parseColor("#60A5FA"))
            gravity = Gravity.CENTER
            setPadding(0, 12.dp(), 0, 2.dp())
        }

        cardAccionRapida.addView(txtCuentaGuardada)
        cardAccionRapida.addView(btnIniciarRapido)
        cardAccionRapida.addView(btnDetener)
        cardAccionRapida.addView(btnCambiarCuenta)
        root.addView(cardAccionRapida)

        // ==================== 5. FORMULARIO DE INICIO DE SESIÓN ====================
        cardLoginForm = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fondo("#111624", 12, "#232B42")
            setPadding(16.dp(), 16.dp(), 16.dp(), 16.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 14.dp() }
        }

        cardLoginForm.addView(TextView(this).apply {
            text = "Vincular tu cuenta"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        cardLoginForm.addView(TextView(this).apply {
            text = "Usa el mismo usuario y contraseña con los que entras a elminerogris.com"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 3.dp(), 0, 12.dp())
        })

        inpUser = EditText(this).apply {
            hint = "Usuario o correo"
            setText(prefs.getString("username", ""))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            background = fondo("#0A0C12", 8, "#29324D")
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10.dp() }
        }

        inpPass = EditText(this).apply {
            hint = "Contraseña"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            background = fondo("#0A0C12", 8, "#29324D")
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10.dp() }
        }

        txtErrorLogin = TextView(this).apply {
            textSize = 12.5f
            setTextColor(Color.parseColor("#F87171"))
            setPadding(0, 0, 0, 10.dp())
            visibility = View.GONE
        }

        btnConectarLogin = Button(this).apply {
            text = "🔐   Conectar e Iniciar Agente"
            isAllCaps = false
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = fondo("#2563EB", 10)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                50.dp()
            )
        }

        cardLoginForm.addView(inpUser)
        cardLoginForm.addView(inpPass)
        cardLoginForm.addView(txtErrorLogin)
        cardLoginForm.addView(btnConectarLogin)
        root.addView(cardLoginForm)

        // ==================== 6. CHECKLIST DE SALUD (PARA PRINCIPIANTES) ====================
        val cardSalud = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fondo("#111624", 12, "#232B42")
            setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 14.dp() }
        }

        cardSalud.addView(TextView(this).apply {
            text = "Estado del teléfono (toca para corregir)"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 8.dp())
        })

        txtCheckWifi = TextView(this).apply {
            textSize = 12.5f
            setPadding(0, 5.dp(), 0, 5.dp())
        }
        txtCheckGps = TextView(this).apply {
            textSize = 12.5f
            setPadding(0, 5.dp(), 0, 5.dp())
        }
        txtCheckBateria = TextView(this).apply {
            textSize = 12.5f
            setPadding(0, 5.dp(), 0, 2.dp())
        }

        cardSalud.addView(txtCheckWifi)
        cardSalud.addView(txtCheckGps)
        cardSalud.addView(txtCheckBateria)
        root.addView(cardSalud)

        // ==================== 7. ACCESOS RÁPIDOS ====================
        val btnAbrirWeb = Button(this).apply {
            text = "🌐   Abrir Panel Web (elminerogris.com)"
            isAllCaps = false
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#93C5FD"))
            background = fondo("#111A2E", 10, "#1D4ED8")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                46.dp()
            ).apply { bottomMargin = 10.dp() }
        }

        btnActualizar = Button(this).apply {
            text = "🔄   Buscar actualizaciones de la app"
            isAllCaps = false
            textSize = 13.5f
            setTextColor(Color.parseColor("#6EE7B7"))
            background = fondo("#0E221B", 10, "#059669")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                44.dp()
            )
        }

        root.addView(btnAbrirWeb)
        root.addView(btnActualizar)

        scroll.addView(root)
        setContentView(scroll)

        // ==================== EVENTOS ====================
        btnConectarLogin.setOnClickListener {
            val u = inpUser.text.toString().trim()
            val p = inpPass.text.toString()
            if (u.isEmpty() || p.isEmpty()) {
                txtErrorLogin.text = "⚠️ Escribe tu usuario y contraseña"
                txtErrorLogin.visibility = View.VISIBLE
                return@setOnClickListener
            }
            txtErrorLogin.visibility = View.GONE
            btnConectarLogin.isEnabled = false
            btnConectarLogin.text = "⏳ Conectando..."
            scope.launch {
                val res = withContext(Dispatchers.IO) { hacerLogin(u, p) }
                btnConectarLogin.isEnabled = true
                btnConectarLogin.text = "🔐   Conectar e Iniciar Agente"
                if (res != null && res.optBoolean("ok", false)) {
                    val subWifi = detectarSubredWifi() ?: res.optString("subnet", "192.168.0")
                    prefs.edit()
                        .putString("username", res.optString("username"))
                        .putString("api_key", res.optString("api_key"))
                        .putString("subnet", subWifi)
                        .putBoolean("service_running", true)
                        .apply()
                    mostrandoFormularioLogin = false
                    iniciarServicio()
                    actualizarTodoUI()
                } else {
                    txtErrorLogin.text = "❌ ${res?.optString("msg") ?: "No se pudo conectar con elminerogris.com"}"
                    txtErrorLogin.visibility = View.VISIBLE
                }
            }
        }

        btnIniciarRapido.setOnClickListener {
            val subWifi = detectarSubredWifi()
            val ed = prefs.edit().putBoolean("service_running", true)
            if (subWifi != null) ed.putString("subnet", subWifi)
            ed.apply()
            iniciarServicio()
            actualizarTodoUI()
        }

        btnDetener.setOnClickListener {
            stopService(Intent(this, AgentService::class.java))
            prefs.edit().putBoolean("service_running", false).apply()
            actualizarTodoUI()
        }

        btnCambiarCuenta.setOnClickListener {
            mostrandoFormularioLogin = true
            actualizarTodoUI()
        }

        txtCheckWifi.setOnClickListener {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
        txtCheckGps.setOnClickListener {
            solicitarPermisosNecesarios()
        }
        txtCheckBateria.setOnClickListener {
            pedirIgnorarBateria()
        }

        btnAbrirWeb.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(urlPanel)))
        }

        btnActualizar.setOnClickListener {
            if (apkUpdateUrl.isNotEmpty()) {
                descargarEInstalar()
            } else {
                btnActualizar.text = "⏳ Verificando versión..."
                verificarActualizacion(manual = true)
            }
        }

        solicitarPermisosNecesarios()
        actualizarTodoUI()
        verificarActualizacion(manual = false)
    }

    override fun onResume() {
        super.onResume()
        actualizarTodoUI()
        iniciarRefrescoEnVivo()
    }

    override fun onPause() {
        super.onPause()
        liveRefreshJob?.cancel()
    }

    private fun iniciarRefrescoEnVivo() {
        liveRefreshJob?.cancel()
        liveRefreshJob = scope.launch {
            while (isActive) {
                actualizarTodoUI()
                delay(3000L)
            }
        }
    }

    private fun actualizarTodoUI() {
        val prefs = getSharedPreferences("emg_prefs", Context.MODE_PRIVATE)
        val user = prefs.getString("username", "") ?: ""
        val apiKey = prefs.getString("api_key", "") ?: ""
        val agentId = obtenerOCrearAgentId(prefs)
        val subnetGuardada = prefs.getString("subnet", "192.168.0") ?: "192.168.0"
        val subnetWifi = detectarSubredWifi()
        val running = prefs.getBoolean("service_running", false)
        val count = prefs.getInt("miners_count", 0)
        val lastSync = prefs.getString("last_sync", "Esperando...") ?: "Esperando..."
        val gpsStatus = prefs.getString("gps_status", "Esperando GPS...") ?: "Esperando GPS..."

        val dp = resources.displayMetrics.density
        fun fondoBanner(bg: String, border: String): GradientDrawable {
            return GradientDrawable().apply {
                setColor(Color.parseColor(bg))
                cornerRadius = 12 * dp
                setStroke((1.5f * dp).toInt(), Color.parseColor(border))
            }
        }

        // 1. Banner de estado (imposible confundirse)
        when {
            running && apiKey.isNotEmpty() -> {
                cardBanner.background = fondoBanner("#092418", "#10B981")
                txtBadgeEstado.text = "●  AGENTE ACTIVO 24/7"
                txtBadgeEstado.setTextColor(Color.parseColor("#34D399"))
                txtDescEstado.text = "Conectado como $user. Enviando datos a elminerogris.com en segundo plano. Puedes minimizar esta app o apagar la pantalla."
            }
            apiKey.isNotEmpty() -> {
                cardBanner.background = fondoBanner("#241B0A", "#F59E0B")
                txtBadgeEstado.text = "●  AGENTE DETENIDO"
                txtBadgeEstado.setTextColor(Color.parseColor("#FBBF24"))
                txtDescEstado.text = "Tu cuenta ($user) está lista, pero el monitoreo está pausado. Pulsa Iniciar para comenzar."
            }
            else -> {
                cardBanner.background = fondoBanner("#151B2E", "#3B82F6")
                txtBadgeEstado.text = "●  LISTO PARA CONFIGURAR"
                txtBadgeEstado.setTextColor(Color.parseColor("#60A5FA"))
                txtDescEstado.text = "Esta app conecta los ASICs de tu red Wi-Fi local con tu panel en elminerogris.com."
            }
        }

        // 2. Métricas 2x2
        valAsics.text = "$count"
        valAsics.setTextColor(if (count > 0 && running) Color.parseColor("#34D399") else Color.WHITE)
        valSync.text = if (running) lastSync else "Pausado"
        valSede.text = agentId
        valSubred.text = "${subnetWifi ?: subnetGuardada}.x"

        // 3. Visibilidad inteligente de Login vs Control Activo
        val tieneSesion = apiKey.isNotEmpty() && !mostrandoFormularioLogin
        if (tieneSesion) {
            cardLoginForm.visibility = View.GONE
            cardAccionRapida.visibility = View.VISIBLE
            txtCuentaGuardada.text = "👤 Cuenta vinculada: $user"
            if (running) {
                btnIniciarRapido.visibility = View.GONE
                btnDetener.visibility = View.VISIBLE
                btnCambiarCuenta.visibility = View.GONE
            } else {
                btnIniciarRapido.visibility = View.VISIBLE
                btnDetener.visibility = View.GONE
                btnCambiarCuenta.visibility = View.VISIBLE
            }
        } else {
            cardLoginForm.visibility = View.VISIBLE
            cardAccionRapida.visibility = View.GONE
        }

        // 4. Checklist de salud
        if (subnetWifi != null) {
            txtCheckWifi.text = "✅  Wi-Fi local conectado ($subnetWifi.x)"
            txtCheckWifi.setTextColor(Color.parseColor("#6EE7B7"))
        } else {
            txtCheckWifi.text = "⚠️  Sin Wi-Fi — Conéctate al router de tu granja"
            txtCheckWifi.setTextColor(Color.parseColor("#FCD34D"))
        }

        val gpsPermOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (gpsPermOk) {
            txtCheckGps.text = "✅  Ubicación: $gpsStatus"
            txtCheckGps.setTextColor(Color.parseColor("#6EE7B7"))
        } else {
            txtCheckGps.text = "⚠️  GPS sin permiso — Toca aquí para activar"
            txtCheckGps.setTextColor(Color.parseColor("#FCD34D"))
        }

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || pm?.isIgnoringBatteryOptimizations(packageName) == true
        if (batOk) {
            txtCheckBateria.text = "✅  Batería 24/7: Sin restricciones de Android"
            txtCheckBateria.setTextColor(Color.parseColor("#6EE7B7"))
        } else {
            txtCheckBateria.text = "⚠️  Ahorro de batería activo — Toca para permitir 24/7"
            txtCheckBateria.setTextColor(Color.parseColor("#FCD34D"))
        }
    }

    private fun pedirIgnorarBateria() {
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

    private fun obtenerOCrearAgentId(prefs: SharedPreferences): String {
        val existente = prefs.getString("agent_id", "")?.trim() ?: ""
        if (existente.length >= 4) return existente
        val nuevoId = "AND-" + UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
        prefs.edit().putString("agent_id", nuevoId).apply()
        return nuevoId
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

    private fun verificarActualizacion(manual: Boolean) {
        scope.launch {
            val info = withContext(Dispatchers.IO) { obtenerVersionNube() }
            if (info == null) {
                if (manual) btnActualizar.text = "🔄   Buscar actualizaciones de la app"
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
                btnActualizar.text = "⬆️   Descargar nueva versión v$nubeName"
            } else if (manual) {
                btnActualizar.text = "✅   App actualizada (v$nubeName)"
                delay(2500)
                btnActualizar.text = "🔄   Buscar actualizaciones de la app"
            }
        }
    }

    private fun descargarEInstalar() {
        btnActualizar.isEnabled = false
        btnActualizar.text = "⬇️   Descargando APK..."
        scope.launch {
            val archivoApk = withContext(Dispatchers.IO) { descargarApk(apkUpdateUrl) }
            if (archivoApk != null) {
                btnActualizar.text = "✅   Abriendo instalador..."
                instalarApk(archivoApk)
            } else {
                btnActualizar.text = "❌   Error descargando — Toca para reintentar"
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

    override fun onDestroy() {
        liveRefreshJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
