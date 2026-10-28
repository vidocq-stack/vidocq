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
package io.vidocq.dirac.cdi.internal;

import io.vidocq.dirac.spi.gen.MetricsCompanion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * CG-05 companion resolution chain: ServiceLoader first, then the {@code $$DiracMetrics}
 * naming convention, and {@code null} (annotation scan) for a stale or broken companion.
 * The tests run on the module path, where a {@code META-INF/services} entry naming a class
 * of a named module is not a provider, so the ServiceLoader hit itself (a {@code provides}
 * declaration) is out of reach of these tests.
 */
class CompanionRegistryTest {

    private static final String SERVICES = "META-INF/services/" + MetricsCompanion.class.getName();

    @TempDir
    Path root;

    @BeforeEach
    void resetCounters() {
        CompanionRegistry.resetForTests();
    }

    @Test
    void brokenServiceEntryFallsBackToTheNamingConvention() throws IOException {
        var companion = resolveWithServices("io.vidocq.dirac.cdi.internal.DoesNotExist$$DiracMetrics");

        assertInstanceOf(MeteredService$$DiracMetrics.class, companion);
        assertEquals(0, CompanionRegistry.serviceLoaderHits());
        assertEquals(1, CompanionRegistry.preGeneratedHits());
    }

    @Test
    void companionOfAnotherClassIsIgnored() {
        assertNull(CompanionRegistry.resolve(Stale.class));
        assertEquals(0, CompanionRegistry.preGeneratedHits());
    }

    @Test
    void classThatIsNotACompanionIsIgnored() {
        assertNull(CompanionRegistry.resolve(Broken.class));
        assertEquals(0, CompanionRegistry.preGeneratedHits());
    }

    @Test
    void scanFallbacksAreCounted() {
        CompanionRegistry.noteScanFallback();

        assertEquals(1, CompanionRegistry.scanFallbacks());
    }

    private MetricsCompanion resolveWithServices(String provider) throws IOException {
        var services = root.resolve(SERVICES);
        Files.createDirectories(services.getParent());
        Files.writeString(services, provider + "\n");
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        try (var loader = new URLClassLoader(new URL[]{root.toUri().toURL()}, getClass().getClassLoader())) {
            thread.setContextClassLoader(loader);
            return CompanionRegistry.resolve(MeteredService.class);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    static class Stale {
    }

    /** Names a companion for {@link Stale} that describes another class (stale jar). */
    public static final class Stale$$DiracMetrics implements MetricsCompanion {
        @Override
        public Class<?> beanClass() {
            return String.class;
        }

        @Override
        public List<MetricSpec> timers() {
            return List.of();
        }

        @Override
        public List<MetricSpec> counters() {
            return List.of();
        }

        @Override
        public List<GaugeSpec> gauges() {
            return List.of();
        }
    }

    static class Broken {
    }

    /** Follows the naming convention without being a {@link MetricsCompanion}. */
    public static final class Broken$$DiracMetrics {
    }
}
