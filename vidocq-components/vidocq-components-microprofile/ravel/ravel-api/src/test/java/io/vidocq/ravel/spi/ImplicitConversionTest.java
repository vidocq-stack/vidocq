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
package io.vidocq.ravel.spi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A generated implicit converter keeps the contract of Ravel's reflective one. */
class ImplicitConversionTest {

    enum Level { LOW, HIGH }

    @Test
    void convertsThroughTheFactory() {
        assertEquals(42, ImplicitConversion.of(Integer.class, Integer::valueOf).convert("42"));
    }

    @Test
    void anEmptyValueIsNullAndANullOneIsRefused() {
        var converter = ImplicitConversion.of(Integer.class, Integer::valueOf);
        assertNull(converter.convert(""));
        assertThrows(NullPointerException.class, () -> converter.convert(null));
    }

    @Test
    void anIllegalArgumentOrNullPointerOfTheFactoryPassesThrough() {
        var iae = new IllegalArgumentException("no");
        var rejecting = ImplicitConversion.of(String.class, v -> { throw iae; });
        var thrown = assertThrows(IllegalArgumentException.class, () -> rejecting.convert("x"));
        assertSame(iae, thrown);
        var nulling = ImplicitConversion.of(String.class, v -> { throw new NullPointerException(); });
        assertThrows(NullPointerException.class, () -> nulling.convert("x"));
    }

    @Test
    void anyOtherFailureBecomesAnIllegalArgument() {
        var failing = ImplicitConversion.of(String.class, v -> { throw new IllegalStateException("boom"); });
        var thrown = assertThrows(IllegalArgumentException.class, () -> failing.convert("x"));
        assertEquals(IllegalStateException.class, thrown.getCause().getClass());
    }

    @Test
    void enumsConvertByName() {
        var converter = ImplicitConversion.ofEnum(Level.class);
        assertEquals(Level.HIGH, converter.convert("HIGH"));
        assertNull(converter.convert(""));
        assertThrows(IllegalArgumentException.class, () -> converter.convert("MEDIUM"));
    }
}
