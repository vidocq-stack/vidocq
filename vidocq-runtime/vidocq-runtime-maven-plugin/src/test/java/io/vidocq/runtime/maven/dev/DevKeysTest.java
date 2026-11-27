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

import io.vidocq.runtime.maven.dev.TestResults.Trigger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vidocq/vidocq#799: a session that restarts starts no second reader of the console. */
class DevKeysTest {

    @Test
    void oneReaderForTheGoalSendsTheKeysToTheLatestSession() {
        AtomicInteger readers = new AtomicInteger();
        AtomicReference<Consumer<Trigger>> reader = new AtomicReference<>();
        DevKeys keys = new DevKeys(sink -> {
            readers.incrementAndGet();
            reader.set(sink);
            return true;
        });
        List<String> got = new ArrayList<>();

        assertTrue(keys.listen(trigger -> got.add("first " + trigger.name())));
        reader.get().accept(Trigger.RUN_ALL);
        assertTrue(keys.listen(trigger -> got.add("second " + trigger.name())));
        reader.get().accept(Trigger.RERUN_FAILED);

        assertEquals(1, readers.get());
        assertEquals(List.of("first RUN_ALL", "second RERUN_FAILED"), got);
    }

    @Test
    void withoutAConsoleNoKeyIsEverRead() {
        AtomicInteger readers = new AtomicInteger();
        DevKeys keys = new DevKeys(_ -> readers.incrementAndGet() < 0);
        assertEquals(false, keys.listen(_ -> { }));
        assertEquals(false, keys.listen(_ -> { }));
        assertEquals(1, readers.get(), "asked once");
    }
}
