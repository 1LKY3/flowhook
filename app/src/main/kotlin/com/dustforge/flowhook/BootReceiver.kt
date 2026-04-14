package com.dustforge.flowhook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            val left = Config.getServicesEnabled(ctx)
            val right = Config.getAdbBridgeEnabled(ctx)
            val mode = when {
                left -> FlowhookService.Mode.FULL
                right -> FlowhookService.Mode.IDLE
                else -> return   // both off, don't start anything
            }
            ctx.startForegroundService(FlowhookService.intentFor(ctx, mode))
        }
    }
}
