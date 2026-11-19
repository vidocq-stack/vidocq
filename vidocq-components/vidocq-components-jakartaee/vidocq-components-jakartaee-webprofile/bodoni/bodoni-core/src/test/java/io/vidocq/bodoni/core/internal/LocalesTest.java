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

package io.vidocq.bodoni.core.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Locale;

import org.junit.jupiter.api.Test;

class LocalesTest {

    @Test
    void parsesLanguageAndCountry() {
        assertEquals(Locale.US, Locales.parse("en_US", null));
        assertEquals(Locale.US, Locales.parse("en-US", null));
        assertEquals(Locale.FRENCH, Locales.parse("fr", null));
    }

    @Test
    void parsesVariant() {
        assertEquals("POSIX", Locales.parse("en_US", "POSIX").getVariant());
    }

    @Test
    void nullOrEmptyIsNull() {
        assertNull(Locales.parse("", null));
        assertNull(Locales.parse(null, null));
    }

    @Test
    void malformedTextIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Locales.parse("e", null));
        assertThrows(IllegalArgumentException.class, () -> Locales.parse("en_U", null));
        assertThrows(IllegalArgumentException.class, () -> Locales.parse("en_US_x", null));
    }

    @Test
    void configValueLocaleIsReturnedAsIs() {
        assertSame(Locale.CANADA, Locales.of(Locale.CANADA));
    }

    @Test
    void configValueStringIsParsed() {
        assertEquals(Locale.CANADA_FRENCH, Locales.of("fr_CA"));
        assertNull(Locales.of(null));
    }

    @Test
    void configValueOfAnotherTypeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Locales.of(42));
    }
}
