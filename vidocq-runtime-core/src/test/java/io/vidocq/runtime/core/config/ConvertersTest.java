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
package io.vidocq.runtime.core.config;

import io.vidocq.runtime.spi.config.Converter;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ConvertersTest {

    @Test
    void stringPassThrough() {
        assertEquals("hello", Converters.forType(String.class).convert("hello"));
    }

    @Test
    void booleanTrueAliases() {
        Converter<Boolean> c = Converters.forType(Boolean.class);
        for (String v : new String[] {"true", "TRUE", "yes", "on", "1", "y"}) {
            assertEquals(Boolean.TRUE, c.convert(v), v);
        }
    }

    @Test
    void booleanFalseAliases() {
        Converter<Boolean> c = Converters.forType(Boolean.class);
        for (String v : new String[] {"false", "NO", "off", "0", "n", ""}) {
            assertEquals(Boolean.FALSE, c.convert(v), v);
        }
    }

    @Test
    void booleanRejectsGarbage() {
        Converter<Boolean> c = Converters.forType(Boolean.class);
        assertThrows(IllegalArgumentException.class, () -> c.convert("maybe"));
    }

    @Test
    void numericConversions() {
        assertEquals(42, Converters.forType(Integer.class).convert("42"));
        assertEquals(42L, Converters.forType(Long.class).convert("42"));
        assertEquals(1.5d, Converters.forType(Double.class).convert("1.5"));
        assertEquals(1.5f, Converters.forType(Float.class).convert("1.5"));
    }

    @Test
    void primitiveTypesShareWrapperConverter() {
        assertEquals(42, Converters.forType(int.class).convert("42"));
        assertEquals(Boolean.TRUE, Converters.forType(boolean.class).convert("true"));
    }

    @Test
    void durationConversion() {
        assertEquals(Duration.ofMinutes(5), Converters.forType(Duration.class).convert("PT5M"));
    }

    @Test
    void uriAndPathConversion() {
        assertEquals(URI.create("https://example.com/x"),
                Converters.forType(URI.class).convert("https://example.com/x"));
        assertEquals(Path.of("/tmp/foo"), Converters.forType(Path.class).convert("/tmp/foo"));
    }

    @Test
    void unsupportedTypeThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> Converters.forType(java.math.BigInteger.class));
    }
}
