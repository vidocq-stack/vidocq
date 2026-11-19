/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.peano.core.internal.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.vidocq.peano.core.internal.PeanoExpressionFactory;
import io.vidocq.peano.core.internal.evaluation.layer.LayerBean;
import io.vidocq.peano.core.internal.evaluation.layer.LayerBeanProxy;
import io.vidocq.peano.core.internal.evaluation.layer.LayerProvider;
import io.vidocq.peano.core.internal.evaluation.layer.LayerTable;
import io.vidocq.peano.core.internal.evaluation.layer.LayerUnwrapper;
import io.vidocq.peano.spi.AccessorProvider;
import io.vidocq.peano.spi.ProxyUnwrapper;
import jakarta.el.ELContext;
import jakarta.el.StandardELContext;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code vidocq dev} boots a fresh application layer on every hot reload, while the Jakarta EL API keeps the first
 * expression factory in a static field of its own (EL-023): the factory must hold nothing of a layer, or
 * every discarded layer stays reachable from that static. The same goes for the proxy unwrappers a layer brings
 * (EL-025): the engine registers them in a static of {@code peano-api}, which outlives every layer.
 */
class CatalogLayerLeakTest {
    @TempDir
    Path directory;

    private Path services;
    /** The services file of a layer that brings its own proxy unwrapper. */
    private Path unwrappers;
    /** The factory {@code ExpressionFactory.newInstance} makes under the first layer, then keeps for the JVM. */
    private PeanoExpressionFactory factory;

    @BeforeEach
    void writeServicesFiles() throws IOException {
        services = directory.resolve("provider.services");
        Files.writeString(services, LayerProvider.class.getName() + "\n", StandardCharsets.UTF_8);
        unwrappers = directory.resolve("unwrapper.services");
        Files.writeString(unwrappers, LayerUnwrapper.class.getName() + "\n", StandardCharsets.UTF_8);
    }

    @Test
    void aDiscardedLayerIsCollectableWhileTheFirstFactoryLives() throws Exception {
        WeakReference<ClassLoader> first = firstLayer();
        assertNull(awaitCollected(first), "the first layer's class loader is still reachable from the factory");
    }

    @Test
    void aDiscardedLayerThatBroughtAProxyUnwrapperIsCollectable() throws Exception {
        WeakReference<ClassLoader> layer = layerThatServedItsProxy();
        assertNull(awaitCollected(layer),
                "the layer's class loader is still reachable from the proxy unwrapper the engine registered for it");
    }

    @Test
    void theProxyUnwrapperOfALiveLayerOutlivesACollection() throws Exception {
        ClassLoader loader = new LayerLoader(services, unwrappers);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            factory = new PeanoExpressionFactory();
            assertEquals("layer", evaluate(loader).value());
            assertNull(awaitCollected(new WeakReference<>(new Object())), "no collection ran");
            assertEquals("layer", evaluate(loader, LayerBeanProxy.class).value(),
                    "the layer is alive and its proxy unwrapper no longer serves its proxy");
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void aLaterLayerHasItsCatalogOnceAndNotTheFirstLayersOne() throws Exception {
        firstLayer();
        ClassLoader second = new LayerLoader(services);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(second);
        try {
            AccessorCatalog catalog = catalogOf(second);
            assertSame(catalog, catalogOf(second));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void twoLayersDoNotShareACatalog() throws Exception {
        ClassLoader one = new LayerLoader(services);
        ClassLoader two = new LayerLoader(services);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(one);
            factory = new PeanoExpressionFactory();
            AccessorCatalog inOne = catalogOf(one);
            Thread.currentThread().setContextClassLoader(two);
            AccessorCatalog inTwo = catalogOf(two);
            assertNotSame(inOne, inTwo);
            Thread.currentThread().setContextClassLoader(one);
            assertSame(inOne, catalogOf(one));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private WeakReference<ClassLoader> firstLayer() throws Exception {
        ClassLoader loader = new LayerLoader(services);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            factory = new PeanoExpressionFactory();
            assertEquals("layer", evaluate(loader).value());
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
        return new WeakReference<>(loader);
    }

    /** A layer with its own proxy unwrapper: the engine registers it, and the layer's table serves the proxy. */
    private WeakReference<ClassLoader> layerThatServedItsProxy() throws Exception {
        ClassLoader loader = new LayerLoader(services, unwrappers);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            factory = new PeanoExpressionFactory();
            assertEquals("layer", evaluate(loader, LayerBeanProxy.class).value());
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
        return new WeakReference<>(loader);
    }

    private AccessorCatalog catalogOf(ClassLoader loader) throws Exception {
        Evaluation evaluation = evaluate(loader);
        assertEquals("layer", evaluation.value());
        return ((GeneratedAccessorELResolver) evaluation.context().getContext(GeneratedAccessorELResolver.class)).catalog();
    }

    private Evaluation evaluate(ClassLoader loader) throws Exception {
        return evaluate(loader, LayerBean.class);
    }

    /** Evaluates {@code ${b.name}} on an instance of the layer's own copy of {@code type}. */
    private Evaluation evaluate(ClassLoader loader, Class<?> type) throws Exception {
        Object bean = loader.loadClass(type.getName()).getConstructor().newInstance();
        var context = new StandardELContext(factory);
        context.getVariableMapper().setVariable("b", factory.createValueExpression(bean, Object.class));
        return new Evaluation(context, factory.createValueExpression(context, "${b.name}", Object.class).getValue(context));
    }

    /** Waits for the collector to take {@code reference}; its referent while it is still reachable, else null. */
    private static <T> T awaitCollected(WeakReference<T> reference) {
        for (int attempt = 0; attempt < 100 && reference.get() != null; attempt++) {
            System.gc();
            LockSupport.parkNanos(50_000_000L);
        }
        return reference.get();
    }

    private record Evaluation(ELContext context, Object value) {
    }

    /** An application layer: its own copy of the {@code layer} classes, and the services files naming its providers. */
    private static final class LayerLoader extends ClassLoader {
        private static final String SERVICES = "META-INF/services/";
        private static final List<String> OWN = List.of(LayerBean.class.getName(), LayerTable.class.getName(),
                LayerProvider.class.getName(), LayerBeanProxy.class.getName(), LayerUnwrapper.class.getName());
        /** The services files of the layer, by resource name. */
        private final Map<String, Path> served;

        /** A layer whose jar names its accessor provider. */
        LayerLoader(Path services) {
            super(CatalogLayerLeakTest.class.getClassLoader());
            served = Map.of(SERVICES + AccessorProvider.class.getName(), services);
        }

        /** A layer whose jar names its accessor provider and its own proxy unwrapper. */
        LayerLoader(Path services, Path unwrappers) {
            super(CatalogLayerLeakTest.class.getClassLoader());
            served = Map.of(SERVICES + AccessorProvider.class.getName(), services,
                    SERVICES + ProxyUnwrapper.class.getName(), unwrappers);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!OWN.contains(name)) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                return loaded != null ? loaded : define(name);
            }
        }

        private Class<?> define(String name) throws ClassNotFoundException {
            try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                if (in == null) throw new ClassNotFoundException(name);
                byte[] bytes = in.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            Path file = served.get(name);
            return file == null ? super.getResources(name) : Collections.enumeration(List.of(file.toUri().toURL()));
        }
    }
}
