package dev.relay.tv.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat

/** Receives PackageInstaller results: shows Android's confirm screen or reports failure. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val info = Updater.pending
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirm)
                    } catch (e: Exception) {
                        Updater.state = UpdateState.Failed("Couldn't open the install screen: ${e.message}", info)
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Android restarts Relay's process as part of the update; nothing else to do.
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Updater.state = UpdateState.Failed("Install cancelled.", info)
            }
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                Updater.state = UpdateState.Failed(
                    "Android refused the update: it isn't signed with the same key as the installed app.",
                    info,
                )
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                Updater.state = UpdateState.Failed("Install failed: $msg", info)
            }
        }
    }
}
