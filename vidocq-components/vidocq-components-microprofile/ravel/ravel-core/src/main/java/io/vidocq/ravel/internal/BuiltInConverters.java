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

import java.io.Serial;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Catalogue of built-in converters for MP Config 3.1 §5.1 / §5.2 (automatic types).
 *
 * <p>All built-in converters have <b>priority 1</b> (the lowest) so that any
 * application converter (default priority 100) can override them, as required by §5.3.</p>
 *
 * <p>All converters throw {@link IllegalArgumentException} if the string cannot be
 * converted — the spec mandates this exception (see §5).</p>
 */
final class BuiltInConverters {

    /** MP §5.3 priority assigned to a built-in converter (the lowest). */
    static final int BUILT_IN_PRIORITY = 1;

    private BuiltInConverters() {
        // utility class
    }

    /**
     * Built once: the built-in converters are stateless, so every {@code Config}
     * shares the same instances (a boxed type and its primitive share one).
     */
    private static final Map<Class<?>, Converter<?>> ALL = createAll();

    /** Returns the immutable {type → converter} map for all built-in converters. */
    static Map<Class<?>, Converter<?>> all() {
        return ALL;
    }

    private static Map<Class<?>, Converter<?>> createAll() {
        var booleans = new BooleanConverter();
        var integers = new IntegerConverter();
        var longs = new LongConverter();
        var floats = new FloatConverter();
        var doubles = new DoubleConverter();
        var shorts = new ShortConverter();
        var bytes = new ByteConverter();
        var characters = new CharacterConverter();
        return Map.ofEntries(
                Map.entry(String.class, new IdentityStringConverter()),
                Map.entry(Boolean.class, booleans),
                Map.entry(boolean.class, booleans),
                Map.entry(Integer.class, integers),
                Map.entry(int.class, integers),
                Map.entry(Long.class, longs),
                Map.entry(long.class, longs),
                Map.entry(Float.class, floats),
                Map.entry(float.class, floats),
                Map.entry(Double.class, doubles),
                Map.entry(double.class, doubles),
                Map.entry(Short.class, shorts),
                Map.entry(short.class, shorts),
                Map.entry(Byte.class, bytes),
                Map.entry(byte.class, bytes),
                Map.entry(Character.class, characters),
                Map.entry(char.class, characters),
                Map.entry(Class.class, new ClassConverter()),
                Map.entry(OptionalInt.class, new OptionalIntConverter()),
                Map.entry(OptionalLong.class, new OptionalLongConverter()),
                Map.entry(OptionalDouble.class, new OptionalDoubleConverter()),
                Map.entry(URI.class, new URIConverter()),
                Map.entry(URL.class, new URLConverter()),
                Map.entry(InetAddress.class, new InetAddressConverter()),
                Map.entry(Duration.class, new DurationConverter()),
                Map.entry(Period.class, new PeriodConverter()),
                Map.entry(LocalDate.class, new LocalDateConverter()),
                Map.entry(LocalTime.class, new LocalTimeConverter()),
                Map.entry(LocalDateTime.class, new LocalDateTimeConverter()),
                Map.entry(OffsetTime.class, new OffsetTimeConverter()),
                Map.entry(OffsetDateTime.class, new OffsetDateTimeConverter()),
                Map.entry(ZonedDateTime.class, new ZonedDateTimeConverter()),
                Map.entry(Instant.class, new InstantConverter())
        );
    }

    // ---------------------------------------------------------------------
    //  Boolean — §5.1.1
    // ---------------------------------------------------------------------

    /**
     * §5.1.1 — {@code true}, {@code 1}, {@code yes}, {@code y}, {@code on}
     * (case-insensitive) are truthy; everything else is {@code false}.
     */
    static final class BooleanConverter implements Converter<Boolean> {
        @Serial private static final long serialVersionUID = 1L;

        @Override
        public Boolean convert(String value) {
            if (value == null) throw new NullPointerException("value");
            String v = value.trim();
            if (v.isEmpty()) return null;
            return v.equalsIgnoreCase("true")
                    || v.equalsIgnoreCase("yes")
                    || v.equalsIgnoreCase("y")
                    || v.equalsIgnoreCase("on")
                    || v.equals("1");
        }
    }

    // ---------------------------------------------------------------------
    //  Numeric primitives & boxes — §5.1
    // ---------------------------------------------------------------------

    static final class IntegerConverter implements Converter<Integer> {
        @Serial private static final long serialVersionUID = 1L;
        @Override public Integer convert(String value) {
            if (value == null) {
                throw new NullPointerException("value");
            }
            if (value.isEmpty()) {
                return null;
            }
            try { return Integer.valueOf(value.trim()); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to int", e); }
        }
    }

