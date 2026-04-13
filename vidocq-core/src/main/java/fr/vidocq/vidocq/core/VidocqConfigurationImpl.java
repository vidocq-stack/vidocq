package fr.vidocq.vidocq.core;

import fr.vidocq.vidocq.spi.VidocqConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;

/**
 * Implémentation de {@link VidocqConfiguration}.
 * <p>
 * Résolution par ordre de priorité :
 * <ol>
 *   <li>Propriétés système ({@code -Dkey=value})</li>
 *   <li>Variables d'environnement (clé en majuscules, {@code .} remplacé par {@code _})</li>
 *   <li>Fichier {@code vidocq.properties} du classpath</li>
 * </ol>
 */
final class VidocqConfigurationImpl implements VidocqConfiguration {

    private static final String PROPERTIES_FILE = "vidocq.properties";

    private final Properties fileProperties;

    VidocqConfigurationImpl() {
        this.fileProperties = loadFileProperties();
    }

    @Override
    public Optional<String> property(String key) {
        // 1. System properties
        String value = System.getProperty(key);
        if (value != null) {
            return Optional.of(value);
        }
        // 2. Environment variables (key.name -> KEY_NAME)
        String envKey = key.toUpperCase().replace('.', '_');
        value = System.getenv(envKey);
        if (value != null) {
            return Optional.of(value);
        }
        // 3. vidocq.properties file
        value = fileProperties.getProperty(key);
        if (value != null) {
            return Optional.of(value);
        }
        return Optional.empty();
    }

    private static Properties loadFileProperties() {
        var props = new Properties();
        try (InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(PROPERTIES_FILE)) {
            if (is != null) {
                props.load(is);
            }
        } catch (IOException e) {
            System.Logger logger = System.getLogger(VidocqConfigurationImpl.class.getName());
            logger.log(System.Logger.Level.WARNING, "Failed to load " + PROPERTIES_FILE, e);
        }
        return props;
    }
}
