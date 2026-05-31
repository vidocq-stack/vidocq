/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
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

    /** Returns the immutable {type → converter} map for all built-in converters. */
    static Map<Class<?>, Converter<?>> all() {
        return Map.ofEntries(
                Map.entry(String.class, IdentityStringConverter.INSTANCE),
                Map.entry(Boolean.class, BooleanConverter.INSTANCE),
                Map.entry(boolean.class, BooleanConverter.INSTANCE),
                Map.entry(Integer.class, IntegerConverter.INSTANCE),
                Map.entry(int.class, IntegerConverter.INSTANCE),
                Map.entry(Long.class, LongConverter.INSTANCE),
                Map.entry(long.class, LongConverter.INSTANCE),
                Map.entry(Float.class, FloatConverter.INSTANCE),
                Map.entry(float.class, FloatConverter.INSTANCE),
                Map.entry(Double.class, DoubleConverter.INSTANCE),
                Map.entry(double.class, DoubleConverter.INSTANCE),
                Map.entry(Short.class, ShortConverter.INSTANCE),
                Map.entry(short.class, ShortConverter.INSTANCE),
                Map.entry(Byte.class, ByteConverter.INSTANCE),
                Map.entry(byte.class, ByteConverter.INSTANCE),
                Map.entry(Character.class, CharacterConverter.INSTANCE),
                Map.entry(char.class, CharacterConverter.INSTANCE),
                Map.entry(Class.class, ClassConverter.INSTANCE),
                Map.entry(OptionalInt.class, OptionalIntConverter.INSTANCE),
                Map.entry(OptionalLong.class, OptionalLongConverter.INSTANCE),
                Map.entry(OptionalDouble.class, OptionalDoubleConverter.INSTANCE),
                Map.entry(URI.class, URIConverter.INSTANCE),
                Map.entry(URL.class, URLConverter.INSTANCE),
                Map.entry(InetAddress.class, InetAddressConverter.INSTANCE),
                Map.entry(Duration.class, DurationConverter.INSTANCE),
                Map.entry(Period.class, PeriodConverter.INSTANCE),
                Map.entry(LocalDate.class, LocalDateConverter.INSTANCE),
                Map.entry(LocalTime.class, LocalTimeConverter.INSTANCE),
                Map.entry(LocalDateTime.class, LocalDateTimeConverter.INSTANCE),
                Map.entry(OffsetTime.class, OffsetTimeConverter.INSTANCE),
                Map.entry(OffsetDateTime.class, OffsetDateTimeConverter.INSTANCE),
                Map.entry(ZonedDateTime.class, ZonedDateTimeConverter.INSTANCE),
                Map.entry(Instant.class, InstantConverter.INSTANCE)
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
        static final BooleanConverter INSTANCE = new BooleanConverter();
        private BooleanConverter() { }

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
        static final IntegerConverter INSTANCE = new IntegerConverter();
        @Override public Integer convert(String value) {
            if (value == null) throw new NullPointerException("value"); if (value.isEmpty()) return null;
            try { return Integer.valueOf(value.trim()); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to int", e); }
        }
    }

    static final class LongConverter implements Converter<Long> {
        @Serial private static final long serialVersionUID = 1L;
        static final LongConverter INSTANCE = new LongConverter();
        @Override public Long convert(String value) {
            if (value == null) throw new NullPointerException("value"); if (value.isEmpty()) return null;
            try { return Long.valueOf(value.trim()); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to long", e); }
        }
    }

    static final class FloatConverter implements Converter<Float> {
        @Serial private static final long serialVersionUID = 1L;
        static final FloatConverter INSTANCE = new FloatConverter();
        @Override public Float convert(String value) {
            if (value == null) throw new NullPointerException("value"); if (value.isEmpty()) return null;
            try { return Float.valueOf(value.trim()); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to float", e); }
        }
    }

    static final class DoubleConverter implements Converter<Double> {
        @Serial private static final long serialVersionUID = 1L;
        static final DoubleConverter INSTANCE = new DoubleConverter();
        @Override public Double convert(String value) {
            if (value == null) throw new NullPointerException("value"); if (value.isEmpty()) return null;
            try { return Double.valueOf(value.trim()); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to double", e); }
        }
    }

    static final class ShortConverter implements Converter<Short> {
        @Serial private static final long serialVersionUID = 1L;
        static final ShortConverter INSTANCE = new ShortConverter();
        @Override public Short convert(String value) {
            if (value == null) throw new NullPointerException("value"); if (value.isEmpty()) return null;
            try { return Short.valueOf(value.trim()); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to short", e); }
        }
    }

    static final class ByteConverter implements Converter<Byte> {
        @Serial private static final long serialVersionUID = 1L;
        static final ByteConverter INSTANCE = new ByteConverter();
        @Override public Byte convert(String value) {
            if (value == null) throw new NullPointerException("value"); if (value.isEmpty()) return null;
            try { return Byte.valueOf(value.trim()); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to byte", e); }
        }
    }

    static final class CharacterConverter implements Converter<Character> {
        @Serial private static final long serialVersionUID = 1L;
        static final CharacterConverter INSTANCE = new CharacterConverter();
        @Override public Character convert(String value) {
            if (value == null) throw new NullPointerException("value"); if (value.isEmpty()) return null;
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
        static final ClassConverter INSTANCE = new ClassConverter();
        @Override public Class<?> convert(String value) {
            if (value == null) throw new NullPointerException("value"); if (value.isEmpty()) return null;
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
        static final OptionalIntConverter INSTANCE = new OptionalIntConverter();
        @Override public OptionalInt convert(String value) {
            if (value == null) throw new NullPointerException("value"); if (value.isEmpty()) return OptionalInt.empty();
            try { return OptionalInt.of(Integer.parseInt(value.trim())); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to OptionalInt", e); }
        }
    }

    static final class OptionalLongConverter implements Converter<OptionalLong> {
        @Serial private static final long serialVersionUID = 1L;
        static final OptionalLongConverter INSTANCE = new OptionalLongConverter();
        @Override public OptionalLong convert(String value) {
            if (value == null) throw new NullPointerException("value"); if (value.isEmpty()) return OptionalLong.empty();
            try { return OptionalLong.of(Long.parseLong(value.trim())); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to OptionalLong", e); }
        }
    }

    static final class OptionalDoubleConverter implements Converter<OptionalDouble> {
        @Serial private static final long serialVersionUID = 1L;
        static final OptionalDoubleConverter INSTANCE = new OptionalDoubleConverter();
        @Override public OptionalDouble convert(String value) {
            if (value == null) throw new NullPointerException("value"); if (value.isEmpty()) return OptionalDouble.empty();
            try { return OptionalDouble.of(Double.parseDouble(value.trim())); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to OptionalDouble", e); }
        }
    }

    // ---------------------------------------------------------------------
    //  Network / URL
    // ---------------------------------------------------------------------

    static final class URIConverter implements Converter<URI> {
        @Serial private static final long serialVersionUID = 1L;
        static final URIConverter INSTANCE = new URIConverter();
        @Override public URI convert(String value) {
            if (value == null) throw new NullPointerException("value"); if (value.isEmpty()) return null;
            try { return new URI(value); }
            catch (java.net.URISyntaxException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to URI", e); }
        }
    }

    static final class URLConverter implements Converter<URL> {
        @Serial private static final long serialVersionUID = 1L;
        static final URLConverter INSTANCE = new URLConverter();
        @Override public URL convert(String value) {
            if (value == null) throw new NullPointerException("value"); if (value.isEmpty()) return null;
            try { return URI.create(value).toURL(); }
            catch (java.net.MalformedURLException | IllegalArgumentException e) {
                throw new IllegalArgumentException("Cannot convert '" + value + "' to URL", e);
            }
        }
    }

    static final class InetAddressConverter implements Converter<InetAddress> {
        @Serial private static final long serialVersionUID = 1L;
        static final InetAddressConverter INSTANCE = new InetAddressConverter();
        @Override public InetAddress convert(String value) {
            if (value == null) throw new NullPointerException("value"); if (value.isEmpty()) return null;
            try { return InetAddress.getByName(value.trim()); }
            catch (UnknownHostException e) { throw new IllegalArgumentException("Cannot resolve '" + value + "' to InetAddress", e); }
        }
    }

    // ---------------------------------------------------------------------
    //  java.time
    // ---------------------------------------------------------------------

    static final class DurationConverter implements Converter<Duration> {
        @Serial private static final long serialVersionUID = 1L;
        static final DurationConverter INSTANCE = new DurationConverter();
        @Override public Duration convert(String value) {
            if (value == null) throw new NullPointerException("value"); if (value.isEmpty()) return null;
            try { return Duration.parse(value); }
            catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to Duration", e); }
        }
    }

    static final class PeriodConverter implements Converter<Period> {
        @Serial private static final long serialVersionUID = 1L;
        static final PeriodConverter INSTANCE = new PeriodConverter();
        @Override public Period convert(String value) {
            if (value == null) throw new NullPointerException("value"); if (value.isEmpty()) return null;
            try { return Period.parse(value); }
            catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to Period", e); }
        }
    }

    static final class LocalDateConverter implements Converter<LocalDate> {
        @Serial private static final long serialVersionUID = 1L;
        static final LocalDateConverter INSTANCE = new LocalDateConverter();
        @Override public LocalDate convert(String value) {
            if (value == null) throw new NullPointerException("value"); if (value.isEmpty()) return null;
            try { return LocalDate.parse(value); }
            catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to LocalDate", e); }
        }
    }

    static final class LocalTimeConverter implements Converter<LocalTime> {
        @Serial private static final long serialVersionUID = 1L;
        static final LocalTimeConverter INSTANCE = new LocalTimeConverter();
        @Override public LocalTime convert(String value) {
            if (value == null) throw new NullPointerException("value"); if (value.isEmpty()) return null;
            try { return LocalTime.parse(value); }
            catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to LocalTime", e); }
        }
    }

    static final class LocalDateTimeConverter implements Converter<LocalDateTime> {
        @Serial private static final long serialVersionUID = 1L;
        static final LocalDateTimeConverter INSTANCE = new LocalDateTimeConverter();
        @Override public LocalDateTime convert(String value) {
            if (value == null) throw new NullPointerException("value"); if (value.isEmpty()) return null;
            try { return LocalDateTime.parse(value); }
            catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to LocalDateTime", e); }
        }
    }

    static final class OffsetTimeConverter implements Converter<OffsetTime> {
        @Serial private static final long serialVersionUID = 1L;
        static final OffsetTimeConverter INSTANCE = new OffsetTimeConverter();
        @Override public OffsetTime convert(String value) {
            if (value == null) throw new NullPointerException("value"); if (value.isEmpty()) return null;
            try { return OffsetTime.parse(value); }
            catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to OffsetTime", e); }
        }
    }

    static final class OffsetDateTimeConverter implements Converter<OffsetDateTime> {
        @Serial private static final long serialVersionUID = 1L;
        static final OffsetDateTimeConverter INSTANCE = new OffsetDateTimeConverter();
        @Override public OffsetDateTime convert(String value) {
            if (value == null) throw new NullPointerException("value"); if (value.isEmpty()) return null;
            try { return OffsetDateTime.parse(value); }
            catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to OffsetDateTime", e); }
        }
    }

    static final class ZonedDateTimeConverter implements Converter<ZonedDateTime> {
        @Serial private static final long serialVersionUID = 1L;
        static final ZonedDateTimeConverter INSTANCE = new ZonedDateTimeConverter();
        @Override public ZonedDateTime convert(String value) {
            if (value == null) throw new NullPointerException("value"); if (value.isEmpty()) return null;
            try { return ZonedDateTime.parse(value); }
            catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to ZonedDateTime", e); }
        }
    }

    static final class InstantConverter implements Converter<Instant> {
        @Serial private static final long serialVersionUID = 1L;
        static final InstantConverter INSTANCE = new InstantConverter();
        @Override public Instant convert(String value) {
            if (value == null) throw new NullPointerException("value"); if (value.isEmpty()) return null;
            try { return Instant.parse(value); }
            catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("Cannot convert '" + value + "' to Instant", e); }
        }
    }
}

