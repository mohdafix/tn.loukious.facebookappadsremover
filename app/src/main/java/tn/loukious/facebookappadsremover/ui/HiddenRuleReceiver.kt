package tn.loukious.facebookappadsremover.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

class HiddenRuleReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SAVE_RULE = "tn.loukious.facebookappadsremover.SAVE_HIDDEN_RULE"
        const val KEY_RULES_JSON = "hidden_ui_rules_json"
        const val TAG = "HiddenRuleReceiver"

        fun getSavedRules(context: Context): List<JSONObject> {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val jsonStr = prefs.getString(KEY_RULES_JSON, "[]") ?: "[]"
            val list = mutableListOf<JSONObject>()
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    list.add(array.getJSONObject(i))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse saved rules", e)
            }
            return list
        }

        fun saveRules(context: Context, rules: List<JSONObject>) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val array = JSONArray()
            rules.forEach { array.put(it) }
            prefs.edit().putString(KEY_RULES_JSON, array.toString()).commit()
            
            try {
                val prefFile = java.io.File(context.applicationInfo.dataDir, "shared_prefs/${context.packageName}_preferences.xml")
                if (prefFile.exists()) {
                    prefFile.setReadable(true, false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to make prefs world-readable", e)
            }
            
            Log.i(TAG, "Saved ${rules.size} rules to SharedPreferences")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SAVE_RULE) return
        val ruleJsonStr = intent.getStringExtra("rule_json") ?: return
        try {
            val newRule = JSONObject(ruleJsonStr)
            val existing = getSavedRules(context).toMutableList()
            // Avoid duplicates by ruleId or selector
            val ruleId = newRule.optString("ruleId")
            val selector = newRule.optString("selector")
            existing.removeAll { 
                (ruleId.isNotEmpty() && it.optString("ruleId") == ruleId) ||
                (selector.isNotEmpty() && it.optString("selector") == selector)
            }
            existing.add(0, newRule)
            saveRules(context, existing)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save rule from broadcast", e)
        }
    }
}
