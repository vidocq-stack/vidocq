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

import org.eclipse.microprofile.config.spi.Converter;

import java.io.Serial;
import java.util.function.Function;

/**
 * An implicit converter around a direct call, with the contract of Ravel's reflective implicit converter: a
 * {@code null} value is a {@link NullPointerException}, an empty one converts to {@code null}, an
 * {@link IllegalArgumentException} or a {@link NullPointerException} of the factory passes through, any other
 * failure becomes an {@link IllegalArgumentException}.
 *
 * @param <T> the target type
 */
public final class ImplicitConversion<T> implements Converter<T> {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String typeName;
    private final transient Function<String, ? extends T> factory;

    private ImplicitConversion(String typeName, Function<String, ? extends T> factory) {
        this.typeName = typeName;
        this.factory = factory;
    }

    /** A converter to {@code type} through {@code factory} (a static method or a constructor reference). */
    public static <T> Converter<T> of(Class<T> type, Function<String, ? extends T> factory) {
        return new ImplicitConversion<>(type.getName(), factory);
    }

    /** A converter to the enum {@code type} through {@link Enum#valueOf(Class, String)}. */
    public static <E extends Enum<E>> Converter<E> ofEnum(Class<E> type) {
        return new ImplicitConversion<>("enum " + type.getName(), value -> {
            try {
                return Enum.valueOf(type, value);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Cannot convert '" + value + "' to enum " + type.getName(), e);
            }
        });
    }

    @Override
    public T convert(String value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        if (value.isEmpty()) {
            return null;
        }
        try {
            return factory.apply(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Cannot convert '" + value + "' to " + typeName, e);
        }
    }
}
