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
package io.vidocq.dirac.internal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * MicroProfile Metrics 5.1.1 spec, "Configuration" of histograms and timers: the
 * {@code mp.metrics.distribution.percentiles}, {@code mp.metrics.distribution.histogram.buckets}
 * and {@code mp.metrics.distribution.timer.buckets} properties, as {@code <name-pattern>=<values>}
 * entries separated by {@code ;}, the most specific pattern winning.
 */
class DistributionConfigTest {

    private static final String PERCENTILES = "mp.metrics.distribution.percentiles";
    private static final String HISTOGRAM_BUCKETS = "mp.metrics.distribution.histogram.buckets";
    private static final String TIMER_BUCKETS = "mp.metrics.distribution.timer.buckets";
    private static final double[] DEFAULTS = {0.5, 0.99};
    private static final double[] NONE = {};

    @AfterEach
    void clearProperties() {
        System.clearProperty(PERCENTILES);
        System.clearProperty(HISTOGRAM_BUCKETS);
        System.clearProperty(TIMER_BUCKETS);
    }

    @Test
    void percentilesDefaultWhenNothingIsConfigured() {
        assertArrayEquals(DEFAULTS, DistributionConfig.resolvePercentiles("orders", DEFAULTS));
    }

    @Test
    void percentilesDefaultForUnmatchedOrBlankMetricName() {
        System.setProperty(PERCENTILES, "other=0.1");
        assertArrayEquals(DEFAULTS, DistributionConfig.resolvePercentiles("orders", DEFAULTS));
        assertArrayEquals(DEFAULTS, DistributionConfig.resolvePercentiles(" ", DEFAULTS));
        assertArrayEquals(DEFAULTS, DistributionConfig.resolvePercentiles(null, DEFAULTS));
    }

    @Test
    void percentilesKeepOnlyValuesStrictlyBetweenZeroAndOne() {
        System.setProperty(PERCENTILES, "orders=0.1, 0.25,,1.0,0,abc,0.999");
        assertArrayEquals(new double[]{0.1, 0.25, 0.999}, DistributionConfig.resolvePercentiles("orders", DEFAULTS));
    }

    @Test
    void emptyPercentileListDisablesPercentiles() {
        System.setProperty(PERCENTILES, "orders=");
        assertArrayEquals(NONE, DistributionConfig.resolvePercentiles("orders", DEFAULTS));
    }

    @Test
    void percentileListWithoutValidValueDisablesPercentiles() {
        System.setProperty(PERCENTILES, "orders=2,abc");
        assertArrayEquals(NONE, DistributionConfig.resolvePercentiles("orders", DEFAULTS));
    }

    @Test
    void exactNameWinsOverWildcard() {
        System.setProperty(PERCENTILES, "ord*=0.1;orders=0.2;*=0.3");
        assertArrayEquals(new double[]{0.2}, DistributionConfig.resolvePercentiles("orders", DEFAULTS));
    }

    @Test
    void mostSpecificWildcardWins() {
        System.setProperty(PERCENTILES, "*=0.3;app.ord*=0.1;app.*=0.2");
        assertArrayEquals(new double[]{0.1}, DistributionConfig.resolvePercentiles("app.orders", DEFAULTS));
        assertArrayEquals(new double[]{0.2}, DistributionConfig.resolvePercentiles("app.items", DEFAULTS));
        assertArrayEquals(new double[]{0.3}, DistributionConfig.resolvePercentiles("other", DEFAULTS));
    }

    @Test
    void wildcardPatternQuotesRegexCharacters() {
        System.setProperty(PERCENTILES, "a.b*=0.4");
        assertArrayEquals(DEFAULTS, DistributionConfig.resolvePercentiles("axb", DEFAULTS));
        assertArrayEquals(new double[]{0.4}, DistributionConfig.resolvePercentiles("a.b.c", DEFAULTS));
    }

    @Test
    void entriesWithoutPatternAreIgnored() {
        System.setProperty(PERCENTILES, "=0.1;novalue;orders=0.6");
        assertArrayEquals(new double[]{0.6}, DistributionConfig.resolvePercentiles("orders", DEFAULTS));
    }

