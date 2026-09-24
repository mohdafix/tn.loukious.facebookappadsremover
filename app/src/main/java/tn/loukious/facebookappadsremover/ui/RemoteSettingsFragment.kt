package tn.loukious.facebookappadsremover.ui

import android.os.Bundle
import android.view.View
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.SwitchPreferenceCompat

/**
 * Base class for the preference tabs.
 *
 * Two jobs on top of PreferenceFragmentCompat:
 *
 *  1. Every widget reads and writes through [RemotePrefs.dataStore] (the
 *     framework's remote-preferences channel) instead of a local file. Until
 *     [RemotePrefs.bound] the data store only returns the XML defaults and the
 *     screen stays disabled.
 *  2. When the service binds, the stored remote values are pulled back into
 *     the widgets ([resyncFromStore]) and the screen unlocks — so a value set
 *     in a previous bound session is shown before the user touches anything.
 */
abstract class RemoteSettingsFragment : PreferenceFragmentCompat() {

    protected abstract val preferencesRes: Int

    private val bindListener: (Boolean) -> Unit = { bound ->
        activity?.runOnUiThread { onServiceStateChanged(bound) }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = RemotePrefs.dataStore
        setPreferencesFromResource(preferencesRes, rootKey)
    }

    override fun onStart() {
        super.onStart()
        RemotePrefs.addListener(bindListener)
        RemotePrefs.migrateFromLocalStorage(requireContext())
        onServiceStateChanged(RemotePrefs.bound)
    }

    override fun onStop() {
        RemotePrefs.removeListener(bindListener)
        super.onStop()
    }

    protected open fun onServiceStateChanged(bound: Boolean) {
        preferenceScreen?.isEnabled = bound
        if (bound) resyncFromStore()
    }

    /**
     * Re-read every persisted value now that the store is live (before bind the
     * data store only answered with the XML defaults). Setting a preference also
     * persists it back through the same store, which is idempotent here.
     */
    private fun resyncFromStore() {
        val screen = preferenceScreen ?: return
        fun visit(pref: Preference) {
            when (pref) {
                is SwitchPreferenceCompat -> {
                    pref.isChecked = RemotePrefs.dataStore.getBoolean(pref.key, pref.isChecked)
                }
                is EditTextPreference -> {
                    pref.text = RemotePrefs.dataStore.getString(pref.key, pref.text)
                }
            }
            if (pref is PreferenceGroup) {
                for (i in 0 until pref.preferenceCount) visit(pref.getPreference(i))
            }
        }
        visit(screen)
    }

    /** The bottom navigation floats over the list; keep the last rows reachable. */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.setPadding(0, 0, 0, 260)
        listView.clipToPadding = false
    }
}