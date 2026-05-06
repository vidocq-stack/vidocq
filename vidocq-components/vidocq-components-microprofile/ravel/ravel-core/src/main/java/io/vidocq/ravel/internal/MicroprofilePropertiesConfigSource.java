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

    private final String name;
    private final Map<String, String> data;

    private MicroprofilePropertiesConfigSource(String name, Map<String, String> data) {
        this.name = name;
        this.data = data;
    }

    /**
     * Énumère toutes les URLs {@code META-INF/microprofile-config.properties} accessibles
     * depuis {@code classLoader} et produit une source par URL.
     *
     * @return liste immuable, jamais {@code null}
     */
    public static List<MicroprofilePropertiesConfigSource> loadAll(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader");
        var sources = new ArrayList<MicroprofilePropertiesConfigSource>();
        try {
            var urls = classLoader.getResources(RESOURCE_PATH);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                sources.add(loadOne(url));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to enumerate " + RESOURCE_PATH + " from ClassLoader", e);
        }
        return List.copyOf(sources);
    }

    private static MicroprofilePropertiesConfigSource loadOne(URL url) {
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
                Collections.unmodifiableMap(data));
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
        return ORDINAL;
    }
}
