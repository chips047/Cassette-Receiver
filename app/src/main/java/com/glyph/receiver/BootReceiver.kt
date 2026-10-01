package com.glyph.receiver

import android.os.Build
import android.content.Intent
import android.content.Context
import android.content.BroadcastReceiver

class BootReceiver : BroadcastReceiver() {

    // Receiver Section
    override fun onReceive(
        context: Context,
        intent:  Intent?
    ): Unit {
        val action: String? = intent?.action

        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") {
            return
        }

        val service_intent: Intent = Intent(context, MainService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(service_intent)

            return
        }

        context.startService(service_intent)
    }
}