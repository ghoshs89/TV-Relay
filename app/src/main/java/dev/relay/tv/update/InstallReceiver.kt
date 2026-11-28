package dev.relay.tv.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import dev.relay.tv.data.AppLog

/** Receives PackageInstaller results: shows Android's confirm screen or reports failure. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val expected = Updater.activeSessionId
        if (expected != -1 && sessionId != -1 && sessionId != expected) {
            AppLog.w("Update", "Ignoring installer callback for old session $sessionId (active $expected)")
            return
        }
        val info = Updater.pending
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        AppLog.i("Update", "Installer status $status: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirm)
                    } catch (e: Exception) {
                        Updater.activeSessionId = -1
                        Updater.state = UpdateState.Failed("Couldn't open the install screen: ${e.message}", info)
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Updater.activeSessionId = -1
                // Android restarts Relay's process as part of the update; nothing else to do.
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Updater.activeSessionId = -1
                Updater.state = UpdateState.Failed("Install cancelled.", info)
            }
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                Updater.activeSessionId = -1
                Updater.state = UpdateState.Failed(
                    "Android refused the update: it isn't signed with the same key as the installed app.",
                    info,
                )
            }
            else -> {
                Updater.activeSessionId = -1
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                Updater.state = UpdateState.Failed("Install failed: $msg", info)
            }
        }
    }
}
