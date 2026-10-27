package tn.loukious.facebookappadsremover.hooks

import android.content.Context
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import tn.loukious.facebookappadsremover.core.L
import tn.loukious.facebookappadsremover.core.Settings
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/** Native Facebook download entry points modelled after Morphe's patches. */
object ContextualDownloadHook {

    private const val TAG = "FBAR.ContextDownload"

    const val STORY_CACHE_KEY = "download.context.story"
    const val REEL_CACHE_KEY = "download.context.reel"
    const val STORY_CACHE_VERSION = "native-v3"
    // This schema is independent of the module's versionCode: old negatives
    // must not silently suppress a revised discovery algorithm on an update.
    private const val REEL_ABSENT_PREFIX = "absent-native-v11|"
    private const val REEL_META_PREFIX = "native-v11|"
    private const val REEL_NEGATIVE_RETRY_MS = 72L * 60 * 60 * 1000

    private const val STORY_MORE_MENU =
        "com.facebook.stories.viewer.ui.buckets.regular.topbar.menu.StoryViewerMoreButtonCallback"
    private const val STORY_CARD = "com.facebook.stories.model.StoryCard"
    private const val VIDEO_PLAYER_PARAMS = "com.facebook.video.engine.api.VideoPlayerParams"
    private const val FB_USER_SESSION = "com.facebook.auth.usersession.FbUserSession"
    private const val SAVE_STORY_ATTEMPTED = "save_story_attempted"
    private const val REEL_SIDEBAR = "UDDSideBarComponent"
    private const val DOWNLOAD_ROW = "fds_control_download_video"
    private const val FUNCTION1 = "kotlin.jvm.functions.Function1"
    private const val LABEL = "Download"
    private const val REPOST_LABEL = "Repost"
    private const val TEST_ID = "download_button"
    private const val REPOST_TEST_ID = "repost_button"
    private const val CAPTION = ""
    private const val TAP_SLOT = 1
    private const val REEL_REPOST_ICON_NAME = "RESHARE"
    private const val STORY_REPOST_ICON_NAME = "RESHARE"

    data class Result(
        val storyMethods: List<Method>?,
        val reelMethods: List<Method>?,
        val reelMetadata: String?,
    )

    private data class ReelMetadata(
        val iconOwner: String,
        val iconField: String,
        val iconWrapper: String,
        val playerField: String,
        val contextField: String,
        val factoryClass: String = "",
        val markerClass: String = "",
        val subPlayerField: String = "",
    ) {
        fun encode(): String = REEL_META_PREFIX + listOf(
            iconOwner,
            iconField,
            iconWrapper,
            playerField,
            contextField,
            factoryClass,
            markerClass,
            subPlayerField,
        ).joinToString("|")

        companion object {
            fun decode(raw: String?): ReelMetadata? {
                if (raw == null || !raw.startsWith(REEL_META_PREFIX)) return null
                val parts = raw.removePrefix(REEL_META_PREFIX).split('|')
                if (parts.size < 5 || parts.take(5).any { it.isBlank() }) return null
                return ReelMetadata(
                    iconOwner = parts[0],
                    iconField = parts[1],
                    iconWrapper = parts[2],
                    playerField = parts[3],
                    contextField = parts[4],
                    factoryClass = parts.getOrElse(5) { "" },
                    markerClass = parts.getOrElse(6) { "" },
                    subPlayerField = parts.getOrElse(7) { "" },
                )
            }
        }
    }

    private data class ReelRuntime(
        val builder: Method,
        val factory: Executable,
        val assembly: Method,
        val marker: Executable,
        val iconField: Field,
        val iconWrapper: Class<*>,
        val playerField: Field,
        val contextField: Field,
        val subPlayerField: Field? = null,
    )

    private data class StoryRuntime(
        val builder: Method,
        val contextField: Field,
        val storyField: Field,
        val menuItemCtor: Constructor<*>,
        val handlerType: Class<*>,
        val iconType: Class<*>,
    )

    private enum class ReelAction { DOWNLOAD, REPOST }

    private data class ReelCall(
        val owner: Any,
        val scoped: Any?,
        var factoryTemplate: Array<Any?>? = null,
        var injected: Boolean = false,
        val createdAt: Long = System.currentTimeMillis(),
    )

    private val hookedStoryBuilders: MutableSet<Method> = ConcurrentHashMap.newKeySet()
    private val hookedStoryCapabilities: MutableSet<Method> = ConcurrentHashMap.newKeySet()
    private val hookedStoryHandlers: MutableSet<Method> = ConcurrentHashMap.newKeySet()
    private val hookedReelBuilders: MutableSet<Method> = ConcurrentHashMap.newKeySet()
    private val hookedReelFactories: MutableSet<Executable> = ConcurrentHashMap.newKeySet()
    private val hookedReelAssemblies: MutableSet<Method> = ConcurrentHashMap.newKeySet()

    private val storyMenuDepth = ThreadLocal.withInitial { 0 }
    private val reelCall = ThreadLocal<ReelCall?>()
    @Volatile private var lastReelCall: ReelCall? = null

    @Volatile private var reelRuntime: ReelRuntime? = null
    @Volatile private var storyRuntime: StoryRuntime? = null

