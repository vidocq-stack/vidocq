package io.vidocq.runtime.core.config;

import io.vidocq.runtime.spi.config.ConfigSource;
import io.vidocq.runtime.spi.config.ConfigSourceProvider;
import io.vidocq.runtime.spi.config.Converter;
import io.vidocq.runtime.spi.config.VidocqConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Default implementation of {@link VidocqConfig}.
 *
 * <p>Discovery of sources in two stages:</p>
 * <ol>
 *   <li>{@link ServiceLoader} on {@link ConfigSourceProvider} — if <em>at least
 *       a</em>provider is registered (typically
 *       {@code vidocq-runtime-ravel-extension} which provides MicroProfile Config),
 *       the union of their sources is used, and the native {@link ConfigSource}
 *       Vidocq are <b>ignored</b>. It is the responsibility of the provider to provide
 *       equivalent substitutes (sys, env, files) — Vidocq does not mix for
 *       avoid double counting and preserve the ordinals of the external engine.</li>
 *   <li>Otherwise, {@link ServiceLoader} on {@link ConfigSource} — behavior
 *       history: the 4 native Vidocq sources (Sys 400, Env 300, ExternalFile,
 *       PropertiesFile 100 which reads {@code vidocq.properties} and
 *       {@code application.properties}).</li>
 * </ol>
 *
 * <p>In both cases, the sources are sorted by {@link ConfigSource#getOrdinal()}
 * decreasing. First-wins resolution.</p>
 *
 * <p>The constructor {@link #VidocqConfigImpl(List)} forces a list of sources
 * explicit (used by unit tests).</p>
 */
public final class VidocqConfigImpl implements VidocqConfig {

    private final List<ConfigSource> sources;

    /**
     * Auto-discovery: first searches for {@link ConfigSourceProvider}
     * via ServiceLoader; failing that, loads the native {@link ConfigSource}.
     */
    public VidocqConfigImpl() {
        this(discover());
    }

    /**
     * Forces an explicit list of sources (test mode).
     */
    public VidocqConfigImpl(List<ConfigSource> sources) {
        List<ConfigSource> copy = new ArrayList<>(sources);
        copy.sort(Comparator.comparingInt(ConfigSource::getOrdinal).reversed());
        this.sources = Collections.unmodifiableList(copy);
    }

    @Override
    public Optional<String> getValue(String key) {
        for (ConfigSource s : sources) {
            String v = s.getValue(key);
            if (v != null) return Optional.of(v);
        }
        return Optional.empty();
    }

    @Override
    public <T> Optional<T> getValue(String key, Class<T> type) {
        return getValue(key).map(raw -> {
            Converter<T> c = Converters.forType(type);
            return c.convert(raw);
        });
    }

    @Override
    public <T> List<T> getValues(String key, Class<T> elementType) {
        return getValue(key)
                .map(raw -> {
                    Converter<T> c = Converters.forType(elementType);
                    List<T> out = new ArrayList<>();
                    for (String item : splitList(raw)) {
                        out.add(c.convert(item));
                    }
                    return out;
                })
                .orElseGet(List::of);
    }

    @Override
    public Iterable<String> getPropertyNames() {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        for (ConfigSource s : sources) {
            all.addAll(s.getPropertyNames());
        }
        return all;
    }

    @Override
    public Iterable<ConfigSource> getConfigSources() {
        return sources;
    }

    // ---------- helpers ----------

    private static List<ConfigSource> discover() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = VidocqConfigImpl.class.getClassLoader();

        // Providers (e.g. Ravel) take control when present — no aggregation
        // with native sources, to respect the ordinal semantics of the
        // external motor and avoid double counting.
        List<ConfigSource> fromProviders = new ArrayList<>();
        for (ConfigSourceProvider p : ServiceLoader.load(ConfigSourceProvider.class)) {
            for (ConfigSource s : p.getConfigSources(cl)) {
                fromProviders.add(s);
            }
        }
        if (!fromProviders.isEmpty()) return fromProviders;

        // Fallback: native Vidocq sources (Sys, Env, ExternalFile, PropertiesFile).
        List<ConfigSource> natives = new ArrayList<>();
        for (ConfigSource s : ServiceLoader.load(ConfigSource.class)) {
            natives.add(s);
        }
        return natives;
    }

    private static List<String> splitList(String raw) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escape = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escape) {
                current.append(c);
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == ',') {
                out.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        out.add(current.toString());
        return out;
    }
}
