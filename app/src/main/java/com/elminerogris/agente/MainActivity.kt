package com.elminerogris.agente

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
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
import android.view.MotionEvent
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
    private lateinit var scrollInicioViaBTC: ScrollView
    private lateinit var scrollPoolViaBTC: ScrollView
    private lateinit var tabBtnInicio: LinearLayout
    private lateinit var tabBtnPanel: LinearLayout
    private lateinit var tabBtnMineros: LinearLayout
    private lateinit var tabBtnAgente: LinearLayout
    private lateinit var txtTabInicio: TextView
    private lateinit var txtTabPanel: TextView
    private lateinit var txtTabMineros: TextView
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
        val esBeta = packageName.endsWith(".beta")

        val headerTxt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headerTxt.addView(TextView(this).apply {
            text = if (esBeta) "El Minero Gris (Beta)" else "El Minero Gris"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (esBeta) Color.parseColor("#38BDF8") else Color.WHITE)
        })
        headerTxt.addView(TextView(this).apply {
            text = if (esBeta) "🧪 Versión de Pruebas  •  v$versionLocalName" else "Puente Local de Granja 24/7  •  v$versionLocalName"
            textSize = 12f
            setTextColor(if (esBeta) Color.parseColor("#F59E0B") else Color.parseColor("#94A3B8"))
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

        scrollInicioViaBTC = crearVistaInicioViaBTC(dp)
        scrollPoolViaBTC = crearVistaPoolMineria(dp)

        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            addView(scrollInicioViaBTC)
            addView(scrollPoolViaBTC)
            addView(panelLayout)
            addView(scrollAgente)
        }

        // ==================== BARRA DE NAVEGACIÓN INFERIOR ESTILO VIABTC ====================
        val bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            val borderTop = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(1.dp(), Color.parseColor("#EEF0F2"))
            }
            background = borderTop
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                58.dp()
            )
        }

        fun crearTabItem(icono: String, texto: String, idx: Int): Pair<LinearLayout, TextView> {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                isClickable = true
                isFocusable = true
                setOnClickListener { cambiarTab(idx) }
            }

            val iconTv = TextView(this).apply {
                text = icono
                textSize = 18f
                gravity = Gravity.CENTER
            }

            val labelTv = TextView(this).apply {
                text = texto
                textSize = 10.5f
                setTextColor(if (idx == 0) Color.parseColor("#00C4B4") else Color.parseColor("#848E9C"))
                setTypeface(null, if (idx == 0) Typeface.BOLD else Typeface.NORMAL)
                gravity = Gravity.CENTER
                setPadding(0, 1.dp(), 0, 0)
            }

            item.addView(iconTv)
            item.addView(labelTv)
            return Pair(item, labelTv)
        }

        val (t0, txt0) = crearTabItem("🏠", "Inicio", 0)
        val (t1, txt1) = crearTabItem("📈", "Pool de minería", 1)
        val (t2, txt2) = crearTabItem("⛏️", "Mineros", 2)
        val (t3, txt3) = crearTabItem("💼", "Activos", 3)

        tabBtnInicio = t0
        txtTabInicio = txt0

        tabBtnPanel = t1
        txtTabPanel = txt1

        tabBtnMineros = t2
        txtTabMineros = txt2

        tabBtnAgente = t3
        txtTabAgente = txt3

        bottomNav.addView(tabBtnInicio)
        bottomNav.addView(tabBtnPanel)
        bottomNav.addView(tabBtnMineros)
        bottomNav.addView(tabBtnAgente)

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F8FAFB"))
            addView(contentFrame)
            addView(bottomNav)
        }

        setContentView(mainLayout)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (tabActual == 2 && webView.canGoBack()) {
                    webView.goBack()
                } else if (tabActual != 0) {
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

    // ==================== COMPONENTE DE GRÁFICO NATIVO DE HASHRATE ====================
    private class GraficoHashrateView(context: Context) : View(context) {
        private val dp = resources.displayMetrics.density
        private fun Float.dp(): Float = this * dp
        private fun Int.dp(): Int = (this * dp).toInt()

        private var puntos: List<Float> = listOf(
            338f, 341f, 339f, 344f, 347f, 345f, 350f, 348f, 353f, 351f, 348f, 352f,
            354f, 351f, 349f, 346f, 350f, 353f, 351f, 354f, 355f, 352f, 349f, 348.5f
        )
        private var etiquetasX: List<String> = listOf("00:00", "04:00", "08:00", "12:00", "16:00", "20:00", "Ahora")
        private var indiceTocado: Int? = null

        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00C4B4")
            strokeWidth = 2.8f.dp()
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EEF2F6")
            strokeWidth = 1f.dp()
            style = Paint.Style.STROKE
        }

        private val avgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F59E0B")
            strokeWidth = 1.4f.dp()
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
        }

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#848E9C")
            textSize = 10f.dp()
        }

        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00C4B4")
            style = Paint.Style.FILL
        }

        private val dotInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        private val tooltipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E2329")
            style = Paint.Style.FILL
        }

        private val tooltipText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 11.5f.dp()
            typeface = Typeface.DEFAULT_BOLD
        }

        private val path = Path()
        private val fillPath = Path()

        fun setRango(rango: String) {
            indiceTocado = null
            puntos = when (rango) {
                "1h" -> listOf(346f, 347.5f, 349f, 348f, 350.2f, 351.5f, 350f, 349.8f, 352f, 351f, 349.5f, 348.5f)
                "24h" -> listOf(
                    338f, 341f, 339f, 344f, 347f, 345f, 350f, 348f, 353f, 351f, 348f, 352f,
                    354f, 351f, 349f, 346f, 350f, 353f, 351f, 354f, 355f, 352f, 349f, 348.5f
                )
                "7d" -> listOf(332f, 339f, 343f, 346f, 345f, 351f, 348.5f)
                "30d" -> listOf(310f, 318f, 325f, 330f, 334f, 338f, 342f, 340f, 345f, 347f, 346f, 350f, 352f, 349f, 348.5f)
                else -> puntos
            }
            etiquetasX = when (rango) {
                "1h" -> listOf("-60m", "-45m", "-30m", "-15m", "Ahora")
                "24h" -> listOf("00:00", "04:00", "08:00", "12:00", "16:00", "20:00", "Ahora")
                "7d" -> listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Hoy")
                "30d" -> listOf("Día 1", "Día 7", "Día 14", "Día 21", "Hoy")
                else -> etiquetasX
            }
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val padLeft = 14f.dp()
                    val padRight = 14f.dp()
                    val usableWidth = width - padLeft - padRight
                    if (usableWidth > 0 && puntos.size > 1) {
                        val step = usableWidth / (puntos.size - 1)
                        val relX = (event.x - padLeft).coerceIn(0f, usableWidth)
                        indiceTocado = (relX / step).toInt().coerceIn(0, puntos.size - 1)
                        invalidate()
                        return true
                    }
                }
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (puntos.isEmpty() || width <= 0 || height <= 0) return

            val padLeft = 14f.dp()
            val padRight = 14f.dp()
            val padTop = 26f.dp()
            val padBottom = 28f.dp()

            val chartWidth = width - padLeft - padRight
            val chartHeight = height - padTop - padBottom

            val minVal = (puntos.minOrNull() ?: 300f) * 0.96f
            val maxVal = (puntos.maxOrNull() ?: 400f) * 1.04f
            val range = (maxVal - minVal).coerceAtLeast(1f)

            // 1. Líneas guía horizontales y valores
            val numGrid = 3
            for (i in 0..numGrid) {
                val y = padTop + (chartHeight / numGrid) * i
                canvas.drawLine(padLeft, y, width - padRight, y, gridPaint)
                val v = maxVal - (range / numGrid) * i
                val txt = "%.0f TH/s".format(v)
                canvas.drawText(txt, width - padRight - textPaint.measureText(txt) - 4.dp(), y - 4.dp(), textPaint)
            }

            // 2. Línea de promedio punteada
            val avgVal = puntos.average().toFloat()
            val avgY = padTop + chartHeight * (1f - (avgVal - minVal) / range)
            canvas.drawLine(padLeft, avgY, width - padRight, avgY, avgPaint)
            val avgLabel = "Promedio: %.1f TH/s".format(avgVal)
            val avgLabelPaint = Paint(textPaint).apply {
                color = Color.parseColor("#F59E0B")
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText(avgLabel, padLeft + 6.dp(), avgY - 5.dp(), avgLabelPaint)

            // 3. Coordenadas de los puntos
            val stepX = chartWidth / (puntos.size - 1)
            val xs = FloatArray(puntos.size)
            val ys = FloatArray(puntos.size)
            for (i in puntos.indices) {
                xs[i] = padLeft + i * stepX
                ys[i] = padTop + chartHeight * (1f - (puntos[i] - minVal) / range)
            }

            // 4. Curva de Bézier suave
            path.reset()
            fillPath.reset()
            path.moveTo(xs[0], ys[0])
            fillPath.moveTo(xs[0], padTop + chartHeight)
            fillPath.lineTo(xs[0], ys[0])

            for (i in 0 until puntos.size - 1) {
                val cx1 = (xs[i] + xs[i + 1]) / 2f
                val cy1 = ys[i]
                val cx2 = (xs[i] + xs[i + 1]) / 2f
                val cy2 = ys[i + 1]
                path.cubicTo(cx1, cy1, cx2, cy2, xs[i + 1], ys[i + 1])
                fillPath.cubicTo(cx1, cy1, cx2, cy2, xs[i + 1], ys[i + 1])
            }

            fillPath.lineTo(xs.last(), padTop + chartHeight)
            fillPath.close()

            // 5. Relleno de degradado mint
            val grad = LinearGradient(
                0f, padTop, 0f, padTop + chartHeight,
                intArrayOf(Color.parseColor("#5500C4B4"), Color.parseColor("#0A00C4B4"), Color.TRANSPARENT),
                floatArrayOf(0f, 0.7f, 1f),
                Shader.TileMode.CLAMP
            )
            fillPaint.shader = grad
            canvas.drawPath(fillPath, fillPaint)

            // 6. Línea principal de Hashrate
            canvas.drawPath(path, linePaint)

            // 7. Punto actual en vivo (último punto)
            val lastIdx = puntos.size - 1
            canvas.drawCircle(xs[lastIdx], ys[lastIdx], 6f.dp(), dotPaint)
            canvas.drawCircle(xs[lastIdx], ys[lastIdx], 3f.dp(), dotInnerPaint)

            // 8. Eje X con etiquetas de tiempo
            if (etiquetasX.isNotEmpty()) {
                val stepLabel = chartWidth / (etiquetasX.size - 1)
                for (i in etiquetasX.indices) {
                    val lx = padLeft + i * stepLabel
                    val ly = height - 8f.dp()
                    val text = etiquetasX[i]
                    val tWidth = textPaint.measureText(text)
                    val drawX = (lx - tWidth / 2f).coerceIn(padLeft, width - padRight - tWidth)
                    canvas.drawText(text, drawX, ly, textPaint)
                }
            }

            // 9. Tooltip interactivo si se toca
            val idx = indiceTocado
            if (idx != null && idx in puntos.indices) {
                val tx = xs[idx]
                val ty = ys[idx]

                val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#00C4B4")
                    strokeWidth = 1.2f.dp()
                    pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
                }
                canvas.drawLine(tx, padTop, tx, padTop + chartHeight, guidePaint)
                canvas.drawCircle(tx, ty, 6f.dp(), dotPaint)
                canvas.drawCircle(tx, ty, 3f.dp(), dotInnerPaint)

                val valText = "%.1f TH/s".format(puntos[idx])
                val tW = tooltipText.measureText(valText)
                val bubbleW = tW + 16.dp()
                val bubbleH = 26f.dp()
                val bubbleX = (tx - bubbleW / 2f).coerceIn(padLeft, width - padRight - bubbleW)
                val bubbleY = (ty - bubbleH - 8.dp()).coerceAtLeast(4f.dp())

                val rect = RectF(bubbleX, bubbleY, bubbleX + bubbleW, bubbleY + bubbleH)
                canvas.drawRoundRect(rect, 6f.dp(), 6f.dp(), tooltipBg)
                canvas.drawText(valText, bubbleX + 8.dp(), bubbleY + 17f.dp(), tooltipText)
            }
        }
    }

    private data class AsicRow(
        val modelo: String,
        val ip: String,
        val algo: String,
        val badge: String?,
        val badgeColor: String?,
        val iconTxt: String,
        val iconBg: String,
        val gananciaUsd: String,
        val gananciaBtc: String,
        val hashrateVal: String,
        val hashrateUnidad: String,
        val tempYEstado: String
    )

    private fun cambiarTab(nuevoTab: Int) {
        tabActual = nuevoTab

        val teal = Color.parseColor("#00C4B4")
        val gray = Color.parseColor("#848E9C")

        txtTabInicio.setTextColor(if (nuevoTab == 0) teal else gray)
        txtTabInicio.setTypeface(null, if (nuevoTab == 0) Typeface.BOLD else Typeface.NORMAL)

        txtTabPanel.setTextColor(if (nuevoTab == 1) teal else gray)
        txtTabPanel.setTypeface(null, if (nuevoTab == 1) Typeface.BOLD else Typeface.NORMAL)

        txtTabMineros.setTextColor(if (nuevoTab == 2) teal else gray)
        txtTabMineros.setTypeface(null, if (nuevoTab == 2) Typeface.BOLD else Typeface.NORMAL)

        txtTabAgente.setTextColor(if (nuevoTab == 3) teal else gray)
        txtTabAgente.setTypeface(null, if (nuevoTab == 3) Typeface.BOLD else Typeface.NORMAL)

        when (nuevoTab) {
            0 -> {
                scrollInicioViaBTC.visibility = View.VISIBLE
                scrollPoolViaBTC.visibility = View.GONE
                panelLayout.visibility = View.GONE
                scrollAgente.visibility = View.GONE
            }
            1 -> {
                scrollInicioViaBTC.visibility = View.GONE
                scrollPoolViaBTC.visibility = View.VISIBLE
                panelLayout.visibility = View.GONE
                scrollAgente.visibility = View.GONE
            }
            2 -> {
                scrollInicioViaBTC.visibility = View.GONE
                scrollPoolViaBTC.visibility = View.GONE
                panelLayout.visibility = View.VISIBLE
                scrollAgente.visibility = View.GONE
                val curUrl = webView.url ?: ""
                if (!curUrl.contains("/mineros")) {
                    webView.loadUrl("https://elminerogris.com/mineros")
                }
            }
            3 -> {
                scrollInicioViaBTC.visibility = View.GONE
                scrollPoolViaBTC.visibility = View.GONE
                panelLayout.visibility = View.GONE
                scrollAgente.visibility = View.VISIBLE
                sincronizarSesionDesdeWeb()
            }
        }
    }

    private fun crearVistaInicioViaBTC(dp: Float): ScrollView {
        fun Int.dp(): Int = (this * dp).toInt()
        fun fondo(colorHex: String, radioDp: Int = 12, bordeHex: String? = null, grosorDp: Int = 1): GradientDrawable {
            return GradientDrawable().apply {
                setColor(Color.parseColor(colorHex))
                cornerRadius = radioDp * dp
                if (bordeHex != null) setStroke((grosorDp * dp).toInt(), Color.parseColor(bordeHex))
            }
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F8FAFB"))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F8FAFB"))
        }

        // ==================== 1. CABECERA CON LOGO OFICIAL EL MINERO GRIS ====================
        val headerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val grad = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#D5F5F0"), Color.parseColor("#F0FAF9"), Color.parseColor("#F8FAFB"))
            )
            background = grad
            setPadding(18.dp(), 18.dp(), 18.dp(), 12.dp())
        }

        val topBarRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val brandBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val imgLogo = ImageView(this).apply {
            try {
                val resId = resources.getIdentifier("logo_elminerogris", "drawable", packageName)
                if (resId != 0) {
                    setImageResource(resId)
                } else {
                    setImageResource(R.mipmap.ic_launcher)
                }
            } catch (_: Exception) {
                setImageResource(R.mipmap.ic_launcher)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(34.dp(), 34.dp()).apply {
                rightMargin = 8.dp()
            }
        }

        val txtBrand = TextView(this).apply {
            text = "El Minero Gris"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1E2329"))
        }

        val badgeBeta = TextView(this).apply {
            text = "BETA"
            textSize = 9.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#D97706"))
            background = fondo("#FEF3C7", 4, "#FDE68A")
            setPadding(5.dp(), 2.dp(), 5.dp(), 2.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = 7.dp() }
        }

        brandBox.addView(imgLogo)
        brandBox.addView(txtBrand)
        brandBox.addView(badgeBeta)

        val topActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val bellFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(36.dp(), 36.dp()).apply {
                rightMargin = 10.dp()
            }
        }
        val iconBell = TextView(this).apply {
            text = "🔔"
            textSize = 19f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val badgeNotif = TextView(this).apply {
            text = "0"
            textSize = 9.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = fondo("#00C4B4", 999)
            layoutParams = FrameLayout.LayoutParams(18.dp(), 18.dp()).apply {
                gravity = Gravity.TOP or Gravity.END
            }
        }
        bellFrame.addView(iconBell)
        bellFrame.addView(badgeNotif)

        val iconProfile = TextView(this).apply {
            text = "👤"
            textSize = 21f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(32.dp(), 32.dp())
            isClickable = true
            isFocusable = true
            setOnClickListener {
                cambiarTab(3)
            }
        }

        topActions.addView(bellFrame)
        topActions.addView(iconProfile)

        topBarRow.addView(brandBox)
        topBarRow.addView(topActions)
        headerContainer.addView(topBarRow)

        // ==================== TARJETA RESUMEN DE LA GRANJA (KPIs) ====================
        val kpiCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fondo("#FFFFFF", 14, "#E2E8F0")
            setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 12.dp()
            }
            elevation = 1.5f * dp
        }

        val kpiRowTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val boxHr = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        boxHr.addView(TextView(this).apply {
            text = "Hashrate Total Flota"
            textSize = 11.5f
            setTextColor(Color.parseColor("#848E9C"))
        })
        val hrTotalRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        hrTotalRow.addView(TextView(this).apply {
            text = "348.5 "
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1E2329"))
        })
        hrTotalRow.addView(TextView(this).apply {
            text = "TH/s"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#00C4B4"))
            setPadding(0, 0, 0, 2.dp())
        })
        boxHr.addView(hrTotalRow)

        val boxGanancia = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        boxGanancia.addView(TextView(this).apply {
            text = "Ganancia Diaria Est."
            textSize = 11.5f
            setTextColor(Color.parseColor("#848E9C"))
        })
        boxGanancia.addView(TextView(this).apply {
            text = "$ 11.64 USD"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0AC18E"))
        })
        boxGanancia.addView(TextView(this).apply {
            text = "≈ 0.000192 BTC/día"
            textSize = 10.5f
            setTextColor(Color.parseColor("#848E9C"))
        })

        kpiRowTop.addView(boxHr)
        kpiRowTop.addView(boxGanancia)
        kpiCard.addView(kpiRowTop)

        val kpiDivider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1.dp()).apply {
                topMargin = 10.dp()
                bottomMargin = 10.dp()
            }
            setBackgroundColor(Color.parseColor("#F1F5F9"))
        }
        kpiCard.addView(kpiDivider)

        val kpiRowBottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        kpiRowBottom.addView(TextView(this).apply {
            text = "● 3 Mineros en línea"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#00BFA5"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        kpiRowBottom.addView(TextView(this).apply {
            text = "⚡ Consumo: 6,574 W"
            textSize = 11.5f
            setTextColor(Color.parseColor("#64748B"))
        })
        kpiCard.addView(kpiRowBottom)

        headerContainer.addView(kpiCard)
        root.addView(headerContainer)

        // ==================== 2. GRID DE ACCESOS RÁPIDOS ASIC (2 FILAS X 4 BOTONES) ====================
        val gridCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fondo("#FFFFFF", 16, "#EDF2F4")
            setPadding(12.dp(), 16.dp(), 12.dp(), 14.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8.dp()
                leftMargin = 14.dp()
                rightMargin = 14.dp()
                bottomMargin = 14.dp()
            }
            elevation = 2f * dp
        }

        fun crearBotonGrid(icono: String, etiqueta: String, accion: () -> Unit): LinearLayout {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(4.dp(), 4.dp(), 4.dp(), 4.dp())
                isClickable = true
                isFocusable = true
                setOnClickListener { accion() }
            }

            val iconCircle = TextView(this).apply {
                text = icono
                textSize = 21f
                gravity = Gravity.CENTER
                background = fondo("#F0FAF9", 999, "#D3F2EE")
                layoutParams = LinearLayout.LayoutParams(46.dp(), 46.dp()).apply {
                    bottomMargin = 6.dp()
                }
            }

            val lbl = TextView(this).apply {
                text = etiqueta
                textSize = 10.5f
                setTextColor(Color.parseColor("#2D3748"))
                gravity = Gravity.CENTER
                setLineSpacing(1f, 0.95f)
            }

            item.addView(iconCircle)
            item.addView(lbl)
            return item
        }

        val rowGrid1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 14.dp()
            }
        }
        rowGrid1.addView(crearBotonGrid("🔍", "Escanear\nRed") {
            actualizarTodoUI()
            Toast.makeText(this, "Escaneando red local por mineros ASIC...", Toast.LENGTH_SHORT).show()
        })
        rowGrid1.addView(crearBotonGrid("⚡", "Reiniciar\nFlota") {
            AlertDialog.Builder(this)
                .setTitle("⚡ Reiniciar Mineros")
                .setMessage("¿Deseas enviar comando de reinicio preventivo a los mineros conectados?")
                .setPositiveButton("Reiniciar") { _, _ ->
                    Toast.makeText(this, "Comando enviado a la flota", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        })
        rowGrid1.addView(crearBotonGrid("🌡️", "Diagnóstico\nChips") {
            cambiarTab(2)
        })
        rowGrid1.addView(crearBotonGrid("⚙️", "Configurar\nPools") {
            cambiarTab(1)
        })

        val rowGrid2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        rowGrid2.addView(crearBotonGrid("🧮", "Calculadora\nRentabilidad") {
            webView.loadUrl("https://elminerogris.com/resumen#calcRentabilidad")
            cambiarTab(2)
        })
        rowGrid2.addView(crearBotonGrid("💾", "Firmware\nOTA") {
            webView.loadUrl("https://elminerogris.com/firmware")
            cambiarTab(2)
        })
        rowGrid2.addView(crearBotonGrid("📊", "Reporte\n24h") {
            webView.loadUrl("https://elminerogris.com/resumen")
            cambiarTab(2)
        })
        rowGrid2.addView(crearBotonGrid("☁️", "Panel\nCloud") {
            webView.loadUrl("https://elminerogris.com/resumen")
            cambiarTab(2)
        })

        gridCard.addView(rowGrid1)
        gridCard.addView(rowGrid2)
        root.addView(gridCard)

        // ==================== 3. TÍTULO DE SECCIÓN: TUS MINEROS ASIC ====================
        val secHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = 18.dp()
                rightMargin = 18.dp()
                bottomMargin = 10.dp()
            }
        }

        val txtSecTitle = TextView(this).apply {
            text = "Mineros ASIC Activos"
            textSize = 17.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1E2329"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val pillFilter = TextView(this).apply {
            text = "Todos los ASICs (3) ▾"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#00BFA5"))
            background = fondo("#E8F9F7", 14)
            setPadding(10.dp(), 5.dp(), 10.dp(), 5.dp())
        }

        secHeader.addView(txtSecTitle)
        secHeader.addView(pillFilter)
        root.addView(secHeader)

        // ==================== 4. ENCABEZADO DE TABLA DE ASICs ====================
        val tableHdr = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18.dp(), 6.dp(), 18.dp(), 8.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val th1 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.35f)
        }
        th1.addView(TextView(this).apply {
            text = "Minero / Modelo"
            textSize = 11f
            setTextColor(Color.parseColor("#848E9C"))
        })
        th1.addView(TextView(this).apply {
            text = "IP y Algoritmo"
            textSize = 10f
            setTextColor(Color.parseColor("#B0B8C1"))
        })

        val th2 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f)
        }
        th2.addView(TextView(this).apply {
            text = "Ganancia Diaria"
            textSize = 11f
            setTextColor(Color.parseColor("#848E9C"))
        })
        th2.addView(TextView(this).apply {
            text = "Est. USD / BTC"
            textSize = 10f
            setTextColor(Color.parseColor("#B0B8C1"))
        })

        val th3 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.35f)
        }
        th3.addView(TextView(this).apply {
            text = "Hashrate Real"
            textSize = 11f
            setTextColor(Color.parseColor("#848E9C"))
        })
        th3.addView(TextView(this).apply {
            text = "Temp y Chips"
            textSize = 10f
            setTextColor(Color.parseColor("#B0B8C1"))
        })

        tableHdr.addView(th1)
        tableHdr.addView(th2)
        tableHdr.addView(th3)
        root.addView(tableHdr)

        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1.dp()).apply {
                leftMargin = 18.dp()
                rightMargin = 18.dp()
                bottomMargin = 4.dp()
            }
            setBackgroundColor(Color.parseColor("#F0F2F5"))
        })

        // ==================== 5. FILAS DE MINEROS ASIC (CON TH/s Y GANANCIA DIARIA) ====================
        val listaAsics = listOf(
            AsicRow("Antminer S21 Pro", "192.168.1.101", "SHA-256d", "Líder", "#10B981", "⛏️", "#00C4B4", "$ 7.82", "0.000129 BTC · 3510W", "234.2", "TH/s", "64°C · Normal 🟢"),
            AsicRow("Antminer S19j Pro", "192.168.1.102", "SHA-256d", null, null, "⛏️", "#1E293B", "$ 3.48", "0.000057 BTC · 3050W", "104.5", "TH/s", "68°C · Normal 🟢"),
            AsicRow("WhatsMiner M50S+", "192.168.1.103", "SHA-256d", null, null, "⚡", "#334155", "$ 4.55", "0.000075 BTC · 3264W", "136.0", "TH/s", "66°C · Normal 🟢"),
            AsicRow("Antminer L7 9050M", "192.168.1.104", "Scrypt", "Dual", "#3B82F6", "Ł", "#345D9D", "$ 8.12", "LTC+DOGE · 3260W", "9.05", "GH/s", "63°C · Normal 🟢")
        )

        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        for (a in listaAsics) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(18.dp(), 14.dp(), 18.dp(), 14.dp())
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    webView.loadUrl("https://elminerogris.com/mineros")
                    cambiarTab(2)
                }
            }

            val c1 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.35f)
            }

            val iconAsic = TextView(this).apply {
                text = a.iconTxt
                textSize = 17f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = fondo(a.iconBg, 10)
                layoutParams = LinearLayout.LayoutParams(36.dp(), 36.dp()).apply {
                    rightMargin = 10.dp()
                }
            }

            val c1Text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val c1Header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            c1Header.addView(TextView(this).apply {
                text = a.modelo
                textSize = 14.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#1E2329"))
            })

            if (a.badge != null && a.badgeColor != null) {
                c1Header.addView(TextView(this).apply {
                    text = a.badge
                    textSize = 8.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    background = fondo(a.badgeColor, 4)
                    setPadding(4.dp(), 1.dp(), 4.dp(), 1.dp())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { leftMargin = 5.dp() }
                })
            }

            c1Text.addView(c1Header)
            c1Text.addView(TextView(this).apply {
                text = "${a.ip} · ${a.algo}"
                textSize = 10.5f
                setTextColor(Color.parseColor("#848E9C"))
            })

            c1.addView(iconAsic)
            c1.addView(c1Text)

            val c2 = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f)
                setPadding(0, 0, 8.dp(), 0)
            }

            val c2RowTop = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            c2RowTop.addView(TextView(this).apply {
                text = a.gananciaUsd + " "
                textSize = 13.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#0AC18E"))
            })
            c2RowTop.addView(TextView(this).apply {
                text = "/día"
                textSize = 10.5f
                setTextColor(Color.parseColor("#848E9C"))
            })

            c2.addView(c2RowTop)
            c2.addView(TextView(this).apply {
                text = a.gananciaBtc
                textSize = 9.8f
                setTextColor(Color.parseColor("#848E9C"))
            })

            val c3 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.35f)
            }

            val c3Text = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val c3RowTop = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            c3RowTop.addView(TextView(this).apply {
                text = a.hashrateVal + " "
                textSize = 13.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#1E2329"))
            })
            c3RowTop.addView(TextView(this).apply {
                text = a.hashrateUnidad
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#00C4B4"))
            })

            c3Text.addView(c3RowTop)
            c3Text.addView(TextView(this).apply {
                text = a.tempYEstado
                textSize = 10f
                setTextColor(Color.parseColor("#64748B"))
            })

            val chevron = TextView(this).apply {
                text = "›"
                textSize = 17f
                setTextColor(Color.parseColor("#B0B8C1"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = 6.dp() }
            }

            c3.addView(c3Text)
            c3.addView(chevron)

            row.addView(c1)
            row.addView(c2)
            row.addView(c3)

            listContainer.addView(row)
            listContainer.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1.dp()).apply {
                    leftMargin = 18.dp()
                    rightMargin = 18.dp()
                }
                setBackgroundColor(Color.parseColor("#F4F6F8"))
            })
        }

        root.addView(listContainer)

        // ==================== 6. TARJETA INFERIOR: TU AGENTE LOCAL 24/7 ====================
        val flotaCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fondo("#FFFFFF", 14, "#E2E8F0")
            setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 14.dp()
                leftMargin = 14.dp()
                rightMargin = 14.dp()
                bottomMargin = 24.dp()
            }
        }

        val flotaTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 6.dp()
            }
        }
        flotaTop.addView(TextView(this).apply {
            text = "⚡ Tu Agente Local 24/7"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1E2329"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        flotaTop.addView(TextView(this).apply {
            text = "● Monitoreando"
            textSize = 11f
            setTextColor(Color.parseColor("#00BFA5"))
            setTypeface(null, Typeface.BOLD)
        })

        val flotaTxt = TextView(this).apply {
            text = "Escaneo continuo de red local, telemetría de placas hashboard y protección contra sobrecalentamiento."
            textSize = 11.5f
            setTextColor(Color.parseColor("#64748B"))
            lineHeight = (17 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 10.dp()
            }
        }

        val btnIrAgente = Button(this).apply {
            text = "📡   Abrir Monitor de Agente Local"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#00BFA5"))
            background = fondo("#E8F9F7", 8, "#B2EBF2")
            setPadding(12.dp(), 8.dp(), 12.dp(), 8.dp())
            setOnClickListener { cambiarTab(3) }
        }

        flotaCard.addView(flotaTop)
        flotaCard.addView(flotaTxt)
        flotaCard.addView(btnIrAgente)
        root.addView(flotaCard)

        scroll.addView(root)
        return scroll
    }

    // ==================== VISTA: POOL DE MINERÍA CON GRÁFICO NATIVO ====================
    private fun crearVistaPoolMineria(dp: Float): ScrollView {
        fun Int.dp(): Int = (this * dp).toInt()
        fun fondo(colorHex: String, radioDp: Int = 12, bordeHex: String? = null, grosorDp: Int = 1): GradientDrawable {
            return GradientDrawable().apply {
                setColor(Color.parseColor(colorHex))
                cornerRadius = radioDp * dp
                if (bordeHex != null) setStroke((grosorDp * dp).toInt(), Color.parseColor(bordeHex))
            }
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F8FAFB"))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F8FAFB"))
            setPadding(0, 0, 0, 24.dp())
        }

        // 1. Cabecera de Pool
        val poolHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val grad = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#D5F5F0"), Color.parseColor("#F0FAF9"), Color.parseColor("#F8FAFB"))
            )
            background = grad
            setPadding(18.dp(), 18.dp(), 18.dp(), 14.dp())
        }

        val rowPoolTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val poolTitleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val txtPoolName = TextView(this).apply {
            text = "ViaBTC Pool ▾"
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1E2329"))
        }
        val txtPoolWorker = TextView(this).apply {
            text = "Worker: elminerogris.001 · SHA-256d (PPS+)"
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
        }
        poolTitleBox.addView(txtPoolName)
        poolTitleBox.addView(txtPoolWorker)

        val badgePoolStatus = TextView(this).apply {
            text = "● 22 ms"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#00BFA5"))
            background = fondo("#E8F9F7", 12, "#B2EBF2")
            setPadding(8.dp(), 4.dp(), 8.dp(), 4.dp())
        }

        rowPoolTop.addView(poolTitleBox)
        rowPoolTop.addView(badgePoolStatus)
        poolHeader.addView(rowPoolTop)
        root.addView(poolHeader)

        // 2. Tarjeta con Gráfico de Hashrate en tiempo real
        val cardGrafico = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fondo("#FFFFFF", 16, "#EDF2F4")
            setPadding(16.dp(), 16.dp(), 16.dp(), 14.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = 14.dp()
                rightMargin = 14.dp()
                bottomMargin = 14.dp()
            }
            elevation = 2f * dp
        }

        val rowGraficoTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val colHrActual = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        colHrActual.addView(TextView(this).apply {
            text = "Hashrate Efectivo del Pool"
            textSize = 11.5f
            setTextColor(Color.parseColor("#848E9C"))
        })

        val rowHrGrande = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        rowHrGrande.addView(TextView(this).apply {
            text = "348.5 "
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1E2329"))
        })
        rowHrGrande.addView(TextView(this).apply {
            text = "TH/s "
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#00C4B4"))
            setPadding(0, 0, 0, 2.dp())
        })
        rowHrGrande.addView(TextView(this).apply {
            text = "+2.4%"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0AC18E"))
            background = fondo("#E8F9F3", 4)
            setPadding(4.dp(), 1.dp(), 4.dp(), 1.dp())
            setPadding(0, 0, 0, 2.dp())
        })
        colHrActual.addView(rowHrGrande)
        rowGraficoTop.addView(colHrActual)
        cardGrafico.addView(rowGraficoTop)

        // Selector de Rango (1h, 24h, 7d, 30d)
        val rowRangos = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 12.dp()
                bottomMargin = 8.dp()
            }
        }

        val graficoView = GraficoHashrateView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                190.dp()
            )
        }

        val botonesRangos = mutableListOf<TextView>()
        fun crearBotonRango(rango: String, esDefault: Boolean = false): TextView {
            val btn = TextView(this).apply {
                text = rango
                textSize = 11.5f
                gravity = Gravity.CENTER
                setTypeface(null, if (esDefault) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (esDefault) Color.parseColor("#00C4B4") else Color.parseColor("#848E9C"))
                background = if (esDefault) fondo("#E8F9F7", 8, "#00C4B4") else fondo("#F4F6F8", 8)
                setPadding(12.dp(), 5.dp(), 12.dp(), 5.dp())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = 6.dp()
                }
                setOnClickListener {
                    graficoView.setRango(rango)
                    for (b in botonesRangos) {
                        val esEste = b == this
                        b.setTypeface(null, if (esEste) Typeface.BOLD else Typeface.NORMAL)
                        b.setTextColor(if (esEste) Color.parseColor("#00C4B4") else Color.parseColor("#848E9C"))
                        b.background = if (esEste) fondo("#E8F9F7", 8, "#00C4B4") else fondo("#F4F6F8", 8)
                    }
                }
            }
            botonesRangos.add(btn)
            return btn
        }

        rowRangos.addView(crearBotonRango("1h"))
        rowRangos.addView(crearBotonRango("24h", true))
        rowRangos.addView(crearBotonRango("7d"))
        rowRangos.addView(crearBotonRango("30d"))
        cardGrafico.addView(rowRangos)

        // El Gráfico Interactivo
        cardGrafico.addView(graficoView)

        val txtTipGrafico = TextView(this).apply {
            text = "👆 Desliza el dedo por el gráfico para ver el hashrate histórico"
            textSize = 10f
            setTextColor(Color.parseColor("#A0AEC0"))
            gravity = Gravity.CENTER
            setPadding(0, 6.dp(), 0, 0)
        }
        cardGrafico.addView(txtTipGrafico)
        root.addView(cardGrafico)

        // 3. Grid de Métricas de Rendimiento del Pool (2x2)
        val gridMetricas = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = 14.dp()
                rightMargin = 14.dp()
                bottomMargin = 14.dp()
            }
        }

        fun crearTarjetaKpi(titulo: String, valor: String, sub: String, colorValor: String): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = fondo("#FFFFFF", 12, "#E2E8F0")
                setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 8.dp()
                }
                addView(TextView(this@MainActivity).apply {
                    text = titulo
                    textSize = 11f
                    setTextColor(Color.parseColor("#848E9C"))
                })
                addView(TextView(this@MainActivity).apply {
                    text = valor
                    textSize = 16f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor(colorValor))
                    setPadding(0, 3.dp(), 0, 1.dp())
                })
                addView(TextView(this@MainActivity).apply {
                    text = sub
                    textSize = 10f
                    setTextColor(Color.parseColor("#64748B"))
                })
            }
        }

        val rowMetricas1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 10.dp()
            }
            addView(crearTarjetaKpi("Shares Aceptados", "48,290", "Eficiencia: 99.85%", "#0AC18E"))
            addView(crearTarjetaKpi("Tasa de Rechazo", "0.15%", "14 shares stale", "#00C4B4"))
        }

        val rowMetricas2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(crearTarjetaKpi("Dificultad Vardiff", "65,536", "Adaptativa automática", "#1E2329"))
            addView(crearTarjetaKpi("Workers Activos", "3 / 3", "100% en línea", "#0AC18E"))
        }

        gridMetricas.addView(rowMetricas1)
        gridMetricas.addView(rowMetricas2)
        root.addView(gridMetricas)

        // 4. Liquidación y Pagos del Pool
        val cardPagos = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fondo("#FFFFFF", 14, "#E2E8F0")
            setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = 14.dp()
                rightMargin = 14.dp()
                bottomMargin = 14.dp()
            }
        }

        val pagosHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 8.dp()
            }
            addView(TextView(this@MainActivity).apply {
                text = "💰 Liquidaciones y Saldo"
                textSize = 13.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#1E2329"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@MainActivity).apply {
                text = "PPS+ Diario"
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#00C4B4"))
            })
        }
        cardPagos.addView(pagosHeader)

        val saldoBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fondo("#F8FAFC", 8, "#E2E8F0")
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 10.dp()
            }
            addView(TextView(this@MainActivity).apply {
                text = "Saldo acumulado sin liquidar:"
                textSize = 10.8f
                setTextColor(Color.parseColor("#64748B"))
            })
            addView(TextView(this@MainActivity).apply {
                text = "0.00142850 BTC  (≈ $86.40 USD)"
                textSize = 14.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#1E2329"))
                setPadding(0, 2.dp(), 0, 0)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Umbral mínimo de retiro: 0.001 BTC · Próximo pago: 00:00 UTC"
                textSize = 10f
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, 2.dp(), 0, 0)
            })
        }
        cardPagos.addView(saldoBox)

        val btnConfigStratum = Button(this).apply {
            text = "⚙️   Configurar Stratum / Cambiar de Pool"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#00C4B4"))
            background = fondo("#E8F9F7", 8, "#B2EBF2")
            setPadding(12.dp(), 8.dp(), 12.dp(), 8.dp())
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("⚙️ Stratum del Pool")
                    .setMessage("URL Primaria:\nstratum+tcp://btc.viabtc.top:3333\n\nRespaldo 1:\nstratum+tcp://btc.viabtc.top:443\n\nRespaldo 2:\nstratum+tcp://btc.viabtc.top:25")
                    .setPositiveButton("Copiar Stratum") { _, _ ->
                        Toast.makeText(this@MainActivity, "Stratum copiado", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cerrar", null)
                    .show()
            }
        }
        cardPagos.addView(btnConfigStratum)
        root.addView(cardPagos)

        scroll.addView(root)
        return scroll
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
            val esBeta = packageName.endsWith(".beta")
            val url = if (esBeta) {
                "https://github.com/elminerogris/el-minero-gris-android/releases/latest/download/ElMineroGris-Beta.apk"
            } else {
                if (apkUpdateUrl.isNotEmpty()) apkUpdateUrl else "https://github.com/elminerogris/el-minero-gris-android/releases/latest/download/ElMineroGris.apk"
            }
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
            val esBeta = packageName.endsWith(".beta")
            val endpoint = if (esBeta) "$urlVersion?channel=beta" else urlVersion
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
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
