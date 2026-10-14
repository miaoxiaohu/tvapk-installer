package com.miaoxiaohu.tvinstall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** TVs rarely get their launcher app reopened by hand, so bring the service back after a reboot. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> Unit
            else -> return
        }
        if (!AppConfig(context).autoStart) return
        try {
            context.startForegroundService(
                Intent(context, ServerService::class.java).setAction(ServerService.ACTION_START)
            )
        } catch (ignored: Exception) {
            // Some firmwares block background starts from a boot receiver; the user can
            // still open the app manually, so this is not worth surfacing.
        }
    }
}