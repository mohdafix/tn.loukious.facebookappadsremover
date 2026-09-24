package tn.loukious.facebookappadsremover.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceFragmentCompat
import tn.loukious.facebookappadsremover.R
import tn.loukious.facebookappadsremover.databinding.HeroStatusCardBinding

class HomeFragment : PreferenceFragmentCompat() {

    private var heroBinding: HeroStatusCardBinding? = null
    private val handler = Handler(Looper.getMainLooper())
    private var fbResponseReceived = false

    private val bindListener: (Boolean) -> Unit = { _ ->
        activity?.runOnUiThread { updateModuleStatus() }
    }

    private val fbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "${context?.packageName}.RECEIVER_FB") {
                fbResponseReceived = true
                val version = intent.getStringExtra("VERSION") ?: "Unknown"
                heroBinding?.let { binding ->
                    binding.fbStatusTitle.text = getString(R.string.fb_status_active, version)
                    binding.fbStatusIcon.setImageResource(R.drawable.ic_check_circle)
                    binding.fbStatusCardBg.setBackgroundResource(R.drawable.hero_glow_fb_active)
                }
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_tab_home, rootKey)
        findPreference<androidx.preference.Preference>("view_supported_versions")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), SupportedVersionsActivity::class.java))
            true
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val prefView = super.onCreateView(inflater, container, savedInstanceState)
        val wrapper = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val cardBinding = HeroStatusCardBinding.inflate(inflater, wrapper, true)
        heroBinding = cardBinding
        wrapper.addView(
            prefView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        return wrapper
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.setPadding(0, 0, 0, 260)
        listView.clipToPadding = false
        setupStatusCard()
    }


    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("${requireContext().packageName}.RECEIVER_FB")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(fbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            requireContext().registerReceiver(fbReceiver, filter)
        }
        RemotePrefs.addListener(bindListener)
        RemotePrefs.migrateFromLocalStorage(requireContext())
        updateModuleStatus()
        checkFacebookStatus()
    }

    override fun onPause() {
        super.onPause()
        RemotePrefs.removeListener(bindListener)
        try {
            requireContext().unregisterReceiver(fbReceiver)
        } catch (e: Exception) {
            // Ignore if not registered
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        heroBinding = null
    }

    private fun setupStatusCard() {
        val binding = heroBinding ?: return
        updateModuleStatus()

        binding.btnRestartFb.setOnClickListener {
            val targetPkg = targetFacebookPackage ?: "com.facebook.katana"
            val intent = Intent("${requireContext().packageName}.FACEBOOK.RESTART").apply {
                setPackage(targetPkg)
            }
            requireContext().sendBroadcast(intent)
            Toast.makeText(requireContext(), "Rebooting Facebook...", Toast.LENGTH_SHORT).show()

            handler.postDelayed({
                try {
                    val launchIntent = requireContext().packageManager.getLaunchIntentForPackage(targetPkg)
                    if (launchIntent != null) {
                        launchIntent.addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        )
                        startActivity(launchIntent)
                    }
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Failed to relaunch: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                checkFacebookStatus()
            }, 1000)
        }

        binding.btnViewSupportedVersions.setOnClickListener {
            startActivity(Intent(requireContext(), SupportedVersionsActivity::class.java))
        }
    }

    /**
     * Module-status card. The framework service binding (RemotePrefs.bound) is
     * the authoritative "loaded by the framework" signal — the old
     * isXposedEnabled() self-hook is unreliable, since it only fires when the
     * framework injects this module's own process.
     */
    private fun updateModuleStatus() {
        val binding = heroBinding ?: return
        if (RemotePrefs.bound) {
            binding.statusCardBg.setBackgroundResource(R.drawable.hero_glow_enabled)
            binding.statusIcon.setImageResource(R.drawable.ic_check_circle)
            binding.statusTitle.setText(R.string.module_enabled)
            binding.statusSummary.setText(R.string.module_status_summary_enabled)
        } else {
            binding.statusCardBg.setBackgroundResource(R.drawable.hero_glow_disabled)
            binding.statusIcon.setImageResource(R.drawable.ic_error_outline)
            binding.statusTitle.setText(R.string.module_disabled)
            binding.statusSummary.setText(R.string.module_status_summary_disabled)
        }
    }

    private var targetFacebookPackage: String? = null

    private fun checkFacebookStatus() {
        val binding = heroBinding ?: return
        fbResponseReceived = false

        var pkgInfo: android.content.pm.PackageInfo? = null
        var pkgName = "com.facebook.katana"
        try {
            pkgInfo = requireContext().packageManager.getPackageInfo("com.facebook.katana", 0)
            pkgName = "com.facebook.katana"
        } catch (e: PackageManager.NameNotFoundException) {
            try {
                pkgInfo = requireContext().packageManager.getPackageInfo("com.facebook.lite", 0)
                pkgName = "com.facebook.lite"
            } catch (e2: PackageManager.NameNotFoundException) {
                // Not installed
            }
        }

        if (pkgInfo == null) {
            binding.fbStatusTitle.setText(R.string.fb_status_not_installed)
            binding.fbStatusSummary.text = ""
            binding.btnRestartFb.isEnabled = false
            targetFacebookPackage = null
            return
        }

        targetFacebookPackage = pkgName
        binding.fbStatusTitle.setText(R.string.fb_status_checking)
        binding.btnRestartFb.isEnabled = true

        // Send ping broadcast to check if Xposed hook in Facebook responds
        val checkIntent = Intent("${requireContext().packageName}.CHECK_FB").apply {
            setPackage(pkgName)
        }
        requireContext().sendBroadcast(checkIntent)

        // If no response after 1 second, Facebook might be closed or hook is inactive
        val finalPkgInfo = pkgInfo
        handler.postDelayed({
            if (!fbResponseReceived && heroBinding != null) {
                heroBinding?.fbStatusTitle?.setText(R.string.fb_status_inactive)
                heroBinding?.fbStatusSummary?.text = "Installed v${finalPkgInfo.versionName} (Not currently running or hooks inactive)"
            }
        }, 1000)
    }
}
