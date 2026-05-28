package io.vidocq.runtime.ext.ravel;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * MicroProfile {@link org.eclipse.microprofile.config.spi.ConfigSource} qui lit
 * {@code application.properties} depuis le classpath.
 *
 * <p>Convention historique SE / MP-compatible. En mode Ravel, ce fichier n'est
 * pas lu par défaut — seul {@code META-INF/microprofile-config.properties} l'est,
 * conformément à la spec MP Config 3.1 §3. Cette source restitue la
 * compatibilité en enregistrant {@code application.properties} comme une MP
 * {@code ConfigSource} standard.</p>
 *
 * <p><b>Ordinal 100</b> — identique à
 * {@code MicroprofilePropertiesConfigSource} (Ravel), ce qui place
 * {@code application.properties} et {@code microprofile-config.properties} au
 * même niveau de priorité.
 * {@link VidocqPropertiesConfigSource} (ordinal 105) gagne sur les deux.</p>
 */
public final class ApplicationPropertiesConfigSource
        implements org.eclipse.microprofile.config.spi.ConfigSource {

    private static final System.Logger LOG =
            System.getLogger(ApplicationPropertiesConfigSource.class.getName());

    private static final String FILE = "application.properties";

    private final Properties properties;

    public ApplicationPropertiesConfigSource() {
        this.properties = load();
    }

    @Override
    public String getName() {
        return "ApplicationPropertiesConfigSource";
    }

    @Override
    public int getOrdinal() {
        return 100;
    }

    @Override
    public String getValue(String key) {
        return properties.getProperty(key);
    }

    @Override
    public Set<String> getPropertyNames() {
        Set<String> names = new HashSet<>();
        for (Object k : properties.keySet()) {
            names.add(String.valueOf(k));
        }
        return Collections.unmodifiableSet(names);
    }

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> map = new java.util.HashMap<>();
        for (Object k : properties.keySet()) {
            String name = String.valueOf(k);
            map.put(name, properties.getProperty(name));
        }
        return Collections.unmodifiableMap(map);
    }

    private static Properties load() {
        Properties props = new Properties();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = ApplicationPropertiesConfigSource.class.getClassLoader();
        try (InputStream is = cl.getResourceAsStream(FILE)) {
            if (is != null) {
                props.load(is);
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Failed to load " + FILE, e);
        }
        return props;
    }
}
