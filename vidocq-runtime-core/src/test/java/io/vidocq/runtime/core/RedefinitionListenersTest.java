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

import io.vidocq.runtime.spi.devconsole.RedefinedClass;
import io.vidocq.runtime.spi.devconsole.Redefinition;
import io.vidocq.runtime.spi.devconsole.RedefinitionListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedMap;
import java.util.SequencedSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code -dev} companions told of a redefinition (hot swap design §5). */
@Timeout(10)
class RedefinitionListenersTest {

    private final List<String> warnings = new CopyOnWriteArrayList<>();

    @Test
    void calledWithTheLoadedClassesAndTheirMethods() throws Exception {
        BlockingQueue<Redefinition> told = new LinkedBlockingQueue<>();
        try (var listeners = RedefinitionListeners.start(_ -> List.of(told::add), warnings::add)) {
            listeners.redefined(ModuleLayer.boot(), methods(String.class, "length", "lambda$new$0"));

            assertEquals(new Redefinition(List.of(new RedefinedClass(String.class,
                    new LinkedHashSet<>(List.of("length", "lambda$new$0"))))), told.poll(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void theListenersAreLoadedFromTheLayerOfEachRedefinition() throws Exception {
        BlockingQueue<ModuleLayer> layers = new LinkedBlockingQueue<>();
        ModuleLayer first = ModuleLayer.boot();
        ModuleLayer second = ModuleLayer.empty();
        try (var listeners = RedefinitionListeners.start(layer -> {
            layers.add(layer);
            return List.of();
        }, warnings::add)) {
            listeners.redefined(first, methods(String.class, "length"));
            listeners.redefined(second, methods(String.class, "length"));

            assertSame(first, layers.poll(5, TimeUnit.SECONDS));
            assertSame(second, layers.poll(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void aThrowingListenerIsLoggedAndTheNextRuns() throws Exception {
        BlockingQueue<Redefinition> told = new LinkedBlockingQueue<>();
        RedefinitionListener failing = new Failing();
        try (var listeners = RedefinitionListeners.start(_ -> List.of(failing, told::add), warnings::add)) {
            listeners.redefined(ModuleLayer.boot(), methods(String.class, "length"));

            assertEquals(1, told.poll(5, TimeUnit.SECONDS).classes().size());
            assertEquals(List.of("Hot reload - the redefinition listener " + Failing.class.getName()
                    + " failed: java.lang.IllegalStateException: no UI"), warnings);
        }
    }

    @Test
    void aBlockedListenerDelaysNeitherTheCallerNorTheNextRedefinition() throws Exception {
        var release = new CountDownLatch(1);
        BlockingQueue<String> told = new LinkedBlockingQueue<>();
        RedefinitionListener blocked = redefinition -> {
            awaitQuietly(release);
            told.add(redefinition.classes().getFirst().type().getSimpleName());
        };
        try (var listeners = RedefinitionListeners.start(_ -> List.of(blocked), warnings::add)) {
            long t0 = System.nanoTime();
            listeners.redefined(ModuleLayer.boot(), methods(String.class, "length"));
            listeners.redefined(ModuleLayer.boot(), methods(Integer.class, "intValue"));
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

            assertTrue(millis < 1_000, () -> "the caller waited " + millis + " ms");
            assertTrue(told.isEmpty());
            release.countDown();
            assertEquals("String", told.poll(5, TimeUnit.SECONDS));
            assertEquals("Integer", told.poll(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void twoRedefinitionsReachTheListenersInOrder() throws Exception {
        BlockingQueue<String> told = new LinkedBlockingQueue<>();
        RedefinitionListener first = redefinition -> told.add("first " + redefinition.classes().getFirst().methods());
        RedefinitionListener second = redefinition -> told.add("second " + redefinition.classes().getFirst().methods());
        try (var listeners = RedefinitionListeners.start(_ -> List.of(first, second), warnings::add)) {
            listeners.redefined(ModuleLayer.boot(), methods(String.class, "a"));
            listeners.redefined(ModuleLayer.boot(), methods(String.class, "b"));

            assertEquals(List.of("first [a]", "second [a]", "first [b]", "second [b]"),
                    List.of(told.poll(5, TimeUnit.SECONDS), told.poll(5, TimeUnit.SECONDS),
                            told.poll(5, TimeUnit.SECONDS), told.poll(5, TimeUnit.SECONDS)));
        }
    }

    @Test
    void noSpiModuleMeansNoListenerAndNoFailure() {
        var notifier = ClassRedefinition.listeners(ModuleLayer.empty(), warnings::add);

        assertSame(ClassRedefinition.Notifier.NONE, notifier);
        notifier.redefined(ModuleLayer.boot(), methods(String.class, "length"));
        notifier.close();
        assertEquals(List.of(), warnings);
    }

    private static SequencedMap<Class<?>, SequencedSet<String>> methods(Class<?> type, String... methods) {
        SequencedMap<Class<?>, SequencedSet<String>> map = new LinkedHashMap<>();
        map.put(type, new LinkedHashSet<>(List.of(methods)));
        return map;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Failing implements RedefinitionListener {
        @Override
        public void redefined(Redefinition redefinition) {
            throw new IllegalStateException("no UI");
        }
    }
}
