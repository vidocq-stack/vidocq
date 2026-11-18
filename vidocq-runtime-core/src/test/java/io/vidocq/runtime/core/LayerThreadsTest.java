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

package io.vidocq.runtime.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LayerThreads — threads that still hold a closing layer")
class LayerThreadsTest {

    @Test
    @DisplayName("a live thread whose context loader is the layer's is reported by name; others are not")
    void reportsThreadsHoldingTheLoader() throws Exception {
        ClassLoader layerLoader = new ClassLoader(null) { };
        var stop = new CountDownLatch(1);
        Thread holder = new Thread(() -> awaitQuietly(stop), "acme-poller");
        holder.setContextClassLoader(layerLoader);
        holder.setDaemon(true);
        holder.start();
        try {
            var names = LayerThreads.holding(layerLoader);
            assertTrue(names.contains("acme-poller"), names.toString());
            assertFalse(names.contains(Thread.currentThread().getName()));
        } finally {
            stop.countDown();
            holder.join();
        }
        assertFalse(LayerThreads.holding(layerLoader).contains("acme-poller"), "a stopped thread is not reported");
    }

    /**
     * The container may run the application's start under a loader of its own built on the layer's, Vauban's
     * {@code CompositeClassLoader}: a thread the application starts then inherits that child, which keeps the layer
     * in memory as well (BUG-20261118-94).
     */
    @Test
    @DisplayName("a thread whose context loader is a child of the layer's loader is reported too")
    void reportsThreadsHoldingAChildOfTheLoader() throws Exception {
        ClassLoader layerLoader = new ClassLoader(null) { };
        ClassLoader composite = new ClassLoader(layerLoader) { };
        ClassLoader unrelated = new ClassLoader(null) { };
        var stop = new CountDownLatch(1);
        Thread holder = new Thread(() -> awaitQuietly(stop), "acme-child-poller");
        holder.setContextClassLoader(composite);
        holder.setDaemon(true);
        Thread other = new Thread(() -> awaitQuietly(stop), "acme-unrelated");
        other.setContextClassLoader(unrelated);
        other.setDaemon(true);
        holder.start();
        other.start();
        try {
            var names = LayerThreads.holding(layerLoader);
            assertTrue(names.contains("acme-child-poller"), names.toString());
            assertFalse(names.contains("acme-unrelated"), names.toString());
        } finally {
            stop.countDown();
            holder.join();
            other.join();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }
}
