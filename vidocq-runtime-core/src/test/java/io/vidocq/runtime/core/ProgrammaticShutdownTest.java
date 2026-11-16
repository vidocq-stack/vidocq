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

import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Vidocq#shutdown()} and {@link Vidocq#shutdownAndWait()} let the application end the running deployment from
 * its own code, without {@code System.exit}: extensions stop in order, the container closes (the CDI
 * {@code Shutdown} event fires) and {@link Vidocq#waitForExit()} returns.
 */
class ProgrammaticShutdownTest {

    static final AtomicInteger OBSERVED = new AtomicInteger();

    private static final Duration LIMIT = Duration.ofSeconds(20);

    private final AtomicInteger stops = new AtomicInteger();
    private final AtomicReference<Thread> stoppedOn = new AtomicReference<>();

    @BeforeEach
    void clean() {
        Vidocq.unregister();
        OBSERVED.set(0);
    }

    @AfterEach
    void forget() {
        Vidocq.unregister();
    }

    @Test
    void shutdownReturnsAtOnceAndStopsOnAnotherVirtualThread() throws Exception {
        CountDownLatch inStop = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        VidocqBootstrap bootstrap = started(new Counting() {
            @Override
            public void onStop() {
                inStop.countDown();
                await(finish); // a stop that needs the caller to go on, like a UI thread draining its queue
                super.onStop();
            }
        });

        AtomicReference<Thread> caller = new AtomicReference<>();
        assertTimeoutPreemptively(LIMIT, () -> {
            caller.set(Thread.currentThread());
            Vidocq.shutdown(); // returns while onStop is still blocked
        });
        assertTrue(inStop.await(5, TimeUnit.SECONDS));
        assertEquals(0, stops.get(), "the caller was not made to wait for the stop");

        finish.countDown();
        assertTrue(bootstrap.awaitShutdown(5_000), "the stop completes without the caller");
        assertEquals(1, stops.get());
        assertTrue(stoppedOn.get().isVirtual(), "the stop runs on a virtual thread");
        assertNotSame(caller.get(), stoppedOn.get());
    }

    @Test
    void shutdownAndWaitStopsOnTheCallerAndReleasesWaitForExit() throws Exception {
        VidocqBootstrap bootstrap = started(new Counting());
        CountDownLatch released = new CountDownLatch(1);
        Thread waiter = Thread.ofVirtual().start(() -> {
            bootstrap.awaitShutdown();
            released.countDown();
        });

        AtomicReference<Thread> caller = new AtomicReference<>();
        assertTimeoutPreemptively(LIMIT, () -> {
            caller.set(Thread.currentThread()); // the timeout runs the call on a thread of its own
            Vidocq.shutdownAndWait();
        });

        assertEquals(1, stops.get(), "stopped when the call returns");
        assertEquals(caller.get(), stoppedOn.get(), "the stop ran on the thread that called");
        assertTrue(released.await(5, TimeUnit.SECONDS));
        waiter.join();
    }

    @Test
    void withoutARunningDeploymentBothAreNoOps() {
        assertDoesNotThrow(Vidocq::shutdown);
        assertDoesNotThrow(Vidocq::shutdownAndWait);
    }

    @Test
    void concurrentShutdownsStopTheExtensionsOnce() {
        VidocqBootstrap bootstrap = started(new Counting());
        int callers = 9;
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = java.util.stream.IntStream.range(0, callers)
                .mapToObj(i -> Thread.ofPlatform().start(() -> {
                    await(go);
                    switch (i % 3) {
                        case 0 -> Vidocq.shutdown();
                        case 1 -> Vidocq.shutdownAndWait();
                        default -> bootstrap.shutdown(); // what the JVM shutdown hook calls
                    }
                })).toList();
        go.countDown();
        assertTimeoutPreemptively(LIMIT, () -> {
            for (Thread t : threads) {
                t.join();
            }
            assertTrue(bootstrap.awaitShutdown(10_000));
        });
        assertEquals(1, stops.get(), "onStop must run once however many callers race");
    }

    @Test
    void shutdownAndWaitFromTheStoppingThreadDoesNotDeadlock() {
        started(new Counting() {
            @Override
            public void onStop() {
                super.onStop();
                Vidocq.shutdownAndWait(); // re-entrant: same thread, already stopping
            }
        });

        assertTimeoutPreemptively(LIMIT, Vidocq::shutdownAndWait);
        assertEquals(1, stops.get());
    }

    @Test
    void shutdownAndWaitFromAShutdownObserverDoesNotDeadlock() {
        VidocqBootstrap bootstrap = VidocqBootstrap.create().banner(BannerMode.OFF)
                .extensions(List.of(new Counting() {
                    @Override
                    public void beforeStart(VaubanContainerBuilder builder) {
                        builder.addComponentProvider(new _VaubanComponents()).addBeanClass(ShutdownObserver.class);
                    }
                })).configure();
        Vidocq.register(bootstrap);
        bootstrap.start();

        assertTimeoutPreemptively(LIMIT, Vidocq::shutdownAndWait);

        assertEquals(1, OBSERVED.get(), "the container fired the CDI Shutdown event to the observer");
        assertEquals(1, stops.get());
    }

    /** The stop {@link Vidocq#shutdown()} runs on its own thread fires the CDI {@code Shutdown} event, once. */
    @Test
    void theAsyncShutdownFiresTheCdiShutdownEventOnce() {
        VidocqBootstrap bootstrap = VidocqBootstrap.create().banner(BannerMode.OFF)
                .extensions(List.of(new Counting() {
                    @Override
                    public void beforeStart(VaubanContainerBuilder builder) {
                        builder.addComponentProvider(new _VaubanComponents()).addBeanClass(ShutdownObserver.class);
                    }
                })).configure();
        Vidocq.register(bootstrap);
        bootstrap.start();

        Vidocq.shutdown();

        assertTimeoutPreemptively(LIMIT, () -> assertTrue(bootstrap.awaitShutdown(10_000)));
        assertEquals(1, OBSERVED.get(), "the CDI Shutdown event reached the application's observer once");
        assertEquals(1, stops.get());
    }

    @Test
    void shutdownAndWaitFromOnStartIsHonouredWhenTheBootCompletes() {
        AtomicBoolean laterStarted = new AtomicBoolean();
        VidocqBootstrap bootstrap = VidocqBootstrap.create().banner(BannerMode.OFF)
                .extensions(List.of(new Counting() {
                    @Override
                    public void onStart(ExtensionContext context) {
                        Vidocq.shutdownAndWait(); // the booting thread: remembered, not run in the middle of the boot
                    }
                }, new Later(laterStarted))).configure();
        Vidocq.register(bootstrap); // before the boot, as bootAndRegister does

        assertTimeoutPreemptively(LIMIT, bootstrap::start);

        assertTrue(laterStarted.get(), "the boot went on to the extensions after the one that asked");
        assertEquals(1, stops.get(), "and the stop followed once the boot was complete");
        assertTrue(bootstrap.awaitShutdown(0));
    }

    @Test
    void shutdownFromOnStartStopsAfterTheBoot() {
        VidocqBootstrap bootstrap = VidocqBootstrap.create().banner(BannerMode.OFF)
                .extensions(List.of(new Counting() {
                    @Override
                    public void onStart(ExtensionContext context) {
                        Vidocq.shutdown(); // another (virtual) thread: it waits for the boot to complete
                    }
                })).configure();
        Vidocq.register(bootstrap);

        assertTimeoutPreemptively(LIMIT, bootstrap::start);

        assertTrue(bootstrap.awaitShutdown(10_000));
        assertEquals(1, stops.get());
    }

    @Test
    void shutdownReachesTheBootOfEveryHotReloadCycle(@TempDir Path dir) throws Exception {
        Path reloadFile = Files.writeString(dir.resolve("reload"), "0\n");
        Files.setLastModifiedTime(reloadFile, FileTime.fromMillis(1_000));
        Path ready = VidocqDevReloadLoop.readyFile(reloadFile);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread loop = Thread.ofPlatform().name("reload-loop").start(() -> {
            try {
                VidocqDevReloadLoop.run(reloadFile, new String[0]);
            } catch (Throwable t) {
                failure.set(t);
            }
        });

        assertTimeoutPreemptively(LIMIT, () -> {
            awaitReady(ready, 1_000);                       // first boot
            Files.setLastModifiedTime(reloadFile, FileTime.fromMillis(2_000));
            awaitReady(ready, 2_000);                       // reload: a second bootstrap
            Vidocq.shutdownAndWait();                       // must reach the second one, not the stopped first
            loop.join();
        });

        assertEquals(null, failure.get());
        assertTrue(!loop.isAlive(), "the loop ended: the shutdown reached the running deployment");
    }

    private static void awaitReady(Path ready, long stamp) {
        while (!isReady(ready, stamp)) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
        }
    }

    private static boolean isReady(Path ready, long stamp) {
        try {
            return Files.exists(ready) && Long.parseLong(Files.readString(ready).strip()) == stamp;
        } catch (java.io.IOException | NumberFormatException _) {
            return false; // being replaced
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private VidocqBootstrap started(VidocqExtension extension) {
        VidocqBootstrap bootstrap = VidocqBootstrap.create().banner(BannerMode.OFF)
                .extensions(List.of(extension)).configure();
        Vidocq.register(bootstrap);
        return bootstrap.start();
    }

    private class Counting implements VidocqExtension {
        @Override
        public String name() {
            return "counting";
        }

        @Override
        public void onStop() {
            stoppedOn.set(Thread.currentThread());
            stops.incrementAndGet();
        }
    }

    private static final class Later implements VidocqExtension {
        private final AtomicBoolean started;

        Later(AtomicBoolean started) {
            this.started = started;
        }

        @Override
        public String name() {
            return "later";
        }

        @Override
        public int priority() {
            return 1_000_000;
        }

        @Override
        public void onStart(ExtensionContext context) {
            started.set(true);
        }
    }
}
