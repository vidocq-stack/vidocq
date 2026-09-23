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
package io.vidocq.runtime.spi.devconsole;

import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportSection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a panel declares to offer an action: checked where it is written, never on the page. */
class PanelActionTest {

    @Test
    void aPanelOffersNoActionUnlessItAsks() {
        DevConsolePanel panel = new DevConsolePanel() {
            @Override
            public String id() {
                return "acme";
            }

            @Override
            public void contribute(StartupReportContext context, StartupReportSection section) {
                section.summary("nothing to do");
            }

            @Override
            public void sample(PanelSample sample) {
                // no value
            }
        };

        assertEquals(List.of(), panel.actions());
    }

    @Test
    void anActionWithoutArgumentsIsItsIdItsLabelAndWhatItDoes() {
        PanelAction clear = new PanelAction("clear", "Clear the cache", null, arguments -> "0 entries");

        assertEquals("clear", clear.id());
        assertNull(clear.confirmation());
        assertEquals(List.of(), clear.arguments());
        assertEquals("0 entries", clear.run().apply(Map.of()));
    }

    @Test
    void theIdFollowsTheKeyRuleAndTheLabelIsNeverBlank() {
        assertThrows(IllegalArgumentException.class, () -> new PanelAction("Clear", "Clear", null, a -> ""));
        assertThrows(IllegalArgumentException.class, () -> new PanelAction("clear", " ", null, a -> ""));
        assertThrows(IllegalArgumentException.class, () -> new PanelAction("clear", "Clear", " ", a -> ""));
        assertThrows(NullPointerException.class, () -> new PanelAction("clear", "Clear", null, null));
    }

    @Test
    void theArgumentsAreAnImmutableCopyNamedOnceEach() {
        List<PanelAction.Argument> declared = new ArrayList<>(List.of(
                PanelAction.Argument.oneOf("level", "Level", "INFO", "DEBUG")));
        PanelAction action = new PanelAction("set-level", "Set", null, declared, a -> "ok");
        declared.clear();

        assertEquals(1, action.arguments().size());
        assertThrows(UnsupportedOperationException.class, () -> action.arguments().clear());
        assertThrows(IllegalArgumentException.class, () -> new PanelAction("set-level", "Set", null, List.of(
                PanelAction.Argument.oneOf("level", "Level", "INFO"),
                PanelAction.Argument.matching("level", "Level", "[A-Z]+")), a -> "ok"));
    }

    @Test
    void anArgumentOfListedValuesAcceptsThoseOnly() {
        PanelAction.Argument level = PanelAction.Argument.oneOf("level", "Level", "INFO", "DEBUG");

        assertTrue(level.accepts("INFO"));
        assertFalse(level.accepts("info"));
        assertFalse(level.accepts("TRACE"));
        assertFalse(level.accepts(null));
        assertEquals(List.of("INFO", "DEBUG"), level.allowedValues());
        assertNull(level.pattern());
    }

    @Test
    void anArgumentOfAPatternAcceptsAWholeMatchNoLongerThanTwoHundredCharacters() {
        PanelAction.Argument logger = PanelAction.Argument.matching("logger", "Logger", "[A-Za-z0-9_.$]+");

        assertTrue(logger.accepts("com.acme.Cart"));
        assertFalse(logger.accepts("com.acme.Cart "), "the whole value, not a part of it");
        assertFalse(logger.accepts(""));
        assertFalse(logger.accepts("a".repeat(201)), "longer than 200 characters");
        assertTrue(logger.accepts("a".repeat(200)));
        assertNull(logger.allowedValues());
        assertEquals("[A-Za-z0-9_.$]+", logger.pattern());
    }

    @Test
    void anArgumentIsNamedByTheKeyRuleAndSaysWhatItAccepts() {
        assertThrows(IllegalArgumentException.class, () -> PanelAction.Argument.oneOf("Level", "Level", "INFO"));
        assertThrows(IllegalArgumentException.class, () -> PanelAction.Argument.oneOf("level", "Level"));
        assertThrows(IllegalArgumentException.class, () -> PanelAction.Argument.oneOf("level", "Level", " "));
        assertThrows(IllegalArgumentException.class, () -> PanelAction.Argument.matching("logger", "Logger", "("));
        assertThrows(IllegalArgumentException.class,
                () -> new PanelAction.Argument("level", "Level", List.of("INFO"), "[A-Z]+"),
                "a list or a pattern, not both");
        assertThrows(IllegalArgumentException.class,
                () -> new PanelAction.Argument("level", "Level", null, null), "a list or a pattern");
    }
}