    /** Retry unsuccessful discovery periodically even if FB's version is unchanged. */
    fun reelCacheNeedsRescan(raw: String?, now: Long = System.currentTimeMillis()): Boolean {
        if (ReelMetadata.decode(raw) != null) return false
        val failedAt = raw?.takeIf { it.startsWith(REEL_ABSENT_PREFIX) }
            ?.removePrefix(REEL_ABSENT_PREFIX)?.toLongOrNull() ?: return true
        return failedAt <= 0L || failedAt > now || now - failedAt >= REEL_NEGATIVE_RETRY_MS
    }

    fun isReelAbsent(raw: String?): Boolean = raw?.startsWith(REEL_ABSENT_PREFIX) == true

    fun reelDiscoveryFailedEntry(): String = REEL_ABSENT_PREFIX + System.currentTimeMillis()

    fun install(
        module: XposedInterface,
        bridge: DexKitBridge,
        classLoader: ClassLoader,
    ): Result {
        val story = discoverStory(module, bridge, classLoader)
        val reel = discoverReel(module, bridge, classLoader)
        return Result(story, reel?.first, reel?.second)
    }

    fun installStoryCached(module: XposedInterface, methods: List<Method>): Boolean {
        L.i(TAG, "installStoryCached called with ${methods.size} methods: ${methods.map { it.declaringClass.name + "." + it.name }}")
        val res = hookStoryMethods(module, methods)
        L.i(TAG, "installStoryCached result: $res")
        return res
    }

    fun installReelsCached(
        module: XposedInterface,
        classLoader: ClassLoader,
        methods: List<Method>,
        metadata: String,
    ): Boolean {
        L.i(TAG, "installReelsCached called with ${methods.size} methods: ${methods.map { it.declaringClass.name + "." + it.name }}, meta=$metadata")
        val meta = ReelMetadata.decode(metadata) ?: run {
            L.w(TAG, "installReelsCached: decode failed for $metadata")
            return false
        }
        if (methods.size < 2) {
            L.w(TAG, "installReelsCached: methods.size < 2 (${methods.size})")
            return false
        }
        val builder = methods[0]
        val assembly = methods[1]

        val factory: Executable = if (meta.factoryClass.isNotBlank()) {
            val cls = runCatching { Class.forName(meta.factoryClass, false, classLoader) }.getOrNull() ?: run {
                L.w(TAG, "installReelsCached: Class.forName failed for factory ${meta.factoryClass}")
                return false
            }
            cls.declaredConstructors.firstOrNull { ctor ->
                ReelFactorySignature.accepts(ctor.parameterTypes.map { it.name })
            } ?: methods.getOrNull(2) ?: run {
                L.w(TAG, "installReelsCached: factory ctor not found on ${cls.name}")
                return false
            }
        } else {
            methods.getOrNull(2) ?: run {
                L.w(TAG, "installReelsCached: methods[2] not found")
                return false
            }
        }

        val marker: Executable = if (meta.markerClass.isNotBlank()) {
            val cls = runCatching { Class.forName(meta.markerClass, false, classLoader) }.getOrNull() ?: run {
                L.w(TAG, "installReelsCached: Class.forName failed for marker ${meta.markerClass}")
                return false
            }
            cls.declaredConstructors.firstOrNull { it.parameterTypes.size == 1 }
                ?: methods.lastOrNull() ?: run {
                    L.w(TAG, "installReelsCached: marker ctor not found on ${cls.name}")
                    return false
                }
        } else {
            methods.lastOrNull() ?: run {
                L.w(TAG, "installReelsCached: methods.lastOrNull() not found")
                return false
            }
        }

        val res = installReelRuntime(module, classLoader, builder, factory, assembly, marker, meta)
        L.i(TAG, "installReelsCached result: $res")
        return res
    }

    // --------------------------------------------------------------- stories

    private fun discoverStory(
        module: XposedInterface,
        bridge: DexKitBridge,
        classLoader: ClassLoader,
    ): List<Method>? = runCatching {
        // Morphe fingerprints the analytics-bearing method only to learn the
        // action class. The actual tap handler is then selected from that class.
        val actionHits = bridge.findMethod {
            matcher { usingStrings(SAVE_STORY_ATTEMPTED) }
        }.filter { it.returnTypeName == "void" && it.paramCount == 1 }

        check(actionHits.size == 1) {
            "Expected 1 save-story action fingerprint, found ${actionHits.size}"
        }
        val actionClass = actionHits.single().declaredClass
            ?: error("Save-story action class could not be resolved")
        val actionClassName = actionClass.name

        val builders = bridge.findMethod {
            matcher { declaredClass(STORY_MORE_MENU) }
        }.filter { method ->
            method.invokes.any { it.isConstructor && it.declaredClassName == actionClassName }
        }
        check(builders.size == 1) {
            "Expected 1 story menu builder creating $actionClassName, found ${builders.size}"
        }
        val builderData = builders.single()

        // Same shape Morphe patches: a non-framework virtual ()Z call.
        val capabilities = builderData.invokes.filter { called ->
            called.paramCount == 0 &&
                called.returnTypeName == "boolean" &&
                !Modifier.isStatic(called.modifiers) &&
                !called.declaredClassName.startsWith("java.") &&
                !called.declaredClassName.startsWith("android.")
        }.distinctBy(MethodData::descriptor)
        check(capabilities.size == 1) {
            "Expected 1 story save capability, found ${capabilities.size}"
        }

        val handlers = actionClass.methods.filter {
            it.methodName != "<init>" && it.returnTypeName == "void" && it.paramCount == 1
        }
        check(handlers.size == 1) {
            "Expected 1 story tap handler on $actionClassName, found ${handlers.size}"
        }

        val methods = listOf(
            builderData.getMethodInstance(classLoader),
            capabilities.single().getMethodInstance(classLoader),
            handlers.single().getMethodInstance(classLoader),
        )
        check(hookStoryMethods(module, methods)) { "No story methods were hooked" }
        L.i(TAG, "native story Save hook installed: $actionClassName")
        L.i(TAG, "native story Repost menu injection ready")
        methods
    }.onFailure { L.w(TAG, "story contextual discovery failed", it) }.getOrNull()