    static final class LongConverter implements Converter<Long> {
        @Serial private static final long serialVersionUID = 1L;
        @Override public Long convert(String value) {
            if (value == null) {
                throw new NullPointerException("value");
            }
            if (value.isEmpty()) {
                return null;
            }
            try { return Long.valueOf(value.trim()); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to long", e); }
        }
    }

    static final class FloatConverter implements Converter<Float> {
        @Serial private static final long serialVersionUID = 1L;
        @Override public Float convert(String value) {
            if (value == null) {
                throw new NullPointerException("value");
            }
            if (value.isEmpty()) {
                return null;
            }
            try { return Float.valueOf(value.trim()); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to float", e); }
        }
    }

    static final class DoubleConverter implements Converter<Double> {
        @Serial private static final long serialVersionUID = 1L;
        @Override public Double convert(String value) {
            if (value == null) {
                throw new NullPointerException("value");
            }
            if (value.isEmpty()) {
                return null;
            }
            try { return Double.valueOf(value.trim()); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to double", e); }
        }
    }

    static final class ShortConverter implements Converter<Short> {
        @Serial private static final long serialVersionUID = 1L;
        @Override public Short convert(String value) {
            if (value == null) {
                throw new NullPointerException("value");
            }
            if (value.isEmpty()) {
                return null;
            }
            try { return Short.valueOf(value.trim()); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to short", e); }
        }
    }

    static final class ByteConverter implements Converter<Byte> {
        @Serial private static final long serialVersionUID = 1L;
        @Override public Byte convert(String value) {
            if (value == null) {
                throw new NullPointerException("value");
            }
            if (value.isEmpty()) {
                return null;
            }
            try { return Byte.valueOf(value.trim()); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to byte", e); }
        }
    }

    static final class CharacterConverter implements Converter<Character> {
        @Serial private static final long serialVersionUID = 1L;
        @Override public Character convert(String value) {
            if (value == null) {
                throw new NullPointerException("value");
            }
            if (value.isEmpty()) {
                return null;
            }
            if (value.length() != 1) {
                throw new IllegalArgumentException("Cannot convert '" + value + "' to char (length != 1)");
            }
            return value.charAt(0);
        }
    }

    // ---------------------------------------------------------------------
    //  Class<?>
    // ---------------------------------------------------------------------

    static final class ClassConverter implements Converter<Class<?>> {
        @Serial private static final long serialVersionUID = 1L;
        @Override public Class<?> convert(String value) {
            if (value == null) {
                throw new NullPointerException("value");
            }
            if (value.isEmpty()) {
                return null;
            }
            try {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                if (cl == null) cl = ClassConverter.class.getClassLoader();
                return Class.forName(value.trim(), true, cl);
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("Cannot load class '" + value + "'", e);
            }
        }
    }

    // ---------------------------------------------------------------------
    //  Optional* (boxed primitives)
    // ---------------------------------------------------------------------

    static final class OptionalIntConverter implements Converter<OptionalInt> {
        @Serial private static final long serialVersionUID = 1L;
        @Override public OptionalInt convert(String value) {
            if (value == null) {
                throw new NullPointerException("value");
            }
            if (value.isEmpty()) {
                return OptionalInt.empty();
            }
            try { return OptionalInt.of(Integer.parseInt(value.trim())); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to OptionalInt", e); }
        }
    }

    static final class OptionalLongConverter implements Converter<OptionalLong> {
        @Serial private static final long serialVersionUID = 1L;
        @Override public OptionalLong convert(String value) {
            if (value == null) {
                throw new NullPointerException("value");
            }
            if (value.isEmpty()) {
                return OptionalLong.empty();
            }
            try { return OptionalLong.of(Long.parseLong(value.trim())); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to OptionalLong", e); }
        }
    }

    static final class OptionalDoubleConverter implements Converter<OptionalDouble> {
        @Serial private static final long serialVersionUID = 1L;
        @Override public OptionalDouble convert(String value) {
            if (value == null) {
                throw new NullPointerException("value");
            }
            if (value.isEmpty()) {
                return OptionalDouble.empty();
            }
            try { return OptionalDouble.of(Double.parseDouble(value.trim())); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to OptionalDouble", e); }
        }
    }

    // ---------------------------------------------------------------------
    //  Network / URL
    // ---------------------------------------------------------------------

    static final class URIConverter implements Converter<URI> {
        @Serial private static final long serialVersionUID = 1L;
        @Override public URI convert(String value) {
            if (value == null) {
                throw new NullPointerException("value");
            }
            if (value.isEmpty()) {
                return null;
            }
            try { return new URI(value); }
            catch (java.net.URISyntaxException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to URI", e); }
        }
    }

