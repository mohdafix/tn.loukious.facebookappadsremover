package tn.loukious.facebookappadsremover.core

import android.content.Context
import android.os.Build
import java.lang.reflect.Method

/**
 * Persistent discovery cache — the same trick the original mod used:
 * a DexKit/dexplore scan over Facebook's ~148MB secondary-dex archive takes
 * minutes on every cold start, and during that window NO hooks are installed
 * (ads slip straight through). The mod cached its dex-search results in
 * SharedPreferences and re-scanned only when the app version changed; this
 * does the same, so after the first launch of a given FB build all hooks
 * install within ~1s of the secondary dexes attaching to the classloader.
 */
object MethodCache {

    private const val PREFS = "fbar_discovery_cache"
    private const val KEY_VERSION = "version"
    private const val KEY_TARGETS = "targets" // "key=Class#m:p,p;Class#m:p;\n..." per line
    private const val KEY_CLASS_PREFIX = "class:"

    /** Cached hook methods for the current FB version, or null on miss. */
    fun loadMethods(context: Context, classLoader: ClassLoader): Map<String, List<Method>>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_VERSION, -1) != versionCode(context)) return null
        val all = prefs.getString(KEY_TARGETS, null) ?: return null
        val out = mutableMapOf<String, List<Method>>()
        for (line in all.split('\n')) {
            if (line.isBlank()) continue
            val idx = line.indexOf('=')
            if (idx <= 0) continue
            val methods = mutableListOf<Method>()
            for (spec in line.substring(idx + 1).split(';')) {
                if (spec.isBlank()) continue
                resolveMethod(spec, classLoader)?.let { methods.add(it) }
            }
            if (methods.isNotEmpty()) out[line.substring(0, idx)] = methods
        }
        return if (out.isEmpty()) null else out
    }

    /** Cached auxiliary class name (e.g. the newsfeed filter Runnable). */
    fun loadClass(context: Context, key: String): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_VERSION, -1) != versionCode(context)) return null
        return prefs.getString(KEY_CLASS_PREFIX + key, null)?.takeIf { it.isNotBlank() }
    }

    fun store(context: Context, methods: Map<String, List<Method>>, classes: Map<String, String>) {
        val sb = StringBuilder()
        val jsonCache = org.json.JSONObject()
        for ((key, list) in methods) {
            if (list.isEmpty()) continue
            sb.append(key).append('=')
            val jsonArray = org.json.JSONArray()
            for (m in list) {
                val sig = m.declaringClass.name + "#" + m.name + ":" + m.parameterTypes.joinToString(",") { it.name }
                sb.append(sig).append(';')
                jsonArray.put(sig)
            }
            sb.append('\n')
            jsonCache.put(key, jsonArray)
        }
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_VERSION, versionCode(context))
            .putString(KEY_TARGETS, sb.toString())
        for ((k, v) in classes) {
            editor.putString(KEY_CLASS_PREFIX + k, v)
            jsonCache.put("class:$k", v)
        }
        editor.apply()

        // Broadcast to module UI for the "Scan DexKit" dialog
        runCatching {
            val fbVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "Unknown"
            val intent = android.content.Intent("tn.loukious.facebookappadsremover.CACHE_UPDATED").apply {
                setPackage("tn.loukious.facebookappadsremover")
                putExtra("FB_VERSION", fbVersion)
                putExtra("CACHE_JSON", jsonCache.toString())
            }
            context.sendBroadcast(intent)
        }.onFailure { L.e("FBAR.Cache", "failed to broadcast cache update", it) }
    }

    /** "Class#name:param,param" -> Method via the app classloader. */
    private fun resolveMethod(spec: String, cl: ClassLoader): Method? = runCatching {
        val hash = spec.indexOf('#')
        val colon = spec.indexOf(':', hash)
        if (hash <= 0 || colon < 0) return@runCatching null
        val cls = Class.forName(spec.substring(0, hash), false, cl)
        val name = spec.substring(hash + 1, colon)
        val paramNames = spec.substring(colon + 1).split(',').filter { it.isNotBlank() }
        val params = paramNames.mapNotNull { resolveType(it, cl) }
        if (params.size != paramNames.size) return@runCatching null
        cls.getDeclaredMethod(name, *params.toTypedArray())
    }.getOrNull()

    private fun resolveType(name: String, cl: ClassLoader): Class<*>? = when (name) {
        "void" -> java.lang.Void.TYPE
        "boolean" -> java.lang.Boolean.TYPE
        "byte" -> java.lang.Byte.TYPE
        "short" -> java.lang.Short.TYPE
        "int" -> java.lang.Integer.TYPE
        "long" -> java.lang.Long.TYPE
        "float" -> java.lang.Float.TYPE
        "double" -> java.lang.Double.TYPE
        "char" -> java.lang.Character.TYPE
        else -> runCatching { Class.forName(name, false, cl) }.getOrNull()
    }

    private fun versionCode(context: Context): Int = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt()
        else @Suppress("DEPRECATION") pi.versionCode
    }.getOrDefault(-1)
}
