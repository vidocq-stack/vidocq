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
 * Implémentation par défaut de {@link VidocqConfig}.
 *
 * <p>Découverte des sources en deux temps :</p>
 * <ol>
 *   <li>{@link ServiceLoader} sur {@link ConfigSourceProvider} — si <em>au moins
 *       un</em> provider est enregistré (typiquement
 *       {@code vidocq-runtime-ravel-extension} qui apporte MicroProfile Config),
 *       l'union de leurs sources est utilisée, et les {@link ConfigSource} natifs
 *       Vidocq sont <b>ignorés</b>. C'est la responsabilité du provider d'apporter
 *       des substituts équivalents (sys, env, fichiers) — Vidocq ne mixe pas pour
 *       éviter le double comptage et préserver les ordinaux du moteur externe.</li>
 *   <li>Sinon, {@link ServiceLoader} sur {@link ConfigSource} — comportement
 *       historique : les 4 sources natives Vidocq (Sys 400, Env 300, ExternalFile,
 *       PropertiesFile 100 qui lit {@code vidocq.properties} et
 *       {@code application.properties}).</li>
 * </ol>
 *
 * <p>Dans les deux cas, les sources sont triées par {@link ConfigSource#getOrdinal()}
 * décroissant. Résolution first-wins.</p>
 *
 * <p>Le constructeur {@link #VidocqConfigImpl(List)} force une liste de sources
 * explicite (utilisé par les tests unitaires).</p>
 */
public final class VidocqConfigImpl implements VidocqConfig {

    private final List<ConfigSource> sources;

    /**
     * Auto-découverte : recherche d'abord les {@link ConfigSourceProvider}
     * via ServiceLoader ; à défaut, charge les {@link ConfigSource} natifs.
     */
    public VidocqConfigImpl() {
        this(discover());
    }

    /**
     * Force une liste explicite de sources (mode test).
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

        // Providers (ex. Ravel) prennent la main quand présents — pas d'agrégation
        // avec les sources natives, pour respecter la sémantique d'ordinaux du
        // moteur externe et éviter le double comptage.
        List<ConfigSource> fromProviders = new ArrayList<>();
        for (ConfigSourceProvider p : ServiceLoader.load(ConfigSourceProvider.class)) {
            for (ConfigSource s : p.getConfigSources(cl)) {
                fromProviders.add(s);
            }
        }
        if (!fromProviders.isEmpty()) return fromProviders;

        // Fallback : sources natives Vidocq (Sys, Env, ExternalFile, PropertiesFile).
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