    private fun hookStoryMethods(module: XposedInterface, methods: List<Method>): Boolean {
        if (methods.size != 3) return false
        val builder = methods[0]
        val capability = methods[1]
        val handler = methods[2]
        val runtime = runCatching { resolveStoryRuntime(builder, handler) }
            .onFailure { L.w(TAG, "story native-menu runtime resolve failed", it) }
            .getOrNull()
        storyRuntime = runtime
        var installed = 0

        if (hookedStoryBuilders.add(builder)) {
            runCatching {
                builder.isAccessible = true
                module.hook(builder).intercept(StoryMenuBuilderHook)
                installed++
            }.onFailure { L.w(TAG, "story menu-builder hook failed", it) }
        }
        if (hookedStoryCapabilities.add(capability)) {
            runCatching {
                capability.isAccessible = true
                module.hook(capability).intercept(StoryCapabilityHook)
                installed++
            }.onFailure { L.w(TAG, "story capability hook failed", it) }
        }
        if (hookedStoryHandlers.add(handler)) {
            runCatching {
                handler.isAccessible = true
                module.hook(handler).intercept(StoryActionHook)
                installed++
            }.onFailure { L.w(TAG, "story action hook failed", it) }
        }

        return installed > 0 ||
            (hookedStoryBuilders.contains(builder) &&
                hookedStoryCapabilities.contains(capability) &&
                hookedStoryHandlers.contains(handler))
    }