    static final class URLConverter implements Converter<URL> {
        @Serial private static final long serialVersionUID = 1L;
        @Override public URL convert(String value) {
            if (value == null) {
                throw new NullPointerException("value");
            }
            if (value.isEmpty()) {
                return null;
            }
            try { return URI.create(value).toURL(); }
            catch (java.net.MalformedURLException | IllegalArgumentException e) {
                throw new IllegalArgumentException("Cannot convert '" + value + "' to URL", e);
            }
        }
    }

    static final class InetAddressConverter implements Converter<InetAddress> {
        @Serial private static final long serialVersionUID = 1L;
        @Override public InetAddress convert(String value) {
            if (value == null) {
                throw new NullPointerException("value");
            }
            if (value.isEmpty()) {
                return null;
            }
            try { return InetAddress.getByName(value.trim()); }
            catch (UnknownHostException e) { throw new IllegalArgumentException("Cannot resolve '" + value + "' to InetAddress", e); }
        }
    }

    // ---------------------------------------------------------------------
    //  java.time
    // ---------------------------------------------------------------------

    static final class DurationConverter implements Converter<Duration> {
        @Serial private static final long serialVersionUID = 1L;
        @Override public Duration convert(String value) {
            if (value == null) {
                throw new NullPointerException("value");
            }
            if (value.isEmpty()) {
                return null;
            }
            try { return Duration.parse(value); }
            catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to Duration", e); }
        }
    }

    static final class PeriodConverter implements Converter<Period> {
        @Serial private static final long serialVersionUID = 1L;
        @Override public Period convert(String value) {
            if (value == null) {
                throw new NullPointerException("value");
            }
            if (value.isEmpty()) {
                return null;
            }
            try { return Period.parse(value); }
            catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to Period", e); }
        }
    }

    static final class LocalDateConverter implements Converter<LocalDate> {
        @Serial private static final long serialVersionUID = 1L;
        @Override public LocalDate convert(String value) {
            if (value == null) {
                throw new NullPointerException("value");
            }
            if (value.isEmpty()) {
                return null;
            }
            try { return LocalDate.parse(value); }
            catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to LocalDate", e); }
        }
    }

    static final class LocalTimeConverter implements Converter<LocalTime> {
        @Serial private static final long serialVersionUID = 1L;
        @Override public LocalTime convert(String value) {
            if (value == null) {
                throw new NullPointerException("value");
            }
            if (value.isEmpty()) {
                return null;
            }
            try { return LocalTime.parse(value); }
            catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to LocalTime", e); }
        }
    }

    static final class LocalDateTimeConverter implements Converter<LocalDateTime> {
        @Serial private static final long serialVersionUID = 1L;
        @Override public LocalDateTime convert(String value) {
            if (value == null) {
                throw new NullPointerException("value");
            }
            if (value.isEmpty()) {
                return null;
            }
            try { return LocalDateTime.parse(value); }
            catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to LocalDateTime", e); }
        }
    }

    static final class OffsetTimeConverter implements Converter<OffsetTime> {
        @Serial private static final long serialVersionUID = 1L;
        @Override public OffsetTime convert(String value) {
            if (value == null) {
                throw new NullPointerException("value");
            }
            if (value.isEmpty()) {
                return null;
            }
            try { return OffsetTime.parse(value); }
            catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to OffsetTime", e); }
        }
    }

    static final class OffsetDateTimeConverter implements Converter<OffsetDateTime> {
        @Serial private static final long serialVersionUID = 1L;
        @Override public OffsetDateTime convert(String value) {
            if (value == null) {
                throw new NullPointerException("value");
            }
            if (value.isEmpty()) {
                return null;
            }
            try { return OffsetDateTime.parse(value); }
            catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to OffsetDateTime", e); }
        }
    }

    static final class ZonedDateTimeConverter implements Converter<ZonedDateTime> {
        @Serial private static final long serialVersionUID = 1L;
        @Override public ZonedDateTime convert(String value) {
            if (value == null) {
                throw new NullPointerException("value");
            }
            if (value.isEmpty()) {
                return null;
            }
            try { return ZonedDateTime.parse(value); }
            catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to ZonedDateTime", e); }
        }
    }

    static final class InstantConverter implements Converter<Instant> {
        @Serial private static final long serialVersionUID = 1L;
        @Override public Instant convert(String value) {
            if (value == null) {
                throw new NullPointerException("value");
            }
            if (value.isEmpty()) {
                return null;
            }
            try { return Instant.parse(value); }
            catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to Instant", e); }
        }
    }
}

