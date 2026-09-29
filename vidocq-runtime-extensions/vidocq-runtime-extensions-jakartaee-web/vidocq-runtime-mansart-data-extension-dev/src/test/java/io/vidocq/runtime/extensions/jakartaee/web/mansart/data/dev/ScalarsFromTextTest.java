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

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A field of a CSV file converted by the attribute's type (CSV spec §4, §5), as the page's JSON would be. */
class ScalarsFromTextTest {

    private static String refused(Class<?> type, String text) {
        return assertThrows(ArgumentException.class, () -> Scalars.fromText(type, text, "x")).getMessage();
    }

    @Test
    void aCsvFieldIsReadAsTheJsonThePageWouldSend() throws ArgumentException {
        assertEquals(3, Scalars.fromText(int.class, "3", "stock"));
        assertEquals(1000L, Scalars.fromText(Long.class, "1e3", "id"));
        assertEquals(new BigDecimal("2.50"), Scalars.fromText(BigDecimal.class, "2.50", "price"));
        assertEquals(2.5, Scalars.fromText(double.class, "2.5", "price"));
        assertEquals(1.0E10, Scalars.fromText(Double.class, "1.0E10", "price"), "what Double.toString writes");
        assertEquals(Boolean.TRUE, Scalars.fromText(boolean.class, "true", "done"));
        assertEquals(Boolean.FALSE, Scalars.fromText(Boolean.class, "false", "done"));
        assertEquals(DayOfWeek.MONDAY, Scalars.fromText(DayOfWeek.class, "MONDAY", "day"));
        assertEquals(LocalDate.of(2026, 10, 1), Scalars.fromText(LocalDate.class, "2026-10-01", "due"));
        UUID uuid = UUID.fromString("1b4e28ba-2fa1-11d2-883f-0016d3cca427");
        assertEquals(uuid, Scalars.fromText(UUID.class, uuid.toString(), "key"));
        assertEquals("", Scalars.fromText(String.class, "", "name"), "\"\": the empty text");
        assertEquals(" a ", Scalars.fromText(String.class, " a ", "name"), "never trimmed");
        assertNull(Scalars.fromText(String.class, null, "name"), "an empty field: null");
        assertNull(Scalars.fromText(Integer.class, null, "stock"));
    }

    @Test
    void aCsvFieldThatDoesNotConvertIsRefusedNamingItsColumn() {
        assertEquals("x: not an integer", refused(int.class, "2.5"));
        assertEquals("x: not an integer", refused(int.class, ""));
        assertEquals("x: not an integer", refused(Integer.class, " 3"), "never trimmed");
        assertEquals("x: not a number", refused(BigDecimal.class, "2,50"), "a decimal comma is no number");
        assertEquals("x: not a number", refused(double.class, "2,5"));
        assertEquals("x: out of range for int", refused(int.class, "3000000000"));
        assertEquals("x: null is not allowed for int", refused(int.class, null));
        assertEquals("x: not a boolean", refused(boolean.class, "yes"));
        assertEquals("x: no constant FUNDAY in DayOfWeek", refused(DayOfWeek.class, "FUNDAY"));
        assertEquals("x: not an ISO date", refused(LocalDate.class, "01/10/2026"));
    }
}
