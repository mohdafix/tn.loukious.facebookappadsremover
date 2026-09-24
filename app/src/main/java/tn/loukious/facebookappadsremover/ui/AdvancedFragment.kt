package tn.loukious.facebookappadsremover.ui

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import org.json.JSONObject
import tn.loukious.facebookappadsremover.R
import tn.loukious.facebookappadsremover.core.SessionBackup

/**
 * Advanced tab — appearance/privacy/navigation/links switches plus the
 * non-toggle controls: session export/import (core.SessionBackup broadcasts
 * into the Facebook process) and the launcher-icon toggle (ui.LauncherIcon,
 * which lives in PackageManager, not the remote preferences).
 */
class AdvancedFragment : RemoteSettingsFragment() {

    override val preferencesRes: Int = R.xml.preferences_tab_advanced

    /**
     * System file browser for importing a specific session export. The FB
     * process cannot open another app's content URIs without a grant, so the
     * picked JSON is read here (~15 KB) and shipped inside the broadcast.
     */
    private val pickSessionFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> onSessionFilePicked(uri) }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

        findPreference<Preference>("view_dynamic_hooks")?.setOnPreferenceClickListener {
            showDynamicHooksDialog()
            true
        }

        findPreference<Preference>("account_export_session")?.setOnPreferenceClickListener {
            sendSession(SessionBackup.ACTION_EXPORT)
            true
        }

        findPreference<Preference>("account_import_latest")?.setOnPreferenceClickListener {
            sendSession(SessionBackup.ACTION_IMPORT)
            true
        }

        findPreference<Preference>("account_import_file")?.setOnPreferenceClickListener {
            pickSessionFile.launch(arrayOf("application/json", "text/*", "*/*"))
            true
        }

        findPreference<Preference>("launcher_icon")?.setOnPreferenceClickListener {
            toggleLauncherIcon()
            true
        }
    }

    override fun onStart() {
        super.onStart()
        refreshLauncherSummary()
    }

    override fun onServiceStateChanged(bound: Boolean) {
        super.onServiceStateChanged(bound)
        // The launcher icon and the session actions don't need the framework
        // service (PackageManager + broadcasts), so keep them usable even
        // while unbound — that matches the upstream screen's behaviour.
        if (!bound) {
            for (key in listOf(
                "account_export_session",
                "account_import_latest",
                "account_import_file",
                "launcher_icon",
            )) {
                findPreference<Preference>(key)?.isEnabled = true
            }
        }
        refreshLauncherSummary()
    }

    private fun refreshLauncherSummary() {
        findPreference<Preference>("launcher_icon")?.summary =
            if (LauncherIcon.isVisible(requireContext())) {
                getString(R.string.pref_launcher_icon_visible_summary)
            } else {
                getString(R.string.pref_launcher_icon_hidden_summary)
            }
    }

    /**
     * Launcher icon toggle (ui.LauncherIcon). Its value lives in
     * PackageManager's component state rather than the framework's remote
     * preferences, so it needs no service and works even while unbound.
     * Hiding is confirmed first, because the icon is the app's only entry
     * point — there is no second screen to fall back to.
     */
    private fun toggleLauncherIcon() {
        val ctx = requireContext()
        if (LauncherIcon.isVisible(ctx)) {
            AlertDialog.Builder(ctx)
                .setTitle("Hide the launcher icon?")
                .setMessage(
                    "The icon disappears from your app drawer. Facebook keeps working, and so do its hooks.\n\n" +
                        "To open this screen again:\n" +
                        "•  Xposed/Vector → Modules → Facebook App Ads Remover → open settings\n" +
                        "•  adb shell am start -n tn.loukious.facebookappadsremover/.ui.MainActivity"
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Hide") { _, _ ->
                    LauncherIcon.setVisible(ctx, false)
                    refreshLauncherSummary()
                }
                .show()
        } else {
            LauncherIcon.setVisible(ctx, true)
            refreshLauncherSummary()
        }
    }

    private fun sendSession(action: String) {
        val intent = android.content.Intent(action).setPackage("com.facebook.katana")
        val sent = runCatching { requireContext().sendBroadcast(intent) }.isSuccess
        Toast.makeText(
            requireContext(),
            if (sent) "Asking Facebook…" else "Broadcast failed — is Facebook installed?",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun onSessionFilePicked(uri: android.net.Uri?) {
        if (uri == null) return // picker cancelled
        val text = runCatching {
            requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            Toast.makeText(requireContext(), "Couldn't read the picked file", Toast.LENGTH_LONG).show()
            return
        }
        // Quick client-side sanity check so obvious wrong files fail here
        // rather than as a silent FB-side toast.
        if (!text.trimStart().startsWith("{") || !text.contains("\"files\"")) {
            Toast.makeText(requireContext(), "That doesn't look like a session export", Toast.LENGTH_LONG).show()
            return
        }
        val intent = android.content.Intent(SessionBackup.ACTION_IMPORT_DATA)
            .setPackage("com.facebook.katana")
            .putExtra(SessionBackup.EXTRA_DATA, text)
        runCatching { requireContext().sendBroadcast(intent) }
            .onSuccess { Toast.makeText(requireContext(), "Asking Facebook…", Toast.LENGTH_SHORT).show() }
            .onFailure {
                Toast.makeText(requireContext(), "Broadcast failed — is Facebook installed?", Toast.LENGTH_SHORT).show()
            }
    }

    private fun getColoredJson(json: String): CharSequence {
        val builder = SpannableStringBuilder(json)

        val keyPattern = java.util.regex.Pattern.compile("\"([^\"]+)\"\\s*:")
        val keyMatcher = keyPattern.matcher(json)
        val keyColor = Color.parseColor("#42A5F5")
        while (keyMatcher.find()) {
            builder.setSpan(ForegroundColorSpan(keyColor), keyMatcher.start(1) - 1, keyMatcher.end(1) + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val stringPattern = java.util.regex.Pattern.compile(":\\s*\"([^\"]*)\"")
        val stringMatcher = stringPattern.matcher(json)
        val stringColor = Color.parseColor("#66BB6A")
        while (stringMatcher.find()) {
            builder.setSpan(ForegroundColorSpan(stringColor), stringMatcher.start(1) - 1, stringMatcher.end(1) + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val nullPattern = java.util.regex.Pattern.compile(":\\s*(null)")
        val nullMatcher = nullPattern.matcher(json)
        val nullColor = Color.parseColor("#EF5350")
        while (nullMatcher.find()) {
            builder.setSpan(ForegroundColorSpan(nullColor), nullMatcher.start(1), nullMatcher.end(1), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        return builder
    }

    private fun showDynamicHooksDialog() {
        val prefs = requireContext().getSharedPreferences("tn.loukious.facebookappadsremover_preferences", Context.MODE_PRIVATE)
        val versionsSet = prefs.getStringSet("cached_fb_versions", null)

        // Backwards compatibility for previous single-version caching
        val singleVersion = prefs.getString("cached_fb_version", null)

        val allVersions = mutableSetOf<String>()
        if (versionsSet != null) allVersions.addAll(versionsSet)
        if (singleVersion != null) allVersions.add(singleVersion)

        if (allVersions.isEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle("Captured Hooks")
                .setMessage("No dynamic hooks captured yet. Make sure to open Facebook to trigger a DexKit scan.")
                .setPositiveButton("Close", null)
                .show()
            return
        }

        val versionsArray = allVersions.sortedDescending().toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Select Facebook Version")
            .setItems(versionsArray) { _, which ->
                showJsonForVersion(versionsArray[which])
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showJsonForVersion(fbVersion: String) {
        val prefs = requireContext().getSharedPreferences("tn.loukious.facebookappadsremover_preferences", Context.MODE_PRIVATE)
        // Try to get specific version json, fallback to old single-version json if missing
        var jsonString = prefs.getString("cached_dexkit_json_$fbVersion", null)
        if (jsonString == null && fbVersion == prefs.getString("cached_fb_version", null)) {
            jsonString = prefs.getString("cached_dexkit_json", null)
        }

        val message = if (jsonString != null) {
            try {
                JSONObject(jsonString).toString(4)
            } catch (e: Exception) {
                "Invalid JSON Data"
            }
        } else {
            "Data not found for this version."
        }

        val textView = android.widget.TextView(requireContext()).apply {
            text = if (jsonString != null) getColoredJson(message) else message
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(48, 48, 48, 48)
            setTextIsSelectable(true)
            textSize = 13f
        }

        val hScroll = android.widget.HorizontalScrollView(requireContext()).apply {
            addView(textView)
        }

        val vScroll = android.widget.ScrollView(requireContext()).apply {
            addView(hScroll)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Captured Hooks (FB v$fbVersion)")
            .setView(vScroll)
            .setPositiveButton("Close", null)
            .show()
    }
}