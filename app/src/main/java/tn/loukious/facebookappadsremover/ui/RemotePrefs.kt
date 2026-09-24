package tn.loukious.facebookappadsremover.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceDataStore
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import tn.loukious.facebookappadsremover.core.Settings
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single binding to the framework's remote-preferences channel, shared by
 * every settings tab.
 *
 * CHANNEL: toggles live in the framework's remote-preferences group
 * "fbar_settings" (Vector/LSPosed daemon), reached from the module app
 * through the libxposed SERVICE library — [XposedServiceHelper] delivers the
 * framework binder, and [XposedService.getRemotePreferences] returns a
 * WRITABLE SharedPreferences whose edits go over binder. The hooks inside the
 * Facebook process read the same group read-only via
 * XposedInterface.getRemotePreferences. Plain SharedPreferences files in the
 * module's own storage are never read by the daemon; every settings widget
 * therefore reads and writes through [dataStore] instead of a local file.
 *
 * Until the service binds, [dataStore] returns the XML defaults and the tab
 * screens render disabled. On bind the stored values are pulled back into the
 * widgets (see RemoteSettingsFragment) and the screens unlock.
 */
object RemotePrefs {

    @Volatile
    var bound: Boolean = false
        private set

    private var prefs: SharedPreferences? = null
    private val listeners = CopyOnWriteArraySet<(Boolean) -> Unit>()
    private val migrationDone = AtomicBoolean(false)

    /**
     * PreferenceDataStore handed to each tab's PreferenceManager. Every
     * get/put delegates straight to the remote prefs, so the tab switches are
     * the same storage the hooks read.
     */
    val dataStore: PreferenceDataStore = object : PreferenceDataStore() {
        override fun getBoolean(key: String, default: Boolean): Boolean =
            prefs?.getBoolean(key, default) ?: default

        override fun putBoolean(key: String, value: Boolean) {
            prefs?.edit()?.putBoolean(key, value)?.apply()
        }

        override fun getString(key: String, default: String?): String? =
            prefs?.getString(key, default) ?: default

        override fun putString(key: String, value: String?) {
            prefs?.edit()?.putString(key, value)?.apply()
        }

        override fun getInt(key: String, default: Int): Int =
            prefs?.getInt(key, default) ?: default

        override fun putInt(key: String, value: Int) {
            prefs?.edit()?.putInt(key, value)?.apply()
        }

        override fun getLong(key: String, default: Long): Long =
            prefs?.getLong(key, default) ?: default

        override fun putLong(key: String, value: Long) {
            prefs?.edit()?.putLong(key, value)?.apply()
        }

        override fun getFloat(key: String, default: Float): Float =
            prefs?.getFloat(key, default) ?: default

        override fun putFloat(key: String, value: Float) {
            prefs?.edit()?.putFloat(key, value)?.apply()
        }

        override fun getStringSet(key: String, defaults: MutableSet<String>?): MutableSet<String>? =
            prefs?.getStringSet(key, defaults) ?: defaults

        override fun putStringSet(key: String, values: MutableSet<String>?) {
            prefs?.edit()?.putStringSet(key, values)?.apply()
        }
    }

    /** Registers the fragment listener; fires immediately with the current state. */
    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
        bind()
        listener(bound)
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    private fun bind() {
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                prefs = runCatching { service.getRemotePreferences(Settings.NAME) }.getOrNull()
                bound = true
                listeners.forEach { it(true) }
            }

            override fun onServiceDied(service: XposedService) {
                prefs = null
                bound = false
                listeners.forEach { it(false) }
            }
        })
    }

    /**
     * One-time carry-over from the plain-SharedPreferences writes of the first
     * settings build (which the framework never saw). Runs once per process but
     * only pushes when the remote storage is still empty.
     */
    fun migrateFromLocalStorage(context: Context) {
        val remote = prefs ?: return
        if (migrationDone.get() && remote.all.isNotEmpty()) return
        if (remote.all.isNotEmpty()) {
            migrationDone.set(true)
            return
        }
        runCatching {
            val legacy = context.getSharedPreferences(Settings.NAME, Context.MODE_PRIVATE)
            val old = legacy.all
            if (old.isEmpty()) return
            remote.edit().apply {
                for ((key, value) in old) {
                    when (value) {
                        is Boolean -> putBoolean(key, value)
                        is String -> putString(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is Set<*> -> @Suppress("UNCHECKED_CAST") (value as? Set<String>)?.let { putStringSet(key, it) }
                    }
                }
            }.apply()
            migrationDone.set(true)
        }
    }
}