package tn.loukious.facebookappadsremover.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class DexKitCacheReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "tn.loukious.facebookappadsremover.CACHE_UPDATED") {
            val fbVersion = intent.getStringExtra("FB_VERSION")
            val cacheJson = intent.getStringExtra("CACHE_JSON")
            if (fbVersion != null && cacheJson != null) {
                val prefs = context.getSharedPreferences("tn.loukious.facebookappadsremover_preferences", Context.MODE_PRIVATE)
                val versions = prefs.getStringSet("cached_fb_versions", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                versions.add(fbVersion)
                
                prefs.edit()
                    .putStringSet("cached_fb_versions", versions)
                    .putString("cached_dexkit_json_$fbVersion", cacheJson)
                    .apply()
                Log.i("FacebookAppAdsRemover", "Received and stored DexKit cache for Facebook v$fbVersion")
            }
        }
    }
}
