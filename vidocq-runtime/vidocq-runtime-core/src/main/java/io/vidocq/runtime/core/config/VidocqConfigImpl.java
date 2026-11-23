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
 *       {@code vidocq-runtime-ravel-config-extension} which provides MicroProfile Config),
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
 * <p>A value is returned with its MicroProfile Config property expressions
 * ({@code ${key}}, {@code ${key:default}}) expanded, as Ravel expands them
 * ({@link PropertyExpressions}).</p>
 *
 * <p>Configuration profiles follow MicroProfile Config 3.1 "Config Profile", as Ravel applies them. The active
 * profile is {@value #PROFILE_KEY}, else {@value #MP_PROFILE_KEY}, read raw from the sources (an environment
 * variable {@code VIDOCQ_PROFILE} or {@code MP_CONFIG_PROFILE} among them). Under a profile {@code dev}, each source
 * is asked for {@code %dev.<key>} before {@code <key>}: a profiled key wins over the plain key of its own source, and
 * the ordinals still order the sources.</p>
 *
 * <p>The constructor {@link #VidocqConfigImpl(List)} forces a list of sources
 * explicit (used by unit tests).</p>
 */
public final class VidocqConfigImpl implements VidocqConfig {

    /** The key that names the active profile; it wins over {@value #MP_PROFILE_KEY}. */
    public static final String PROFILE_KEY = "vidocq.profile";
    /** The MicroProfile Config key that names the active profile when {@value #PROFILE_KEY} has no value. */
    public static final String MP_PROFILE_KEY = "mp.config.profile";

    private final List<ConfigSource> sources;
    /** The class names of the providers whose sources replaced the native ones; empty for the native sources. */
    private final List<String> sourceProviders;
    /**
     * {@code %<profile>.} of the active profile, {@code ""} when no profile is active; {@code null} until the first
     * read, so that a source that fails fails a read, never the construction. Two threads that both read it first
     * find the same value.
     */
    private volatile String profilePrefix;
    private final PropertyExpressions expressions;

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
        this(new Discovery(sources, List.of()));
    }

    private VidocqConfigImpl(Discovery discovery) {
        List<ConfigSource> copy = new ArrayList<>(discovery.sources());
        copy.sort(Comparator.comparingInt(ConfigSource::getOrdinal).reversed());
        this.sources = Collections.unmodifiableList(copy);
        this.sourceProviders = List.copyOf(discovery.providers());
        this.expressions = new PropertyExpressions(this::rawValue);
    }

    /**
     * The class names of the {@link ConfigSourceProvider}s whose sources replaced Vidocq's native ones, in
     * discovery order; empty when the native sources are used. The startup report names them.
     */
    public List<String> sourceProviders() {
        return sourceProviders;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The value of the first source that has the key, under the active profile first; its property
     * expressions expanded; empty as well when an expression without a default names a key that has no value.</p>
     *
     * @throws IllegalArgumentException when an expression is circular or has no closing brace
     */
    @Override
    public Optional<String> getValue(String key) {
        String raw = rawValue(key);
        return raw == null ? Optional.empty() : expressions.expand(key, raw);
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
        String prefix = profilePrefix();
        LinkedHashSet<String> all = new LinkedHashSet<>();
        for (ConfigSource s : sources) {
            for (String name : s.getPropertyNames()) {
                all.add(name);
                // The keys of the active profile also answer under their plain name, as in Ravel.
                if (!prefix.isEmpty() && name != null && name.startsWith(prefix)) {
                    all.add(name.substring(prefix.length()));
                }
            }
        }
        return all;
    }

    @Override
    public Iterable<ConfigSource> getConfigSources() {
        return sources;
    }

    /** {@inheritDoc} Read as the sources hold the keys, once, at the first lookup. */
    @Override
    public Optional<String> activeProfile() {
        String prefix = profilePrefix();
        return prefix.isEmpty() ? Optional.empty() : Optional.of(prefix.substring(1, prefix.length() - 1));
    }

    // ---------- helpers ----------

    /**
     * The value of the first source that has {@code key}, as that source holds it; {@code null} when none has it.
     * Under a profile, each source is asked for the profiled key before the plain one (MicroProfile Config 3.1).
     */
    private String rawValue(String key) {
        String prefix = profilePrefix();
        if (prefix.isEmpty()) {
            return rawValue(sources, key);
        }
        String profiled = prefix + key;
        for (ConfigSource s : sources) {
            String v = s.getValue(profiled);
            if (v == null) v = s.getValue(key);
            if (v != null) return v;
        }
        return null;
    }

    /**
     * {@code %<profile>.} of the profile {@value #PROFILE_KEY} names, else {@value #MP_PROFILE_KEY}; {@code ""} for
     * none. Read from the sources once, at the first lookup.
     */
    private String profilePrefix() {
        String prefix = profilePrefix;
        if (prefix == null) {
            String profile = trimToNull(rawValue(sources, PROFILE_KEY));
            if (profile == null) profile = trimToNull(rawValue(sources, MP_PROFILE_KEY));
            prefix = profile == null ? "" : "%" + profile + ".";
            profilePrefix = prefix;
        }
        return prefix;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** The value of the first source that has {@code key}, as that source holds it; {@code null} when none has it. */
    private static String rawValue(List<ConfigSource> sources, String key) {
        for (ConfigSource s : sources) {
            String v = s.getValue(key);
            if (v != null) return v;
        }
        return null;
    }

    /** The sources found, and the providers they came from when a provider replaced the native ones. */
    private record Discovery(List<ConfigSource> sources, List<String> providers) {}

    private static Discovery discover() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = VidocqConfigImpl.class.getClassLoader();

        // Providers (e.g. Ravel) take control when present — no aggregation
        // with native sources, to respect the ordinal semantics of the
        // external motor and avoid double counting.
        List<ConfigSource> fromProviders = new ArrayList<>();
        List<String> providers = new ArrayList<>();
        for (ConfigSourceProvider p : ServiceLoader.load(ConfigSourceProvider.class)) {
            providers.add(p.getClass().getName());
            for (ConfigSource s : p.getConfigSources(cl)) {
                fromProviders.add(s);
            }
        }
        if (!fromProviders.isEmpty()) return new Discovery(fromProviders, providers);

        // Fallback: native Vidocq sources (Sys, Env, ExternalFile, PropertiesFile).
        List<ConfigSource> natives = new ArrayList<>();
        for (ConfigSource s : ServiceLoader.load(ConfigSource.class)) {
            natives.add(s);
        }
        return new Discovery(natives, List.of());
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
