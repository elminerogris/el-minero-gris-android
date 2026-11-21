package com.elminerogris.agente

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val accionesValidas = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON"
        )
        if (action !in accionesValidas) return

        val prefs = context.getSharedPreferences("emg_prefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        if (apiKey.isEmpty()) return

        try {
            val serviceIntent = Intent(context, AgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (_: Exception) {}
    }
}
