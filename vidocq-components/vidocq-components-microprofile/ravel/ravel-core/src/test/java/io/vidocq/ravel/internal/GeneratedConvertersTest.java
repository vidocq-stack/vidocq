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
package io.vidocq.ravel.internal;

import io.vidocq.ravel.spi.GeneratedConverters;
import org.eclipse.microprofile.config.spi.Converter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The implicit converters Ravel's processor generates are used before the reflective discovery, which stays the
 * path for any type they do not know.
 */
class GeneratedConvertersTest {

    /** A type with a public {@code of(String)}: the reflective implicit converter can handle it too. */
    public record Code(String value) {
        public static Code of(String value) {
            return new Code("reflective:" + value);
        }
    }

    public record Other(String value) {
        public static Other of(String value) {
            return new Other("reflective:" + value);
        }
    }

    @Test
    void theGeneratedConverterIsUsedFirst() {
        var calls = new AtomicInteger();
        var config = config();
        config.useGeneratedConverters(List.of(new GeneratedConverters() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> Converter<T> converter(Class<T> type) {
                if (type != Code.class) {
                    return null;
                }
                return (Converter<T>) (Converter<Code>) value -> {
                    calls.incrementAndGet();
                    return new Code("generated:" + value);
                };
            }
        }));

        assertEquals(new Code("generated:a"), config.getValue("code", Code.class));
        assertEquals(1, calls.get());
        assertEquals(new Other("reflective:b"), config.getValue("other", Other.class),
                "a type the generated converters do not know keeps the reflective path");
    }

    @Test
    void withoutGeneratedConvertersTheReflectivePathIsUnchanged() {
        var config = config();
        config.useGeneratedConverters(List.of());

        assertEquals(new Code("reflective:a"), config.getValue("code", Code.class));
    }

    private static RavelConfig config() {
        var source = new MapConfigSource("test", 100, Map.of("code", "a", "other", "b"));
        return new RavelConfig(List.of(source), Map.of(String.class, (Converter<String>) v -> v),
                GeneratedConvertersTest.class.getClassLoader());
    }
}
