package io.vidocq.mpserver.core.config;

import io.vidocq.mpserver.spi.config.ConfigSource;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Source lisant les variables d'environnement.
 * <p>
 * Résolution en trois passes (règles MicroProfile Config) :
 * <ol>
 *   <li>clé exacte</li>
 *   <li>remplace les caractères non alphanumériques par {@code _}</li>
 *   <li>idem + upper-case</li>
 * </ol>
 */
public final class EnvConfigSource implements ConfigSource {

    @Override
    public String getName() {
        return "Environment";
    }

    @Override
    public int getOrdinal() {
        return 300;
    }

    @Override
    public String getValue(String key) {
        Map<String, String> env = System.getenv();
        String v = env.get(key);
        if (v != null) return v;
        String normalized = sanitize(key);
        v = env.get(normalized);
        if (v != null) return v;
        return env.get(normalized.toUpperCase(Locale.ROOT));
    }

    @Override
    public Set<String> getPropertyNames() {
        return System.getenv().keySet();
    }

    static String sanitize(String key) {
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            sb.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        return sb.toString();
    }
}
