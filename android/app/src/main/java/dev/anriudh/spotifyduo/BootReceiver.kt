package dev.anriudh.spotifyduo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Alarms do not survive a reboot, so the chain has to be restarted. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) RefreshScheduler.ensureScheduled(ctx)
    }
}
