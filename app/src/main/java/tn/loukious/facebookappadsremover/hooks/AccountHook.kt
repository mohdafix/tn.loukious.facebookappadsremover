package tn.loukious.facebookappadsremover.hooks

import android.content.Context
import android.content.SharedPreferences
import tn.loukious.facebookappadsremover.core.L
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import org.json.JSONArray
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * M3 account tools — port of the original mod's ViewerContext installer
 * (GhTaOHdq4kOQbdazOJvf.qsJGnbanr07zUGNprRk, libnc.so.c 123432–123752, with
 * the capture callback at 108048–109500).
 *
 * The mod's session-read mechanism (fully reversed):
 *
 *     class = XposedHelpers.findClass("com.facebook.auth.viewercontext.ViewerContext", cl)
 *     for (m : class.getDeclaredMethods())
 *         if (!abstract && !static && m.name.equals("equals"))   // DAT_00fd9f18 = "equals"
 *             XposedBridge.hookMethod(m, GhTa$bsrncODWYJ5TNENFG95a)
 *
 * The callback observes every ViewerContext.equals() call (FB invokes it
 * constantly while building requests) and captures four fields off the
 * instance via XposedHelpers.getObjectField, storing each into encrypted
 * prefs through P9vfxZIhzRWfAHHtpfTQ.JdPl0A614BiPGbCvPPk:
 *
 *   mSessionCookiesString → FBFULLCOOKIE   (the JSON cookie array, verbatim;
 *                                           \n/\r/\t sanitized to spaces)
 *   new JSONArray(...)    → FBHTTPCOOKIE   via S7VA.XS11PXyJIvbyHBPk0TZ:
 *                                           for each cookie object,
 *                                           name + "=" + value, joined by "; "
 *   mAuthToken            → FBTOKEN
 *   mUsername             → FBUSERNAME     (pref only — no in-memory static)
 *   mUserId               → FBUSERID
 *
 * The mod re-captures on every call until the four in-memory statics
 * (xZw29/T7Puc/UuCRUj/s9sEFa = cookieJson/httpCookie/token/userId) are all
 * non-empty, then unhooks the method with one final refresh.
 *
 * Nothing here needs DexKit: ViewerContext is a stable com.facebook.* class
 * in the secondary dex (verified present with identical field names in stock
 * 576.0.0.42.73), so the hook installs as soon as the secondary dexes attach
 * to the app classloader.
 */
object AccountHook {

    private const val TAG = "FBAR.Account"

    /** The module's own prefs file in the FB process. */
    private const val PREFS_NAME = "fbar_account"

    // Pref keys — the mod's app.telegram.bemai3012_* keys in parentheses.
    const val KEY_USER_ID = "user_id"           // FBUSERID
    const val KEY_USERNAME = "username"         // FBUSERNAME
    const val KEY_AUTH_TOKEN = "auth_token"     // FBTOKEN
    const val KEY_COOKIE_JSON = "cookie_json"   // FBFULLCOOKIE
    const val KEY_COOKIE_HTTP = "cookie_http"   // FBHTTPCOOKIE

    /** Stable secondary-dex class name (no discovery cache needed). */
    const val VIEWER_CONTEXT_CLASS = "com.facebook.auth.viewercontext.ViewerContext"

    private lateinit var prefs: SharedPreferences

    /** The hooked equals() methods — unhooked once the capture is complete. */
    private val hookHandles = ArrayList<XposedInterface.HookHandle>()

