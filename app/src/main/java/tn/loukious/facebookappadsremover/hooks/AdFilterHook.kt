package tn.loukious.facebookappadsremover.hooks

import android.content.Context
import tn.loukious.facebookappadsremover.core.AdTargets
import tn.loukious.facebookappadsremover.core.HookAction
import tn.loukious.facebookappadsremover.core.HookTarget
import tn.loukious.facebookappadsremover.core.L
import tn.loukious.facebookappadsremover.core.Settings
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * M2 ad/filter hook installation — ports the three original-mod native
 * installers (AdjzonOq0OhXgiiuU6H feed, Rn0LbcxLisWuSI9YThk video-ads,
 * kEMr0xnlytyyfRWPypcd banner dex-scan) onto the libxposed API.
 *
 * Callback semantics (recovered from libnc.so):
 *  - BLOCK_NULL:     toggle on → skip original, return null/false-safe default
 *                    (mod: bsrncODWYJ5TNENFG95a preset-result)
 *  - BLOCK_FALSE:    toggle on → skip original, return false
 *                    (mod: banner-ads XC_MethodReplacement)
 *  - SPONSORED_NULL: toggle on → run the ad check on receiver + args; null the
 *                    call only for sponsored stories
 *                    (mod: FNjBxTKppvYVTRFkExAd + X.2Wa.A00 classifier).
 *
 * The mod's ad check ultimately asked GraphQLStory for its sponsored-data
 * subtree (A0l() != null in its build). Getter names drift per release, so we
 * use TreeJNI.hasFieldValue(FIELD_NAME_HASH_CODE_sponsored_data) — the hash
 * (-132939024) is identical in the mod's build and stock 576.0.0.42.73.
 */
object AdFilterHook {

    private const val TAG = "FBAR.Ads"

    /** Safety cap on the banner dex-scan (the mod's dexplore query was limited too). */
    private const val MAX_BANNER_CLASSES = 100

    /** Banner-scan class cache — internal state, kept in the FB process. */
    private const val BANNER_CACHE_NAME = "fbar_prefs_banner"

    /** The Facebook app classloader — module code can't see secondary-dex classes otherwise. */
    @Volatile
    internal var appClassLoader: ClassLoader? = null

    /**
     * Master toggle (mod: app.telegram.bemai3012_swHOME_ADS, default TRUE).
     * Re-read on each hook invocation, like the mod did — the settings UI
     * (core.Settings remote preferences) can flip it between invocations.
     */
    private fun enabled(): Boolean = Settings.getBoolean(Settings.ADS_ENABLED, true)

    fun init(context: Context) {
        appClassLoader = context.classLoader
        L.i(TAG, "Ad filter enabled=${enabled()}")
    }

    /** Installs every resolved method target from the discovery report. */
    fun install(module: XposedInterface, targets: List<HookTarget>, methods: Map<String, List<Method>>) {
        var installed = 0
        for (target in targets) {
            val action = target.action ?: continue
            for (m in methods[target.key].orEmpty()) {
                // R8 centralizes string constants into dispatch tables —
                // `static String xxx(int)` with a giant switch. Anchor strings
                // then resolve to the TABLE, not the real method, and nulling
                // its return corrupts every caller (execSQL(null), non-null
                // contract NPEs, null event names in logging). Never hook them.
                if (isStringDispatchTable(m)) {
                    L.w(TAG, "skipping string-table method ${target.key}: ${m.declaringClass.name}.${m.name}/${m.parameterCount}")
                    continue
                }
                try {
                    module.hook(m).intercept(hookerFor(action, "${target.key}: ${m.declaringClass.name}.${m.name}/${m.parameterCount}"))
                    installed++
                    L.i(TAG, "hooked ${target.key}: ${m.declaringClass.name}.${m.name}/${m.parameterCount}")
                } catch (t: Throwable) {
                    L.w(TAG, "hook failed ${target.key}: ${m.declaringClass.name}.${m.name}", t)
                }
            }
        }
        L.i(TAG, "Ad filter: $installed method hook(s) installed")
    }

    private fun hookerFor(action: HookAction, label: String): Hooker = when (action) {        HookAction.BLOCK_NULL -> LoggingBlockHook(label)
        HookAction.BLOCK_FALSE -> BlockFalseHook
        HookAction.SPONSORED_NULL -> SponsoredNullHook
        HookAction.RECEIVER_SPONSORED_NULL -> ReceiverSponsoredNullHook
    }

