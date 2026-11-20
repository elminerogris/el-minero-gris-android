package com.elminerogris.agente

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val urlLogin = "https://antminer-panel.onrender.com/api/agente/login"

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

        layout.addView(titulo)
        layout.addView(inpUser)
        layout.addView(inpPass)
        layout.addView(txtEstado)
        layout.addView(btnIniciar)
        layout.addView(btnDetener)
        setContentView(layout)
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
