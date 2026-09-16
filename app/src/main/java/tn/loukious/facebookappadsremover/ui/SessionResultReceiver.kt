package tn.loukious.facebookappadsremover.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import tn.loukious.facebookappadsremover.core.SessionBackup

/**
 * Shows the Facebook process's session export/import result in the module
 * app. The work happens inside FB (core.SessionBackup), but FB is
 * backgrounded while the user presses the buttons, and Android 11+
 * suppresses background toasts — so FB sends ACTION_RESULT here and this
 * foreground app does the talking.
 *
 * Referencing SessionBackup's constants is safe here: they're compile-time
 * consts, so no SessionBackup initialization runs in this process.
 */
class SessionResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SessionBackup.ACTION_RESULT) return
        val ok = intent.getBooleanExtra(SessionBackup.EXTRA_OK, false)
        val message = intent.getStringExtra(SessionBackup.EXTRA_MESSAGE) ?: return
        runCatching {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
        if (!ok) {
            android.util.Log.e("FBAR.Session", "reported failure: $message")
        }
    }
}
