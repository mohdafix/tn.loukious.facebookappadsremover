package tn.loukious.facebookappadsremover.hooks

import android.content.Context
import tn.loukious.facebookappadsremover.core.L
import tn.loukious.facebookappadsremover.core.Settings
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * M2.5 newsfeed content filter — port of the original mod's content-filter
 * installer (libnc.so, EQRQYtm1nsiKbj6wysU, .c 118019–118734).
 *
 * The mod hooked the processNewStories Runnable (its build: X.2Qj.run;
 * 576.0.0.42.73: X.2rJ = FeedUnitCollectionManager$processNewStories$…$1).
 * The Runnable's run() opens with:
 *
 *     C2WY c2wy = this.A01;
 *     ImmutableCollection immutableCollection = c2wy.A05;   // new-story edges
 *
 * The mod's beforeHookedMethod filtered that collection (dropping edges by
 * GraphQLFeedStoryCategory) and wrote the survivor list back via
 * ImmutableList.copyOf, so run() only ever saw the filtered set.
 *
 * The mod's category→toggle map was recovered from the decrypted string
 * order inside the native callback — each block is [category compare →
 * toggle read → counter write], interleaved as:
 *
 *   SPONSORED            ← swHOME_ADS              ("Remove 'Sponsored' posts")
 *   PROMOTION            ← swHOME_THREADS          ("Remove posts from Threads")
 *   FB_SHORTS            ← swHOME_REELS            ("Remove Reels")
 *   ENGAGEMENT           ← swHOME_GOIY             ("Remove 'Suggested for you'")
 *   ENGAGEMENT_QP        ← swNhungNguoiBanCoTheBiet ("Remove friend suggestions")
 *   MULTI_FB_STORIES_TRAY← Story24hInNewsFeed      ("Remove Stories in feed")
 *
 * Nothing here references an obfuscated name: the Runnable is found by its
 * unique literal "Added stories to FUC", the edge model and the category enum
 * keep their real names (com.facebook.graphql.model.GraphQLFeedUnitEdge /
 * com.crossapp.graphql.facebook.enums.GraphQLFeedStoryCategory), and the
 * category getter is resolved by return type — the same anchors survive every
 * release.
 */
object NewsfeedFilterHook {

    private const val TAG = "FBAR.Feed"

    // Toggle keys live in core.Settings (defaults mirror the mod's ship
    // state: sponsored removal on, the content-type filters opt-in). The
    // SPONSORED category rides the master ads toggle — one swHOME_ADS in
    // the mod, one ads.enabled switch here.

    private const val EDGE_CLASS = "com.facebook.graphql.model.GraphQLFeedUnitEdge"
    private const val CATEGORY_ENUM = "com.crossapp.graphql.facebook.enums.GraphQLFeedStoryCategory"
    private const val IMMUTABLE_COLLECTION = "com.google.common.collect.ImmutableCollection"
    private const val IMMUTABLE_LIST = "com.google.common.collect.ImmutableList"

    /** Anchor literal — unique in the secondary dex, lives in the Runnable itself. */
    private const val RUNNABLE_ANCHOR = "Added stories to FUC"

    @Volatile private var appClassLoader: ClassLoader? = null

    /** Category enum name → toggle key (the mod's map, see class doc). */
    private val categoryPrefs = mapOf(
        "SPONSORED" to Settings.ADS_ENABLED,
        "PROMOTION" to Settings.FEED_THREADS,
        "FB_SHORTS" to Settings.FEED_REELS,
        "ENGAGEMENT" to Settings.FEED_SUGGESTIONS,
        "ENGAGEMENT_QP" to Settings.FEED_PYMK,
        "MULTI_FB_STORIES_TRAY" to Settings.FEED_STORIES,
    )

    /** Resolved-once reflection members (the mod cached its field lookups too). */
    private var edgeClass: Class<*>? = null
    private var categoryClass: Class<*>? = null
    private var copyOf: Method? = null
    private val categoryGetterCache = ConcurrentHashMap<Class<*>, Method>()
    private val collectionFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val categoryGetterMisses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val collectionFieldMisses = ConcurrentHashMap.newKeySet<Class<*>>()

    fun init(context: Context) {
        appClassLoader = context.classLoader
        val on = enabledCategories()
        val ai = Settings.getBoolean(Settings.FEED_AI_CONTENT, false)
        val kw = keywordList()
        L.i(TAG, "Newsfeed filter categories: ${if (on.isEmpty()) "(none)" else on.joinToString()}" +
                ", aiContent=$ai, keywords=${if (kw.isEmpty()) "(none)" else kw.size}")
    }

    private fun enabledCategories(): Set<String> =
        // Mod ship state: only sponsored removal ON; the content-type filters
        // (threads/reels/suggestions/PYMK) are opt-in.
        categoryPrefs.filterValues { Settings.getBoolean(it, it == Settings.ADS_ENABLED) }.keys

