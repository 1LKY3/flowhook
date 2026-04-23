package com.dustforge.flowhook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // v0.3.7: also handle our own APK replacement so post-install
        // dormancy stops being a thing. `pm install -r` kills the running
        // process; without this receiver Android leaves the app installed
        // but stopped, and the only way back into FlowhookService was a
        // manual tap or an external `am start`. Now the app resurrects
        // itself whenever its package is replaced — no external poke,
        // no recovery-script band-aid.
        val action = intent.action
        val isBoot = action == Intent.ACTION_BOOT_COMPLETED ||
                     action == "android.intent.action.QUICKBOOT_POWERON"
        val isSelfReplaced = action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!isBoot && !isSelfReplaced) return

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
