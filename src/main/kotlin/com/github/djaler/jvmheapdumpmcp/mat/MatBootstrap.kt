package com.github.djaler.jvmheapdumpmcp.mat

import org.eclipse.core.runtime.ContributorFactorySimple
import org.eclipse.core.runtime.RegistryFactory
import org.eclipse.core.runtime.content.IContentTypeManager
import org.eclipse.core.runtime.spi.IRegistryProvider
import org.eclipse.core.runtime.spi.RegistryStrategy
import org.eclipse.mat.parser.internal.ParserPlugin
import org.eclipse.mat.snapshot.SnapshotFactory
import java.lang.reflect.Proxy
import java.util.logging.Logger

/**
 * Bootstraps Eclipse MAT for standalone (non-OSGi) operation.
 *
 * MAT's [SnapshotFactory] is initialized by its static block, which reads the
 * `org.eclipse.mat.api.factory` extension from the Eclipse extension registry to find
 * the [org.eclipse.mat.parser.internal.SnapshotFactoryImpl]. Parsing HPROF files also
 * requires `org.eclipse.mat.parser.parser` extensions in [ParserPlugin]'s registry.
 *
 * We bootstrap by:
 * 1. Creating a standalone [org.eclipse.core.internal.registry.ExtensionRegistry].
 * 2. Contributing plugin.xml-style XML fragments to register all necessary extensions.
 * 3. Injecting the registry as the platform default via [RegistryFactory.setDefaultRegistryProvider].
 * 4. Force-initializing [SnapshotFactory] (triggering its static block).
 * 5. Injecting a [ParserPlugin] instance with the same registry via reflection.
 */
object MatBootstrap {

    private val log = Logger.getLogger(MatBootstrap::class.java.name)

    @Volatile
    private var initialized = false