    /** The keyword list, split on commas/semicolons/newlines, lowercased. */
    private fun keywordList(): List<String> =
        Settings.getString(Settings.FEED_KEYWORDS, "")
            .split(',', ';', '\n')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

    /** True when the keyword filter is switched on with at least one keyword. */
    private fun keywordsEnabled(): Boolean =
        Settings.getBoolean(Settings.FEED_KEYWORDS_ENABLED, false) && keywordList().isNotEmpty()

    /**
     * Finds the processNewStories Runnable class via DexKit and hooks its
     * run() — the exact hook the mod installed (X.2Qj.run, beforeHookedMethod).
     *
     * @return the Runnable class name when found, for the discovery cache.
     */
    fun install(module: XposedInterface, bridge: DexKitBridge, classLoader: ClassLoader): String? {
        val hits = runCatching {
            bridge.findClass {
                matcher { addUsingString(RUNNABLE_ANCHOR, StringMatchType.Equals) }
            }
        }.getOrElse {
            L.w(TAG, "DexKit query failed for newsfeed filter", it)
            return null
        }
        val className = hits.firstOrNull()?.name
        if (className == null) {
            L.w(TAG, "NOT_FOUND newsfeed processNewStories Runnable (anchor: $RUNNABLE_ANCHOR)")
            return null
        }
        return if (hookRunnable(module, classLoader, className)) className else null
    }

    /** Cache-hit path: hook the previously discovered Runnable directly. */
    fun installCached(module: XposedInterface, classLoader: ClassLoader, className: String): Boolean =
        hookRunnable(module, classLoader, className)

    private fun hookRunnable(module: XposedInterface, classLoader: ClassLoader, className: String): Boolean {
        val cls = runCatching { Class.forName(className, false, classLoader) }.getOrNull()
        if (cls == null) {
            L.w(TAG, "class resolve failed: $className")
            return false
        }
        val run = runCatching { cls.getDeclaredMethod("run") }.getOrNull()
        if (run == null) {
            L.w(TAG, "no run() on $className — not the Runnable?")
            return false
        }
        runCatching {
            module.hook(run).intercept(FilterHook)
            L.i(TAG, "hooked processNewStories Runnable: $className.run()")
        }.onFailure {
            L.w(TAG, "hook failed on $className.run()", it)
            return false
        }

        return true
    }

