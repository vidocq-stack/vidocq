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
package io.vidocq.runtime.extensions.microprofile.ravel;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The MicroProfile Config registry of Ravel is keyed by class loader and lives as long as the JVM, so the
 * configuration of an application layer stays reachable, and the layer with it, until somebody releases it:
 * the runtime never did, and every hot reload of {@code vidocq dev} kept one more layer in memory.
 */

@DisplayName("RavelConfigExtension - the configuration of a layer does not outlive it")
class RavelConfigExtensionTest {

    private static ClassLoader loader(ClassLoader parent) {
        return new URLClassLoader(new URL[0], parent);
    }

    /** Runs {@code body} with {@code layer} as the context class loader, as the runtime boots a layer. */
    private static void inLayer(ClassLoader layer, Runnable body) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(layer);
        try {
            body.run();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    @DisplayName("a stopped layer can be garbage collected")
    void stoppedLayerIsCollectable() {
        WeakReference<ClassLoader> layer = bootAndStopOneLayer();

        for (int i = 0; i < 50 && layer.get() != null; i++) {
            System.gc();
        }
        assertNull(layer.get(), "the class loader of the stopped layer is still reachable from the config registry");
    }

    @Test
    @DisplayName("onStop releases the configuration the layer was booted with")
    void stopReleasesTheLayerConfig() {
        ClassLoader layer = loader(getClass().getClassLoader());
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        inLayer(layer, () -> {
            RavelConfigExtension extension = new RavelConfigExtension();
            extension.configure(null);
            Config booted = resolver.getConfig(layer);
            assertSame(booted, resolver.getConfig(layer), "the layer keeps one configuration while it runs");

            extension.onStop();

            Config afterStop = resolver.getConfig(layer);
            assertNotSame(booted, afterStop, "the registry still held the configuration of the stopped layer");
            resolver.releaseConfig(afterStop);
        });
    }

    @Test
    @DisplayName("onStop also releases the configuration of a loader built on top of the layer's")
    void stopReleasesTheLoadersBuiltOnTheLayer() {
        ClassLoader layer = loader(getClass().getClassLoader());
        ClassLoader composite = loader(layer);
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        inLayer(layer, () -> {
            RavelConfigExtension extension = new RavelConfigExtension();
            extension.configure(null);
            Config underComposite = resolver.getConfig(composite);

            extension.onStop();

            Config afterStop = resolver.getConfig(composite);
            assertNotSame(underComposite, afterStop, "the configuration read under the composite loader stayed");
            resolver.releaseConfig(afterStop);
        });
    }

    @Test
    @DisplayName("onStop is harmless when the boot failed before configure, and when called twice")
    void stopIsIdempotent() {
        RavelConfigExtension neverConfigured = new RavelConfigExtension();
        assertDoesNotThrow(neverConfigured::onStop);

        inLayer(loader(getClass().getClassLoader()), () -> {
            RavelConfigExtension extension = new RavelConfigExtension();
            extension.configure(null);
            extension.onStop();
            assertDoesNotThrow(extension::onStop);
        });
    }

    @Test
    @DisplayName("the stop of a layer leaves the configuration of another loader alone")
    void stopLeavesOtherLoadersAlone() {
        ClassLoader first = loader(getClass().getClassLoader());
        ClassLoader second = loader(getClass().getClassLoader());
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        Config other = resolver.getConfig(second);
        inLayer(first, () -> {
            RavelConfigExtension extension = new RavelConfigExtension();
            extension.configure(null);
            extension.onStop();
        });

        assertSame(other, resolver.getConfig(second));
        resolver.releaseConfig(other);
        resolver.releaseConfig(resolver.getConfig(first));
    }

    /** Boots then stops a layer through the extension, and returns a weak handle on its class loader. */
    private WeakReference<ClassLoader> bootAndStopOneLayer() {
        ClassLoader layer = loader(getClass().getClassLoader());
        inLayer(layer, () -> {
            RavelConfigExtension extension = new RavelConfigExtension();
            extension.configure(null);
            // the application reads its configuration while it runs, under the layer's loader and under one
            // the container builds on it
            ConfigProvider.getConfig();
            ConfigProviderResolver.instance().getConfig(loader(layer));
            extension.onStop();
        });
        return new WeakReference<>(layer);
    }
}
