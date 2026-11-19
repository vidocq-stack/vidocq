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

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.SequencedSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReloadRequestTest {

    @Test
    void aRedefineRequestListsEachClassWithItsMethods() {
        var request = new ReloadRequest.Redefine(methods(
                "io.x.MainView", List.of("<init>", "lambda$new$0"),
                "io.x.Service", List.of("greet")));

        assertEquals("42\nredefine\nio.x.MainView <init> lambda$new$0\nio.x.Service greet\n", request.format(42));
    }

    @Test
    void aSwapRequestCarriesItsReason() {
        assertEquals("42\nswap MainView gained a method lambda$new$1\n",
                new ReloadRequest.Swap("MainView gained a method lambda$new$1").format(42));
    }

    @Test
    void aSwapWithoutReasonIsTheModeAlone() {
        assertEquals("7\nswap\n", new ReloadRequest.Swap("").format(7));
    }

    @Test
    void parsingGivesBackWhatWasFormatted() {
        var redefine = new ReloadRequest.Redefine(methods(
                "io.x.MainView", List.of("<init>", "lambda$new$0"),
                "io.x.Service", List.of("greet")));
        var swap = new ReloadRequest.Swap("Order lost a field total");

        assertEquals(redefine, ReloadRequest.parse(redefine.format(1)));
        assertEquals(swap, ReloadRequest.parse(swap.format(1)));
    }

    @Test
    void aKeptSwapRoundTrips() {
        var swap = new ReloadRequest.Swap("MainView gained a method lambda$new$1", Optional.empty());

        assertEquals("7\nswap MainView gained a method lambda$new$1\nsessions keep\n", swap.format(7));
        assertEquals(swap, ReloadRequest.parse(swap.format(7)));
    }

    @Test
    void aKeptSwapWithoutReasonRoundTrips() {
        var swap = new ReloadRequest.Swap("", Optional.empty());

        assertEquals("7\nswap\nsessions keep\n", swap.format(7));
        assertEquals(swap, ReloadRequest.parse(swap.format(7)));
    }

    @Test
    void aDroppedSwapRoundTripsWithItsReason() {
        var swap = new ReloadRequest.Swap("Order lost a field total", Optional.of("vidocq.dev.keepSessions is false"));

        assertEquals("7\nswap Order lost a field total\nsessions drop vidocq.dev.keepSessions is false\n",
                swap.format(7));
        assertEquals(swap, ReloadRequest.parse(swap.format(7)));
    }

    @Test
    void aSwapWithoutASessionsLineDropsSilently() {
        assertEquals(new ReloadRequest.Swap("A changed", Optional.of("")), ReloadRequest.parse("7\nswap A changed\n"));
        assertEquals(Optional.of(""), swapOf("7\n").dropSessions());
        assertEquals(Optional.of(""), swapOf("7\nswap\n").dropSessions());
        assertEquals("7\nswap x\n", new ReloadRequest.Swap("x").format(7));
    }

    @Test
    void anUnknownThirdLineDropsSilently() {
        assertEquals(Optional.of(""), swapOf("7\nswap x\nsessions maybe\n").dropSessions());
    }

    @Test
    void aRedefinitionHasNoSessionsLine() {
        var redefine = new ReloadRequest.Redefine(methods("io.x.MainView", List.of("greet")));

        assertEquals("7\nredefine\nio.x.MainView greet\n", redefine.format(7));
        assertEquals(redefine, ReloadRequest.parse(redefine.format(7)));
    }

    @Test
    void aFileWithoutAModeLineIsASwapWithoutReason() {
        assertEquals(new ReloadRequest.Swap(""), ReloadRequest.parse("123456\n"));
        assertEquals(new ReloadRequest.Swap(""), ReloadRequest.parse(""));
    }

    @Test
    void anUnknownModeOrAnEmptyRedefineIsASwap() {
        assertEquals(new ReloadRequest.Swap(""), ReloadRequest.parse("1\nreplace everything\n"));
        assertEquals(new ReloadRequest.Swap(""), ReloadRequest.parse("1\nredefine\n"));
    }

    @Test
    void aRedefinedClassMayListNoMethod() {
        var parsed = ReloadRequest.parse("1\nredefine\nio.x.MainView\n");

        assertEquals(new ReloadRequest.Redefine(methods("io.x.MainView", List.of())), parsed);
    }

    @Test
    void windowsLineEndingsAreRead() {
        assertEquals(new ReloadRequest.Redefine(methods("io.x.A", List.of("run"))),
                ReloadRequest.parse("1\r\nredefine\r\nio.x.A run\r\n"));
    }

    @Test
    void aRedefineRequestNeedsAClass() {
        var empty = new LinkedHashMap<String, SequencedSet<String>>();

        assertThrows(IllegalArgumentException.class, () -> new ReloadRequest.Redefine(empty));
    }

    private static ReloadRequest.Swap swapOf(String content) {
        return assertInstanceOf(ReloadRequest.Swap.class, ReloadRequest.parse(content));
    }

    private static SequencedMap<String, SequencedSet<String>> methods(Object... classThenMethods) {
        var result = new LinkedHashMap<String, SequencedSet<String>>();
        for (int i = 0; i < classThenMethods.length; i += 2) {
            @SuppressWarnings("unchecked")
            var names = (List<String>) classThenMethods[i + 1];
            result.put((String) classThenMethods[i], new LinkedHashSet<>(names));
        }
        return result;
    }
}
