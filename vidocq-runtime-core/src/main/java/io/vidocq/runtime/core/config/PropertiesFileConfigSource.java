package io.vidocq.runtime.core.config;

import io.vidocq.runtime.spi.config.ConfigSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * Source reading {@code vidocq.properties} and {@code application.properties} from the classpath.
 * Ordinal 100.
 */
public final class PropertiesFileConfigSource implements ConfigSource {

    private static final System.Logger LOG =
            System.getLogger(PropertiesFileConfigSource.class.getName());

    private static final String[] FILES = {"vidocq.properties", "application.properties"};

    private final Properties properties;

    public PropertiesFileConfigSource() {
        this.properties = load();
    }

    @Override
    public String getName() {
        return "PropertiesFile";
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

    private static Properties load() {
        Properties props = new Properties();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = PropertiesFileConfigSource.class.getClassLoader();
        for (String file : FILES) {
            try (InputStream is = cl.getResourceAsStream(file)) {
                if (is != null) {
                    props.load(is);
                }
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Failed to load " + file, e);
            }
        }
        return props;
    }
}
