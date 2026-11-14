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
package io.vidocq.runtime.maven.dev;

import io.vidocq.runtime.devservices.host.DevServicesException;
import io.vidocq.runtime.devservices.host.DevServicesSession;
import io.vidocq.runtime.devservices.host.StateFile;
import io.vidocq.runtime.devservices.spi.DevService;
import io.vidocq.runtime.devservices.spi.DevServiceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261114-08: the tests of {@code vidocq:dev} get dev services of their own, opened at their first run, kept for
 * every later one, and closed with the session; the application's containers and data are never theirs.
 */
@Timeout(20)
class DevTestServicesTest {

    @TempDir
    Path basedir;

    private final List<String> infos = new CopyOnWriteArrayList<>();
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private final AtomicInteger opens = new AtomicInteger();

    /** The tests' PostgreSQL container, as a provider that counts its starts and stops. */
    private static final class Container implements DevService {
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();

        @Override
        public String id() {
            return "postgres";
        }

        @Override
        public boolean appliesWhen(DevServiceContext ctx) {
            return true;
        }

        @Override
        public Map<String, String> start(DevServiceContext ctx) {
            starts.incrementAndGet();
            return Map.of("vidocq.pool.url", "jdbc:postgresql://localhost:32790/vidocq");
        }

        @Override
        public void stop() {
            stops.incrementAndGet();
        }
    }

    /** Opens the tests' session on {@code container}, counting the opens. */
    private DevServicesSession open(Container container) throws DevServicesException {
        opens.incrementAndGet();
        return DevServicesSession.forTesting(DevServicesSession.DEV_TESTS_HOST, basedir, List.of(container),
                System.getLogger("DevTestServicesTest"));
    }

    @Test
    void noContainerStartsBeforeTheFirstRun() {
        Container container = new Container();
        DevTestServices services = new DevTestServices(() -> open(container), infos::add, warnings::add);

        assertEquals(Map.of(), services.keys());
        services.close();

        assertEquals(0, opens.get(), "a session without a test run starts no container");
        assertEquals(List.of(), infos);
    }

    @Test
    void theFirstRunOpensTheSessionAndEveryLaterRunReusesIt() throws Exception {
        Container container = new Container();
        DevTestServices services = new DevTestServices(() -> open(container), infos::add, warnings::add);

        services.prepare();
        Map<String, String> keys = services.keys();
        services.prepare();

        assertEquals(1, opens.get());
        assertEquals(1, container.starts.get());
        assertEquals(Map.of("vidocq.pool.url", "jdbc:postgresql://localhost:32790/vidocq",
                "vidocq.dev.provided.vidocq.pool.url", "postgres"), keys, "marked as the application's keys are");
        assertEquals(keys, services.keys());
        assertEquals(List.of(DevTestServices.STARTING), infos);
        Path state = basedir.resolve("target").resolve(StateFile.TEST_FILE_NAME);
        assertTrue(Files.readString(state).contains("\"state\":\"running\""), Files.readString(state));
        assertFalse(Files.exists(basedir.resolve("target").resolve(StateFile.FILE_NAME)),
                "the dev session's file is not the tests'");

        services.close();
        services.close();

        assertEquals(1, container.stops.get());
        assertTrue(Files.readString(state).contains("\"state\":\"stopped\""), Files.readString(state));
        assertEquals(Map.of(), services.keys(), "a closed session hands no key");
    }

    @Test
    void noSessionOpensOnceClosed() {
        Container container = new Container();
        DevTestServices services = new DevTestServices(() -> open(container), infos::add, warnings::add);

        services.close();
        services.prepare();

        assertEquals(0, opens.get());
        assertEquals(Map.of(), services.keys());
    }

    @Test
    void aSessionThatCannotStartIsAWarningAndTheNextRunTriesAgain() {
        Container container = new Container();
        AtomicInteger attempts = new AtomicInteger();
        DevTestServices services = new DevTestServices(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new DevServicesException("DevService 'postgres' failed to start: no docker", null);
            }
            return open(container);
        }, infos::add, warnings::add);

        services.prepare();

        assertEquals(Map.of(), services.keys(), "this run goes on without them");
        assertEquals(List.of("Tests: their dev services did not start (DevService 'postgres' failed to start: no"
                + " docker); this run goes on without them, the next one tries again"), warnings);

        services.prepare();

        assertEquals("jdbc:postgresql://localhost:32790/vidocq", services.keys().get("vidocq.pool.url"));
        services.close();
        assertEquals(1, container.stops.get());
    }

    @Test
    void closingWaitsForTheSessionTheFirstRunIsOpening() throws Exception {
        Container container = new Container();
        CountDownLatch opening = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        DevTestServices services = new DevTestServices(() -> {
            opening.countDown();
            await(started);
            return open(container);
        }, infos::add, warnings::add);
        Thread worker = Thread.ofPlatform().name("vidocq-tests").start(services::prepare);
        assertTrue(opening.await(5, TimeUnit.SECONDS), "the first run opens the session");

        Thread closer = Thread.ofPlatform().name("vidocq-dev-shutdown").start(services::close);
        closer.join(300);
        boolean closedBeforeTheOpenEnded = !closer.isAlive();
        started.countDown();
        closer.join(5_000);
        worker.join(5_000);

        assertFalse(closedBeforeTheOpenEnded, "close waits for the containers being started");
        assertEquals(1, container.starts.get());
        assertEquals(1, container.stops.get(), "the containers started meanwhile are stopped, never left running");
    }

    /** Waits for {@code latch}, as a container start takes its time. */
    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "never released");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