    fun initialize() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            doInitialize()
            initialized = true
        }
    }

    private fun doInitialize() {
        log.info("Bootstrapping Eclipse MAT for standalone operation")

        val strategy = RegistryStrategy(null, null)
        val registry = RegistryFactory.createRegistry(strategy, null, null)

        RegistryFactory.setDefaultRegistryProvider(IRegistryProvider { registry })

        val matApiContributor = ContributorFactorySimple.createContributor("org.eclipse.mat.api")
        val matParserContributor = ContributorFactorySimple.createContributor("org.eclipse.mat.parser")
        val matHprofContributor = ContributorFactorySimple.createContributor("org.eclipse.mat.hprof")
        val matReportContributor = ContributorFactorySimple.createContributor("org.eclipse.mat.report")

        // 1. Register org.eclipse.mat.api.factory extension point and SnapshotFactoryImpl
        registry.addContribution(
            apiFactoryXml().byteInputStream(),
            matApiContributor,
            false,
            null,
            null,
            null
        )

        // 2. Register the org.eclipse.mat.parser.parser extension point and HPROF parser
        registry.addContribution(
            hprofParserXml().byteInputStream(),
            matParserContributor,
            false,
            null,
            null,
            null
        )

        // 3. Register the org.eclipse.mat.hprof.enhancer extension point (no enhancers needed,
        //    but EnhancerRegistry.init() NPEs if the extension point is missing)
        registry.addContribution(
            hprofEnhancerXml().byteInputStream(),
            matHprofContributor,
            false,
            null,
            null,
            null
        )

        // 4. Register org.eclipse.mat.report.query extension point (needed by QueryRegistry)
        registry.addContribution(
            reportQueryXml().byteInputStream(),
            matReportContributor,
            false,
            null,
            null,
            null
        )

        // 4. Inject IContentTypeManager so Platform.getContentTypeManager() returns non-null.
        //    SnapshotFactoryImpl.openSnapshot() calls it; if it returns null the NPE occurs.
        //    We provide a stub that returns null for getContentType() so MAT falls back to
        //    extension-based parser detection (the .hprof path).
        injectContentTypeManager()

        // 5. Force SnapshotFactory static initialization (reads the registry)
        triggerSnapshotFactoryInit()

        // 6. Inject ParserPlugin so ParserRegistry reads from the same registry
        injectParserPlugin(registry)

        // 7. Inject MATPlugin so ClassSpecificNameResolverRegistry and other api registries work
        injectMATPlugin(registry)

        // 8. Inject ReportPlugin so QueryRegistry works
        injectReportPlugin(registry)

        // 9. Inject HprofPlugin singleton and other stubs needed for HPROF parsing
        injectHprofPlugin()

        // 10. Inject a mock BundleContext into PlatformActivator so Platform.inDebugMode() works
        injectPlatformActivatorContext()

        log.info("Eclipse MAT bootstrap complete")
    }

    private fun apiFactoryXml() = """
        <?xml version="1.0" encoding="UTF-8"?>
        <plugin>
            <extension-point id="factory" name="MAT Snapshot Factory"/>
            <extension-point id="nameResolver" name="MAT Class Specific Name Resolver"/>
            <extension-point id="collectionExtractorProvider" name="MAT Collection Extractor Provider"/>
            <extension-point id="requestResolver" name="MAT Request Detail Resolver"/>
            <extension-point id="threadResolver" name="MAT Thread Detail Resolver"/>
            <extension-point id="ticketResolver" name="MAT Trouble Ticket Resolver"/>
            <extension point="org.eclipse.mat.api.factory">
                <factory impl="org.eclipse.mat.parser.internal.SnapshotFactoryImpl"/>
            </extension>
        </plugin>
    """.trimIndent()

    private fun reportQueryXml() = """
        <?xml version="1.0" encoding="UTF-8"?>
        <plugin>
            <extension-point id="query" name="MAT Report Query"/>
            <extension-point id="report" name="MAT Report"/>
            <extension-point id="renderer" name="MAT Renderer"/>
            <extension point="org.eclipse.mat.report.query">
                <query impl="org.eclipse.mat.inspections.OQLQuery"/>
            </extension>
            <extension point="org.eclipse.mat.report.query">
                <query impl="org.eclipse.mat.inspections.FindLeaksQuery"/>
            </extension>
            <extension point="org.eclipse.mat.report.query">
                <query impl="org.eclipse.mat.inspections.threads.ThreadOverviewQuery"/>
            </extension>
        </plugin>
    """.trimIndent()

    private fun hprofEnhancerXml() = """
        <?xml version="1.0" encoding="UTF-8"?>
        <plugin>
            <extension-point id="enhancer" name="MAT HPROF Enhancer"/>
        </plugin>
    """.trimIndent()

    private fun hprofParserXml() = """
        <?xml version="1.0" encoding="UTF-8"?>
        <plugin>
            <extension-point id="parser" name="MAT Heap Dump Parser"/>
            <extension point="org.eclipse.mat.parser.parser">
                <parser
                    id="org.eclipse.mat.hprof.parser"
                    name="HPROF"
                    fileExtension="hprof,bin"
                    indexBuilder="org.eclipse.mat.hprof.HprofIndexBuilder"
                    objectReader="org.eclipse.mat.hprof.HprofHeapObjectReader"/>
            </extension>
        </plugin>
    """.trimIndent()

    private fun triggerSnapshotFactoryInit() {
        try {
            // The static block in SnapshotFactory reads the extension registry,
            // but our standalone registry may not be wired correctly for that lookup.
            // Inject SnapshotFactoryImpl directly via reflection as a reliable fallback.
            val factoryField = SnapshotFactory::class.java.getDeclaredField("factory")
            factoryField.isAccessible = true

            // Try the normal static init first
            Class.forName("org.eclipse.mat.snapshot.SnapshotFactory")

            if (factoryField.get(null) == null) {
                log.info("SnapshotFactory.factory is null — injecting SnapshotFactoryImpl directly via reflection")
                val implClass = Class.forName("org.eclipse.mat.parser.internal.SnapshotFactoryImpl")
                val impl = implClass.getDeclaredConstructor().newInstance()
                factoryField.set(null, impl)
            }

            // Replace the default HashMap-backed snapshotCache with a ConcurrentHashMap
            // so that multiple threads can open snapshots safely (same fix as Eclipse JIFA).
            val factoryImpl = factoryField.get(null)
            if (factoryImpl != null) {
                val cacheField = factoryImpl.javaClass.getDeclaredField("snapshotCache")
                cacheField.isAccessible = true
                cacheField.set(factoryImpl, java.util.concurrent.ConcurrentHashMap<Any, Any>())
            }
        } catch (e: Exception) {
            log.warning("Could not initialize SnapshotFactory: ${e.message}")
        }
    }

    /**
     * Injects a stub [IContentTypeManager] into [org.eclipse.core.internal.runtime.InternalPlatform]
     * so that [org.eclipse.core.runtime.Platform.getContentTypeManager] returns non-null.
     *
     * The stub returns null from [IContentTypeManager.getContentType], which causes
     * [org.eclipse.mat.parser.internal.SnapshotFactoryImpl.openSnapshot] to skip content-type
     * matching and fall back to file-extension–based parser detection — which is what we want
     * for .hprof files.
     *
     * We use [sun.misc.Unsafe.allocateInstance] to instantiate [org.osgi.util.tracker.ServiceTracker]
     * without calling any constructor (all constructors require an [org.osgi.framework.BundleContext]).
     * Then we set its `cachedService` field directly so [org.osgi.util.tracker.ServiceTracker.getService]
     * immediately returns the stub without going through OSGi service lookup.
     */
    @Suppress("UNCHECKED_CAST")
    private fun injectContentTypeManager() {
        try {
            // Build a no-op IContentTypeManager proxy. getContentType() returning null is enough
            // to trigger the extension-based fallback in SnapshotFactoryImpl.
            val contentTypeManagerProxy = Proxy.newProxyInstance(
                IContentTypeManager::class.java.classLoader,
                arrayOf(IContentTypeManager::class.java)
            ) { _, method, args ->
                when (method.name) {
                    "getContentType" -> null
                    "getAllContentTypes" -> emptyArray<Any>()
                    "findContentTypesFor" -> emptyArray<Any>()
                    else -> null
                }
            } as IContentTypeManager

            // Allocate a ServiceTracker without calling its constructor (requires BundleContext).
            val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            val unsafe = unsafeField.get(null) as sun.misc.Unsafe

            val trackerClass = Class.forName("org.osgi.util.tracker.ServiceTracker")
            val stubTracker = unsafe.allocateInstance(trackerClass)

            // Set cachedService so getService() returns our proxy immediately.
            val cachedServiceField = trackerClass.getDeclaredField("cachedService")
            cachedServiceField.isAccessible = true
            cachedServiceField.set(stubTracker, contentTypeManagerProxy)

            // Inject the stub tracker into InternalPlatform.contentTracker.
            val internalPlatformClass = Class.forName("org.eclipse.core.internal.runtime.InternalPlatform")
            val getDefaultMethod = internalPlatformClass.getDeclaredMethod("getDefault")
            val internalPlatform = getDefaultMethod.invoke(null)

            val contentTrackerField = internalPlatformClass.getDeclaredField("contentTracker")
            contentTrackerField.isAccessible = true
            contentTrackerField.set(internalPlatform, stubTracker)

            log.info("IContentTypeManager stub injected into InternalPlatform")
        } catch (e: Exception) {
            log.warning("Could not inject IContentTypeManager: ${e.message} — snapshot opening may fail")
        }
    }

    private fun injectParserPlugin(registry: org.eclipse.core.runtime.IExtensionRegistry) {
        try {
            val pluginClass = ParserPlugin::class.java
            val defaultField = pluginClass.getDeclaredField("plugin")
            defaultField.isAccessible = true

            val instance = pluginClass.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()

            val trackerClass = Class.forName("org.eclipse.core.runtime.dynamichelpers.ExtensionTracker")
            val tracker = trackerClass.getDeclaredConstructor(
                Class.forName("org.eclipse.core.runtime.IExtensionRegistry")
            ).newInstance(registry)

            val trackerField = pluginClass.getDeclaredField("tracker")
            trackerField.isAccessible = true
            trackerField.set(instance, tracker)

            val registryClass = Class.forName("org.eclipse.mat.parser.internal.util.ParserRegistry")
            val trackerInterface = Class.forName("org.eclipse.core.runtime.dynamichelpers.IExtensionTracker")
            val parserRegistry = registryClass
                .getDeclaredConstructor(trackerInterface)
                .also { it.isAccessible = true }
                .newInstance(tracker)

            val registryField = pluginClass.getDeclaredField("registry")
            registryField.isAccessible = true
            registryField.set(instance, parserRegistry)

            defaultField.set(null, instance)
            log.info("ParserPlugin injected with HPROF parser registry")
        } catch (e: Exception) {
            log.warning("ParserPlugin injection failed: ${e.message} — MAT may not open HPROF files")
        }
    }

    /**
     * Creates a mock [org.osgi.framework.Bundle] proxy that returns [symbolicName] as the
     * symbolic name. Used to satisfy [org.eclipse.core.runtime.Plugin.getBundle] callers such
     * as [org.eclipse.mat.snapshot.query.Icons.getURL] which calls `plugin.getBundle()`.
     * [org.eclipse.core.runtime.FileLocator.find] handles a null URL gracefully, so returning
     * null from [org.osgi.framework.Bundle.getResource] / [org.osgi.framework.Bundle.findEntries]
     * is fine — MAT just skips icon loading.
     */
    private fun createMockBundle(symbolicName: String): Any {
        val bundleClass = Class.forName("org.osgi.framework.Bundle")
        return Proxy.newProxyInstance(
            bundleClass.classLoader,
            arrayOf(bundleClass)
        ) { _, method, _ ->
            when (method.name) {
                "getSymbolicName" -> symbolicName
                "getBundleId" -> 0L
                "getState" -> 32 // Bundle.ACTIVE
                else -> null
            }
        }
    }

    /**
     * Injects a MAT plugin singleton (MATPlugin, ReportPlugin, etc.) with an [ExtensionTracker]
     * backed by our standalone extension registry. These plugins follow the same pattern:
     * a static `plugin` field, a `tracker` field, and a no-arg constructor that calls `super()`.
     *
     * Also injects a mock [org.osgi.framework.Bundle] so that [org.eclipse.core.runtime.Plugin.getBundle]
     * returns non-null — needed by [org.eclipse.mat.snapshot.query.Icons.getURL] which calls
     * `MATPlugin.getDefault().getBundle()`.
     */
    private fun injectMatPlugin(
        pluginClassName: String,
        registry: org.eclipse.core.runtime.IExtensionRegistry,
        logName: String,
        bundleSymbolicName: String = pluginClassName
    ) {
        try {
            val pluginClass = Class.forName(pluginClassName)
            val pluginField = pluginClass.getDeclaredField("plugin")
            pluginField.isAccessible = true

            val instance = pluginClass.getDeclaredConstructor().newInstance()

            val trackerClass = Class.forName("org.eclipse.core.runtime.dynamichelpers.ExtensionTracker")
            val tracker = trackerClass.getDeclaredConstructor(
                Class.forName("org.eclipse.core.runtime.IExtensionRegistry")
            ).newInstance(registry)

            val trackerField = pluginClass.getDeclaredField("tracker")
            trackerField.isAccessible = true
            trackerField.set(instance, tracker)

            // Inject a mock Bundle so getBundle() returns non-null.
            val bundleField = Class.forName("org.eclipse.core.runtime.Plugin").getDeclaredField("bundle")
            bundleField.isAccessible = true
            bundleField.set(instance, createMockBundle(bundleSymbolicName))

            pluginField.set(null, instance)
            log.info("$logName injected with ExtensionTracker")
        } catch (e: Exception) {
            log.warning("$logName injection failed: ${e.message}")
        }
    }

    private fun injectMATPlugin(registry: org.eclipse.core.runtime.IExtensionRegistry) =
        injectMatPlugin("org.eclipse.mat.internal.MATPlugin", registry, "MATPlugin", "org.eclipse.mat.api")

    private fun injectReportPlugin(registry: org.eclipse.core.runtime.IExtensionRegistry) =
        injectMatPlugin("org.eclipse.mat.report.internal.ReportPlugin", registry, "ReportPlugin", "org.eclipse.mat.report")

    /**
     * Injects a mock [org.osgi.framework.BundleContext] into [org.eclipse.core.internal.runtime.PlatformActivator]
     * so that [org.eclipse.core.runtime.Platform.inDebugMode] and [org.eclipse.core.runtime.Platform.inDevelopmentMode]
     * don't NPE. The stub returns null from [org.osgi.framework.BundleContext.getProperty], which causes
     * both methods to return false.
     */
    @Suppress("UNCHECKED_CAST")
    private fun injectPlatformActivatorContext() {
        try {
            val bundleContextClass = Class.forName("org.osgi.framework.BundleContext")
            val mockContext = Proxy.newProxyInstance(
                bundleContextClass.classLoader,
                arrayOf(bundleContextClass)
            ) { _, method, _ ->
                when (method.returnType) {
                    String::class.java -> null
                    Boolean::class.java, java.lang.Boolean.TYPE -> false
                    else -> null
                }
            }

            val platformActivatorClass = Class.forName("org.eclipse.core.internal.runtime.PlatformActivator")
            val contextField = platformActivatorClass.getDeclaredField("context")
            contextField.isAccessible = true
            contextField.set(null, mockContext)

            log.info("PlatformActivator.context mock injected")
        } catch (e: Exception) {
            log.warning("PlatformActivator injection failed: ${e.message}")
        }
    }

    /**
     * Injects [org.eclipse.mat.hprof.HprofPlugin] singleton so that
     * [org.eclipse.mat.hprof.HprofPlugin.getDefault] returns non-null.
     *
     * [org.eclipse.mat.hprof.ui.HprofPreferences.getCurrentStrictness] calls
     * `HprofPlugin.getDefault().getBundle().getSymbolicName()` — we inject a mock [Bundle]
     * proxy into the `bundle` field so the call chain succeeds.
     *
     * We also inject a stub [org.eclipse.core.runtime.preferences.IPreferencesService] into
     * [org.eclipse.core.internal.runtime.InternalPlatform] so that
     * [org.eclipse.core.runtime.Platform.getPreferencesService] returns non-null.
     * The stub returns an empty string for [org.eclipse.core.runtime.preferences.IPreferencesService.getString],
     * which causes [org.eclipse.mat.hprof.ui.HprofPreferences.HprofStrictness] to fall back to
     * the default permissive mode.
     */
    @Suppress("UNCHECKED_CAST")
    private fun injectHprofPlugin() {
        try {
            // 1. Create a HprofPlugin instance and inject the mock Bundle.
            val mockBundle = createMockBundle("org.eclipse.mat.hprof")

            // 2. Create a HprofPlugin instance and inject the mock Bundle.
            val hprofPluginClass = Class.forName("org.eclipse.mat.hprof.HprofPlugin")
            val hprofPluginInstance = hprofPluginClass.getDeclaredConstructor().newInstance()

            val bundleField = Class.forName("org.eclipse.core.runtime.Plugin").getDeclaredField("bundle")
            bundleField.isAccessible = true
            bundleField.set(hprofPluginInstance, mockBundle)

            val pluginField = hprofPluginClass.getDeclaredField("plugin")
            pluginField.isAccessible = true
            pluginField.set(null, hprofPluginInstance)

            log.info("HprofPlugin stub injected")

            // 3. Inject a stub IPreferencesService into InternalPlatform so
            //    Platform.getPreferencesService() returns non-null.
            val prefsServiceClass = Class.forName("org.eclipse.core.runtime.preferences.IPreferencesService")
            val stubPrefsService = Proxy.newProxyInstance(
                prefsServiceClass.classLoader,
                arrayOf(prefsServiceClass)
            ) { _, method, _ ->
                when (method.returnType) {
                    String::class.java -> ""
                    Boolean::class.java, java.lang.Boolean.TYPE -> false
                    Int::class.java, java.lang.Integer.TYPE -> 0
                    Long::class.java, java.lang.Long.TYPE -> 0L
                    Float::class.java, java.lang.Float.TYPE -> 0.0f
                    Double::class.java, java.lang.Double.TYPE -> 0.0
                    else -> null
                }
            }

            val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            val unsafe = unsafeField.get(null) as sun.misc.Unsafe

            val trackerClass = Class.forName("org.osgi.util.tracker.ServiceTracker")
            val prefsTracker = unsafe.allocateInstance(trackerClass)
            val cachedServiceField = trackerClass.getDeclaredField("cachedService")
            cachedServiceField.isAccessible = true
            cachedServiceField.set(prefsTracker, stubPrefsService)

            val internalPlatformClass = Class.forName("org.eclipse.core.internal.runtime.InternalPlatform")
            val internalPlatform = internalPlatformClass.getDeclaredMethod("getDefault").invoke(null)
            val prefsTrackerField = internalPlatformClass.getDeclaredField("preferencesTracker")
            prefsTrackerField.isAccessible = true
            prefsTrackerField.set(internalPlatform, prefsTracker)

            log.info("IPreferencesService stub injected into InternalPlatform")
        } catch (e: Exception) {
            log.warning("HprofPlugin injection failed: ${e.message}")
        }
    }
}