    /**
     * Debug wrapper around [BlockHook]: logs the first invocation of each
     * hooked method so the log right before any crash identifies the hook
     * that fired (hot paths — one log per method, then silent).
     */
    private class LoggingBlockHook(private val label: String) : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!logged) {
                logged = true
                L.i(TAG, "block-null fired: $label")
            }
            if (!enabled()) return chain.proceed()
            return nullResult(chain.executable as? Method)
        }
        private var logged = false
    }

    /**
     * Banner-ads dex-scan — port of the mod's kEMr0xnlytyyfRWPypcd installer:
     * find every class referencing any of the ~40 banner QPL/log anchors (the
     * dexplore reference filter was an OR over the anchor set, so we run one
     * DexKit class query per anchor and union the results), then hook each
     * declared method that returns boolean and takes at least one param,
     * replacing it with false. Found class names are cached in prefs
     * ('cached_banner_classes' in the mod) so later launches skip the scan.
     */
    fun installBannerScan(module: XposedInterface, bridge: DexKitBridge?, classLoader: ClassLoader, context: Context) {
        val cache = context.getSharedPreferences(BANNER_CACHE_NAME, Context.MODE_PRIVATE)
        val cached: Set<String> = cache.getStringSet("cached_banner_classes", emptySet()).orEmpty()

        val classNames: Set<String> = if (cached.isNotEmpty()) {
            L.i(TAG, "Banner scan: using ${cached.size} cached class(es)")
            cached
        } else if (bridge == null) {
            // Cache-hit launch path: no DexKit bridge. The class set was
            // populated on first launch; if prefs were wiped, the next full
            // discovery pass rebuilds it.
            L.w(TAG, "Banner scan: no cached classes and no bridge — skipping")
            return
        } else {
            val found = sortedSetOf<String>()
            for (anchor in AdTargets.bannerAnchors) {
                // Exact string-pool equality — the mod's dexplore reference
                // filter matched whole constants (its anchor list carries
                // 'banner_ad' AND 'banner_ads' separately). DexKit's default
                // is Contains, which over-matches ('bannerPo' also hits
                // 'bannerPosition'…) and pulled in event-logging plumbing
                // that crashed the app when its boolean checks were nulled.
                val hits = try {
                    bridge.findClass { matcher { addUsingString(anchor, StringMatchType.Equals) } }
                } catch (t: Throwable) {
                    L.w(TAG, "banner anchor query failed: $anchor", t)
                    null
                } ?: continue
                for (c in hits) found.add(c.name)
                if (found.size >= MAX_BANNER_CLASSES) break
            }
            L.i(TAG, "Banner scan: found ${found.size} class(es)")
            if (found.isNotEmpty()) {
                cache.edit().putStringSet("cached_banner_classes", found).apply()
            }
            found
        }

        var classesHooked = 0
        var methodsHooked = 0
        for (name in classNames) {
            val cls = runCatching { Class.forName(name, false, classLoader) }.getOrNull() ?: continue
            try {
                var hookedInClass = 0
                for (m in cls.declaredMethods) {
                    if (m.returnType != java.lang.Boolean.TYPE) continue
                    if (m.parameterCount < 1) continue
                    if (Modifier.isAbstract(m.modifiers)) continue
                    // Never intercept equals(): the mod's blind
                    // boolean-method sweep hooked overridden equals() in
                    // Quicksilver data classes, breaking HashMap lookups
                    // app-wide (manifested as NPEs deep in event logging).
                    if (m.name == "equals" && m.parameterCount == 1) continue
                    if (m.isSynthetic || m.isBridge) continue
                    try {
                        module.hook(m).intercept(BlockFalseHook)
                        methodsHooked++; hookedInClass++
                        L.i(TAG, "banner hook: $name.${m.name}/${m.parameterCount}")
                    } catch (t: Throwable) {
                        L.w(TAG, "banner hook failed: $name.${m.name}", t)
                    }
                }
                if (hookedInClass > 0) classesHooked++
            } catch (t: Throwable) {
                L.w(TAG, "banner class scan failed: $name", t)
            }
        }
        L.i(TAG, "Banner filter: $methodsHooked boolean method(s) in $classesHooked class(es)")
    }

    // ------------------------------------------------------------------
    // Hookers
    // ------------------------------------------------------------------

    /**
     * R8 string-constant dispatch table: `static String m(int)` — hundreds of
     * interned literals behind a switch. Anchored discovery resolves to these
     * whenever the anchor literal was centralized, so they must be excluded.
     */
    internal fun isStringDispatchTable(m: Method): Boolean {
        return Modifier.isStatic(m.modifiers) &&
            m.returnType == String::class.java &&
            m.parameterCount == 1 &&
            m.parameterTypes[0] == Int::class.javaPrimitiveType
    }

    /** Skip original, return null (or a primitive-safe default) — mod bsrnc preset-result. */
    object BlockHook : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!enabled()) return chain.proceed()
            return nullResult(chain.executable as? Method)
        }
    }

    /** Skip original, return false — mod banner-ads replacement. */
    object BlockFalseHook : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!enabled()) return chain.proceed()
            return java.lang.Boolean.FALSE
        }
    }

    /** Ad-check receiver + args, null only sponsored — mod FNjBxTKppvYVTRFkExAd. */
    object SponsoredNullHook : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!enabled()) return chain.proceed()
            return if (SponsoredCheck.isSponsored(chain.thisObject) ||
                chain.args.any { SponsoredCheck.isSponsored(it) }
            ) {
                L.i(TAG, "blocked sponsored render: ${chain.executable.name}")
                nullResult(chain.executable as? Method)
            } else {
                chain.proceed()
            }
        }
    }

    /**
     * Null only when the receiver stringifies with "SPONSORED" — mod
     * UVFD3BoJI5sq4SwfZZu0 on X.4qr.A00 (the FBShortsMidCardFeedUnit TreeJNI
     * type-node getter): String.valueOf(thisObject).contains("SPONSORED")
     * decides, so the getter nulls only sponsored mid-card units.
     */
    object ReceiverSponsoredNullHook : Hooker {
        private var logged = false

        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!enabled()) return chain.proceed()
            val sponsored = runCatching {
                chain.thisObject?.toString()?.contains("SPONSORED") == true
            }.getOrDefault(false)
            if (!sponsored) return chain.proceed()
            if (!logged) {
                logged = true
                L.i(TAG, "receiver-sponsored null fired: ${chain.executable.name}")
            }
            return nullResult(chain.executable as? Method)
        }
    }

    /** Returning null from a primitive-returning method crashes; substitute defaults. */
    private fun nullResult(m: Method?): Any? {
        return when (m?.returnType) {
            java.lang.Boolean.TYPE -> java.lang.Boolean.FALSE
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Double.TYPE -> 0.0
            java.lang.Float.TYPE -> 0f
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> ' '
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // Ad check (mod: X.2Wa.A00 -> C2Rc.A0H -> GraphQLStory.A0l() != null)
    // ------------------------------------------------------------------

    private object SponsoredCheck {
        private const val PARTIAL_STORY = "com.facebook.graphql.model.GraphQLPartialStory"

        @Volatile private var hashField: Field? = null
        @Volatile private var resolvedHash = false
        // Optional.empty = "class checked, no hasFieldValue method" — a plain
        // null value would NPE on ConcurrentHashMap.put.
        private val hasFieldValueCache = ConcurrentHashMap<Class<*>, java.util.Optional<Method>>()

        /**
         * True if the object is a sponsored story or wraps one. Mirrors the
         * mod's classifier: any GraphQLStory in the wrapper chain with a
         * non-null sponsored_data subtree.
         */
        fun isSponsored(obj: Any?): Boolean {
            if (obj == null) return false
            if (isSponsoredTree(obj)) return true
            if (isSkippable(obj)) return false
            return try {
                // Wrapper types (FeedProps and friends): test every object
                // field one level deep — the mod walked the same parent chain.
                var cls: Class<*> = obj.javaClass
                while (cls != null && cls != Any::class.java) {
                    for (f in cls.declaredFields) {
                        if (f.isSynthetic || Modifier.isStatic(f.modifiers)) continue
                        val t = f.type
                        if (t.isPrimitive || t == String::class.java || t.isArray) continue
                        f.isAccessible = true
                        val v = runCatching { f.get(obj) }.getOrNull() ?: continue
                        if (v !== obj && !isSkippable(v) && isSponsoredTree(v)) return true
                    }
                    cls = cls.superclass
                }
                false
            } catch (t: Throwable) {
                L.w(TAG, "isSponsored walk failed on ${obj.javaClass.name}", t)
                false
            }
        }

        /** Direct test: TreeJNI-backed object whose sponsored_data field is set. */
        private fun isSponsoredTree(obj: Any): Boolean {
            val hash = sponsoredHash() ?: return false
            var cls: Class<*>? = obj.javaClass
            while (cls != null) {
                val c = cls
                val cached = hasFieldValueCache[c]
                val m = if (cached != null) cached.orElse(null) else run {
                    val found = runCatching {
                        val mm = c.getDeclaredMethod("hasFieldValue", Int::class.javaPrimitiveType)
                        mm.isAccessible = true; mm
                    }.getOrNull()
                    hasFieldValueCache[c] = java.util.Optional.ofNullable(found)
                    found
                }
                if (m != null) {
                    return runCatching { m.invoke(obj, hash) as Boolean }.getOrDefault(false)
                }
                cls = c.superclass
            }
            return false
        }

        /** Cheap value types we never want to reflect into. */
        private fun isSkippable(obj: Any): Boolean {
            val n = obj.javaClass.name
            return n.startsWith("java.") || n.startsWith("android.") || n.startsWith("kotlin.")
        }

        /** Resolves FIELD_NAME_HASH_CODE_sponsored_data from GraphQLPartialStory (stable name+constant). */
        private fun sponsoredHash(): Int? {
            if (resolvedHash) return hashField?.get(null) as? Int
            resolvedHash = true
            return runCatching {
                val c = Class.forName(PARTIAL_STORY, false, appClassLoader)
                val f = c.getDeclaredField("FIELD_NAME_HASH_CODE_sponsored_data")
                f.isAccessible = true
                hashField = f
                f.get(null) as Int
            }.getOrElse {
                L.w(TAG, "sponsored_data hash constant not found — ad check degraded", it)
                null
            }
        }
    }
}
