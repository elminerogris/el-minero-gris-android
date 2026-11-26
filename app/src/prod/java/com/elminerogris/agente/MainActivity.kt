package com.elminerogris.agente

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var liveRefreshJob: Job? = null

    private val urlPanel = "https://elminerogris.com/login"
    private val urlLogin = "https://elminerogris.com/api/agente/login"
    private val urlVersion = "https://elminerogris.com/api/agente/apk_version"
    private var apkUpdateUrl = ""
    private var mostrandoFormularioLogin = false
    private var actualizandoSwitchInterno = false

    // Componentes del Panel Web y Navegación
    private lateinit var webView: WebView
    private lateinit var webProgressBar: ProgressBar
    private lateinit var panelLayout: FrameLayout
    private lateinit var scrollAgente: ScrollView
    private lateinit var tabBtnPanel: LinearLayout
    private lateinit var tabBtnAgente: LinearLayout
    private lateinit var txtTabPanel: TextView
    private lateinit var txtTabAgente: TextView
    private lateinit var dotAgente: View
    private var tabActual: Int = 0

    private lateinit var cardBanner: LinearLayout
    private lateinit var txtBadgeEstado: TextView
    private lateinit var txtDescEstado: TextView
    private lateinit var txtEstadoTunel: TextView

    private lateinit var valAsics: TextView
    private lateinit var valSync: TextView
    private lateinit var valSede: TextView
    private lateinit var valSubred: TextView

    private lateinit var txtCheckWifi: TextView

    // Modo 24/7 (Batería sin restricciones con interruptor visual)
    private lateinit var cardModo247: LinearLayout
    private lateinit var switchModo247: Switch
    private lateinit var txtEstadoModo247: TextView

    // Clima / GPS opcional
    private lateinit var switchClima: Switch
    private lateinit var txtEstadoClima: TextView

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
    private lateinit var btnDiagnostico: Button
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
        txtEstadoTunel = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 7.dp(), 0, 0)
        }
        cardBanner.addView(txtBadgeEstado)
        cardBanner.addView(txtDescEstado)
        cardBanner.addView(txtEstadoTunel)
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

        // ==================== 4. CONTROL PRINCIPAL ====================
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

        // ==================== 6. ESTADO DE RED WI-FI ====================
        val cardWifi = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fondo("#111624", 12, "#232B42")
            setPadding(16.dp(), 13.dp(), 16.dp(), 13.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12.dp() }
        }

        txtCheckWifi = TextView(this).apply {
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
        }
        cardWifi.addView(txtCheckWifi)
        root.addView(cardWifi)

        // ==================== 7. TARJETA CON INTERRUPTOR: MODO 24/7 ====================
        cardModo247 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fondo("#111624", 12, "#232B42")
            setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12.dp() }
        }

        val fila247Header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val titulo247 = TextView(this).apply {
            text = "🔋  Modo 24/7 (Sin cortes)"
            textSize = 13.8f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        switchModo247 = Switch(this)

        fila247Header.addView(titulo247)
        fila247Header.addView(switchModo247)

        val desc247 = TextView(this).apply {
            text = "Evita que Android cierre la app al apagar la pantalla. Actívalo y toca \"Permitir\" para monitorear sin interrupciones."
            textSize = 11.8f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 6.dp(), 0, 5.dp())
        }

        txtEstadoModo247 = TextView(this).apply {
            textSize = 11.8f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 2.dp(), 0, 0)
        }

        cardModo247.addView(fila247Header)
        cardModo247.addView(desc247)
        cardModo247.addView(txtEstadoModo247)
        root.addView(cardModo247)

        // ==================== 8. TARJETA OPCIONAL: CLIMA EXACTO ====================
        val cardClima = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fondo("#111624", 12, "#232B42")
            setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 14.dp() }
        }

        val filaClimaHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val tituloClima = TextView(this).apply {
            text = "🌤️  Clima exacto de tus ASICs"
            textSize = 13.8f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        switchClima = Switch(this).apply {
            isChecked = prefs.getBoolean("gps_enabled", false)
        }

        filaClimaHeader.addView(tituloClima)
        filaClimaHeader.addView(switchClima)

        val descClima = TextView(this).apply {
            text = "Opcional: activa el GPS para medir la temperatura exterior exacta de tu granja y mejorar la protección térmica automática."
            textSize = 11.8f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 6.dp(), 0, 4.dp())
        }

        txtEstadoClima = TextView(this).apply {
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 2.dp(), 0, 0)
        }

        cardClima.addView(filaClimaHeader)
        cardClima.addView(descClima)
        cardClima.addView(txtEstadoClima)
        root.addView(cardClima)

        // ==================== 9. ACCESOS RÁPIDOS Y DIAGNÓSTICO ====================
        val btnAbrirWeb = Button(this).apply {
            text = "📊   Ver Panel Web en la App"
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

        btnDiagnostico = Button(this).apply {
            text = "🩺   Diagnóstico rápido de red y ASICs"
            isAllCaps = false
            textSize = 13.5f
            setTextColor(Color.parseColor("#7DD3FC"))
            background = fondo("#0D1D2C", 10, "#0284C7")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                44.dp()
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
        root.addView(btnDiagnostico)
        root.addView(btnActualizar)

        scroll.addView(root)
        scrollAgente = scroll
        scrollAgente.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        // ==================== PANEL WEB (WEBVIEW A PANTALLA COMPLETA) ====================
        panelLayout = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#0A0C12"))
        }

        webProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                3.dp()
            ).apply { gravity = Gravity.TOP }
            progressDrawable = GradientDrawable().apply {
                setColor(Color.parseColor("#3B82F6"))
            }
        }

        webView = WebView(this).apply {
            val wv = this
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#0A0C12"))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = false
                displayZoomControls = false
                setSupportZoom(true)
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = "$userAgentString ElMineroGris-AndroidApp/1.0"
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (url.startsWith("https://elminerogris.com") || url.startsWith("http://elminerogris.com")) {
                        return false
                    }
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) {}
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    webProgressBar.progress = 15
                    webProgressBar.visibility = View.VISIBLE
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    webProgressBar.visibility = View.GONE
                    sincronizarSesionDesdeWeb()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    webProgressBar.progress = newProgress
                    if (newProgress >= 100) {
                        webProgressBar.visibility = View.GONE
                    } else {
                        webProgressBar.visibility = View.VISIBLE
                    }
                }
            }
        }

        panelLayout.addView(webView)
        panelLayout.addView(webProgressBar)

        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            addView(panelLayout)
            addView(scrollAgente)
        }

        // ==================== BARRA DE NAVEGACIÓN INFERIOR ====================
        val bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0D111A"))
            val borderTop = GradientDrawable().apply {
                setColor(Color.parseColor("#0D111A"))
                setStroke(1.dp(), Color.parseColor("#1E293B"))
            }
            background = borderTop
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                58.dp()
            )
        }

        tabBtnPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            isClickable = true
            isFocusable = true
        }

        txtTabPanel = TextView(this).apply {
            text = "📊  Panel Web"
            textSize = 13.5f
            setTextColor(Color.parseColor("#3B82F6"))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        tabBtnPanel.addView(txtTabPanel)

        tabBtnAgente = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            isClickable = true
            isFocusable = true
        }

        val agenteRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        dotAgente = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(8.dp(), 8.dp()).apply {
                rightMargin = 6.dp()
            }
            background = fondo("#64748B", 999)
        }

        txtTabAgente = TextView(this).apply {
            text = "📡  Agente Wi-Fi"
            textSize = 13.5f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
        }

        agenteRow.addView(dotAgente)
        agenteRow.addView(txtTabAgente)
        tabBtnAgente.addView(agenteRow)

        bottomNav.addView(tabBtnPanel)
        bottomNav.addView(tabBtnAgente)

        tabBtnPanel.setOnClickListener { cambiarTab(0) }
        tabBtnAgente.setOnClickListener {
            sincronizarSesionDesdeWeb()
            cambiarTab(1)
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0C12"))
            addView(contentFrame)
            addView(bottomNav)
        }

        setContentView(mainLayout)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (tabActual == 0 && webView.canGoBack()) {
                    webView.goBack()
                } else if (tabActual == 1) {
                    cambiarTab(0)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        webView.loadUrl(urlPanel)
        cambiarTab(0)

        // ==================== EVENTOS ====================
        switchModo247.setOnCheckedChangeListener { _, isChecked ->
            if (actualizandoSwitchInterno) return@setOnCheckedChangeListener
            val yaPermitido = estaBateriaSinRestricciones()
            if (isChecked && !yaPermitido) {
                actualizandoSwitchInterno = true
                switchModo247.isChecked = false
                actualizandoSwitchInterno = false
                pedirIgnorarBateria()
            } else if (!isChecked && yaPermitido) {
                actualizandoSwitchInterno = true
                switchModo247.isChecked = true
                actualizandoSwitchInterno = false
                abrirAjustesBateriaSistema()
            }
        }

        cardModo247.setOnClickListener {
            if (!estaBateriaSinRestricciones()) {
                pedirIgnorarBateria()
            }
        }

        switchClima.setOnCheckedChangeListener { _, isChecked ->
            if (actualizandoSwitchInterno) return@setOnCheckedChangeListener
            if (isChecked) {
                val tienePerm = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (tienePerm) {
                    prefs.edit().putBoolean("gps_enabled", true).apply()
                    actualizarTodoUI()
                } else {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        102
                    )
                }
            } else {
                prefs.edit().putBoolean("gps_enabled", false).apply()
                actualizarTodoUI()
            }
        }

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
                    val ed = prefs.edit()
                        .putString("username", res.optString("username"))
                        .putString("api_key", res.optString("api_key"))
                        .putString("subnet", subWifi)
                        .putBoolean("service_running", true)
                    val mUser = res.optString("miner_user", "").trim()
                    val mPass = res.optString("miner_pass", "").trim()
                    if (mUser.isNotEmpty() && mPass.isNotEmpty()) {
                        ed.putString("miner_user", mUser).putString("miner_pass", mPass)
                    }
                    ed.apply()
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
            prefs.edit()
                .putBoolean("service_running", false)
                .putBoolean("tunnel_connected", false)
                .apply()
            actualizarTodoUI()
        }

        btnCambiarCuenta.setOnClickListener {
            mostrandoFormularioLogin = true
            actualizarTodoUI()
        }

        cardWifi.setOnClickListener {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }

        btnAbrirWeb.setOnClickListener {
            cambiarTab(0)
        }

        btnDiagnostico.setOnClickListener {
            ejecutarDiagnosticoRapido()
        }

        btnActualizar.setOnClickListener {
            if (apkUpdateUrl.isNotEmpty()) {
                iniciarFlujoActualizacion()
            } else {
                btnActualizar.text = "⏳ Verificando versión..."
                verificarActualizacion(manual = true)
            }
        }

        solicitarPermisoNotificaciones()
        actualizarTodoUI()
        verificarActualizacion(manual = false)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 102) {
            val prefs = getSharedPreferences("emg_prefs", Context.MODE_PRIVATE)
            val concedido = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            prefs.edit().putBoolean("gps_enabled", concedido).apply()
            actualizandoSwitchInterno = true
            switchClima.isChecked = concedido
            actualizandoSwitchInterno = false
            actualizarTodoUI()
        }
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

    private fun ejecutarDiagnosticoRapido() {
        btnDiagnostico.isEnabled = false
        btnDiagnostico.text = "🩺   Probando red y ASICs..."
        val prefs = getSharedPreferences("emg_prefs", Context.MODE_PRIVATE)
        val sub = detectarSubredWifi() ?: (prefs.getString("subnet", "192.168.0") ?: "192.168.0")
        val tunelOk = prefs.getBoolean("tunnel_connected", false)
        val running = prefs.getBoolean("service_running", false)

        scope.launch {
            val reporte = withContext(Dispatchers.IO) {
                val sb = StringBuilder()
                sb.append("• Wi-Fi Local: ${if (detectarSubredWifi() != null) "✅ Conectado ($sub.x)" else "⚠️ Sin Wi-Fi ($sub.x)"}\n")

                val nubeOk = try {
                    val conn = (URL(urlVersion).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 5000
                        readTimeout = 5000
                    }
                    conn.responseCode == 200
                } catch (_: Exception) { false }

                sb.append("• Servidor Web: ${if (nubeOk) "✅ elminerogris.com responde" else "❌ Sin conexión a la nube"}\n")
                sb.append("• Agente 24/7: ${if (running) "✅ Activo" else "⏸ Detenido"}\n")
                sb.append("• Túnel Remoto: ${if (tunelOk) "⚡ Conectado" else "⏳ Reconectando"}\n")

                val activos4028 = mutableListOf<String>()
                (2..254).map { oct ->
                    async(Dispatchers.IO) {
                        val ip = "$sub.$oct"
                        try {
                            Socket().use { s -> s.connect(InetSocketAddress(ip, 4028), 350) }
                            ip
                        } catch (_: Exception) { null }
                    }
                }.awaitAll().filterNotNull().forEach { activos4028.add(it) }

                sb.append("• ASICs (puerto 4028): ${activos4028.size} detectado(s)")
                if (activos4028.isNotEmpty()) {
                    sb.append("\n  IPs: ${activos4028.take(8).joinToString(", ")}${if (activos4028.size > 8) "..." else ""}")
                }
                sb.toString()
            }

            btnDiagnostico.isEnabled = true
            btnDiagnostico.text = "🩺   Diagnóstico rápido de red y ASICs"

            AlertDialog.Builder(this@MainActivity)
                .setTitle("🩺 Diagnóstico del Puente Local")
                .setMessage(reporte)
                .setPositiveButton("Aceptar", null)
                .show()
        }
    }

    private fun estaBateriaSinRestricciones(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isIgnoringBatteryOptimizations(packageName) == true
    }

    private fun actualizarTodoUI() {
        val prefs = getSharedPreferences("emg_prefs", Context.MODE_PRIVATE)
        val user = prefs.getString("username", "") ?: ""
        val apiKey = prefs.getString("api_key", "") ?: ""
        val agentId = obtenerOCrearAgentId(prefs)
        val subnetGuardada = prefs.getString("subnet", "192.168.0") ?: "192.168.0"
        val subnetWifi = detectarSubredWifi()
        val running = prefs.getBoolean("service_running", false)
        val tunelOk = prefs.getBoolean("tunnel_connected", false)
        val count = prefs.getInt("miners_count", 0)
        val lastSync = prefs.getString("last_sync", "Esperando...") ?: "Esperando..."
        val gpsActivo = prefs.getBoolean("gps_enabled", false)
        val batOk = estaBateriaSinRestricciones()

        val dp = resources.displayMetrics.density
        fun fondoBanner(bg: String, border: String): GradientDrawable {
            return GradientDrawable().apply {
                setColor(Color.parseColor(bg))
                cornerRadius = 12 * dp
                setStroke((1.5f * dp).toInt(), Color.parseColor(border))
            }
        }

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

        when {
            !running || apiKey.isEmpty() -> {
                txtEstadoTunel.text = "○ Túnel remoto: Detenido"
                txtEstadoTunel.setTextColor(Color.parseColor("#94A3B8"))
            }
            tunelOk -> {
                txtEstadoTunel.text = "⚡ Túnel remoto: Conectado (Router y ASICs listos)"
                txtEstadoTunel.setTextColor(Color.parseColor("#38BDF8"))
            }
            else -> {
                txtEstadoTunel.text = "⏳ Túnel remoto: Reconectando..."
                txtEstadoTunel.setTextColor(Color.parseColor("#FBBF24"))
            }
        }

        valAsics.text = "$count"
        valAsics.setTextColor(if (count > 0 && running) Color.parseColor("#34D399") else Color.WHITE)
        valSync.text = if (running) lastSync else "Pausado"
        valSede.text = agentId
        valSubred.text = "${subnetWifi ?: subnetGuardada}.x"

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

        if (subnetWifi != null) {
            txtCheckWifi.text = "✅  Wi-Fi local conectado ($subnetWifi.x)"
            txtCheckWifi.setTextColor(Color.parseColor("#6EE7B7"))
        } else {
            txtCheckWifi.text = "⚠️  Sin Wi-Fi — Toca aquí para conectar al router de tu granja"
            txtCheckWifi.setTextColor(Color.parseColor("#FCD34D"))
        }

        actualizandoSwitchInterno = true
        switchModo247.isChecked = batOk
        switchClima.isChecked = gpsActivo
        actualizandoSwitchInterno = false

        if (batOk) {
            cardModo247.background = fondoBanner("#111624", "#10B981")
            txtEstadoModo247.text = "✅ Protegido — Funcionando 24/7 sin cortes"
            txtEstadoModo247.setTextColor(Color.parseColor("#34D399"))
        } else {
            cardModo247.background = fondoBanner("#1C160C", "#F59E0B")
            txtEstadoModo247.text = "⚠️ Desactivado — Toca el interruptor para permitir 24/7"
            txtEstadoModo247.setTextColor(Color.parseColor("#FBBF24"))
        }

        if (gpsActivo) {
            txtEstadoClima.text = "● Activo — Usando ubicación precisa para el clima exterior"
            txtEstadoClima.setTextColor(Color.parseColor("#34D399"))
        } else {
            txtEstadoClima.text = "○ Desactivado — Usando clima aproximado por ciudad"
            txtEstadoClima.setTextColor(Color.parseColor("#64748B"))
        }

        if (::dotAgente.isInitialized) {
            dotAgente.background = if (running) {
                GradientDrawable().apply {
                    setColor(Color.parseColor("#10B981"))
                    cornerRadius = 999f
                }
            } else {
                GradientDrawable().apply {
                    setColor(Color.parseColor("#64748B"))
                    cornerRadius = 999f
                }
            }
        }
    }

    private fun cambiarTab(nuevoTab: Int) {
        tabActual = nuevoTab
        if (nuevoTab == 0) {
            panelLayout.visibility = View.VISIBLE
            scrollAgente.visibility = View.GONE
            txtTabPanel.setTextColor(Color.parseColor("#3B82F6"))
            txtTabPanel.setTypeface(null, Typeface.BOLD)
            txtTabAgente.setTextColor(Color.parseColor("#94A3B8"))
            txtTabAgente.setTypeface(null, Typeface.NORMAL)
        } else {
            panelLayout.visibility = View.GONE
            scrollAgente.visibility = View.VISIBLE
            txtTabPanel.setTextColor(Color.parseColor("#94A3B8"))
            txtTabPanel.setTypeface(null, Typeface.NORMAL)
            txtTabAgente.setTextColor(Color.parseColor("#3B82F6"))
            txtTabAgente.setTypeface(null, Typeface.BOLD)
        }
    }

    private fun sincronizarSesionDesdeWeb() {
        val cookies = CookieManager.getInstance().getCookie("https://elminerogris.com") ?: ""
        if (!cookies.contains("session=")) return

        scope.launch(Dispatchers.IO) {
            try {
                val conn = (URL("https://elminerogris.com/api/agente/mi_sesion").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("Cookie", cookies)
                    setRequestProperty("User-Agent", "ElMineroGris-AndroidApp/1.0")
                }
                if (conn.responseCode == 200) {
                    val jsonStr = conn.inputStream.bufferedReader().readText()
                    val resp = JSONObject(jsonStr)
                    if (resp.optBoolean("ok", false)) {
                        val u = resp.optString("username", "").trim()
                        val k = resp.optString("api_key", "").trim()
                        val sub = resp.optString("subnet", "192.168.0").trim()
                        val mUser = resp.optString("miner_user", "").trim()
                        val mPass = resp.optString("miner_pass", "").trim()

                        if (u.isNotEmpty() && k.isNotEmpty()) {
                            val prefs = getSharedPreferences("emg_prefs", Context.MODE_PRIVATE)
                            val uGuardado = prefs.getString("username", "") ?: ""
                            val kGuardada = prefs.getString("api_key", "") ?: ""

                            if (u != uGuardado || k != kGuardada) {
                                val ed = prefs.edit()
                                    .putString("username", u)
                                    .putString("api_key", k)
                                    .putString("subnet", sub)
                                if (mUser.isNotEmpty()) ed.putString("miner_user", mUser)
                                if (mPass.isNotEmpty()) ed.putString("miner_pass", mPass)
                                ed.apply()

                                withContext(Dispatchers.Main) {
                                    mostrandoFormularioLogin = false
                                    actualizarTodoUI()
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
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
                abrirAjustesBateriaSistema()
            }
        }
    }

    private fun abrirAjustesBateriaSistema() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {}
        }
    }

    private fun obtenerOCrearAgentId(prefs: SharedPreferences): String {
        val existente = prefs.getString("agent_id", "")?.trim() ?: ""
        if (existente.length >= 4) return existente
        val nuevoId = "AND-" + UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
        prefs.edit().putString("agent_id", nuevoId).apply()
        return nuevoId
    }

    private fun solicitarPermisoNotificaciones() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
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

    private fun iniciarFlujoActualizacion() {
        try {
            val url = if (apkUpdateUrl.isNotEmpty()) apkUpdateUrl else "https://github.com/elminerogris/el-minero-gris-android/releases/latest/download/ElMineroGris.apk"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "No se pudo abrir el enlace de descarga", Toast.LENGTH_SHORT).show()
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
