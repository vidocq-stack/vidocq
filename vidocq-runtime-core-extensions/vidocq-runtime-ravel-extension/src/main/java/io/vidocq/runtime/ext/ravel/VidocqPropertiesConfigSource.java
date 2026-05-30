package io.vidocq.runtime.ext.ravel;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * MicroProfile {@link org.eclipse.microprofile.config.spi.ConfigSource} which reads
 * {@code vidocq.properties} from the classpath.
 *
 * <p>Historically, Vidocq reads {@code vidocq.properties} (name
 * runtime specific), with {@code application.properties} as fallback
 * (SE/MicroProfile-compatible convention). In Ravel mode — when this
 * extension is on the path module — Ravel does not read {@code vidocq.properties}
 * default; this source restores historical compatibility.</p>
 *
 * <p><b>Ordinal 105</b> — slightly higher than
 * {@link ApplicationPropertiesConfigSource} (100) and
 * {@code MicroprofilePropertiesConfigSource} (100, default value of the spec
 * MP Config 3.1 §3 for {@code META-INF/microprofile-config.properties}). In
 * case of collision on the same key, {@code vidocq.properties} wins, which
 * preserves Vidocq semantics prior to Ravel integration.</p>
 *
 * <p>The file is read only once at instantiation (at startup). For
 * reload the config without restarting, you would have to rebuild the {@code Config}
 * via {@code ConfigProviderResolver} — not natively supported by MP Config.</p>
 */
public final class VidocqPropertiesConfigSource
        implements org.eclipse.microprofile.config.spi.ConfigSource {

    private static final System.Logger LOG =
            System.getLogger(VidocqPropertiesConfigSource.class.getName());

    private static final String FILE = "vidocq.properties";

    private final Properties properties;

    public VidocqPropertiesConfigSource() {
        this.properties = load();
    }

    @Override
    public String getName() {
        return "VidocqPropertiesConfigSource";
    }

    @Override
    public int getOrdinal() {
        return 105;
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
        if (cl == null) cl = VidocqPropertiesConfigSource.class.getClassLoader();
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