    /** In-memory statics — the mod's GhTa.xZw29/T7Puc/UuCRUj/s9sEFa. */
    @Volatile private var cookieJson: String? = null
    @Volatile private var cookieHttp: String? = null
    @Volatile private var token: String? = null
    @Volatile private var userId: String? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Resolves ViewerContext through the app classloader and hooks its
     * instance equals() — the mod's exact installer. Idempotent: returns
     * false quietly while the secondary dexes are not attached yet, so the
     * caller can retry on the same cadence as the discovery probe.
     */
    fun install(module: XposedInterface, classLoader: ClassLoader): Boolean {
        if (hookHandles.isNotEmpty()) return true
        val cls = runCatching {
            Class.forName(VIEWER_CONTEXT_CLASS, false, classLoader)
        }.getOrNull() ?: return false

        // Mod: getDeclaredMethods(), skip abstract, skip static, name == "equals".
        val targets = cls.declaredMethods.filter {
            !Modifier.isAbstract(it.modifiers) &&
                !Modifier.isStatic(it.modifiers) &&
                it.name == "equals"
        }
        if (targets.isEmpty()) {
            L.w(TAG, "no instance equals() on $VIEWER_CONTEXT_CLASS — class shape changed?")
            return false
        }

        val fields = HashMap<String, Field>(4)
        for (name in listOf("mSessionCookiesString", "mAuthToken", "mUsername", "mUserId")) {
            runCatching {
                val f = cls.getDeclaredField(name)
                f.isAccessible = true
                fields[name] = f
            }.onFailure { L.w(TAG, "field missing: $name", it) }
        }

        var installed = 0
        for (m in targets) {
            runCatching {
                m.isAccessible = true
                val handle = module.hook(m).intercept(CaptureHooker)
                hookHandles.add(handle)
                installed++
                L.i(TAG, "hooked ${cls.name}.${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
            }.onFailure { L.w(TAG, "hook failed on ${cls.name}.${m.name}", it) }
        }
        if (installed == 0) return false
        this.fields = fields
        return true
    }

    private var fields: Map<String, Field> = emptyMap()

    /**
     * bsrncODWYJ5TNENFG95a.beforeHookedMethod port: observe, capture, never
     * alter the original result.
     */
    private object CaptureHooker : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            runCatching { capture(chain.thisObject) }
                .onFailure { L.w(TAG, "capture failed", it) }
            return chain.proceed()
        }
    }

    /** The capture blocks (libnc.so.c 108510–109455). */
    private fun capture(viewerContext: Any?) {
        if (viewerContext == null) return

        // mSessionCookiesString → cookieJson + FBFULLCOOKIE, then the JSON
        // array walked into an HTTP Cookie header → cookieHttp + FBHTTPCOOKIE.
        stringField(viewerContext, "mSessionCookiesString")?.let { raw ->
            val sanitized = raw.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
            if (sanitized.isNotBlank()) {
                cookieJson = sanitized
                put(KEY_COOKIE_JSON, sanitized)
                jsonToHttpCookie(sanitized)?.let { http ->
                    cookieHttp = http
                    put(KEY_COOKIE_HTTP, http)
                    L.i(TAG, "ViewerContext FullCookie updated (json=${sanitized.length} chars, http=${http.length} chars)")
                } ?: L.w(TAG, "ViewerContext FullCookie updated (json only; HTTP conversion failed)")
            }
        }

        // mAuthToken → FBTOKEN.
        stringField(viewerContext, "mAuthToken")?.let { v ->
            if (v.isNotBlank()) {
                token = v
                put(KEY_AUTH_TOKEN, v)
                L.i(TAG, "ViewerContext Token updated (${mask(v)})")
            }
        }

        // mUsername → FBUSERNAME (pref only, like the mod).
        stringField(viewerContext, "mUsername")?.let { v ->
            if (v.isNotBlank()) {
                put(KEY_USERNAME, v)
                L.i(TAG, "ViewerContext UserName updated ($v)")
            }
        }

        // mUserId → FBUSERID.
        stringField(viewerContext, "mUserId")?.let { v ->
            if (v.isNotBlank()) {
                userId = v
                put(KEY_USER_ID, v)
                L.i(TAG, "ViewerContext UserId updated ($v)")
            }
        }

        // The mod unhooks once all four statics are populated (after a final
        // refresh, which this very call just performed).
        if (!cookieJson.isNullOrEmpty() && !cookieHttp.isNullOrEmpty() &&
            !token.isNullOrEmpty() && !userId.isNullOrEmpty()
        ) {
            L.i(TAG, "account capture complete — unhooking equals()")
            for (h in hookHandles) runCatching { h.unhook() }
            hookHandles.clear()
        }
    }

    /**
     * The captured Graph user token, for repost/page calls — in-memory static
     * first, pref fallback (mod: pO4TjBxQp2IFO1UyrtSF.oA72A8FsG9IKF8BK7Lb,
     * libnc.so.c 1319475–1319736 — static GhTa.UuCRUjLnvPR7SP3GNGo, then the
     * FBTOKEN pref). Blank/null = "no token" to callers.
     */
    fun authToken(): String? =
        token?.takeIf { it.isNotBlank() }
            ?: if (this::prefs.isInitialized) {
                runCatching { prefs.getString(KEY_AUTH_TOKEN, "") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
            } else null

    /**
     * S7VA.XS11PXyJIvbyHBPk0TZ(JSONArray) port (libnc.so.c 684239–684517):
     * for each cookie object take `name` and `value` (DAT_0105aed8/af18) and
     * join them as `name=value` separated by "; " (DAT_0105af98/af58) — a
     * standard HTTP Cookie header.
     */
    fun jsonToHttpCookie(json: String): String? = runCatching {
        val arr = JSONArray(json)
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            if (i != 0) sb.append("; ")
            sb.append(c.getString("name")).append('=').append(c.getString("value"))
        }
        sb.toString().takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun stringField(obj: Any, name: String): String? = runCatching {
        val f = fields[name] ?: return@runCatching null
        (f.get(obj) as? String)
    }.getOrNull()

    private fun put(key: String, value: String) {
        runCatching { prefs.edit().putString(key, value).apply() }
            .onFailure { L.w(TAG, "pref write failed: $key", it) }
    }

    /** Never log secrets in full — length plus a short head is enough to verify. */
    private fun mask(s: String): String = "${s.length} chars, ${s.take(4)}…"
}
