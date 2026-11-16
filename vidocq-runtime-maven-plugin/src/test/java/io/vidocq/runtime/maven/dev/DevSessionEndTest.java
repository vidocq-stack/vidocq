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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An application that stops itself ({@code Vidocq.shutdown()}) ends the whole {@code vidocq:dev} session; a crash or a
 * stop the host asked for does not.
 */
class DevSessionEndTest {

    private final AtomicInteger ended = new AtomicInteger();
    private final List<String> logged = new ArrayList<>();

    @Test
    void aCleanExitOfItsOwnEndsTheSession() {
        CompletableFuture<Integer> exit = new CompletableFuture<>();
        VidocqDevMojo.endSessionWhenTheApplicationStops(exit, () -> false, ended::incrementAndGet, logged::add);
        assertEquals(0, ended.get(), "nothing before the child exits");

        exit.complete(0);

        assertEquals(1, ended.get());
        assertEquals(List.of("The application stopped itself — ending dev mode."), logged);
    }

    @Test
    void aCrashKeepsTheSessionWaitingForAFix() {
        VidocqDevMojo.endSessionWhenTheApplicationStops(CompletableFuture.completedFuture(1), () -> false,
                ended::incrementAndGet, logged::add);

        assertEquals(0, ended.get());
        assertTrue(logged.isEmpty());
    }

    @Test
    void aStopTheHostAskedForIsNotTheApplicationsOwn() {
        VidocqDevMojo.endSessionWhenTheApplicationStops(CompletableFuture.completedFuture(0), () -> true,
                ended::incrementAndGet, logged::add);

        assertEquals(0, ended.get(), "a respawn or Ctrl+C stops the child through the host");
    }
}
