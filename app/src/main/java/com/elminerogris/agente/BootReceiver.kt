package com.elminerogris.agente

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            try {
                val prefs = context.getSharedPreferences("emg_prefs", Context.MODE_PRIVATE)
                val apiKey = prefs.getString("api_key", "")?.trim() ?: ""
                val debeCorrer = prefs.getBoolean("service_running", false)
                if (apiKey.isNotEmpty() && debeCorrer) {
                    val svc = Intent(context, AgentService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(svc)
                    } else {
                        context.startService(svc)
                    }
                }
            } catch (_: Exception) {}
        }
    }
}
