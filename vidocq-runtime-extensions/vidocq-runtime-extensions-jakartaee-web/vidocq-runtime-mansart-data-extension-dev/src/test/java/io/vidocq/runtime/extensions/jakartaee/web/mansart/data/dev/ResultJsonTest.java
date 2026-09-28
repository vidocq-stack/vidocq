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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Gizmo;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Level;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a method returns, as JSON and as the first words of the summary (spec §6). */
class ResultJsonTest {

    private final EntityJson entities = RunFixtures.entities();

    private static Gizmo bolt(long id) {
        return RunFixtures.gizmo(id, "bolt", 3, Level.LOW, null, null);
    }

    @Test
    void anEntityIsOneRow() {
        ResultJson.Result result = ResultJson.of(bolt(1), false, entities);

        assertEquals("1 row", result.what());
        assertFalse(result.rows());
        assertEquals("{\"id\":1,\"name\":\"bolt\",\"stock\":3,\"level\":\"LOW\",\"due\":null,\"price\":null}",
                result.body());
    }

    @Test
    void aListIsRowsCountedAndCutAtOneHundred() {
        assertEquals("3 rows", ResultJson.of(List.of(bolt(1), bolt(2), bolt(3)), false, entities).what());
        ResultJson.Result none = ResultJson.of(List.of(), false, entities);
        assertEquals("no row", none.what());
        assertEquals("[]", none.body());

        List<Gizmo> many = LongStream.rangeClosed(1, 150).mapToObj(ResultJsonTest::bolt).toList();
        ResultJson.Result first = ResultJson.of(many, false, entities);

        assertEquals("first 100 rows", first.what());
        assertTrue(first.rows());
        assertEquals(100, ((List<?>) Json.parse(first.body())).size());
    }

    @Test
    void aStreamIsReadToItsHundredAndFirstRowAndClosed() {
        AtomicInteger read = new AtomicInteger();
        AtomicBoolean closed = new AtomicBoolean();
        Stream<Gizmo> stream = LongStream.rangeClosed(1, 1000).mapToObj(id -> {
            read.incrementAndGet();
            return bolt(id);
        }).onClose(() -> closed.set(true));

        ResultJson.Result result = ResultJson.of(stream, false, entities);

        assertEquals("first 100 rows", result.what());
        assertEquals(101, read.get());
        assertTrue(closed.get());
    }

    @Test
    void aStreamThatFailsIsClosedAndTheFailureThrown() {
        AtomicBoolean closed = new AtomicBoolean();
        Stream<Gizmo> failing = Stream.<Gizmo>generate(() -> {
            throw new IllegalStateException("connection lost");
        }).onClose(() -> closed.set(true));

        assertThrows(IllegalStateException.class, () -> ResultJson.of(failing, false, entities));
        assertTrue(closed.get());
    }

    @Test
    void anOptionalIsItsValueOrNull() {
        assertEquals("1 row", ResultJson.of(Optional.of(bolt(1)), false, entities).what());
        ResultJson.Result empty = ResultJson.of(Optional.empty(), false, entities);
        assertEquals("no row", empty.what());
        assertEquals("null", empty.body());
    }

    @Test
    void voidIsDoneWithNoBody() {
        ResultJson.Result done = ResultJson.of(null, true, entities);

        assertEquals("done", done.what());
        assertNull(done.body());
    }

    @Test
    void aScalarIsItsValue() {
        ResultJson.Result count = ResultJson.of(42L, false, entities);
        assertEquals("42", count.what());
        assertEquals("42", count.body());
        assertEquals("true", ResultJson.of(true, false, entities).what());
        assertEquals("no row", ResultJson.of(null, false, entities).what());
    }

    @Test
    void anArrayIsRows() {
        ResultJson.Result result = ResultJson.of(new int[] {1, 2}, false, entities);

        assertEquals("2 rows", result.what());
        assertEquals("[1,2]", result.body());
    }

    @Test
    void aReferencedEntityIsItsIdNeverTheGraph() {
        ResultJson.Result result = ResultJson.of(List.of(RunFixtures.part(5L, bolt(7), "left")), false, entities);

        assertEquals("[{\"id\":5,\"gizmo\":7,\"label\":\"left\"}]", result.body());
    }
}
