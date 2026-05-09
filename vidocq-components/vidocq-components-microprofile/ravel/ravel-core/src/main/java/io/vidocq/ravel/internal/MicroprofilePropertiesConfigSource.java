/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.internal;

import org.eclipse.microprofile.config.spi.ConfigSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * {@link ConfigSource} adossée à un fichier {@code META-INF/microprofile-config.properties}
 * — ordinal 100 par défaut (MP Config 3.1 §3.4).
 *
 * <p>Une instance par URL trouvée sur le {@link ClassLoader} : un classpath qui contient
 * plusieurs JARs avec ce fichier produit autant de sources distinctes (cf. spec §3.4 :
 * <em>"There can be multiple of these files, e.g. one per JAR."</em>).</p>
 *
 * <p>Snapshot figé au chargement — pas de hot-reload (hors spec MP Config 3.1).</p>
 */
public final class MicroprofilePropertiesConfigSource implements ConfigSource {

    /** Chemin canonique du fichier de propriétés MP Config (§3.4). */
    public static final String RESOURCE_PATH = "META-INF/microprofile-config.properties";

    private static final int ORDINAL = 100;
    /** §7.5 — ordinal des fichiers profil-aware {@code microprofile-config-{profile}.properties}. */
    private static final int PROFILED_ORDINAL = 110;

    private final String name;
    private final Map<String, String> data;
    private final int defaultOrdinal;

    private MicroprofilePropertiesConfigSource(String name, Map<String, String> data, int defaultOrdinal) {
        this.name = name;
        this.data = data;
        this.defaultOrdinal = defaultOrdinal;
    }

    /**
     * Énumère toutes les URLs {@code META-INF/microprofile-config.properties} accessibles
     * depuis {@code classLoader} et produit une source par URL.
     *
     * @return liste immuable, jamais {@code null}
     */
    public static List<MicroprofilePropertiesConfigSource> loadAll(ClassLoader classLoader) {
        return loadFromPath(classLoader, RESOURCE_PATH, ORDINAL);
    }

    /**
     * §7.5 — charge les fichiers profil-aware {@code META-INF/microprofile-config-{profile}.properties}
     * (ordinal par défaut 110, écrasant le fichier non profilé).
     */
    public static List<MicroprofilePropertiesConfigSource> loadProfile(ClassLoader classLoader, String profile) {
        Objects.requireNonNull(profile, "profile");
        return loadFromPath(classLoader,
                "META-INF/microprofile-config-" + profile + ".properties", PROFILED_ORDINAL);
    }

    private static List<MicroprofilePropertiesConfigSource> loadFromPath(
            ClassLoader classLoader, String path, int defaultOrdinal) {
        Objects.requireNonNull(classLoader, "classLoader");
        var sources = new ArrayList<MicroprofilePropertiesConfigSource>();
        try {
            var urls = classLoader.getResources(path);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                sources.add(loadOne(url, defaultOrdinal));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to enumerate " + path + " from ClassLoader", e);
        }
        return List.copyOf(sources);
    }

    private static MicroprofilePropertiesConfigSource loadOne(URL url, int defaultOrdinal) {
        var props = new Properties();
        try (InputStream in = url.openStream()) {
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + url, e);
        }
        var data = new HashMap<String, String>(props.size());
        for (var name : props.stringPropertyNames()) {
            data.put(name, props.getProperty(name));
        }
        return new MicroprofilePropertiesConfigSource(
                "MicroprofilePropertiesConfigSource[" + url + "]",
                Collections.unmodifiableMap(data),
                defaultOrdinal);
    }

    @Override
    public String getValue(String propertyName) {
        return data.get(propertyName);
    }

    @Override
    public Set<String> getPropertyNames() {
        return data.keySet();
    }

    @Override
    public Map<String, String> getProperties() {
        return data;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public int getOrdinal() {
        // §3.4 — un éventuel {@code config_ordinal} dans le fichier remplace
        // l'ordinal par défaut.
        String override = data.get("config_ordinal");
        if (override != null) {
            try {
                return Integer.parseInt(override.trim());
            } catch (NumberFormatException ignored) {
                // valeur non parsable → ordinal par défaut
            }
        }
        return defaultOrdinal;
    }
}
