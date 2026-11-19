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
package io.vidocq.runtime.core.commonpool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CommonPoolThreadFactory — the common pool's workers get the application's loader")
class CommonPoolThreadFactoryTest {

    private final ClassLoader application = new ClassLoader("application-layer", null) { };
    private final ClassLoader next = new ClassLoader("next-layer", null) { };

    @AfterEach
    void forgetTheLoader() {
        CommonPoolThreadFactory.useLoader(null);
    }

    @Test
    @DisplayName("a worker gets the application's loader, and the system class loader without one, as the JDK gives")
    void aWorkerGetsTheApplicationsLoader() throws Exception {
        CommonPoolThreadFactory.useLoader(application);
        ForkJoinPool pool = pool();
        try {
            ForkJoinWorkerThread worker = new CommonPoolThreadFactory().newThread(pool);
            assertSame(application, worker.getContextClassLoader());
            assertTrue(worker.isDaemon(), "a worker never keeps the JVM alive, as the JDK's");
        } finally {
            pool.shutdownNow();
        }
        CommonPoolThreadFactory.useLoader(null);
        ForkJoinPool other = pool();
        try {
            assertSame(ClassLoader.getSystemClassLoader(),
                    new CommonPoolThreadFactory().newThread(other).getContextClassLoader());
        } finally {
            other.shutdownNow();
        }
    }

    /** A dev reload installs a new layer while the pool's workers live on: they move to it. */
    @Test
    @DisplayName("a live worker moves to the loader of the next layer")
    void aLiveWorkerMovesToTheNextLayer() throws Exception {
        CommonPoolThreadFactory.useLoader(application);
        ForkJoinPool pool = pool();
        try {
            Thread first = pool.submit(Thread::currentThread).get(30, TimeUnit.SECONDS);
            assertSame(application, pool.submit(() -> Thread.currentThread().getContextClassLoader())
                    .get(30, TimeUnit.SECONDS));

            CommonPoolThreadFactory.useLoader(next);

            Thread second = pool.submit(Thread::currentThread).get(30, TimeUnit.SECONDS);
            assertSame(first, second, "the same worker, alive across the reload");
            assertSame(next, pool.submit(() -> Thread.currentThread().getContextClassLoader())
                    .get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("prepare names the factory when nothing did, and keeps a factory the launch named")
    void prepareKeepsTheLaunchersChoice() {
        String before = System.getProperty(CommonPoolThreadFactory.PROPERTY);
        try {
            System.clearProperty(CommonPoolThreadFactory.PROPERTY);
            CommonPoolThreadFactory.prepare();
            assertEquals(CommonPoolThreadFactory.class.getName(), System.getProperty(CommonPoolThreadFactory.PROPERTY));

            System.setProperty(CommonPoolThreadFactory.PROPERTY, "acme.OwnFactory");
            CommonPoolThreadFactory.prepare();
            assertEquals("acme.OwnFactory", System.getProperty(CommonPoolThreadFactory.PROPERTY));
        } finally {
            if (before == null) {
                System.clearProperty(CommonPoolThreadFactory.PROPERTY);
            } else {
                System.setProperty(CommonPoolThreadFactory.PROPERTY, before);
            }
        }
    }

    /** One worker at most, so that the second task runs on the worker of the first. */
    private static ForkJoinPool pool() {
        return new ForkJoinPool(1, new CommonPoolThreadFactory(), null, false);
    }
}
