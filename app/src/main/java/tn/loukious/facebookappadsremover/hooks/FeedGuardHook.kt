package tn.loukious.facebookappadsremover.hooks

import android.content.Context
import tn.loukious.facebookappadsremover.BuildConfig
import tn.loukious.facebookappadsremover.core.L
import tn.loukious.facebookappadsremover.core.MethodCache
import tn.loukious.facebookappadsremover.core.Settings
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.MatchType
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.ClassData
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * CSR-experiment feed guard — port of our own pre-rewrite feed experiment
 * filters (Patches.kt: the FeedCSRCacheFilter input/result hooks, the
 * late-feed list sanitizers and the structural Litho component guard),
 * re-based from the legacy XposedBridge API onto libxposed.
 *
 * Facebook serves some accounts the News Feed through the CSR (Composite
 * Surface Renderer) pipeline instead of the classic FeedUnit pipeline those
 * users never reach. The classic hooks (NewsfeedFilterHook on
 * processNewStories, AdFilterHook's story blockers) never see that traffic,
 * so sponsored stories sailed through. Three mechanisms, all preserved from
 * the old implementation:
 *
 *  1. CSR CACHE FILTERS — the CSR pipeline caches feed units through
 *     year-versioned filter classes (FeedCSRCacheFilter{,2025H1,2026H1,
 *     2026H2}; the class set rotates with FB's experiment cohorts). Their
 *     entry points take (FbUserSession, …, ImmutableList, int) and return a
 *     list-bearing result object: sponsored items are dropped from the input
 *     list BEFORE the filter runs (rebuilt via ImmutableList.copyOf) and the
 *     kept-list is re-filtered from the RESULT afterwards, so nothing
 *     sponsored survives into the CSR cache.
 *
 *  2. LATE-FEED SANITIZERS — three shapes of "the feed list is already
 *     cached, consume it" consumers (storage-lifecycle classes, the story
 *     pool's vending timer, the CSR/FriendlyFeed/FbShorts storage lifecycle
 *     impls) each receive an ImmutableList that is filtered in place before
 *     the original runs.
 *
 *  3. COMPONENT GUARD — cached feed rows render through Litho components
 *     whose spec names survive obfuscation as string constants
 *     ("NewsFeedFeedUnitComponent" / "LoggingComponent"). The component
 *     holds the GraphQLFeedUnitEdge; when that edge is definitely sponsored
 *     the render/layout method is nulled so Litho skips the row entirely.
 *
 * The classifier is reflection-by-shape only (FeedItemInspector): category
 * enums matched by their constants, children matched by type shape — no
 * obfuscated member name is referenced anywhere. Discovery anchors are
 * stable strings; every hook method is resolved structurally, which lets
 * the cache-hit path rebuild the hooks from class names alone.
 *
 * API note: libxposed's [XposedInterface.Chain.getArgs] returns an IMMUTABLE
 * list — the list rewrites go through `chain.proceed(newArgs)`, unlike the
 * old `param.args[i] = …` mutation.
 */
object FeedGuardHook {

    private const val TAG = "FBAR.FeedGuard"

    // ------------------------------------------------------------------
    // Cache (own prefs file, host+module version keyed)
    // ------------------------------------------------------------------

    /** Old "fbar_feed_guard_cache.properties" — same role, prefs-backed now. */
    private const val CACHE_PREFS = "fbar_feed_guard_cache"
    private const val KEY_HOST_VERSION = "version"
    private const val KEY_MODULE_VERSION = "moduleVersion"
    private const val KEY_CLASSES = "classes"

    /** MethodCache class-map key when persisting through the shared cache. */
    const val CACHE_KEY = "feed.guard.classes"

    /** Role prefixes in the cached/returned class list. */
    private const val ROLE_CSR = "csr:"
    private const val ROLE_LATE = "late:"
    private const val ROLE_COMPONENT = "comp:"
    private const val ROLE_WRAPPER = "wrap:"

    // ------------------------------------------------------------------
    // Stable names & anchors (nothing obfuscated)
    // ------------------------------------------------------------------

    private const val GRAPHQL_FEED_UNIT_EDGE_CLASS = "com.facebook.graphql.model.GraphQLFeedUnitEdge"
    private const val GRAPHQL_MULTI_ADS_FEED_UNIT_CLASS = "com.facebook.graphql.model.GraphQLFBMultiAdsFeedUnit"
    private const val GRAPHQL_QUICK_PROMO_FEED_UNIT_CLASS =
        "com.facebook.graphql.model.GraphQLQuickPromotionNativeTemplateFeedUnit"
    private const val FB_USER_SESSION_CLASS = "com.facebook.auth.usersession.FbUserSession"
    private const val IMMUTABLE_LIST_CLASS = "com.google.common.collect.ImmutableList"

    /** Batch anchor strings — the year-versioned filters log their own names. */
    private val CSR_FILTER_STRINGS = listOf(
        "FeedCSRCacheFilter",
        "FeedCSRCacheFilter2025H1",
        "FeedCSRCacheFilter2026H1",
    )

    /** Zero-arg String-getter tags — the fallback path, incl. the 2026H2 cohort. */
    private val CSR_FILTER_NAME_TAGS = CSR_FILTER_STRINGS + "FeedCSRCacheFilter2026H2"

    /** CSR storage lifecycle impls that consume a cached feed list. */
    private val CSR_LIFECYCLE_TAGS = listOf(
        "CSRNoOpStorageLifecycleImpl",
        "FeedCSRStorageLifecycle",
        "FriendlyFeedCSRStorageLifecycle",
        "FbShortsCSRStorageLifecycle",
    )

    /** Litho spec names — final String constants on the generated components. */
    private const val FEED_UNIT_COMPONENT_NAME = "NewsFeedFeedUnitComponent"
    private const val FEED_WRAPPER_COMPONENT_NAME = "LoggingComponent"

    /** Litho layout entry-point arities seen across builds (A1F/A1H on 576). */
    private val FEED_RENDER_PARAMETER_COUNTS = listOf(1, 2)

    /** Categories that mark a feed unit as an ad (constants of GraphQLFeedStoryCategory-like enums). */
    private val FEED_AD_CATEGORY_VALUES = setOf(
        "SPONSORED",
        "PROMOTION",
        "ENGAGEMENT_QP",
        "AD",
        "ADVERTISEMENT",
        "BANNER",
    )

    /**
     * Categories that must NEVER be dropped even though they wrap ad-ish
     * children — the Shorts shelf and the Stories tray carry their own
     * non-sponsored content.
     */
    private val FEED_SAFE_CONTAINER_CATEGORY_VALUES = setOf(
        "FB_SHORTS",
        "MULTI_FB_STORIES_TRAY",
    )

    /** Lowercase tokens that identify an ad unit by class/type name. */
    private val FEED_AD_SIGNAL_TOKENS = listOf(
        "sponsored",
        "promotion",
        "multiads",
        "quickpromotion",
        "reels_banner_ad",
        "reelsbannerads",
        "reels_post_loop_deferred_card",
        "deferred_card",
        "adbreakdeferredcta",
        "instreamadidlewithbannerstate",
        "instream_legacy_banner_ad",
        "unified_player_banner_ad",
        "banner_ad_",
        "floatingcta",
    )

    /** Hot-path log throttle: first 3 hits, then every 25th (old HOOK_HIT_LOG_EVERY). */
    private const val HOOK_HIT_LOG_EVERY = 25

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    /** The Facebook app classloader — ImmutableList rebuild loader hint. */
    @Volatile
    private var appClassLoader: ClassLoader? = null

    /**
     * Dedup guard: the cache-hit install and the full DexKit pass resolve
     * the same Method objects — hooking twice would filter every list twice.
     * One set covers all three mechanisms; keys are globally unique per
     * method (declaringClass#name(params):return).
     */
    private val hookedMethodKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val hookHitCounters = ConcurrentHashMap<String, AtomicInteger>()

    /** Litho component registries (old feedComponentCandidates / feedWrapperCandidates). */
    private val componentCandidates = ConcurrentHashMap<String, Class<*>>()
    private val wrapperCandidates = ConcurrentHashMap<String, Class<*>>()

    /** Guard pairs that actually produced render hooks — persisted for the cache. */
    private val resolvedComponentNames = ConcurrentHashMap.newKeySet<String>()
    private val resolvedWrapperNames = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var feedInspector: FeedItemInspector? = null

    /**
     * Master ad-block switch (same gate as AdFilterHook — the feed guard
     * rides the mod's one swHOME_ADS switch). Re-read per invocation so
     * flipping the toggle applies without a reinstall; the old port gated
     * only at install time.
     */
    private fun enabled(): Boolean =
        Settings.getBoolean(Settings.ADS_ENABLED, true) &&
            Settings.getBoolean(Settings.ADS_FEED_GUARD, true)

    private fun inspector(): FeedItemInspector {
        feedInspector?.let { return it }
        return synchronized(this) {
            feedInspector ?: FeedItemInspector(emptyList())
                .also { feedInspector = it }
        }
    }

    // ------------------------------------------------------------------
    // Full-scan entry (DexKit available)
    // ------------------------------------------------------------------

    /**
     * Runs the CSR cache-filter hooks, the late-feed sanitizers and the
     * component guard. Returns the discovered class names, role-prefixed
     * ("csr:X", "late:Y", "comp:Z", "wrap:W"), for the caller to persist
     * ([saveCache] / MethodCache class map). An empty list means nothing
     * was found on this build — the caller should record the "absent"
     * sentinel so it doesn't rescan every launch.
     */
    fun install(
        module: XposedInterface,
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        context: Context,
    ): List<String> {
        appClassLoader = classLoader
        val found = LinkedHashSet<String>()

        runCatching { installCsrFilters(module, bridge, classLoader, found) }
            .onFailure { L.w(TAG, "CSR filter discovery failed", it) }
        runCatching { installLateFeedSanitizers(module, bridge, classLoader, found) }
            .onFailure { L.w(TAG, "Late-feed sanitizer discovery failed", it) }
        runCatching { installComponentGuard(module, bridge, classLoader, found) }
            .onFailure { L.w(TAG, "Feed component guard failed", it) }

        L.i(TAG, "full scan: ${found.size} cacheable class(es)")
        return found.toList()
    }

    /**
     * CSR cache filters: the batch query finds classes whose code references
     * the year-versioned filter names; the fallback finds classes exposing a
     * zero-arg String getter returning one of those names (self-describing
     * factories). The entry point on each candidate is then resolved by
     * parameter shape — no method name involved.
     */
    private fun installCsrFilters(
        module: XposedInterface,
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        found: MutableSet<String>,
    ) {
        val batch = runCatching {
            bridge.batchFindClassUsingStrings {
                groups(mapOf("feedCsrFilters" to CSR_FILTER_STRINGS), StringMatchType.Equals)
            }
        }.getOrDefault(emptyMap())["feedCsrFilters"].orEmpty()
        val classData =
            if (batch.isNotEmpty()) batch else findClassesByZeroArgStringTags(bridge, CSR_FILTER_NAME_TAGS)

        val candidates = LinkedHashMap<String, Class<*>>()
        for (cd in classData) {
            val cls = runCatching { cd.getInstance(classLoader) }.getOrNull() ?: continue
            candidates.putIfAbsent(cls.name, cls)
        }

        var hooked = 0
        for (cls in candidates.values) {
            val hook = resolveCsrHookForClass(cls) ?: continue
            if (hookCsrFilter(module, hook)) {
                hooked++
                found.add(ROLE_CSR + hook.method.declaringClass.name)
            }
        }
        L.i(TAG, "CSR cache filters: $hooked hook(s) from ${candidates.size} candidate class(es)")
    }

    /**
     * Late-feed sanitizers — three discovery paths, each a different shape
     * of cached-list consumer (old resolveLateFeedListHooks):
     *   (a) storage lifecycle: void m(any, ImmutableList, int)
     *   (b) story-pool vending: void m(ImmutableList, String)
     *   (c) CSR storage lifecycle impls: void m(FbUserSession, any, ImmutableList)
     */
    private fun installLateFeedSanitizers(
        module: XposedInterface,
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        found: MutableSet<String>,
    ) {
        var hooked = 0

        for (cls in classesByStrings(bridge, classLoader, "handleStorageStories", "Empty Storage List")) {
            val hook = resolveLateHookForClass(cls, LateFeedShape.STORAGE)
            if (hook != null && hookLateFeedList(module, hook)) {
                hooked++
                found.add(ROLE_LATE + hook.method.declaringClass.name)
            }
        }

        for (cls in classesByStrings(bridge, classLoader, "cancelVendingTimerAndAddToPool_")) {
            val hook = resolveLateHookForClass(cls, LateFeedShape.VENDING)
            if (hook != null && hookLateFeedList(module, hook)) {
                hooked++
                found.add(ROLE_LATE + hook.method.declaringClass.name)
            }
        }

        for (cd in findClassesByZeroArgStringTags(bridge, CSR_LIFECYCLE_TAGS)) {
            val cls = runCatching { cd.getInstance(classLoader) }.getOrNull() ?: continue
            val hook = resolveLateHookForClass(cls, LateFeedShape.LIFECYCLE)
            if (hook != null && hookLateFeedList(module, hook)) {
                hooked++
                found.add(ROLE_LATE + hook.method.declaringClass.name)
            }
        }
        L.i(TAG, "late-feed sanitizers: $hooked hook(s)")
    }

    /**
     * Component guard: DexKit finds the Litho component classes by their
     * spec-name constants (the old class-load notifier path is dropped —
     * this DexKit query covers the same classes without a runtime hook),
     * then the pair-matching installer resolves fields and render methods.
     */
    private fun installComponentGuard(
        module: XposedInterface,
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        found: MutableSet<String>,
    ) {
        registerLithoComponentClasses(bridge, classLoader, FEED_UNIT_COMPONENT_NAME, componentCandidates)
        registerLithoComponentClasses(bridge, classLoader, FEED_WRAPPER_COMPONENT_NAME, wrapperCandidates)
        installComponentGuard(module)
        resolvedComponentNames.forEach { found.add(ROLE_COMPONENT + it) }
        resolvedWrapperNames.forEach { found.add(ROLE_WRAPPER + it) }
    }

    // ------------------------------------------------------------------
    // Cache-hit entry (no DexKit)
    // ------------------------------------------------------------------

    /**
     * Rebuilds every hook from the role-prefixed class names alone: the CSR
     * and late-feed methods are re-resolved by parameter shape, the guard
     * classes re-registered into the pair-matching registries. Entries whose
     * class is not yet loadable (secondary dexes not attached) fail softly —
     * safe to re-invoke on the caller's retry cadence, the registries and
     * the method-key set make this idempotent.
     *
     * @param cached role-prefixed entries as returned by [install]
     * @return true when at least one hook was installed
     */
    fun installCached(
        module: XposedInterface,
        classLoader: ClassLoader,
        context: Context,
        cached: Set<String>,
    ): Boolean {
        if (cached.isEmpty()) return false
        appClassLoader = classLoader
        var installed = 0
        for (entry in cached) {
            runCatching {
                val role = entry.substringBefore(':', "")
                val className = entry.substringAfter(':', "")
                if (className.isBlank()) return@runCatching
                val cls = Class.forName(className, false, classLoader)
                when (role) {
                    ROLE_CSR -> resolveCsrHookForClass(cls)?.let {
                        if (hookCsrFilter(module, it)) installed++
                    }
                    ROLE_LATE -> resolveLateHookForClass(cls)?.let {
                        if (hookLateFeedList(module, it)) installed++
                    }
                    ROLE_COMPONENT -> componentCandidates.putIfAbsent(cls.name, cls)
                    ROLE_WRAPPER -> wrapperCandidates.putIfAbsent(cls.name, cls)
                }
            }.onFailure { L.w(TAG, "cached install failed for $entry", it) }
        }
        // The guard needs the full component × wrapper cross product, so it
        // runs after every registry entry has been re-registered.
        installed += installComponentGuard(module)
        L.i(TAG, "cached install: $installed hook(s) from ${cached.size} class(es)")
        return installed > 0
    }

    // ------------------------------------------------------------------
    // Cache persistence (old saveFeedGuardCandidateCache / loadCached…)
    // ------------------------------------------------------------------

    /**
     * Persists the discovered class list. Invalidate on host update (names
     * are per-build obfuscated) or module update (hook logic may change).
     */
    fun saveCache(context: Context, names: List<String>) {
        if (names.isEmpty()) return
        runCatching {
            context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_HOST_VERSION, hostVersionCode(context))
                .putString(KEY_MODULE_VERSION, moduleVersionKey())
                .putStringSet(KEY_CLASSES, names.toSet())
                .apply()
            L.i(TAG, "cache saved: ${names.size} class(es)")
        }.onFailure { L.w(TAG, "cache save failed", it) }
    }

    /** Reads the cache back, or null when absent/stale. */
    fun loadCache(context: Context): Set<String>? = runCatching {
        val prefs = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_HOST_VERSION, -1) != hostVersionCode(context)) return null
        if (prefs.getString(KEY_MODULE_VERSION, "") != moduleVersionKey()) return null
        prefs.getStringSet(KEY_CLASSES, emptySet())?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    // ------------------------------------------------------------------
    // ModuleMain wiring (shared MethodCache class-map channel)
    // ------------------------------------------------------------------

    /**
     * True when the shared discovery cache already carries this hook's
     * class-map entry — a cache written before the feed-guard port must
     * force a rescan (checked from ModuleMain.installFromCache).
     */
    fun cachePresent(context: Context): Boolean =
        runCatching { MethodCache.loadClass(context, CACHE_KEY) }.getOrNull() != null

    /**
     * Cache-hit install through the shared class map: reads the stored
     * role-prefixed CSV and delegates to [installCached]. No-op on a miss;
     * the "absent" sentinel means the anchors found nothing on this build.
     * Idempotent, so a partially-attached secondary dex can be retried.
     */
    fun installFromCache(module: XposedInterface, classLoader: ClassLoader, context: Context) {
        val state = runCatching { MethodCache.loadClass(context, CACHE_KEY) }.getOrNull()
        when {
            state == null -> return
            state == "absent" -> L.i(TAG, "anchor not found on this build — skipping")
            else -> runCatching {
                installCached(module, classLoader, context, state.split(',').toSet())
            }.onFailure { L.e(TAG, "cached installation failed", it) }
        }
    }

    /**
     * Full-scan install (called from ModuleMain.installAdFilters) that also
     * reduces its result to the class-map entry for [CACHE_KEY] — "absent"
     * when the anchors found nothing, so a NOT_FOUND build doesn't rescan.
     */
    fun installAndCache(
        module: XposedInterface,
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        context: Context,
    ): String =
        runCatching { install(module, bridge, classLoader, context) }
            .onFailure { L.e(TAG, "installation failed", it) }
            .getOrDefault(emptyList())
            .takeIf { it.isNotEmpty() }?.joinToString(",") ?: "absent"

    private fun hostVersionCode(context: Context): Int = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt()
        else @Suppress("DEPRECATION") pi.versionCode
    }.getOrDefault(-1)

    /** The old feedGuardCacheModuleKey format. */
    private fun moduleVersionKey(): String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    // ------------------------------------------------------------------
    // Structural method resolution (shared by both entry points)
    // ------------------------------------------------------------------

    /** A CSR cache-filter entry point: method + index of its ImmutableList arg. */
    private data class FeedCsrFilterHook(val method: Method, val listArgIndex: Int)

    /** A late-feed list consumer: method + index of its ImmutableList arg. */
    private data class FeedListSanitizerHook(val method: Method, val listArgIndex: Int)

    /** The three late-feed shapes; each fixes its list-arg index. */
    private enum class LateFeedShape(val listArgIndex: Int) {
        /** void m(any, ImmutableList, int) */
        STORAGE(1),

        /** void m(ImmutableList, String) */
        VENDING(0),

        /** void m(FbUserSession, any, ImmutableList) */
        LIFECYCLE(2),
    }

    /**
     * First non-static-synthetic non-abstract method up the hierarchy that
     * matches — the reflection equivalent of the old DexKit findMethod with
     * paramTypes/returnType matchers, reused verbatim on the cache-hit path.
     */
    private fun firstHookableMethod(cls: Class<*>, matches: (Method) -> Boolean): Method? {
        var current: Class<*>? = cls
        while (current != null && current != Any::class.java) {
            val type = current
            val found = type.declaredMethods.firstOrNull { m ->
                !Modifier.isAbstract(m.modifiers) &&
                    !m.isSynthetic &&
                    !m.isBridge &&
                    matches(m)
            }
            if (found != null) return found.apply { isAccessible = true }
            current = type.superclass
        }
        return null
    }

    /**
     * CSR entry-point shapes (old resolveFeedCsrFilterMethods):
     *   4-arg (FbUserSession, any, ImmutableList, int) → list at index 2
     *   3-arg (FbUserSession, ImmutableList, int)       → list at index 1
     */
    private fun resolveCsrHookForClass(cls: Class<*>): FeedCsrFilterHook? {
        if (cls.isInterface || Modifier.isAbstract(cls.modifiers)) return null
        val fourArg = firstHookableMethod(cls) { m ->
            val p = m.parameterTypes
            p.size == 4 &&
                p[0].name == FB_USER_SESSION_CLASS &&
                p[2].name == IMMUTABLE_LIST_CLASS &&
                p[3] == Int::class.javaPrimitiveType
        }
        if (fourArg != null) return FeedCsrFilterHook(fourArg, 2)
        val threeArg = firstHookableMethod(cls) { m ->
            val p = m.parameterTypes
            p.size == 3 &&
                p[0].name == FB_USER_SESSION_CLASS &&
                p[1].name == IMMUTABLE_LIST_CLASS &&
                p[2] == Int::class.javaPrimitiveType
        }
        return threeArg?.let { FeedCsrFilterHook(it, 1) }
    }

    private fun resolveLateHookForClass(cls: Class<*>): FeedListSanitizerHook? {
        for (shape in LateFeedShape.values()) {
            resolveLateHookForClass(cls, shape)?.let { return it }
        }
        return null
    }

    private fun resolveLateHookForClass(cls: Class<*>, shape: LateFeedShape): FeedListSanitizerHook? {
        if (cls.isInterface || Modifier.isAbstract(cls.modifiers)) return null
        val method = firstHookableMethod(cls) { m ->
            m.returnType == Void.TYPE && matchesLateShape(m.parameterTypes, shape)
        } ?: return null
        return FeedListSanitizerHook(method, shape.listArgIndex)
    }

    private fun matchesLateShape(params: Array<Class<*>>, shape: LateFeedShape): Boolean = when (shape) {
        LateFeedShape.STORAGE ->
            params.size == 3 &&
                params[1].name == IMMUTABLE_LIST_CLASS &&
                params[2] == Int::class.javaPrimitiveType
        LateFeedShape.VENDING ->
            params.size == 2 &&
                params[0].name == IMMUTABLE_LIST_CLASS &&
                params[1] == String::class.java
        LateFeedShape.LIFECYCLE ->
            params.size == 3 &&
                params[0].name == FB_USER_SESSION_CLASS &&
                params[2].name == IMMUTABLE_LIST_CLASS
    }

    // ------------------------------------------------------------------
    // Hook installation (dedup-guarded)
    // ------------------------------------------------------------------

    private fun methodHookKey(method: Method): String {
        return "${method.declaringClass.name}#${method.name}(" +
            method.parameterTypes.joinToString(",") { it.name } +
            "):${method.returnType.name}"
    }

    private fun hookCsrFilter(module: XposedInterface, hook: FeedCsrFilterHook): Boolean {
        if (!hookedMethodKeys.add(methodHookKey(hook.method))) return false
        return runCatching {
            hook.method.isAccessible = true
            module.hook(hook.method).intercept(CsrFilterHooker(hook.method, hook.listArgIndex))
            L.i(TAG, "hooked CSR filter: ${hook.method.declaringClass.name}.${hook.method.name}/${hook.listArgIndex}")
            true
        }.getOrElse {
            L.w(TAG, "CSR filter hook failed: ${hook.method.declaringClass.name}.${hook.method.name}", it)
            false
        }
    }

    private fun hookLateFeedList(module: XposedInterface, hook: FeedListSanitizerHook): Boolean {
        if (!hookedMethodKeys.add(methodHookKey(hook.method))) return false
        return runCatching {
            hook.method.isAccessible = true
            module.hook(hook.method).intercept(LateFeedHooker(hook.method, hook.listArgIndex))
            L.i(TAG, "hooked late-feed sanitizer: ${hook.method.declaringClass.name}.${hook.method.name}/${hook.listArgIndex}")
            true
        }.getOrElse {
            L.w(TAG, "late-feed hook failed: ${hook.method.declaringClass.name}.${hook.method.name}", it)
            false
        }
    }

    // ------------------------------------------------------------------
    // Component guard (old installFacebookFeedComponentGuard)
    // ------------------------------------------------------------------

    /**
     * Cross product of registered components × wrappers: a pair matches when
     * the component declares an edge field, the wrapper declares a child
     * field typed as the component, and the two share a Litho layout
     * context type at a known arity. Every render method of a matched pair
     * is hooked; render methods are matched by shape because their
     * obfuscated names rotate per build (A1H on 571, A1F+A1H on 576).
     *
     * @return the number of render methods newly hooked
     */
    private fun installComponentGuard(module: XposedInterface): Int {
        var installed = 0
        for (componentClass in componentCandidates.values) {
            val edgeField = runCatching { resolveFeedEdgeField(componentClass) }.getOrNull()
                ?: continue
            for (wrapperClass in wrapperCandidates.values) {
                if (wrapperClass == componentClass) continue
                val wrapperChildField = runCatching { resolveWrapperChildField(wrapperClass, componentClass) }
                    .getOrNull() ?: continue
                val renderMethods = FEED_RENDER_PARAMETER_COUNTS.firstNotNullOfOrNull { parameterCount ->
                    val layoutContextType = runCatching {
                        resolveLithoLayoutContextType(componentClass, wrapperClass, parameterCount)
                    }.getOrNull() ?: return@firstNotNullOfOrNull null
                    val methods = listOf(componentClass, wrapperClass).flatMap { type ->
                        lithoLayoutMethods(type, layoutContextType, parameterCount)
                    }
                    methods.ifEmpty { null }
                } ?: continue

                resolvedComponentNames.add(componentClass.name)
                resolvedWrapperNames.add(wrapperClass.name)
                for (method in renderMethods) {
                    if (!hookedMethodKeys.add(methodHookKey(method))) continue
                    runCatching {
                        method.isAccessible = true
                        module.hook(method).intercept(
                            ComponentGuardHooker(componentClass, wrapperClass, edgeField, wrapperChildField, method)
                        )
                        installed++
                    }.onFailure {
                        L.w(TAG, "component guard hook failed: ${method.declaringClass.name}.${method.name}", it)
                    }
                }
            }
        }
        if (installed > 0) {
            L.i(TAG, "component guard: $installed render hook(s), " +
                "components=${resolvedComponentNames.joinToString()} wrappers=${resolvedWrapperNames.joinToString()}")
        }
        return installed
    }

    /** Classes carrying the Litho spec name as a String constant (old registerLithoComponentClasses). */
    private fun registerLithoComponentClasses(
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        componentName: String,
        registry: ConcurrentHashMap<String, Class<*>>,
    ) {
        val classes = runCatching {
            bridge.findClass {
                matcher {
                    usingStrings(listOf(componentName), StringMatchType.Equals)
                }
            }.mapNotNull { classData ->
                runCatching { classData.getInstance(classLoader) }.getOrNull()
            }
        }.getOrDefault(emptyList())
        classes.forEach { discovered -> registry.putIfAbsent(discovered.name, discovered) }
        L.i(TAG, "Litho component name=$componentName classes=${classes.joinToString { it.name }}")
    }

    /** Static builder factories share the layout shape — only instance methods hook. */
    private fun lithoLayoutMethods(
        type: Class<*>,
        contextType: Class<*>,
        parameterCount: Int,
    ): List<Method> {
        return type.declaredMethods.filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.parameterCount == parameterCount &&
                !method.returnType.isPrimitive &&
                method.parameterTypes[0] == contextType
        }.onEach { it.isAccessible = true }
    }

    /** The Litho context type both classes accept; falls back to the component's most common. */
    private fun resolveLithoLayoutContextType(
        componentClass: Class<*>,
        wrapperClass: Class<*>,
        parameterCount: Int,
    ): Class<*>? {
        fun contextCandidates(type: Class<*>): List<Class<*>> {
            return type.declaredMethods
                .filter { method ->
                    !Modifier.isStatic(method.modifiers) &&
                        method.parameterCount == parameterCount &&
                        !method.returnType.isPrimitive &&
                        !method.parameterTypes[0].isPrimitive
                }
                .map { it.parameterTypes[0] }
        }

        val componentCandidates = contextCandidates(componentClass)
        val wrapperCandidates = contextCandidates(wrapperClass).toSet()
        return componentCandidates.firstOrNull { it in wrapperCandidates }
            ?: componentCandidates.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    }

    /**
     * The component's edge field: typed GraphQLFeedUnitEdge, or the first
     * non-primitive field whose type declares a story-category accessor
     * (the enum moved package between builds, so match by constants).
     */
    private fun resolveFeedEdgeField(componentClass: Class<*>): Field? {
        val declared = componentClass.declaredFields.filter { field ->
            !Modifier.isStatic(field.modifiers) && !field.type.isPrimitive
        }
        val resolved = declared.firstOrNull { it.type.name == GRAPHQL_FEED_UNIT_EDGE_CLASS }
            ?: declared.firstOrNull { declaresFeedStoryCategoryAccessor(it.type) }
        return resolved?.apply { isAccessible = true }
    }

    /** The wrapper's child field typed as (a supertype of) the component. */
    private fun resolveWrapperChildField(wrapperClass: Class<*>, componentClass: Class<*>): Field? {
        val resolved = wrapperClass.declaredFields.firstOrNull { field ->
            !Modifier.isStatic(field.modifiers) &&
                field.type != Any::class.java &&
                field.type.isAssignableFrom(componentClass)
        }
        return resolved?.apply { isAccessible = true }
    }

    private fun declaresFeedStoryCategoryAccessor(type: Class<*>): Boolean {
        return runCatching {
            type.declaredMethods.any { method ->
                method.parameterCount == 0 &&
                    method.returnType.isEnum &&
                    method.returnType.enumConstants?.any { constant ->
                        val name = constant.toString()
                        name == "SPONSORED" || name == "PROMOTION"
                    } == true
            }
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------
    // DexKit class discovery helpers
    // ------------------------------------------------------------------

    /**
     * Classes exposing a zero-arg String method that uses the tag — the
     * year-versioned filter classes self-describe through such getters.
     */
    private fun findClassesByZeroArgStringTags(
        bridge: DexKitBridge,
        tags: Collection<String>,
    ): List<ClassData> {
        val candidates = LinkedHashMap<String, ClassData>()
        tags.forEach { tag ->
            runCatching {
                bridge.findClass {
                    matcher {
                        methods {
                            matchType = MatchType.Contains
                            add {
                                returnType = "java.lang.String"
                                paramCount = 0
                                usingStrings(tag)
                            }
                        }
                    }
                }
            }.getOrDefault(emptyList()).forEach { candidates.putIfAbsent(it.name, it) }
        }
        return candidates.values.toList()
    }

    private fun classesByStrings(
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        vararg strings: String,
    ): List<Class<*>> = runCatching {
        bridge.findClass { matcher { usingStrings(*strings) } }
    }.getOrDefault(emptyList()).mapNotNull { cd ->
        runCatching { cd.getInstance(classLoader) }.getOrNull()
    }

    // ------------------------------------------------------------------
    // Hookers
    // ------------------------------------------------------------------

    /**
     * CSR cache filter, both phases of the old hook: filter the input list
     * before the original runs, then re-filter the kept-list it returns.
     */
    private class CsrFilterHooker(
        private val hookMethod: Method,
        private val listArgIndex: Int,
    ) : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val newArgs = if (enabled()) runCatching { filterListArg(chain) }.getOrNull() else null
            val result = if (newArgs != null) chain.proceed(newArgs) else chain.proceed()
            if (!enabled()) return result
            return runCatching { filterResult(result) }.getOrNull() ?: result
        }

        /** Before phase: drop sponsored items from the input ImmutableList. */
        private fun filterListArg(chain: XposedInterface.Chain): Array<Any?>? = runCatching {
            val original = chain.args.getOrNull(listArgIndex) as? Iterable<*> ?: return@runCatching null
            val (kept, removed) = partitionSponsored(original)
            if (removed <= 0) return@runCatching null
            val rebuilt = buildImmutableListLike(chain.args[listArgIndex], kept) ?: return@runCatching null
            val newArgs: Array<Any?> = chain.args.toTypedArray()
            newArgs[listArgIndex] = rebuilt
            logHookHitThrottled("csrFilterIn", hookMethod) { "removed=$removed" }
            newArgs
        }.getOrNull()

        /**
         * After phase: the result carries the list the filter decided to
         * keep — rebuild it (and its stats fields) without the sponsored
         * items so nothing survives into the CSR cache.
         */
        private fun filterResult(result: Any?): Any? = runCatching {
            val items = extractFeedItemsFromResult(result) ?: return@runCatching null
            val (kept, removed) = partitionSponsored(items)
            if (removed <= 0) return@runCatching null
            val rebuilt = rebuildFeedResult(result ?: return@runCatching null, kept)
                ?: return@runCatching null
            logHookHitThrottled("csrFilterOut", hookMethod) { "removed=$removed" }
            rebuilt
        }.getOrNull()
    }

    /** Late-feed sanitizer: filter the list argument, then run the original. */
    private class LateFeedHooker(
        private val hookMethod: Method,
        private val listArgIndex: Int,
    ) : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!enabled()) return chain.proceed()
            val newArgs = runCatching { filterListArg(chain) }.getOrNull()
            return if (newArgs != null) chain.proceed(newArgs) else chain.proceed()
        }

        private fun filterListArg(chain: XposedInterface.Chain): Array<Any?>? = runCatching {
            val original = chain.args.getOrNull(listArgIndex) as? Iterable<*> ?: return@runCatching null
            val (kept, removed) = partitionSponsored(original)
            if (removed <= 0) return@runCatching null
            val rebuilt = buildImmutableListLike(chain.args[listArgIndex], kept) ?: return@runCatching null
            val newArgs: Array<Any?> = chain.args.toTypedArray()
            newArgs[listArgIndex] = rebuilt
            logHookHitThrottled("lateFeedSanitize", hookMethod) { "removed=$removed" }
            newArgs
        }.getOrNull()
    }

    /**
     * Component guard: the owner is the component (or a wrapper whose child
     * field holds it); when the component's edge is definitely sponsored,
     * null the render so Litho skips the row.
     */
    private class ComponentGuardHooker(
        private val componentClass: Class<*>,
        private val wrapperClass: Class<*>,
        private val edgeField: Field,
        private val wrapperChildField: Field,
        private val hookMethod: Method,
    ) : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!enabled()) return chain.proceed()
            val owner = chain.thisObject ?: return chain.proceed()
            val component = when {
                componentClass.isInstance(owner) -> owner
                wrapperClass.isInstance(owner) -> runCatching { wrapperChildField.get(owner) }
                    .getOrNull()?.takeIf { componentClass.isInstance(it) }
                else -> null
            } ?: return chain.proceed()
            val edge = runCatching { edgeField.get(component) }.getOrNull()
            if (edge == null) {
                logHookHitThrottled("feedComponentNoEdge", hookMethod) { "component=${componentClass.name}" }
                return chain.proceed()
            }
            if (!inspector().isDefinitelySponsoredFeedItem(edge)) {
                logHookHitThrottled("feedComponentPass", hookMethod) { "component=${componentClass.name}" }
                return chain.proceed()
            }
            logHookHitThrottled("feedComponentBlock", hookMethod) { inspector().describe(edge) }
            return null
        }
    }

    // ------------------------------------------------------------------
    // List filtering helpers
    // ------------------------------------------------------------------

    /** Splits a feed list into (kept items, sponsored-removed count). */
    private fun partitionSponsored(items: Iterable<*>): Pair<List<Any?>, Int> {
        val kept = ArrayList<Any?>()
        var removed = 0
        for (item in items) {
            if (inspector().isDefinitelySponsoredFeedItem(item)) removed++ else kept.add(item)
        }
        return kept to removed
    }

    /**
     * ImmutableList.copyOf through any classloader that can see the host's
     * Guava — the sample's own loader can be the bootstrap one (e.g. a
     * Collections singleton), hence the app-classloader hint plus a fallback
     * on the first kept element.
     */
    private fun buildImmutableListLike(sample: Any?, items: List<Any?>): Any? {
        if (sample == null) return null
        val loaders = sequenceOf(
            appClassLoader,
            sample.javaClass.classLoader,
            items.firstOrNull { it != null }?.javaClass?.classLoader,
        ).filterNotNull().distinct()
        for (loader in loaders) {
            val rebuilt = runCatching {
                val immutableListClass = Class.forName(IMMUTABLE_LIST_CLASS, false, loader)
                val copyOf = immutableListClass.getDeclaredMethod("copyOf", Iterable::class.java)
                copyOf.invoke(null, items)
            }.getOrNull()
            if (rebuilt != null) return rebuilt
        }
        return null
    }

    /** The result itself when it is a list, else its first Iterable field. */
    private fun extractFeedItemsFromResult(result: Any?): Iterable<*>? {
        if (result == null) return null
        if (result is Iterable<*>) return result
        return runCatching {
            val field = result.javaClass.declaredFields.firstOrNull { candidate ->
                Iterable::class.java.isAssignableFrom(candidate.type)
            } ?: return null
            field.isAccessible = true
            field.get(result) as? Iterable<*>
        }.getOrNull()
    }

    /**
     * Rebuilds a filter result: (ImmutableList, IntArray, int, int, int)
     * constructor, stats array cloned, surviving list swapped. Returns null
     * when the shape doesn't match — the original result is kept then.
     */
    private fun rebuildFeedResult(result: Any, items: List<Any?>): Any? {
        val type = result.javaClass
        val fields = runCatching {
            type.declaredFields.onEach { it.isAccessible = true }
        }.getOrNull() ?: return null

        val listField = fields.firstOrNull { candidate ->
            !Modifier.isStatic(candidate.modifiers) &&
                Iterable::class.java.isAssignableFrom(candidate.type)
        } ?: return null

        val intArrayField = fields.firstOrNull { candidate ->
            !Modifier.isStatic(candidate.modifiers) && candidate.type == IntArray::class.java
        } ?: return null

        val intFields = fields.filter { candidate ->
            !Modifier.isStatic(candidate.modifiers) && candidate.type == Int::class.javaPrimitiveType
        }
        if (intFields.size < 3) return null

        val originalList = runCatching { listField.get(result) }.getOrNull()
        val rebuiltList = buildImmutableListLike(originalList, items) ?: return null
        val stats = runCatching { intArrayField.get(result) as? IntArray }.getOrNull()?.clone() ?: return null
        val ints = intFields.map { field -> runCatching { field.getInt(result) }.getOrNull() ?: return null }

        val constructor = type.declaredConstructors.firstOrNull { constructor ->
            constructor.parameterCount == 5 &&
                constructor.parameterTypes.getOrNull(0)?.name == IMMUTABLE_LIST_CLASS &&
                constructor.parameterTypes.getOrNull(1) == IntArray::class.java &&
                constructor.parameterTypes.drop(2).all { it == Int::class.javaPrimitiveType }
        } ?: return null

        constructor.isAccessible = true
        return runCatching {
            constructor.newInstance(rebuiltList, stats, ints[0], ints[1], ints[2])
        }.getOrNull()
    }

    /** Hot-path logging: first 3 hits per hook, then every 25th. Detail is lazy. */
    private fun logHookHitThrottled(hookName: String, method: Method, detail: (() -> String)? = null) {
        val hits = hookHitCounters.computeIfAbsent(hookName) { AtomicInteger(0) }.incrementAndGet()
        if (hits <= 3 || hits % HOOK_HIT_LOG_EVERY == 0) {
            val extra = detail?.invoke()?.let { " $it" } ?: ""
            L.i(TAG, "Hook hit $hookName count=$hits at ${method.declaringClass.name}.${method.name}$extra")
        }
    }

    // ------------------------------------------------------------------
    // Feed item classifier (old FeedItemInspector, trimmed to the
    // definite-check machinery the three mechanisms use)
    // ------------------------------------------------------------------

    /**
     * Reflection-by-shape sponsored classifier. The category enum moved
     * package and the accessor names rotate per build, so everything is
     * matched structurally: category getters are 0-arg methods returning an
     * enum whose constants include SPONSORED/PROMOTION; children are probed
     * by score (GraphQLFeedUnitEdge > graphql model > com.facebook.*).
     *
     * Constructed with the story-pool item contract types when available;
     * with an empty collection — the component guard's configuration — the
     * contract accessors resolve to null and [edgeFrom] falls back to
     * shape-based child resolution.
     */
    private class FeedItemInspector(itemContractTypes: Collection<Class<*>>) {
        private val itemModelAccessor = resolveItemModelAccessor(itemContractTypes)
        private val itemEdgeAccessor = resolveItemEdgeAccessor(itemContractTypes)
        private val itemNetworkAccessor = resolveItemNetworkAccessor(itemContractTypes)
        private val categoryMethodCache = ConcurrentHashMap<Class<*>, Method>()
        private val edgeAccessorCache = ConcurrentHashMap<Class<*>, Method>()
        private val edgeCategoryAccessorCache = ConcurrentHashMap<Class<*>, Method>()
        private val feedUnitAccessorCache = ConcurrentHashMap<Class<*>, Method>()
        private val backendDataAccessorCache = ConcurrentHashMap<Class<*>, Method>()
        private val typeNameMethodCache = ConcurrentHashMap<Class<*>, Method>()

        private data class FeedItemFacts(
            val modelCategory: String?,
            val edgeCategory: String?,
            val network: Boolean?,
            val inflatedUnitClass: String?,
            val inflatedTypeName: String?,
            val backendUnitClass: String?,
            val backendTypeName: String?,
        )

        /**
         * The strict check the feed guard filters on: model/edge category
         * (with safe-container categories exempting the item), then the
         * inflated unit's class and type name. Never true for organic
         * content — that's what separates it from the heuristic signal scan
         * the old port used for the story pool.
         */
        fun isDefinitelySponsoredFeedItem(value: Any?): Boolean {
            if (value == null) return false

            val model = invokeNoThrow(itemModelAccessor, value)
            val modelCategory = readCategory(model)
            if (isSafeFeedContainerCategory(modelCategory)) {
                return false
            }
            if (isSponsoredFeedCategory(modelCategory)) {
                return true
            }

            val edge = edgeFrom(value)
            val edgeCategory = readEdgeCategory(edge) ?: readCategory(edge)
            if (isSafeFeedContainerCategory(edgeCategory)) {
                return false
            }
            if (isSponsoredFeedCategory(edgeCategory)) {
                return true
            }

            val feedUnit = feedUnitFrom(edge)
            val backendData = backendDataFrom(edge)
            val inflatedUnitClassName = feedUnit?.javaClass?.name
            val backendUnitClassName = backendData?.javaClass?.name
            if (
                inflatedUnitClassName == GRAPHQL_MULTI_ADS_FEED_UNIT_CLASS ||
                inflatedUnitClassName == GRAPHQL_QUICK_PROMO_FEED_UNIT_CLASS
            ) {
                return true
            }

            val typeName = readTypeName(feedUnit) ?: readTypeName(backendData)
            if (
                isLikelyAdTypeName(typeName) ||
                isAdSignalText(inflatedUnitClassName) ||
                isAdSignalText(backendUnitClassName)
            ) {
                return true
            }

            return false
        }

        /** One-line classification used by the throttled guard logs. */
        fun describe(item: Any?): String {
            if (item == null) return "null"

            val facts = factsFor(item)
            val modelCategory = facts.modelCategory ?: "unknown"
            val edgeCategory = facts.edgeCategory ?: "unknown"
            val network = facts.network?.toString() ?: "unknown"
            val inflatedUnitClass = facts.inflatedUnitClass ?: "null"
            val inflatedTypeName = facts.inflatedTypeName ?: "unknown"
            val backendUnitClass = facts.backendUnitClass ?: "null"
            val backendTypeName = facts.backendTypeName ?: "unknown"

            return "modelCat=$modelCategory edgeCat=$edgeCategory " +
                "isAdDef=${isDefinitelySponsoredFeedItem(item)} network=$network " +
                "wrapper=${item.javaClass.name} inflated=$inflatedUnitClass/$inflatedTypeName " +
                "backend=$backendUnitClass/$backendTypeName"
        }

        private fun factsFor(item: Any?): FeedItemFacts {
            val model = invokeNoThrow(itemModelAccessor, item)
            val edge = edgeFrom(item)
            val feedUnit = feedUnitFrom(edge)
            val backendData = backendDataFrom(edge)
            return FeedItemFacts(
                modelCategory = readCategory(model),
                edgeCategory = readEdgeCategory(edge) ?: readCategory(edge),
                network = invokeNoThrow(itemNetworkAccessor, item) as? Boolean,
                inflatedUnitClass = feedUnit?.javaClass?.name,
                inflatedTypeName = readTypeName(feedUnit),
                backendUnitClass = backendData?.javaClass?.name,
                backendTypeName = readTypeName(backendData),
            )
        }

        private fun edgeFrom(value: Any?): Any? {
            if (value == null) return null
            if (value.javaClass.name == GRAPHQL_FEED_UNIT_EDGE_CLASS) return value

            invokeNoThrow(itemEdgeAccessor, value)?.let { directEdge ->
                if (directEdge.javaClass.name == GRAPHQL_FEED_UNIT_EDGE_CLASS) {
                    return directEdge
                }
            }

            val fallback = cachedMethod(edgeAccessorCache, value.javaClass) {
                resolveChildAccessor(value) { candidateValue ->
                    candidateValue != null &&
                        candidateValue.javaClass.name == GRAPHQL_FEED_UNIT_EDGE_CLASS
                }
            }
            return invokeNoThrow(fallback, value)
        }

        private fun feedUnitFrom(edge: Any?): Any? {
            if (edge == null) return null

            val accessor = cachedMethod(feedUnitAccessorCache, edge.javaClass) {
                resolveChildAccessor(edge) { candidateValue ->
                    val className = candidateValue?.javaClass?.name
                    className == GRAPHQL_MULTI_ADS_FEED_UNIT_CLASS ||
                        className == GRAPHQL_QUICK_PROMO_FEED_UNIT_CLASS ||
                        readTypeName(candidateValue)
                            ?.let { it != "FeedUnitEdge" && it != "FeedBackendData" } == true
                }
            }
            return invokeNoThrow(accessor, edge)
        }

        private fun backendDataFrom(edge: Any?): Any? {
            if (edge == null) return null

            val accessor = cachedMethod(backendDataAccessorCache, edge.javaClass) {
                resolveChildAccessor(edge) { candidateValue ->
                    readTypeName(candidateValue) == "FeedBackendData"
                }
            }
            return invokeNoThrow(accessor, edge)
        }

        private fun readEdgeCategory(value: Any?): String? {
            if (value == null) return null

            val accessor = cachedMethod(edgeCategoryAccessorCache, value.javaClass) {
                findCategoryAccessor(value.javaClass)
            }
            return invokeNoThrow(accessor, value)?.toString()
        }

        private fun resolveNamedNoArgAccessor(type: Class<*>, methodName: String): Method? {
            return allInstanceMethods(type).firstOrNull { candidate ->
                candidate.parameterCount == 0 && candidate.name == methodName
            }?.apply { isAccessible = true }
        }

        private fun readCategory(value: Any?): String? {
            if (value == null) return null

            if (value.javaClass.isEnum) {
                return value.toString()
            }

            val accessor = cachedMethod(categoryMethodCache, value.javaClass) {
                findCategoryAccessor(value.javaClass)
            }
            return invokeNoThrow(accessor, value)?.toString()
        }

        /** 0-arg method returning an enum whose constants include SPONSORED/PROMOTION. */
        private fun findCategoryAccessor(type: Class<*>): Method? {
            return allInstanceMethods(type).firstOrNull { candidate ->
                candidate.parameterCount == 0 &&
                    candidate.returnType.isEnum &&
                    candidate.returnType.enumConstants?.any {
                        val name = it.toString()
                        name == "SPONSORED" || name == "PROMOTION"
                    } == true
            }?.apply { isAccessible = true }
        }

        private fun readTypeName(value: Any?): String? {
            if (value == null) return null

            val accessor = cachedMethod(typeNameMethodCache, value.javaClass) {
                resolveNamedNoArgAccessor(value.javaClass, "getTypeName")
            }
            return invokeNoThrow(accessor, value) as? String
        }

        private fun cachedMethod(
            cache: ConcurrentHashMap<Class<*>, Method>,
            type: Class<*>,
            resolver: () -> Method?,
        ): Method? {
            cache[type]?.let { return it }
            val resolved = resolver() ?: return null
            return cache.putIfAbsent(type, resolved) ?: resolved
        }

        private fun resolveItemModelAccessor(itemContractTypes: Collection<Class<*>>): Method? {
            return itemContractTypes
                .asSequence()
                .flatMap { type -> allInstanceMethods(type).asSequence() }
                .firstOrNull { candidate ->
                    candidate.parameterCount == 0 &&
                        candidate.name != "clone" &&
                        !candidate.returnType.isPrimitive &&
                        candidate.returnType != Any::class.java &&
                        candidate.returnType != String::class.java &&
                        !candidate.returnType.isEnum
                }?.apply { isAccessible = true }
        }

        private fun resolveItemEdgeAccessor(itemContractTypes: Collection<Class<*>>): Method? {
            return itemContractTypes
                .asSequence()
                .flatMap { type -> allInstanceMethods(type).asSequence() }
                .firstOrNull { candidate ->
                    candidate.parameterCount == 0 &&
                        candidate.name != "clone" &&
                        (candidate.returnType == Any::class.java ||
                            candidate.returnType.name == GRAPHQL_FEED_UNIT_EDGE_CLASS)
                }?.apply { isAccessible = true }
        }

        private fun resolveItemNetworkAccessor(itemContractTypes: Collection<Class<*>>): Method? {
            return itemContractTypes
                .asSequence()
                .flatMap { type -> allInstanceMethods(type).asSequence() }
                .firstOrNull { candidate ->
                    candidate.parameterCount == 0 &&
                        candidate.returnType == Boolean::class.javaPrimitiveType
                }?.apply { isAccessible = true }
        }

        /**
         * Probes 0-arg object-returning methods, best-scored type first, and
         * returns the first whose invocation [acceptsValue] — each accessor
         * is resolved once per class and cached.
         */
        private fun resolveChildAccessor(target: Any, acceptsValue: (Any?) -> Boolean): Method? {
            return allInstanceMethods(target.javaClass)
                .asSequence()
                .filter { candidate ->
                    candidate.parameterCount == 0 &&
                        !candidate.returnType.isPrimitive &&
                        candidate.returnType != Void.TYPE &&
                        candidate.returnType != String::class.java &&
                        !candidate.returnType.isEnum &&
                        candidate.declaringClass != Any::class.java
                }
                .sortedByDescending { candidate -> scoreChildAccessor(candidate.returnType) }
                .firstOrNull { candidate ->
                    acceptsValue(invokeNoThrow(candidate.apply { isAccessible = true }, target))
                }
        }

        private fun scoreChildAccessor(type: Class<*>): Int {
            return when {
                type.name == GRAPHQL_FEED_UNIT_EDGE_CLASS -> 4
                type.name.startsWith("com.facebook.graphql.model.") -> 3
                type.name.startsWith("com.facebook.") -> 2
                !type.name.startsWith("java.") &&
                    !type.name.startsWith("javax.") &&
                    !type.name.startsWith("android.") &&
                    !type.name.startsWith("kotlin.") -> 1
                else -> 0
            }
        }

        private fun isSponsoredFeedCategory(value: String?): Boolean {
            return value != null && value in FEED_AD_CATEGORY_VALUES
        }

        private fun isSafeFeedContainerCategory(value: String?): Boolean {
            return value != null && value in FEED_SAFE_CONTAINER_CATEGORY_VALUES
        }

        private fun isLikelyAdTypeName(value: String?): Boolean {
            if (value == null) return false
            if (value.contains("QuickPromotion", ignoreCase = true)) return true
            return isAdSignalText(value)
        }

        private fun isAdSignalText(value: String?): Boolean {
            if (value.isNullOrBlank()) return false
            val normalized = value.lowercase()
            return FEED_AD_SIGNAL_TOKENS.any { token -> normalized.contains(token) }
        }

        /** Declared instance methods up the hierarchy plus interfaces, deduped by name+arity. */
        private fun allInstanceMethods(type: Class<*>): List<Method> {
            val methods = LinkedHashMap<String, Method>()
            var current: Class<*>? = type
            while (current != null && current != Any::class.java) {
                val owner = current
                owner.declaredMethods.forEach { method ->
                    if (!Modifier.isStatic(method.modifiers)) {
                        method.isAccessible = true
                        methods.putIfAbsent("${owner.name}#${method.name}/${method.parameterCount}", method)
                    }
                }
                owner.interfaces.forEach { iface ->
                    iface.declaredMethods.forEach { method ->
                        if (!Modifier.isStatic(method.modifiers)) {
                            method.isAccessible = true
                            methods.putIfAbsent("${iface.name}#${method.name}/${method.parameterCount}", method)
                        }
                    }
                }
                current = owner.superclass
            }
            return methods.values.toList()
        }

        private fun invokeNoThrow(method: Method?, target: Any?): Any? {
            if (method == null || target == null) return null
            return runCatching { method.invoke(target) }.getOrNull()
        }
    }
}