    private object StoryMenuBuilderHook : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val previous = storyMenuDepth.get() ?: 0
            storyMenuDepth.set(previous + 1)
            return try {
                val result = chain.proceed()
                if (Settings.getBoolean(Settings.DOWNLOAD_SHOW_ICON, true)) {
                    val owner = chain.thisObject
                    if (owner != null) {
                        runCatching { appendStoryRepost(chain, owner) }
                            .onFailure { L.w(TAG, "story Repost menu injection failed", it) }
                    }
                }
                result
            } finally {
                storyMenuDepth.set(previous)
            }
        }
    }

    private fun resolveStoryRuntime(builder: Method, handler: Method): StoryRuntime {
        val menuItemClass = handler.parameterTypes.singleOrNull()
            ?: error("Story action no longer takes one menu item")
        val ctor = menuItemClass.declaredConstructors.firstOrNull { constructor ->
            val p = constructor.parameterTypes
            p.size >= 10 &&
                p[0].isInterface &&
                p[1].isEnum &&
                CharSequence::class.java.isAssignableFrom(p[2]) &&
                p[3] == Integer::class.java && p[4] == Integer::class.java &&
                p[5] == Integer::class.java && p[6] == String::class.java &&
                p[7] == String::class.java && p[8] == Int::class.javaPrimitiveType &&
                p[9] == Boolean::class.javaPrimitiveType
        } ?: error("Story menu item constructor changed: ${menuItemClass.name}")
        ctor.isAccessible = true

        val owner = builder.declaringClass
        val contextField = owner.declaredFields.firstOrNull {
            Context::class.java.isAssignableFrom(it.type)
        } ?: error("Story menu owner no longer has a Context field")
        val storyField = owner.declaredFields.firstOrNull {
            it.type.name == STORY_CARD || it.type.name.endsWith(".StoryCard")
        } ?: error("Story menu owner no longer has a StoryCard field")
        contextField.isAccessible = true
        storyField.isAccessible = true

        return StoryRuntime(
            builder = builder,
            contextField = contextField,
            storyField = storyField,
            menuItemCtor = ctor,
            handlerType = ctor.parameterTypes[0],
            iconType = ctor.parameterTypes[1],
        )
    }

    private fun appendStoryRepost(chain: XposedInterface.Chain, owner: Any) {
        val runtime = storyRuntime ?: return
        val builderIndex = runtime.builder.parameterTypes.indexOfFirst {
            it.name == "com.google.common.collect.ImmutableList\$Builder"
        }
        if (builderIndex < 0) return
        val listBuilder = chain.args.getOrNull(builderIndex) ?: return
        val context = runtime.contextField.get(owner) as? Context ?: return
        val storyCard = runtime.storyField.get(owner) ?: return
        val icon = enumConstant(runtime.iconType, STORY_REPOST_ICON_NAME) ?: run {
            L.w(TAG, "story Repost icon $STORY_REPOST_ICON_NAME not present")
            return
        }

        val loader = runtime.handlerType.classLoader ?: ContextualDownloadHook::class.java.classLoader
        val clickHandler = Proxy.newProxyInstance(loader, arrayOf(runtime.handlerType)) { proxy, method, args ->
            when (method.name) {
                "toString" -> "FBARStoryRepostHandler"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> {
                    if (method.parameterCount == 1) {
                        L.i(TAG, "native story Repost tapped")
                        DownloadHook.openStoryRepost(context, storyCard)
                    }
                    null
                }
            }
        }
        val item = runtime.menuItemCtor.newInstance(
            clickHandler,
            icon,
            null,
            null,
            null,
            null,
            // Match Facebook's native Save-story layout: its visible label
            // lives in A06 (resource id). For a custom string, Wkb.A08 is the
            // direct-string fallback for that same *primary* label slot. Our
            // previous A03 placement was the secondary text slot, hence gray.
            REPOST_LABEL,
            null,
            // Save story uses menu item id 2 and enabled=false. Mirroring
            // those style flags keeps Repost on the same native row path.
            2,
            false,
        )
        val add = listBuilder.javaClass.methods.firstOrNull {
            it.name == "add" && it.parameterCount == 1
        } ?: error("ImmutableList.Builder.add(Object) not found")
        add.invoke(listBuilder, item)
        L.i(TAG, "native Repost item appended to story menu")
    }

    private object StoryCapabilityHook : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!Settings.getBoolean(Settings.DOWNLOAD_SHOW_ICON, true)) return chain.proceed()
            return if ((storyMenuDepth.get() ?: 0) > 0) true else chain.proceed()
        }
    }

    private object StoryActionHook : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!Settings.getBoolean(Settings.DOWNLOAD_SHOW_ICON, true)) return chain.proceed()
            val owner = chain.thisObject ?: return chain.proceed()
            var context: Context? = null
            var storyCard: Any? = null

            var type: Class<*>? = owner.javaClass
            while (type != null && type != Any::class.java) {
                val current = type
                for (field in runCatching { current.declaredFields }.getOrElse { emptyArray() }) {
                    val value = runCatching {
                        field.isAccessible = true
                        field.get(owner)
                    }.getOrNull() ?: continue
                    if (context == null && Context::class.java.isAssignableFrom(field.type)) {
                        context = value as? Context
                    }
                    if (storyCard == null && (field.type.name == STORY_CARD || field.type.name.endsWith(".StoryCard"))) {
                        storyCard = value
                    }
                }
                type = current.superclass
            }

            return if (storyCard != null && DownloadHook.openStoryDownload(context, storyCard)) {
                null
            } else {
                chain.proceed()
            }
        }
    }

    // ----------------------------------------------------------------- reels

    private fun discoverReel(
        module: XposedInterface,
        bridge: DexKitBridge,
        classLoader: ClassLoader,
    ): Pair<List<Method>, String>? = runCatching {
        val anchorHits = bridge.findMethod {
            matcher { usingStrings(REEL_SIDEBAR) }
        }
        L.i(TAG, "anchorHits: ${anchorHits.map { "${it.declaredClassName}.${it.methodName}${it.descriptor}" }}")
        val sidebarCandidates = anchorHits.filter { method ->
            method.invokes.any { called ->
                called.paramTypeNames.count { it == FUNCTION1 } >= 4 &&
                    called.paramTypeNames.firstOrNull() == FB_USER_SESSION
            }
        }
        L.i(TAG, "sidebarCandidates: ${sidebarCandidates.map { "${it.declaredClassName}.${it.methodName}${it.descriptor}" }}")
        check(sidebarCandidates.isNotEmpty()) {
            "Expected at least 1 reel sidebar builder, found 0"
        }
        val sidebar = sidebarCandidates.singleOrNull()
            ?: sidebarCandidates.firstOrNull { it.paramCount == 1 }
            ?: sidebarCandidates.first()
        check(sidebar.paramCount >= 1) { "Reel sidebar builder takes 0 params" }
        L.i(TAG, "selected sidebar: ${sidebar.declaredClassName}.${sidebar.methodName}${sidebar.descriptor}")
        L.i(TAG, "sidebar invokes: ${sidebar.invokes.map { "${it.declaredClassName}.${it.methodName}${it.descriptor}" }}")

        val factories = sidebar.invokes.filter { called ->
            called.paramTypeNames.count { it == FUNCTION1 } >= 4 &&
                called.paramTypeNames.firstOrNull() == FB_USER_SESSION
        }.distinctBy(MethodData::descriptor)
        check(factories.isNotEmpty()) { "Expected at least 1 reel button factory, found 0" }
        val factory = factories.firstOrNull { it.isMethod && ReelFactorySignature.accepts(it.paramTypeNames) }
            ?: factories.firstOrNull { ReelFactorySignature.accepts(it.paramTypeNames) }
            ?: factories.first()

        check(ReelFactorySignature.accepts(factory.paramTypeNames)) {
            "Reel button factory has incompatible signature: ${factory.descriptor}"
        }

        val parameters = factory.paramTypeNames
        val iconBaseType = parameters[5]

        val wrapperCtors = sidebar.invokes.filter { called ->
            called.isConstructor &&
                called.paramCount == 1 &&
                called.declaredClass?.superClass?.name == iconBaseType
        }.distinctBy { it.declaredClassName }
        check(wrapperCtors.isNotEmpty()) { "Expected at least 1 reel icon wrapper, found 0" }
        val iconWrapperCtor = wrapperCtors.singleOrNull() ?: wrapperCtors.first()
        val iconEnumType = iconWrapperCtor.paramTypeNames.single()

        val componentBuilder = anchorHits.firstOrNull { hit ->
            hit.paramCount == 1 &&
                hit.invokes.any { called -> called.descriptor == sidebar.descriptor }
        } ?: sidebar
        L.i(TAG, "componentBuilder: ${componentBuilder.declaredClassName}.${componentBuilder.methodName}${componentBuilder.descriptor}")
        L.i(TAG, "componentBuilder invokes: ${componentBuilder.invokes.map { "${it.declaredClassName}.${it.methodName}${it.descriptor}" }}")

        val candidateInvokes = (componentBuilder.invokes + sidebar.invokes).distinctBy(MethodData::descriptor)
        val assemblies = candidateInvokes.filter { called ->
            called.paramTypeNames.count { it == "java.util.ArrayList" } >= 2
        }.distinctBy(MethodData::descriptor).ifEmpty {
            candidateInvokes.filter { called ->
                called.paramTypeNames.count { it == "java.util.ArrayList" || it == "java.util.List" } >= 2 ||
                    called.paramTypeNames.any { it == "java.util.AbstractCollection" || it == "java.util.Collection" }
            }.distinctBy(MethodData::descriptor)
        }
        L.i(TAG, "all assembly candidates: ${assemblies.map { "${it.declaredClassName}.${it.methodName}${it.descriptor}" }}")
        check(assemblies.isNotEmpty()) { "Expected at least 1 reel sidebar assembly call, found 0" }
        val assembly = assemblies.first()

        val markers = (componentBuilder.invokes + sidebar.invokes).filter { called ->
            called.paramCount == 1 &&
                called.paramTypeNames.single() == iconEnumType &&
                (called.returnTypeName != "void" || (called.isConstructor && called.declaredClass?.superClass?.name == iconBaseType))
        }.distinctBy(MethodData::descriptor)
        check(markers.isNotEmpty()) { "Expected at least 1 reel icon marker, found 0" }
        val marker = markers.first()

        val icon = findDownloadIcon(bridge, iconEnumType)
        check(icon != null) { "Facebook download row exposes no icon of $iconEnumType" }

        val component = componentBuilder.declaredClass ?: sidebar.declaredClass
            ?: error("Reel sidebar class could not be resolved")

        var playerFieldName: String? = null
        var subPlayerFieldName: String? = null

        val directPlayerField = component.fields.firstOrNull { it.typeName == VIDEO_PLAYER_PARAMS }
        if (directPlayerField != null) {
            playerFieldName = directPlayerField.fieldName
        } else {
            for (f in component.fields) {
                val sub = f.type.fields.firstOrNull { it.typeName == VIDEO_PLAYER_PARAMS }
                if (sub != null) {
                    playerFieldName = f.fieldName
                    subPlayerFieldName = sub.fieldName
                    break
                }
            }
        }
        check(playerFieldName != null) { "Expected at least 1 reel player field, found 0" }

        val scopedType = componentBuilder.paramTypes.firstOrNull() ?: sidebar.paramTypes.firstOrNull()
            ?: error("Reel sidebar scoped parameter type could not be resolved")
        val contextFields = scopedType.fields.filter { it.typeName == Context::class.java.name }
        check(contextFields.isNotEmpty()) { "Expected at least 1 context field on ${scopedType.name}, found 0" }
        val contextField = contextFields.first()

        val factoryExecutable: Executable = if (factory.isConstructor) {
            factory.getConstructorInstance(classLoader)
        } else {
            factory.getMethodInstance(classLoader)
        }
        val markerExecutable: Executable = if (marker.isConstructor) {
            marker.getConstructorInstance(classLoader)
        } else {
            marker.getMethodInstance(classLoader)
        }

        val meta = ReelMetadata(
            iconOwner = icon.declaredClassName,
            iconField = icon.fieldName,
            iconWrapper = iconWrapperCtor.declaredClassName,
            playerField = playerFieldName,
            contextField = contextField.fieldName,
            factoryClass = if (factory.isConstructor) factory.declaredClassName else "",
            markerClass = if (marker.isConstructor) marker.declaredClassName else "",
            subPlayerField = subPlayerFieldName ?: "",
        )

        val builderMethod = componentBuilder.getMethodInstance(classLoader)
        val assemblyMethod = assembly.getMethodInstance(classLoader)

        check(installReelRuntime(module, classLoader, builderMethod, factoryExecutable, assemblyMethod, markerExecutable, meta)) {
            "Native reel hooks were not installed"
        }
        L.i(TAG, "native reel Download button installed via ${componentBuilder.declaredClassName}.${componentBuilder.methodName}")
        val methods = mutableListOf(builderMethod, assemblyMethod)
        if (factoryExecutable is Method) methods.add(factoryExecutable)
        if (markerExecutable is Method) methods.add(markerExecutable)
        methods to meta.encode()
    }.onFailure { L.w(TAG, "reel native-button discovery failed", it) }.getOrNull()

    private fun findDownloadIcon(bridge: DexKitBridge, iconEnumType: String): FieldData? {
        val strings = listOf(
            DOWNLOAD_ROW,
            "fds_control_download",
            "download_video",
            "fds_control_save_video",
            "save_video",
        )
        for (str in strings) {
            val candidate = bridge.findMethod {
                matcher { usingStrings(str) }
            }.asSequence()
                .flatMap { it.usingFields.asSequence() }
                .map { it.field }
                .firstOrNull { field ->
                    field.typeName == iconEnumType && Modifier.isStatic(field.modifiers)
                }
            if (candidate != null) return candidate
        }
        // Fallback: search icon enum class fields directly for a matching static enum member
        val iconClass = bridge.findClass {
            matcher { className(iconEnumType) }
        }.firstOrNull() ?: return null
        val enumNames = listOf("DOWNLOAD", "ARROW_DOWN_TRAY", "DOWNLOAD_ROW", "SAVE_STORY", "SAVE")
        for (name in enumNames) {
            val f = iconClass.fields.firstOrNull {
                it.fieldName.equals(name, ignoreCase = true) &&
                    it.typeName == iconEnumType && Modifier.isStatic(it.modifiers)
            }
            if (f != null) return f
        }
        return iconClass.fields.firstOrNull {
            it.typeName == iconEnumType && Modifier.isStatic(it.modifiers) &&
                it.fieldName.contains("DOWNLOAD", ignoreCase = true)
        }
    }

    @Synchronized
    private fun installReelRuntime(
        module: XposedInterface,
        classLoader: ClassLoader,
        builder: Method,
        factory: Executable,
        assembly: Method,
        marker: Executable,
        meta: ReelMetadata,
    ): Boolean {
        L.i(TAG, "installReelRuntime: builder=${builder.declaringClass.name}.${builder.name}, factory=$factory, assembly=${assembly.declaringClass.name}.${assembly.name}, marker=$marker")
        if (marker is Method && !Modifier.isStatic(marker.modifiers)) {
            L.w(TAG, "reel marker is no longer static")
            return false
        }
        if (factory is Method && !Modifier.isStatic(factory.modifiers)) {
            L.w(TAG, "reel factory method is no longer static")
            return false
        }
        if (!ReelFactorySignature.accepts(factory.parameterTypes.map { it.name })) {
            L.w(TAG, "cached reel factory signature changed: $factory")
            return false
        }

        val runtime = runCatching {
            val iconOwner = Class.forName(meta.iconOwner, false, classLoader)
            val iconWrapper = Class.forName(meta.iconWrapper, false, classLoader)
            val iconField = iconOwner.getDeclaredField(meta.iconField).apply { isAccessible = true }
            val playerField = builder.declaringClass.getDeclaredField(meta.playerField).apply { isAccessible = true }
            val subPlayerField = if (meta.subPlayerField.isNotBlank()) {
                playerField.type.getDeclaredField(meta.subPlayerField).apply { isAccessible = true }
            } else null
            val scopedType = builder.parameterTypes.firstOrNull() ?: return false
            val contextField = scopedType.getDeclaredField(meta.contextField).apply { isAccessible = true }
            ReelRuntime(
                builder = builder,
                factory = factory.apply { isAccessible = true },
                assembly = assembly.apply { isAccessible = true },
                marker = marker.apply { isAccessible = true },
                iconField = iconField,
                iconWrapper = iconWrapper,
                playerField = playerField,
                contextField = contextField,
                subPlayerField = subPlayerField,
            )
        }.onFailure { L.w(TAG, "reel runtime metadata resolve failed", it) }.getOrNull() ?: return false

        reelRuntime = runtime
        if (!hookedReelBuilders.contains(builder)) {
            runCatching {
                module.hook(builder).intercept(ReelBuilderHook)
                hookedReelBuilders.add(builder)
            }.onFailure { L.w(TAG, "reel builder hook failed", it) }
        }
        if (!hookedReelFactories.contains(factory)) {
            runCatching {
                module.hook(factory).intercept(ReelFactoryHook)
                hookedReelFactories.add(factory)
            }.onFailure { L.w(TAG, "reel factory hook failed", it) }
        }
        if (!hookedReelAssemblies.contains(assembly)) {
            runCatching {
                module.hook(assembly).intercept(ReelAssemblyHook)
                hookedReelAssemblies.add(assembly)
            }.onFailure { L.w(TAG, "reel assembly hook failed", it) }
        }
        val success = hookedReelBuilders.contains(builder) &&
            hookedReelFactories.contains(factory) &&
            hookedReelAssemblies.contains(assembly)
        L.i(TAG, "installReelRuntime result: $success (builder=${hookedReelBuilders.contains(builder)}, factory=${hookedReelFactories.contains(factory)}, assembly=${hookedReelAssemblies.contains(assembly)})")

        return success
    }

    private object ReelBuilderHook : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val owner = chain.thisObject
            L.i(TAG, "ReelBuilderHook entered: owner=${owner?.javaClass?.name}")
            if (owner == null) return chain.proceed()
            val previous = reelCall.get()
            val scoped = chain.args.firstOrNull { arg ->
                arg != null && runCatching {
                    reelRuntime?.contextField?.get(arg) is Context
                }.getOrDefault(false)
            } ?: chain.args.getOrNull(0)
            val call = ReelCall(owner, scoped)
            reelCall.set(call)
            lastReelCall = call
            return try {
                chain.proceed()
            } finally {
                reelCall.set(previous)
                L.i(TAG, "ReelBuilderHook exited (hasTemplate=${call.factoryTemplate != null})")
            }
        }
    }

    private object ReelAssemblyHook : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val showIcon = Settings.getBoolean(Settings.DOWNLOAD_SHOW_ICON, true)
            if (!showIcon) return chain.proceed()
            val call = reelCall.get()
                ?: lastReelCall?.takeIf { !it.injected && System.currentTimeMillis() - it.createdAt < 3000 }
            if (call == null) return chain.proceed()
            if (!call.injected) {
                L.i(TAG, "ReelAssemblyHook: injecting native buttons")
                runCatching { injectNativeReelButtons(chain, call) }
                    .onFailure { L.w(TAG, "native reel button injection failed", it) }
            }
            return chain.proceed()
        }
    }

    /** Capture one real Facebook sidebar-button invocation as the template. */
    private object ReelFactoryHook : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val call = reelCall.get()
                ?: lastReelCall?.takeIf { System.currentTimeMillis() - it.createdAt < 3000 }
            L.i(TAG, "ReelFactoryHook entered: call=${call != null}, args=${chain.args.size}, hasTemplate=${call?.factoryTemplate != null}")
            if (call != null && call.factoryTemplate == null) {
                call.factoryTemplate = chain.args.toTypedArray()
            }
            return chain.proceed()
        }
    }

    private fun injectNativeReelButtons(chain: XposedInterface.Chain, call: ReelCall) {
        val runtime = reelRuntime ?: run {
            L.w(TAG, "injectNativeReelButtons: runtime is null")
            return
        }
        val factoryClass = if (runtime.factory is Constructor<*>) runtime.factory.declaringClass else (runtime.factory as Method).returnType
        val markerClass = if (runtime.marker is Constructor<*>) runtime.marker.declaringClass else (runtime.marker as Method).returnType

        @Suppress("UNCHECKED_CAST")
        val collections = chain.args.mapIndexedNotNull { index, arg ->
            (arg as? MutableCollection<Any?>)?.let { index to it }
        }

        val buttonEntry = collections.firstOrNull { (_, list) ->
            list.any { it != null && factoryClass.isInstance(it) }
        } ?: collections.lastOrNull()

        val markerEntry = collections.firstOrNull { (index, list) ->
            index != buttonEntry?.first && list.any { it != null && markerClass.isInstance(it) }
        } ?: collections.firstOrNull { it.first != buttonEntry?.first }

        val buttons = buttonEntry?.second ?: run {
            L.w(TAG, "injectNativeReelButtons: button collection not found")
            return
        }
        val markers = markerEntry?.second
        val buttonListIndex = buttonEntry.first

        L.i(TAG, "injectNativeReelButtons: buttonIndex=$buttonListIndex (size=${buttons.size}, item=${buttons.firstOrNull()?.javaClass?.name}), markerIndex=${markerEntry?.first} (size=${markers?.size}, item=${markers?.firstOrNull()?.javaClass?.name})")

        val playerObj = runtime.playerField.get(call.owner) ?: run {
            L.w(TAG, "injectNativeReelButtons: playerField.get returned null")
            return
        }
        val player = runtime.subPlayerField?.get(playerObj) ?: playerObj
        val context = call.scoped?.let { runtime.contextField.get(it) as? Context } ?: run {
            L.w(TAG, "injectNativeReelButtons: context is null")
            return
        }
        val template = call.factoryTemplate ?: run {
            L.w(TAG, "reel sidebar reached assembly before any button factory call")
            return
        }
        val downloadIcon = runtime.iconField.get(null) ?: run {
            L.w(TAG, "injectNativeReelButtons: downloadIcon is null")
            return
        }
        val downloadButton = buildNativeButton(
            runtime, template, context, player, ReelAction.DOWNLOAD, downloadIcon) ?: run {
            L.w(TAG, "injectNativeReelButtons: buildNativeButton returned null")
            return
        }
        val downloadMarker = runCatching {
            if (runtime.marker is Constructor<*>) {
                runtime.marker.newInstance(downloadIcon)
            } else {
                (runtime.marker as Method).invoke(null, downloadIcon)
            }
        }.onFailure { L.w(TAG, "downloadMarker instantiation failed", it) }.getOrNull()

        try {
            buttons.add(downloadButton)
        } catch (e: UnsupportedOperationException) {
            L.i(TAG, "buttons is unmodifiable, replacing with ArrayList")
            val copy = ArrayList(buttons)
            copy.add(downloadButton)
            chain.args[buttonListIndex] = copy
        }
        if (markers != null && downloadMarker != null) {
            runCatching { markers.add(downloadMarker) }
        }
        L.i(TAG, "native Download button appended to reel sidebar (buttons size now ${buttons.size})")

        val repostIcon = enumConstant(runtime.iconField.type, REEL_REPOST_ICON_NAME)
        if (repostIcon != null) {
            val repostButton = buildNativeButton(
                runtime, template, context, player, ReelAction.REPOST, repostIcon)
            val repostMarker = runCatching {
                if (runtime.marker is Constructor<*>) {
                    runtime.marker.newInstance(repostIcon)
                } else {
                    (runtime.marker as Method).invoke(null, repostIcon)
                }
            }.getOrNull()
            if (repostButton != null) {
                try {
                    val currentButtons = chain.args.getOrNull(buttonListIndex) as? MutableCollection<Any?> ?: buttons
                    currentButtons.add(repostButton)
                } catch (e: Exception) {
                    val copy = ArrayList(buttons)
                    copy.add(repostButton)
                    chain.args[buttonListIndex] = copy
                }
                if (markers != null && repostMarker != null) {
                    runCatching { markers.add(repostMarker) }
                }
                L.i(TAG, "native Repost button appended to reel sidebar")
            }
        } else {
            L.w(TAG, "reel Repost icon $REEL_REPOST_ICON_NAME not present")
        }
        call.injected = true
    }

    private fun buildNativeButton(
        runtime: ReelRuntime,
        template: Array<Any?>,
        context: Context,
        player: Any,
        action: ReelAction,
        icon: Any,
    ): Any? = runCatching {
        val p = runtime.factory.parameterTypes
        if (template.size != p.size ||
            !ReelFactorySignature.accepts(p.map { it.name })
        ) {
            L.w(TAG, "reel button factory layout no longer matches the captured template (template=${template.size}, params=${p.size})")
            return null
        }
        val functionType = p.firstOrNull { it.name == FUNCTION1 } ?: run {
            L.w(TAG, "FUNCTION1 not found in parameters")
            return null
        }
        val handlers = (0..7).map { slot ->
            functionProxy(functionType, slot, context, player, action)
        }

        val primaryCtor = p[2].declaredConstructors.firstOrNull {
            it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == functionType
        } ?: p[2].declaredConstructors.firstOrNull { it.parameterTypes.size == 2 }
            ?: run {
                L.w(TAG, "primaryCtor not found on ${p[2].name}")
                return null
            }
        primaryCtor.isAccessible = true

        val labelCtor = p[3].declaredConstructors.firstOrNull {
            it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == functionType
        } ?: p[3].declaredConstructors.firstOrNull { it.parameterTypes.size == 2 }
            ?: run {
                L.w(TAG, "labelCtor not found on ${p[3].name}")
                return null
            }
        labelCtor.isAccessible = true

        val iconCtor = runtime.iconWrapper.declaredConstructors.firstOrNull {
            it.parameterTypes.size == 1 && it.parameterTypes[0].isAssignableFrom(runtime.iconField.type)
        } ?: runtime.iconWrapper.declaredConstructors.firstOrNull { it.parameterTypes.size == 1 }
            ?: run {
                L.w(TAG, "iconCtor not found on ${runtime.iconWrapper.name}")
                return null
            }
        iconCtor.isAccessible = true

        val label = if (action == ReelAction.DOWNLOAD) LABEL else REPOST_LABEL
        val testId = if (action == ReelAction.DOWNLOAD) TEST_ID else REPOST_TEST_ID

        // Keep Facebook's real session/mode and any opaque state from a button
        // it just built for this exact reel, replacing only the values Morphe
        // explicitly supplies for its Download button.
        val args = template.copyOf()
        args[2] = primaryCtor.newInstance(label, handlers[0])
        args[3] = labelCtor.newInstance(label, handlers[1])
        args[4] = labelCtor.newInstance(label, handlers[2])
        args[5] = iconCtor.newInstance(icon)
        args[6] = true
        args[7] = false
        args[8] = testId
        args[9] = CAPTION
        // Factory parameter 10 is String. Morphe's `const/16 v50, 0x0`
        // passes a null object reference here, not integer zero.
        args[10] = null
        args[11] = handlers[3]
        args[12] = handlers[4]
        args[13] = handlers[5]
        args[14] = handlers[6]
        if (p[15].name == FUNCTION1) {
            args[15] = handlers[7]
        } else if (p[15] == Integer.TYPE || p[15] == java.lang.Integer::class.java) {
            args[15] = 0x11
        }
        args[16] = false
        args[17] = true
        args[18] = false
        // Any additional flags are deliberately left as Facebook supplied
        // them in the original button for this reel.
        when (val f = runtime.factory) {
            is Method -> f.invoke(null, *args)
            is Constructor<*> -> f.newInstance(*args)
            else -> null
        }
    }.onFailure { L.e(TAG, "buildNativeButton exception for $action", it) }.getOrNull()

    private fun functionProxy(
        functionType: Class<*>,
        slot: Int,
        context: Context,
        player: Any,
        action: ReelAction,
    ): Any {
        val loader = functionType.classLoader ?: ContextualDownloadHook::class.java.classLoader
        val unit = runCatching {
            Class.forName("kotlin.Unit", true, loader).getField("INSTANCE").get(null)
        }.getOrNull()
        return Proxy.newProxyInstance(loader, arrayOf(functionType)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> {
                    if (slot == TAP_SLOT) {
                        when (action) {
                            ReelAction.DOWNLOAD -> {
                                L.i(TAG, "native reel Download tapped")
                                DownloadHook.openReelDownload(context, player)
                            }
                            ReelAction.REPOST -> {
                                L.i(TAG, "native reel Repost tapped")
                                DownloadHook.openReelRepost(context, player)
                            }
                        }
                    }
                    unit
                }
                "toString" -> "FBARReel${action.name}Handler($slot)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> null
            }
        }
    }

    private fun enumConstant(type: Class<*>, name: String): Any? =
        type.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == name }
}
