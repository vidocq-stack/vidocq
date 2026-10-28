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

import org.eclipse.microprofile.config.spi.Converter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ImplicitConverter — §5.2 factory selection and failure reporting")
class ImplicitConverterFailureTest {

    private static final String IAE = "iae";
    private static final String NPE = "npe";
    private static final String OTHER = "other";

    @Test
    void null_type_has_no_implicit_converter() {
        assertNull(ImplicitConverter.create(null));
    }

    @Test
    void instance_of_method_is_ignored_in_favour_of_the_constructor() {
        Converter<InstanceOf> converter = ImplicitConverter.create(InstanceOf.class);

        assertEquals("ctor:x", converter.convert("x").origin);
    }

    @Test
    void valueOf_returning_another_type_is_ignored_in_favour_of_the_constructor() {
        Converter<ForeignValueOf> converter = ImplicitConverter.create(ForeignValueOf.class);

        assertEquals("ctor:y", converter.convert("y").origin);
    }

    @Test
    void factory_method_rejects_null_and_maps_empty_to_null() {
        Converter<ThrowingFactory> converter = ImplicitConverter.create(ThrowingFactory.class);

        assertThrows(NullPointerException.class, () -> converter.convert(null));
        assertNull(converter.convert(""));
    }

    @Test
    void factory_method_failures_keep_IAE_and_NPE_and_wrap_the_others() {
        Converter<ThrowingFactory> converter = ImplicitConverter.create(ThrowingFactory.class);

        assertSame(ThrowingFactory.IAE_FAILURE, assertThrows(IllegalArgumentException.class, () -> converter.convert(IAE)));
        assertSame(ThrowingFactory.NPE_FAILURE, assertThrows(NullPointerException.class, () -> converter.convert(NPE)));
        IllegalArgumentException wrapped = assertThrows(IllegalArgumentException.class, () -> converter.convert(OTHER));
        assertInstanceOf(IllegalStateException.class, wrapped.getCause());
    }

    @Test
    void constructor_rejects_null_and_maps_empty_to_null() {
        Converter<ThrowingConstructor> converter = ImplicitConverter.create(ThrowingConstructor.class);

        assertThrows(NullPointerException.class, () -> converter.convert(null));
        assertNull(converter.convert(""));
    }

    @Test
    void constructor_failures_keep_IAE_and_NPE_and_wrap_the_others() {
        Converter<ThrowingConstructor> converter = ImplicitConverter.create(ThrowingConstructor.class);

        assertThrows(IllegalArgumentException.class, () -> converter.convert(IAE));
        assertThrows(NullPointerException.class, () -> converter.convert(NPE));
        IllegalArgumentException wrapped = assertThrows(IllegalArgumentException.class, () -> converter.convert(OTHER));
        assertInstanceOf(IllegalStateException.class, wrapped.getCause());
    }

    @Test
    void abstract_class_constructor_cannot_instantiate() {
        Converter<AbstractWithConstructor> converter = ImplicitConverter.create(AbstractWithConstructor.class);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> converter.convert("z"));
        assertInstanceOf(InstantiationException.class, e.getCause());
    }

    @Test
    void enum_converter_rejects_null_and_maps_empty_to_null() {
        Converter<Level> converter = ImplicitConverter.create(Level.class);

        assertThrows(NullPointerException.class, () -> converter.convert(null));
        assertNull(converter.convert(""));
        assertEquals(Level.HIGH, converter.convert("HIGH"));
    }

    // -- Fixtures (public: §5.2 only considers public members) ----------------------------------

    public enum Level { LOW, HIGH }

    public static final class InstanceOf {
        final String origin;

        public InstanceOf(String value) {
            this.origin = "ctor:" + value;
        }

        public InstanceOf of(String value) {
            return new InstanceOf("of:" + value);
        }
    }

    public static final class ForeignValueOf {
        final String origin;

        public ForeignValueOf(String value) {
            this.origin = "ctor:" + value;
        }

        public static String valueOf(String value) {
            return "valueOf:" + value;
        }
    }

    public static final class ThrowingFactory {
        static final IllegalArgumentException IAE_FAILURE = new IllegalArgumentException(IAE);
        static final NullPointerException NPE_FAILURE = new NullPointerException(NPE);

        private ThrowingFactory() {
            // instances only come from of(String), which always fails
        }

        public static ThrowingFactory of(String value) {
            switch (value) {
                case IAE -> throw IAE_FAILURE;
                case NPE -> throw NPE_FAILURE;
                default -> throw new IllegalStateException(value);
            }
        }
    }

    public static final class ThrowingConstructor {
        public ThrowingConstructor(String value) {
            switch (value) {
                case IAE -> throw new IllegalArgumentException(value);
                case NPE -> throw new NullPointerException(value);
                default -> throw new IllegalStateException(value);
            }
        }
    }

    public abstract static class AbstractWithConstructor {
        final String value;

        public AbstractWithConstructor(String value) {
            this.value = value;
        }
    }
}
