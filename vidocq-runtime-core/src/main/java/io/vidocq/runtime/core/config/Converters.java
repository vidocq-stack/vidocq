package io.vidocq.runtime.core.config;

import io.vidocq.runtime.spi.config.Converter;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Registre des convertisseurs built-in pour {@link io.vidocq.runtime.spi.config.VidocqConfig}.
 * <p>
 * Types supportés : {@code String, Boolean, Integer, Long, Double, Float,
 * Duration, URI, Path}. Les primitifs sont mappés vers leurs wrappers.
 * </p>
 */
final class Converters {

    private static final Map<Class<?>, Converter<?>> BUILTIN = new HashMap<>();

    static {
        BUILTIN.put(String.class, (Converter<String>) s -> s);
        BUILTIN.put(Boolean.class, (Converter<Boolean>) Converters::parseBoolean);
        BUILTIN.put(boolean.class, (Converter<Boolean>) Converters::parseBoolean);
        BUILTIN.put(Integer.class, (Converter<Integer>) Integer::valueOf);
        BUILTIN.put(int.class, (Converter<Integer>) Integer::valueOf);
        BUILTIN.put(Long.class, (Converter<Long>) Long::valueOf);
        BUILTIN.put(long.class, (Converter<Long>) Long::valueOf);
        BUILTIN.put(Double.class, (Converter<Double>) Double::valueOf);
        BUILTIN.put(double.class, (Converter<Double>) Double::valueOf);
        BUILTIN.put(Float.class, (Converter<Float>) Float::valueOf);
        BUILTIN.put(float.class, (Converter<Float>) Float::valueOf);
        BUILTIN.put(Duration.class, (Converter<Duration>) Duration::parse);
        BUILTIN.put(URI.class, (Converter<URI>) URI::create);
        BUILTIN.put(Path.class, (Converter<Path>) Paths::get);
    }

    private Converters() {}

    @SuppressWarnings("unchecked")
    static <T> Converter<T> forType(Class<T> type) {
        Converter<?> c = BUILTIN.get(type);
        if (c == null) {
            throw new IllegalArgumentException("No converter registered for " + type.getName());
        }
        return (Converter<T>) c;
    }

    private static Boolean parseBoolean(String s) {
        String v = s.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (v) {
            case "true", "yes", "y", "on", "1" -> Boolean.TRUE;
            case "false", "no", "n", "off", "0", "" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException("Invalid boolean: " + s);
        };
    }
}
