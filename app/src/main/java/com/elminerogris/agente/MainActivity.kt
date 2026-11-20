package com.elminerogris.agente

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
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
    private val urlLogin = "https://antminer-panel.onrender.com/api/agente/login"
    private val urlVersion = "https://antminer-panel.onrender.com/api/agente/apk_version"
    private var apkUpdateUrl = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("emg_prefs", Context.MODE_PRIVATE)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(60, 60, 60, 60)
            setBackgroundColor(Color.parseColor("#0F111A"))
        }

        val titulo = TextView(this).apply {
            text = "⛏️ El Minero Gris\nAgente Android 24/7"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }

        val inpUser = EditText(this).apply {
            hint = "Usuario"
            setText(prefs.getString("username", ""))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        val inpPass = EditText(this).apply {
            hint = "Contraseña"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        val txtEstado = TextView(this).apply {
            text = if ((prefs.getString("api_key", "") ?: "").isNotEmpty())
                "✅ Sesión guardada (${prefs.getString("username", "")})"
            else "Inicia sesión para vincular esta granja"
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }

        val btnIniciar = Button(this).apply {
            text = "▶ Iniciar Agente 24/7"
            setBackgroundColor(Color.parseColor("#3366FF"))
            setTextColor(Color.WHITE)
        }

        val btnDetener = Button(this).apply {
            text = "⏹ Detener Agente"
            setBackgroundColor(Color.parseColor("#33384D"))
            setTextColor(Color.WHITE)
        }

        val btnActualizar = Button(this).apply {
            text = "⬆️ Nueva versión disponible — Actualizar"
            setBackgroundColor(Color.parseColor("#00C853"))
            setTextColor(Color.WHITE)
            visibility = View.GONE
        }

        btnIniciar.setOnClickListener {
            val u = inpUser.text.toString().trim()
            val p = inpPass.text.toString()
            if (u.isEmpty() || p.isEmpty()) {
                txtEstado.text = "⚠️ Completa usuario y contraseña"
                return@setOnClickListener
            }
            txtEstado.text = "⏳ Conectando con El Minero Gris..."
            scope.launch {
                val res = withContext(Dispatchers.IO) { hacerLogin(u, p) }
                if (res != null && res.optBoolean("ok", false)) {
                    prefs.edit()
                        .putString("username", res.optString("username"))
                        .putString("api_key", res.optString("api_key"))
                        .putString("subnet", res.optString("subnet", "192.168.0"))
                        .apply()
                    iniciarServicio()
                    txtEstado.text = "🟢 Agente activo 24/7 (${res.optString("subnet")}.x)"
                } else {
                    txtEstado.text = "❌ ${res?.optString("msg") ?: "Error de conexión"}"
                }
            }
        }

        btnDetener.setOnClickListener {
            stopService(Intent(this, AgentService::class.java))
            txtEstado.text = "⏸ Agente detenido"
        }

        btnActualizar.setOnClickListener {
            if (apkUpdateUrl.isEmpty()) return@setOnClickListener
            btnActualizar.isEnabled = false
            txtEstado.text = "⬇️ Descargando actualización..."
            scope.launch {
                val archivoApk = withContext(Dispatchers.IO) { descargarApk(apkUpdateUrl) }
                if (archivoApk != null) {
                    txtEstado.text = "✅ Descarga lista. Abriendo instalador..."
                    instalarApk(archivoApk)
                } else {
                    txtEstado.text = "❌ Error descargando actualización"
                    btnActualizar.isEnabled = true
                }
            }
        }

        layout.addView(titulo)
        layout.addView(inpUser)
        layout.addView(inpPass)
        layout.addView(txtEstado)
        layout.addView(btnIniciar)
        layout.addView(btnDetener)
        layout.addView(btnActualizar)
        setContentView(layout)

        verificarActualizacion(btnActualizar)
    }

    private fun verificarActualizacion(btnActualizar: Button) {
        scope.launch {
            val info = withContext(Dispatchers.IO) { obtenerVersionNube() } ?: return@launch
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
                btnActualizar.text = "⬆️ Actualizar a v$nubeName"
                btnActualizar.visibility = View.VISIBLE
            }
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
