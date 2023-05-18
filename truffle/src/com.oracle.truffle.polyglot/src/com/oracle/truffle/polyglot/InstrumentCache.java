/*
 * Copyright (c) 2015, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.polyglot;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.oracle.truffle.api.TruffleOptions;
import com.oracle.truffle.api.instrumentation.TruffleInstrument;
import com.oracle.truffle.api.instrumentation.TruffleInstrument.Registration;
import com.oracle.truffle.api.instrumentation.provider.TruffleInstrumentProvider;
import com.oracle.truffle.polyglot.EngineAccessor.AbstractClassLoaderSupplier;
import com.oracle.truffle.polyglot.EngineAccessor.StrongClassLoaderSupplier;
import org.graalvm.polyglot.SandboxPolicy;

final class InstrumentCache {
    private static final List<InstrumentCache> nativeImageCache = TruffleOptions.AOT ? new ArrayList<>() : null;
    private static Map<List<AbstractClassLoaderSupplier>, List<InstrumentCache>> runtimeCaches = new HashMap<>();

    private final String className;
    private final String id;
    private final String name;
    private final String version;
    private final String website;
    private final boolean internal;
    private final Set<String> services;
    private final ProviderAdapter providerAdapter;
    private final SandboxPolicy sandboxPolicy;

    /**
     * Initializes state for native image generation.
     *
     * NOTE: this method is called reflectively by downstream projects.
     *
     * @param imageClassLoader class loader passed by the image builder.
     */
    @SuppressWarnings("unused")
    private static void initializeNativeImageState(ClassLoader imageClassLoader) {
        nativeImageCache.addAll(doLoad(List.of(new StrongClassLoaderSupplier(imageClassLoader))));
    }

    /**
     * Collect tools included in a native image.
     *
     * NOTE: this method is called reflectively by TruffleBaseFeature
     */
    @SuppressWarnings("unused")
    private static Set<String> collectInstruments() {
        assert TruffleOptions.AOT : "Only supported during image generation";
        Set<String> res = new HashSet<>();
        for (InstrumentCache instrumentCache : nativeImageCache) {
            res.add(instrumentCache.id);
        }
        return res;
    }

    /**
     * Initializes state for native image generation.
     *
     * NOTE: this method is called reflectively by downstream projects.
     */
    @SuppressWarnings("unused")
    private static void resetNativeImageState() {
        nativeImageCache.clear();
        runtimeCaches.clear();
    }

    private InstrumentCache(String id, String name, String version, String className, boolean internal, Set<String> services,
                    ProviderAdapter providerAdapter, String website, SandboxPolicy sandboxPolicy) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.website = website;
        this.className = className;
        this.internal = internal;
        this.services = services;
        this.providerAdapter = providerAdapter;
        this.sandboxPolicy = sandboxPolicy;
    }

    boolean isInternal() {
        return internal;
    }

    static List<InstrumentCache> load() {
        if (TruffleOptions.AOT) {
            return nativeImageCache;
        }
        synchronized (InstrumentCache.class) {
            List<AbstractClassLoaderSupplier> classLoaders = EngineAccessor.locatorOrDefaultLoaders();
            List<InstrumentCache> cache = runtimeCaches.get(classLoaders);
            if (cache == null) {
                cache = doLoad(classLoaders);
                runtimeCaches.put(classLoaders, cache);
            }
            return cache;
        }
    }

    static <T> Iterable<T> loadTruffleService(Class<T> type) {
        List<T> result = new ArrayList<>();
        for (InstrumentCache cache : load()) {
            cache.providerAdapter.loadTruffleService(type).forEach(result::add);
        }
        return result;
    }

    static List<InstrumentCache> doLoad(List<AbstractClassLoaderSupplier> suppliers) {
        List<InstrumentCache> list = new ArrayList<>();
        Set<String> classNamesUsed = new HashSet<>();
        ClassLoader truffleClassLoader = InstrumentCache.class.getClassLoader();
        boolean usesTruffleClassLoader = false;
        for (AbstractClassLoaderSupplier supplier : suppliers) {
            ClassLoader loader = supplier.get();
            if (loader == null || !isValidLoader(loader)) {
                continue;
            }
            usesTruffleClassLoader |= truffleClassLoader == loader;
            loadProviders(loader).filter((p) -> supplier.accepts(p.getProviderClass())).forEach((p) -> loadInstrumentImpl(p, list, classNamesUsed));
            loadDeprecatedProviders(loader).filter((p) -> supplier.accepts(p.getProviderClass())).forEach((p) -> loadInstrumentImpl(p, list, classNamesUsed));
        }
        /*
         * Resolves a missing debugger instrument when the GuestLangToolsClassLoader does not define
         * module. If the ClassLoader does not define module it has no ServiceCatalog. The
         * ServiceLoader does not load module services from parent classloader. This code can be
         * removed if we add system classloader into GraalVMLocator.
         */
        if (!usesTruffleClassLoader) {
            Module truffleModule = InstrumentCache.class.getModule();
            loadProviders(truffleClassLoader).//
                            filter((p) -> p.getProviderClass().getModule().equals(truffleModule)).//
                            forEach((p) -> loadInstrumentImpl(p, list, classNamesUsed));
        }
        list.sort(Comparator.comparing(InstrumentCache::getId));
        return list;
    }

    @SuppressWarnings("deprecation")
    private static Stream<? extends ProviderAdapter> loadDeprecatedProviders(ClassLoader loader) {
        return StreamSupport.stream(ServiceLoader.load(TruffleInstrument.Provider.class, loader).spliterator(), false).map(DeprecatedProvider::new);
    }

    private static Stream<? extends ProviderAdapter> loadProviders(ClassLoader loader) {
        return StreamSupport.stream(ServiceLoader.load(TruffleInstrumentProvider.class, loader).spliterator(), false).map(NewProvider::new);
    }

    private static void loadInstrumentImpl(ProviderAdapter providerAdapter, List<? super InstrumentCache> list, Set<? super String> classNamesUsed) {
        Class<?> providerClass = providerAdapter.getProviderClass();
        Module providerModule = providerClass.getModule();
        ModuleUtils.exportTransitivelyTo(providerModule);
        Registration reg = providerClass.getAnnotation(Registration.class);
        if (reg == null) {
            emitWarning("Provider %s is missing @Registration annotation.", providerClass);
            return;
        }
        String className = providerAdapter.getInstrumentClassName();
        String name = reg.name();
        String id = reg.id();
        if (id == null || id.isEmpty()) {
            /* use class name default id */
            int lastIndex = className.lastIndexOf('$');
            if (lastIndex == -1) {
                lastIndex = className.lastIndexOf('.');
            }
            id = className.substring(lastIndex + 1);
        }
        String version = reg.version();
        String website = reg.website();
        SandboxPolicy sandboxPolicy = reg.sandbox();
        boolean internal = reg.internal();
        Set<String> servicesClassNames = new TreeSet<>(providerAdapter.getServicesClassNames());
        // we don't want multiple instruments with the same class name
        if (!classNamesUsed.contains(className)) {
            classNamesUsed.add(className);
            list.add(new InstrumentCache(id, name, version, className, internal, servicesClassNames, providerAdapter, website, sandboxPolicy));
        }
    }

    private static boolean isValidLoader(ClassLoader loader) {
        try {
            Class<?> truffleInstrumentClassAsSeenByLoader = Class.forName(TruffleInstrument.class.getName(), true, loader);
            return truffleInstrumentClassAsSeenByLoader == TruffleInstrument.class;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    String getId() {
        return id;
    }

    String getName() {
        return name;
    }

    String getClassName() {
        return className;
    }

    String getVersion() {
        return version;
    }

    TruffleInstrument loadInstrument() {
        return providerAdapter.create();
    }

    boolean supportsService(Class<?> clazz) {
        return services.contains(clazz.getName()) || services.contains(clazz.getCanonicalName());
    }

    String[] services() {
        return services.toArray(new String[0]);
    }

    String getWebsite() {
        return website;
    }

    SandboxPolicy getSandboxPolicy() {
        return sandboxPolicy;
    }

    private static void emitWarning(String message, Object... args) {
        PrintStream out = System.err;
        out.printf("[engine] " + message + "%n", args);
    }

    private interface ProviderAdapter {
        Class<?> getProviderClass();

        TruffleInstrument create();

        String getInstrumentClassName();

        Collection<String> getServicesClassNames();

        <T> Iterable<T> loadTruffleService(Class<T> type);
    }

    @SuppressWarnings("deprecation")
    private static final class DeprecatedProvider implements ProviderAdapter {

        private final TruffleInstrument.Provider provider;

        DeprecatedProvider(TruffleInstrument.Provider provider) {
            Objects.requireNonNull(provider, "Provider must be non null");
            this.provider = provider;
        }

        @Override
        public Class<?> getProviderClass() {
            return provider.getClass();
        }

        @Override
        public TruffleInstrument create() {
            return provider.create();
        }

        @Override
        public String getInstrumentClassName() {
            return provider.getInstrumentClassName();
        }

        @Override
        public Collection<String> getServicesClassNames() {
            return provider.getServicesClassNames();
        }

        @Override
        public <T> Iterable<T> loadTruffleService(Class<T> type) {
            return List.of();
        }
    }

    private static final class NewProvider implements ProviderAdapter {

        private final TruffleInstrumentProvider provider;

        NewProvider(TruffleInstrumentProvider provider) {
            Objects.requireNonNull(provider, "Provider must be non null");
            this.provider = provider;
        }

        @Override
        public Class<?> getProviderClass() {
            return provider.getClass();
        }

        @Override
        public TruffleInstrument create() {
            return (TruffleInstrument) EngineAccessor.INSTRUMENT_PROVIDER.create(provider);
        }

        @Override
        public String getInstrumentClassName() {
            return EngineAccessor.INSTRUMENT_PROVIDER.getInstrumentClassName(provider);
        }

        @Override
        public Collection<String> getServicesClassNames() {
            return EngineAccessor.INSTRUMENT_PROVIDER.getServicesClassNames(provider);
        }

        @Override
        public <T> Iterable<T> loadTruffleService(Class<T> type) {
            return EngineAccessor.INSTRUMENT_PROVIDER.loadTruffleService(provider, type);
        }
    }
}
