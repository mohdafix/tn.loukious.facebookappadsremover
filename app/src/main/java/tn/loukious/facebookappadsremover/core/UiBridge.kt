package tn.loukious.facebookappadsremover.core

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Looper
import android.os.Process

/**
 * Broadcast bridge between the module app's Home tab and the hooked Facebook
 * process.
 *
 * The module app cannot query the Facebook process directly, so the two talk
 * over targeted broadcasts (same scheme as core.SessionBackup):
 *
 *  - CHECK_FB  → the FB process answers RECEIVER_FB with its versionName, so
 *                the Home tab's "Facebook Active & Hooked" card goes green.
 *  - RESTART   → the FB process force-stops itself (killProcess). The module
 *                app then relaunches the launcher activity, which cold-starts
 *                a fresh hooked process that re-reads the (just changed)
 *                remote preferences.
 *
 * Runs inside com.facebook.katana; [ModuleMain.onAppCreated] calls [install].
 */
object UiBridge {

    private const val TAG = "FBAR.UiBridge"

    /** Must match the module's package name — the Home tab builds actions with it. */
    private const val MODULE_PACKAGE = "tn.loukious.facebookappadsremover"

    const val ACTION_CHECK_FB = "$MODULE_PACKAGE.CHECK_FB"
    const val ACTION_ANSWER_FB = "$MODULE_PACKAGE.RECEIVER_FB"
    const val ACTION_RESTART_FB = "$MODULE_PACKAGE.FACEBOOK.RESTART"
    const val EXTRA_FB_VERSION = "VERSION"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val app = context.applicationContext
            when (intent.action) {
                ACTION_CHECK_FB -> answerStatus(app)
                ACTION_RESTART_FB -> restartApp(app)
            }
        }
    }

    /** Registers the actions — call from onAppCreated (FB process). */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun install(context: Context) {
        runCatching {
            val filter = IntentFilter(ACTION_CHECK_FB).apply { addAction(ACTION_RESTART_FB) }
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            L.i(TAG, "bridge receiver registered ($MODULE_PACKAGE)")
        }.onFailure { L.e(TAG, "bridge receiver registration failed", it) }
    }

    private fun answerStatus(context: Context) {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "Unknown"
        val reply = Intent(ACTION_ANSWER_FB)
            .setPackage(MODULE_PACKAGE)
            .putExtra(EXTRA_FB_VERSION, version)
        runCatching { context.sendBroadcast(reply) }
            .onFailure { L.e(TAG, "status answer failed", it) }
    }

    /**
     * Restart — port of the pre-rewrite module's FACEBOOK.RESTART handler:
     * only Facebook's main process acts, and it fully owns the relaunch
     * (no module-side package visibility needed — getLaunchIntentForPackage
     * resolves against FB's own package here):
     *
     *   1. finish+remove every task,
     *   2. start the launcher activity again via makeRestartActivityTask,
     *   3. die ~3s later once the new task is up.
     *
     * The cold start then re-reads the remote preferences, which is the whole
     * point of "Restart Facebook to apply". The module app additionally
     * relaunches FB after 1s as a safety net (now that <queries> makes its
     * launcher intent resolvable); the duplicate launch just fronts an
     * already-foreground task.
     */
    private fun restartApp(context: Context) {
        if (context.packageName != Application.getProcessName()) return
        L.i(TAG, "restart requested — tearing down tasks, relaunching")
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.appTasks?.forEach { task ->
                runCatching { task.finishAndRemoveTask() }
            }
        }
        runCatching {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (launch?.component != null) {
                context.startActivity(
                    Intent.makeRestartActivityTask(launch.component).setPackage(context.packageName)
                )
            }
        }
        android.os.Handler(Looper.getMainLooper()).postDelayed({
            L.i(TAG, "terminating FB process ${Process.myPid()}")
            Process.killProcess(Process.myPid())
            Runtime.getRuntime().exit(0)
        }, 3000)
    }
}