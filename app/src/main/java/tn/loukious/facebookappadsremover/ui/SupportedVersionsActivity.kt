package tn.loukious.facebookappadsremover.ui

import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import tn.loukious.facebookappadsremover.R

class SupportedVersionsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_supported_versions)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val fbContainer = findViewById<LinearLayout>(R.id.fb_versions_container)
        val adScopesContainer = findViewById<LinearLayout>(R.id.ad_scopes_container)
        val totalText = findViewById<TextView>(R.id.total_versions_text)
        val installedVersionText = findViewById<TextView>(R.id.installed_version_text)
        val installedStatusText = findViewById<TextView>(R.id.installed_status_text)

        checkInstalledFacebookVersion(installedVersionText, installedStatusText)

        val fbVersions = resources.getStringArray(R.array.supported_versions_facebook).sortedArrayDescending()
        populateVersions(fbContainer, fbVersions)

        val adScopes = resources.getStringArray(R.array.supported_ad_scopes)
        populateVersions(adScopesContainer, adScopes)

        val total = fbVersions.size
        totalText.text = "$total Katana Versions Supported"
    }

    private fun checkInstalledFacebookVersion(versionText: TextView, statusText: TextView) {
        try {
            val pkgInfo = packageManager.getPackageInfo("com.facebook.katana", 0)
            versionText.text = "Installed: Facebook v${pkgInfo.versionName}\n(com.facebook.katana)"
            statusText.text = "Active Target / DexKit Compatible"
            statusText.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1A10B981"))
            statusText.setTextColor(Color.parseColor("#10B981"))
            return
        } catch (e: PackageManager.NameNotFoundException) {
            // Check lite
        }
        try {
            val pkgInfo = packageManager.getPackageInfo("com.facebook.lite", 0)
            versionText.text = "Installed: Facebook Lite v${pkgInfo.versionName}\n(com.facebook.lite)"
            statusText.text = "Active Target / DexKit Compatible"
            statusText.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1A10B981"))
            statusText.setTextColor(Color.parseColor("#10B981"))
            return
        } catch (e: PackageManager.NameNotFoundException) {
            versionText.text = "Facebook App Not Installed"
            statusText.text = "Please install Facebook Katana or Lite"
            statusText.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1AEF4444"))
            statusText.setTextColor(Color.parseColor("#EF4444"))
        }
    }

    private fun populateVersions(container: LinearLayout, items: Array<String>) {
        val inflater = LayoutInflater.from(this)
        for (i in items.indices) {
            val row = inflater.inflate(R.layout.item_supported_version, container, false)
            val nameView = row.findViewById<TextView>(R.id.version_name)
            val divider = row.findViewById<View>(R.id.version_divider)

            nameView.text = items[i]

            if (i == items.size - 1 && divider != null) {
                divider.visibility = View.GONE
            }

            container.addView(row)
        }
    }
}
