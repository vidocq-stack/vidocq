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

import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What an action returns: a summary line, and optionally a body, an error flag and the details of the exchange. */
class ActionResultTest {

    @Test
    void aNullSummaryReadsDoneAndALongOneIsCut() {
        assertEquals("done", ActionResult.of(null).summary());
        String cut = ActionResult.of("x".repeat(250)).summary();
        assertEquals(200, cut.length());
        assertTrue(cut.endsWith("..."), cut);
        assertEquals("y".repeat(200), ActionResult.of("y".repeat(200)).summary());
    }

    @Test
    void aBodyIsTextOrJsonAndDefaultsToText() {
        assertEquals("text/plain", new ActionResult("ok", null, "hello", false, null).contentType());
        assertEquals("application/json", new ActionResult("ok", "application/json", "{}", false, null).contentType());
        assertNull(new ActionResult("ok", "application/json", null, false, null).contentType(), "no body, no type");
        assertThrows(IllegalArgumentException.class, () -> new ActionResult("ok", "text/html", "<b>", false, null));
    }

    @Test
    void aBodyOrDetailsPastTheLimitIsTruncatedAndSaysSo() {
        String big = "a".repeat(300_000);

        ActionResult result = new ActionResult("ok", "text/plain", big, false, big);

        assertEquals(ActionResult.MAX_CONTENT, result.body().length());
        assertTrue(result.body().endsWith("… truncated at 256 KiB"), result.body().substring(262_100));
        assertEquals(ActionResult.MAX_CONTENT, result.details().length());
        String exact = "b".repeat(ActionResult.MAX_CONTENT);
        assertEquals(exact, new ActionResult("ok", "text/plain", exact, false, null).body());
    }

    @Test
    void theErrorFlagIsKept() {
        assertTrue(new ActionResult("error -32602: bad", "application/json", "{}", true, null).error());
        assertFalse(ActionResult.of("ok").error());
        assertNull(ActionResult.of("ok").body());
        assertNull(ActionResult.of("ok").details());
    }
}
