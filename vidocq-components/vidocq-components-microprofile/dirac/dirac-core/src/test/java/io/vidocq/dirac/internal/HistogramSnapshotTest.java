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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Histogram snapshots, MicroProfile Metrics 5.1.1 §3.3 ({@code Snapshot}: max, mean,
 * percentiles, histogram buckets and {@code dump}), including the empty histogram and
 * configured buckets / disabled percentiles.
 */
class HistogramSnapshotTest {

    private static final String PERCENTILES = "mp.metrics.distribution.percentiles";
    private static final String HISTOGRAM_BUCKETS = "mp.metrics.distribution.histogram.buckets";

    @AfterEach
    void clearProperties() {
        System.clearProperty(PERCENTILES);
        System.clearProperty(HISTOGRAM_BUCKETS);
    }

    @Test
    void emptyHistogramReportsZeros() {
        var histogram = new HistogramImpl();
        var snapshot = histogram.getSnapshot();
        var typed = histogram.snapshot();

        assertEquals(0, snapshot.size());
        assertEquals(0d, snapshot.getMax());
        assertEquals(0d, snapshot.getMean());
        assertEquals(0d, snapshot.percentileValues()[0].getValue());
        assertEquals(0, typed.min());
        assertEquals(0, typed.max());
        assertEquals(0d, typed.p999());
    }

    @Test
    void configuredBucketsCountValuesAtOrBelowTheirBound() {
        System.setProperty(HISTOGRAM_BUCKETS, "sizes=5,10");
        var histogram = new HistogramImpl("sizes", false);
        histogram.update(1);
        histogram.update(5);
        histogram.update(7);
        histogram.update(12);

        var buckets = histogram.getSnapshot().bucketValues();

        assertEquals(2, buckets.length);
        assertEquals(5d, buckets[0].getBucket());
        assertEquals(2, buckets[0].getCount());
        assertEquals(10d, buckets[1].getBucket());
        assertEquals(3, buckets[1].getCount());
    }

    @Test
    void disabledPercentilesLeaveTheTypedSnapshotAtZero() {
        System.setProperty(PERCENTILES, "quiet=");
        var histogram = new HistogramImpl("quiet", false);
        histogram.update(4);

        var typed = histogram.snapshot();

        assertEquals(0, histogram.getSnapshot().percentileValues().length);
        assertEquals(0d, typed.p50());
        assertEquals(4, typed.max());
    }

    @Test
    void dumpWritesOneSortedValuePerLine() {
        var histogram = new HistogramImpl();
        histogram.update(9);
        histogram.update(2);
        var out = new ByteArrayOutputStream();

        histogram.getSnapshot().dump(out);

        assertEquals("2\n9\n", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void dumpFailureIsReported() {
        var histogram = new HistogramImpl();
        histogram.update(1);
        var snapshot = histogram.getSnapshot();
        var failing = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("closed");
            }
        };

        assertThrows(IllegalStateException.class, () -> snapshot.dump(failing));
    }
}
