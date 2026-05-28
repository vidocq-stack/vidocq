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
 * {@code vidocq.properties} depuis le classpath.
 *
 * <p>Historiquement, Vidocq lit en priorité {@code vidocq.properties} (nom
 * spécifique au runtime), avec {@code application.properties} comme fallback
 * (convention SE / MicroProfile-compatible). En mode Ravel — quand cette
 * extension est sur le module path — Ravel ne lit pas {@code vidocq.properties}
 * par défaut ; cette source restitue la compatibilité historique.</p>
 *
 * <p><b>Ordinal 105</b> — légèrement supérieur à
 * {@link ApplicationPropertiesConfigSource} (100) et à
 * {@code MicroprofilePropertiesConfigSource} (100, valeur par défaut de la spec
 * MP Config 3.1 §3 pour {@code META-INF/microprofile-config.properties}). En
 * cas de collision sur une même clé, {@code vidocq.properties} gagne, ce qui
 * préserve la sémantique Vidocq antérieure à l'intégration Ravel.</p>
 *
 * <p>Le fichier est lu une seule fois à l'instanciation (au démarrage). Pour
 * recharger la config sans redémarrer, il faudrait reconstruire le {@code Config}
 * via {@code ConfigProviderResolver} — non supporté nativement par MP Config.</p>
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