    /**
     * beforeHookedMethod port: filter this.A01.A05 (the new-story
     * ImmutableCollection) by story category, write back ImmutableList.copyOf.
     */
    private object FilterHook : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            runCatching { filterCollection(chain.thisObject) }
                .onFailure { L.w(TAG, "feed filter pass failed", it) }
            return chain.proceed()
        }
    }

    /** @return true if anything was removed. */
    private fun filterCollection(runnable: Any?): Boolean {
        val enabled = enabledCategories()
        val aiOn = Settings.getBoolean(Settings.FEED_AI_CONTENT, false)
        val keywords = if (keywordsEnabled()) keywordList() else emptyList()
        if (enabled.isEmpty() && !aiOn && keywords.isEmpty()) return false

        val holderField = findHolderField(runnable ?: return false) ?: return false
        val holder = runCatching { holderField.get(runnable) }.getOrNull() ?: return false
        val collectionField = findCollectionField(holder) ?: return false
        val collection = runCatching { collectionField.get(holder) }.getOrNull() ?: return false
        val elements = (collection as? Iterable<*>)?.toList() ?: return false
        if (elements.isEmpty()) return false

        val kept = ArrayList<Any>(elements.size)
        val removed = HashMap<String, Int>()
        for (e in elements) {
            if (e == null) continue
            val category = categoryOf(e)
            when {
                category != null && category in enabled ->
                    removed[category] = (removed[category] ?: 0) + 1
                aiOn && AiCheck.isAiContent(e) ->
                    removed["AI_CONTENT"] = (removed["AI_CONTENT"] ?: 0) + 1
                keywords.isNotEmpty() && matchesKeywords(e, keywords) ->
                    removed["KEYWORD"] = (removed["KEYWORD"] ?: 0) + 1
                else -> kept.add(e)
            }
        }
        if (removed.isEmpty()) return false

        val copy = copyOfMethod()?.let { m ->
            runCatching { m.invoke(null, kept as Iterable<*>) }.getOrNull()
        }
        if (copy != null) {
            runCatching { collectionField.set(holder, copy) }.onFailure {
                L.w(TAG, "write-back failed; keeping original collection", it)
                return false
            }
        } else {
            return false
        }
        L.i(TAG, "removed ${removed.values.sum()}/${elements.size} row(s): " +
                removed.entries.joinToString { "${it.key}=${it.value}" })
        return true
    }

    /**
     * Keyword match: the TreeJNI toString() dump of the edge carries the whole
     * story tree (message text, attachment titles…), so a case-insensitive
     * contains() over it is obfuscation-proof. Only runs for rows that passed
     * the category filters, and only when keywords are configured.
     */
    private fun matchesKeywords(edge: Any, keywords: List<String>): Boolean {
        val text = runCatching { edge.toString().lowercase() }.getOrNull() ?: return false
        return keywords.any { text.contains(it) }
    }

    /**
     * The Runnable holds its owner state in a synthetic field (this.A01 → the
     * collection holder). Names drift per release, so locate it structurally:
     * the field whose value declares an ImmutableCollection field.
     */
    private fun findHolderField(runnable: Any): Field? {
        var cls: Class<*>? = runnable.javaClass
        while (cls != null) {
            for (f in cls.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                val v = runCatching { f.get(runnable) }.getOrNull() ?: continue
                if (findCollectionField(v) != null) return f
            }
            cls = cls.superclass
        }
        return null
    }

    /** The ImmutableCollection-typed field on the holder (the mod's A05). */
    private fun findCollectionField(holder: Any): Field? =
        collectionFieldFor(holder.javaClass)?.also { it.isAccessible = true }

    private fun collectionFieldFor(cls: Class<*>): Field? {
        // ConcurrentHashMap can't hold nulls — misses tracked separately.
        collectionFieldCache[cls]?.let { return it }
        if (cls in collectionFieldMisses) return null
        var c: Class<*>? = cls
        var result: Field? = null
        val collType = runCatching {
            Class.forName(IMMUTABLE_COLLECTION, false, appClassLoader)
        }.getOrNull()
        while (c != null && result == null) {
            // ImmutableList and friends extend ImmutableCollection — declared
            // type may be any subtype (the mod's A05 was ImmutableList).
            result = c.declaredFields.firstOrNull {
                collType != null && collType.isAssignableFrom(it.type) &&
                    !java.lang.reflect.Modifier.isStatic(it.modifiers)
            }
            c = c.superclass
        }
        if (result != null) collectionFieldCache[cls] = result
        else collectionFieldMisses.add(cls)
        return result
    }

    /** GraphQLFeedUnitEdge → its GraphQLFeedStoryCategory enum name, or null. */
    private fun categoryOf(edge: Any?): String? {
        if (edge == null) return null
        val ec = edgeClass ?: runCatching {
            Class.forName(EDGE_CLASS, false, appClassLoader).also { edgeClass = it }
        }.getOrNull() ?: return null
        if (!ec.isInstance(edge)) return null
        var getter = categoryGetterCache[ec]
        if (getter == null && ec !in categoryGetterMisses) {
            getter = resolveCategoryGetter(ec)
            if (getter != null) categoryGetterCache[ec] = getter
            else categoryGetterMisses.add(ec)
        }
        if (getter == null) return null
        val category = runCatching { getter.invoke(edge) }.getOrNull() ?: return null
        return (category as? Enum<*>)?.name
    }

    /** The getter whose return type IS the category enum (B9B in this build). */
    private fun resolveCategoryGetter(edgeCls: Class<*>): Method? {
        val cat = categoryClass ?: runCatching {
            Class.forName(CATEGORY_ENUM, false, appClassLoader).also { categoryClass = it }
        }.getOrNull() ?: return null
        var c: Class<*>? = edgeCls
        while (c != null) {
            c.declaredMethods.firstOrNull { it.returnType == cat && it.parameterCount == 0 }?.let {
                return it.also { it.isAccessible = true }
            }
            c = c.superclass
        }
        return null
    }

    private fun copyOfMethod(): Method? {
        copyOf?.let { return it }
        val m = runCatching {
            val list = Class.forName(IMMUTABLE_LIST, false, appClassLoader)
            list.getDeclaredMethod("copyOf", Iterable::class.java).also { it.isAccessible = true }
        }.getOrNull()
        copyOf = m
        return m
    }

    /**
     * AI-content marker check — same TreeJNI technique as AdFilterHook's
     * sponsored_data test.
     *
     * The flag hash is -1133610173 = String.hashCode("was_self_disclosed_as_
     * ai_generated"), a field of the gen-AI transparency model (C717744h in
     * 576.0.0.42.73, "renamed from: X.44h"). The verified consumer pattern
     * (C5IO.java:202-204):
     *
     *     C717744h m = graphQLStory.A0Y();
     *     if (m != null) z = m.getCachedBoolean(-1133610173);
     *
     * Names drift per release, so the model is reached structurally: from the
     * feed edge, follow no-arg getters whose return type is a TreeJNI model
     * (its hierarchy declares hasFieldValue(int)); two levels deep covers the
     * known chain edge → story-holder (C44g, four typed getters on the edge)
     * → transparency model. hasFieldValue(hash) is false on models that don't
     * own the field, so probing wrong branches is harmless — the exact
     * property AdFilterHook.SponsoredCheck relies on.
     */
    private object AiCheck {
        /** String.hashCode("was_self_disclosed_as_ai_generated"). */
        private const val AI_HASH = -1133610173

        /** Visited-object cap — the walk is bounded even on surprise shapes. */
        private const val MAX_VISIT = 60

        /** Optional.empty = "checked, no hasFieldValue method" (ConcurrentHashMap holds no nulls). */
        private val hasFieldValueCache = ConcurrentHashMap<Class<*>, java.util.Optional<Method>>()

        /** TreeJNI-model getters per class, resolved once. */
        private val modelGetterCache = ConcurrentHashMap<Class<*>, List<Method>>()

        /**
         * Root class → the getter chain that reached the transparency holder
         * (edge getter → holder getter). Once found, later rows walk only the
         * cached path instead of re-probing every branch.
         */
        private val pathCache = ConcurrentHashMap<Class<*>, List<Method>>()

        fun isAiContent(root: Any?): Boolean {
            if (root == null) return false
            pathCache[root.javaClass]?.let { path ->
                if (runPath(root, path)) return true
                // Path went stale (shape change mid-run?) — fall through to a
                // fresh walk so the cache can be rebuilt.
                pathCache.remove(root.javaClass)
            }
            val path = ArrayList<Method>()
            return walk(root, root.javaClass, path, HashSet())
        }

        private fun runPath(root: Any, path: List<Method>): Boolean {
            var obj: Any = root
            for (m in path) {
                obj = runCatching { m.invoke(obj) }.getOrNull() ?: return false
            }
            return hasFlag(obj)
        }

        /** Depth-first walk over TreeJNI-model getters; records the path on success. */
        private fun walk(obj: Any, rootClass: Class<*>, path: MutableList<Method>, seen: MutableSet<Any>): Boolean {
            if (seen.size > MAX_VISIT || !seen.add(obj)) return false
            if (isSkippable(obj)) return false
            if (hasFlag(obj)) {
                if (path.isNotEmpty()) pathCache[rootClass] = ArrayList(path)
                return true
            }
            if (path.size >= 2) return false // edge → holder → transparency model
            for (m in modelGetters(obj.javaClass)) {
                val v = runCatching { m.invoke(obj) }.getOrNull() ?: continue
                path.add(m)
                if (walk(v, rootClass, path, seen)) return true
                path.removeAt(path.lastIndex)
            }
            return false
        }

        /** hasFieldValue(AI_HASH) == true — set and non-default (true). */
        private fun hasFlag(obj: Any): Boolean {
            val m = hasFieldValueOf(obj.javaClass) ?: return false
            return runCatching { m.invoke(obj, AI_HASH) as? Boolean }.getOrDefault(false) == true
        }

        private fun hasFieldValueOf(cls: Class<*>): Method? {
            hasFieldValueCache[cls]?.let { return it.orElse(null) }
            var c: Class<*>? = cls
            var found: Method? = null
            while (c != null && found == null) {
                val cur: Class<*> = c
                found = runCatching {
                    cur.getDeclaredMethod("hasFieldValue", Int::class.javaPrimitiveType)
                }.getOrNull()
                c = cur.superclass
            }
            hasFieldValueCache[cls] = java.util.Optional.ofNullable(found)
            return found?.also { it.isAccessible = true }
        }

        /** No-arg instance getters returning another TreeJNI model. */
        private fun modelGetters(cls: Class<*>): List<Method> {
            modelGetterCache[cls]?.let { return it }
            val result = ArrayList<Method>()
            var c: Class<*>? = cls
            while (c != null) {
                for (m in c.declaredMethods) {
                    if (m.parameterCount != 0) continue
                    if (java.lang.reflect.Modifier.isStatic(m.modifiers)) continue
                    if (m.isSynthetic || m.isBridge) continue
                    if (m.returnType == cls || !isTreeModel(m.returnType)) continue
                    m.isAccessible = true
                    result.add(m)
                }
                c = c.superclass
            }
            modelGetterCache[cls] = result
            return result
        }

        /** A TreeJNI model: its hierarchy declares hasFieldValue(int). */
        private fun isTreeModel(cls: Class<*>): Boolean =
            !cls.isPrimitive && cls != Void.TYPE &&
                cls.name.let { it.startsWith("com.facebook") || it.startsWith("p000X") } &&
                hasFieldValueOf(cls) != null

        /** Value types we never reflect into. */
        private fun isSkippable(obj: Any): Boolean {
            val n = obj.javaClass.name
            return n.startsWith("java.") || n.startsWith("android.") ||
                n.startsWith("kotlin.") || n.startsWith("com.google.")
        }
    }
}