    @Test
    void histogramBucketsKeepPositiveNumbers() {
        System.setProperty(HISTOGRAM_BUCKETS, "sizes=1,2.5,-3,0,x,10");
        assertArrayEquals(new double[]{1, 2.5, 10}, DistributionConfig.resolveHistogramBuckets("sizes"));
    }

    @Test
    void histogramBucketsAreEmptyWhenUnsetOrBlank() {
        assertArrayEquals(NONE, DistributionConfig.resolveHistogramBuckets("sizes"));
        System.setProperty(HISTOGRAM_BUCKETS, "sizes=");
        assertArrayEquals(NONE, DistributionConfig.resolveHistogramBuckets("sizes"));
    }

    @Test
    void timerBucketsAreConvertedToNanoseconds() {
        System.setProperty(TIMER_BUCKETS, "latency=5ns,2us,3ms,1s,2m,1h,250");
        assertArrayEquals(new double[]{5, 2_000, 3_000_000, 1_000_000_000d, 120_000_000_000d,
                        3_600_000_000_000d, 250_000_000},
                DistributionConfig.resolveTimerBuckets("latency"));
    }

    @Test
    void invalidTimerBucketsAreIgnored() {
        System.setProperty(TIMER_BUCKETS, "latency=1.5,abcms,-2s,0ms,7MS");
        assertArrayEquals(new double[]{7_000_000}, DistributionConfig.resolveTimerBuckets("latency"));
    }

    @Test
    void timerBucketsAreEmptyWhenUnsetOrBlank() {
        assertArrayEquals(NONE, DistributionConfig.resolveTimerBuckets("latency"));
        System.setProperty(TIMER_BUCKETS, "latency= ");
        assertArrayEquals(NONE, DistributionConfig.resolveTimerBuckets("latency"));
    }

    @Test
    void readsMicroProfileConfigPropertiesOfTheContextClassLoader(@TempDir Path root) throws IOException {
        var metaInf = Files.createDirectories(root.resolve("META-INF"));
        Files.writeString(metaInf.resolve("microprofile-config.properties"),
                HISTOGRAM_BUCKETS + "=sizes=4,8\n");
        try (var loader = new URLClassLoader(new URL[]{root.toUri().toURL()}, null)) {
            var buckets = withContextClassLoader(loader, () -> DistributionConfig.resolveHistogramBuckets("sizes"));
            var percentiles = withContextClassLoader(loader,
                    () -> DistributionConfig.resolvePercentiles("sizes", DEFAULTS));

            assertArrayEquals(new double[]{4, 8}, buckets);
            assertArrayEquals(DEFAULTS, percentiles);
        }
    }

    @Test
    void systemPropertyWinsOverConfigFile(@TempDir Path root) throws IOException {
        var metaInf = Files.createDirectories(root.resolve("META-INF"));
        Files.writeString(metaInf.resolve("microprofile-config.properties"), HISTOGRAM_BUCKETS + "=sizes=4\n");
        System.setProperty(HISTOGRAM_BUCKETS, "sizes=9");
        try (var loader = new URLClassLoader(new URL[]{root.toUri().toURL()}, null)) {
            assertArrayEquals(new double[]{9},
                    withContextClassLoader(loader, () -> DistributionConfig.resolveHistogramBuckets("sizes")));
        }
    }

    @Test
    void unreadableConfigResourcesFallBackToDefaults() {
        var failing = new ClassLoader(null) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                throw new IOException("unreadable");
            }
        };
        assertArrayEquals(NONE,
                withContextClassLoader(failing, () -> DistributionConfig.resolveHistogramBuckets("sizes")));
    }

    @Test
    void missingContextClassLoaderUsesTheDefiningLoader() {
        var empty = new ClassLoader(null) {
            @Override
            public Enumeration<URL> getResources(String name) {
                return Collections.emptyEnumeration();
            }
        };
        assertArrayEquals(NONE, withContextClassLoader(null, () -> DistributionConfig.resolveTimerBuckets("t")));
        assertArrayEquals(NONE, withContextClassLoader(empty, () -> DistributionConfig.resolveTimerBuckets("t")));
    }

    private static double[] withContextClassLoader(ClassLoader loader, java.util.function.Supplier<double[]> action) {
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return action.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
