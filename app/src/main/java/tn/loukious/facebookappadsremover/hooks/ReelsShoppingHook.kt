package tn.loukious.facebookappadsremover.hooks

import android.content.Context
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import org.luckypray.dexkit.DexKitBridge
import tn.loukious.facebookappadsremover.core.L
import tn.loukious.facebookappadsremover.core.MethodCache
import tn.loukious.facebookappadsremover.core.Settings
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Reels shopping-card removal — port of our own pre-rewrite guard
 * (Patches.kt, installReelsViewerAdRenderBlock's shoppable section).
 *
 * The small "Shop now" banner overlaying a promotional reel is drawn by
 * shoppable product-card components. Unlike the ad-classified reels
 * renderables, these hold a shopping product payload rather than an
 * ad-model, so every render of them is a shopping card — the render is
 * blocked unconditionally.
 *
 * Discovery, exactly like the old code:
 *
 *  1. ANCHOR PASS — the three stable component-name strings
 *     ("FbShortsShoppableProductItemComponent" & co.) bootstrap discovery.
 *
 *  2. STRUCTURAL PASS — the anchors' shared non-framework field type is the
 *     shopping payload holder; every renderable holding a field of that
 *     type is then caught by a DexKit field-type + render-method match,
 *     covering the variants without string anchors (banner card,
 *     marketplace card, hscroll items...).
 *
 * The old reels-shopping STICKER hider (view-tree pill scan by composite
 * accessibility label) is intentionally not ported: it was a fallback for
 * tagged-product stickers on organic reels, a different surface than the
 * promotional cards blocked here.
 */
object ReelsShoppingHook {

    private const val TAG = "FBAR.ReelsShopping"

    /** MethodCache class-map key when persisting through the shared cache. */
    const val CACHE_KEY = "reels.shopping.classes"

    /** Role prefixes in the cached/returned class list. */
    private const val ROLE_ANCHOR = "anchor:"
    private const val ROLE_STRUCT = "struct:"

    /** Litho component-name anchors (old code: shoppingAnchors). */
    private val SHOPPING_ANCHORS = listOf(
        "FbShortsShoppableProductItemComponent",
        "FbShortsShoppableAdsItemComponent",
        "FbShortsShoppableMarketplaceCardComponent",
    )

    /**
     * Dedup registry — the early cached install and the full DexKit pass
     * resolve the same render methods; hooking twice would run the skip
     * logic twice per render (harmless but wasteful).
     */
    private val hookedRenders: MutableSet<Method> = ConcurrentHashMap.newKeySet()

    /**
     * Sub-switch (mod: the shopping block rode the same swHOME_ADS master
     * as the rest of the reels guard). Re-read per invocation so flipping
     * the toggle applies without a reinstall.
     */
    private fun enabled(): Boolean =
        Settings.getBoolean(Settings.ADS_ENABLED, true) &&
            Settings.getBoolean(Settings.ADS_REELS_SHOPPING, true)

    // ------------------------------------------------------------------
    // Full scan (DexKit available)
    // ------------------------------------------------------------------

    /**
     * Runs the anchor pass and the structural payload pass. Returns the
     * discovered class names, role-prefixed ("anchor:X", "struct:Y"), for
     * the caller to persist.
     */
    fun install(
        module: XposedInterface,
        bridge: DexKitBridge,
        classLoader: ClassLoader,
    ): List<String> {
        val found = mutableListOf<String>()

        // Anchor pass: hook every anchored renderable directly.
        val anchorClasses = mutableListOf<Class<*>>()
        for (anchor in SHOPPING_ANCHORS) {
            runCatching {
                val matches = bridge.findClass { matcher { usingStrings(anchor) } }
                for (candidate in matches) {
                    val clazz = runCatching { candidate.getInstance(classLoader) }.getOrNull()
                        ?: continue
                    anchorClasses.add(clazz)
                    if (hookRenderable(module, clazz)) {
                        found.add(ROLE_ANCHOR + clazz.name)
                    }
                }
                L.i(TAG, "anchor pass $anchor: ${matches.size} class(es)")
            }.onFailure { L.w(TAG, "anchor pass failed: $anchor", it) }
        }

        // Structural pass: derive the shopping payload type from the anchors
        // (the non-framework field type shared by most of them) and catch
        // every renderable holding a field of that type.
        val payloadType = resolveShoppingPayloadType(anchorClasses)
        if (payloadType != null) {
            runCatching {
                val matches = bridge.findClass {
                    matcher {
                        addFieldForType(payloadType)
                        addMethod { name("render") }
                    }
                }
                for (candidate in matches) {
                    val clazz = runCatching { candidate.getInstance(classLoader) }.getOrNull()
                        ?: continue
                    if (hookRenderable(module, clazz)) {
                        found.add(ROLE_STRUCT + clazz.name)
                    }
                }
                L.i(TAG, "structural pass for ${payloadType.name}: ${matches.size} renderable(s)")
            }.onFailure { L.w(TAG, "structural pass failed", it) }
        } else {
            L.w(TAG, "shopping payload type not resolved from ${anchorClasses.size} anchor(s)")
        }

        L.i(TAG, "full scan: ${found.size} cacheable class(es)")
        return found
    }

    /**
     * Derives the shoppable product payload class from the anchor
     * components: the non-framework field type shared by most anchored
     * renderables (their product payload holder). Anchors may individually
     * lack the field, so any type shared by at least two wins (majority).
     */
    private fun resolveShoppingPayloadType(anchorClasses: List<Class<*>>): Class<*>? {
        val counts = HashMap<Class<*>, Int>()
        for (clazz in anchorClasses) {
            clazz.declaredFields
                .filter { !Modifier.isStatic(it.modifiers) }
                .forEach { field -> counts.merge(field.type, 1, Int::plus) }
        }
        return counts.entries
            .filter { (type, count) ->
                count >= 2 && !type.name.startsWith("java.") && !type.isPrimitive
            }
            .maxByOrNull { it.value }?.key
    }

    // ------------------------------------------------------------------
    // Cache-hit entry (no DexKit)
    // ------------------------------------------------------------------

    /**
     * Re-hooks from previously discovered class names. Idempotent (the
     * [hookedRenders] registry swallows the overlap with a concurrent full
     * scan), fails softly while the secondary dexes are not yet attached.
     *
     * @param cached role-prefixed entries as returned by [install]
     * @return true when at least one render was hooked
     */
    fun installCached(
        module: XposedInterface,
        classLoader: ClassLoader,
        cached: Set<String>,
    ): Boolean {
        if (cached.isEmpty()) return false
        var hooked = 0
        for (entry in cached) {
            runCatching {
                val clazz = Class.forName(entry.substringAfter(':'), false, classLoader)
                if (hookRenderable(module, clazz)) hooked++
            }.onFailure { L.w(TAG, "cached install failed for $entry", it) }
        }
        L.i(TAG, "cached install: $hooked renderable(s) from ${cached.size} class(es)")
        return hooked > 0
    }

    // ------------------------------------------------------------------
    // Hooking
    // ------------------------------------------------------------------

    /**
     * Hooks the non-static zero-ceremony render entry point on [clazz]:
     * the render is skipped outright (old hookReelsShoppingRenderable) —
     * every render of these components is a shopping card.
     *
     * @return true when a new render method was hooked on this class.
     */
    private fun hookRenderable(module: XposedInterface, clazz: Class<*>): Boolean {
        val render = clazz.declaredMethods.firstOrNull {
            it.name == "render" && !Modifier.isStatic(it.modifiers)
        } ?: return false
        if (!hookedRenders.add(render)) return false
        runCatching {
            render.isAccessible = true
            module.hook(render).intercept(RenderSkipHook)
        }.onFailure {
            L.w(TAG, "render hook failed: ${clazz.name}.render", it)
            return false
        }
        L.i(TAG, "shopping card block: ${clazz.name}.render")
        return true
    }

    /** Skips the render entirely — the component exists solely to draw the shopping card. */
    private object RenderSkipHook : Hooker {
        private var logged = 0

        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!enabled()) return chain.proceed()
            if (logged < 8) {
                logged++
                L.i(TAG, "shopping card block fired: ${chain.executable.declaringClass.name}")
            }
            return null
        }
    }

    // ------------------------------------------------------------------
    // ModuleMain wiring (shared MethodCache class-map channel)
    // ------------------------------------------------------------------

    /**
     * True when the shared discovery cache already carries this hook's
     * class-map entry — a cache written before this port must force a
     * rescan (checked from ModuleMain.installFromCache).
     */
    fun cachePresent(context: Context): Boolean =
        runCatching { MethodCache.loadClass(context, CACHE_KEY) }.getOrNull() != null

    /**
     * Cache-hit install through the shared class map: reads the stored
     * role-prefixed CSV and delegates to [installCached]. No-op on a miss;
     * the "absent" sentinel means the anchors found nothing on this build.
     */
    fun installFromCache(module: XposedInterface, classLoader: ClassLoader, context: Context) {
        val state = runCatching { MethodCache.loadClass(context, CACHE_KEY) }.getOrNull()
        when {
            state == null -> return
            state == "absent" -> L.i(TAG, "anchor not found on this build — skipping")
            else -> runCatching {
                installCached(module, classLoader, state.split(',').toSet())
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
        runCatching { install(module, bridge, classLoader) }
            .onFailure { L.e(TAG, "installation failed", it) }
            .getOrDefault(emptyList())
            .takeIf { it.isNotEmpty() }?.joinToString(",") ?: "absent"
}
