package io.vidocq.mpserver.ext.chappe.spi;

import io.vidocq.mpserver.spi.ExtensionContext;
import io.vidocq.mpserver.spi.config.VidocqConfig;

import java.util.List;
import java.util.Optional;

/**
 * Vue scopée de la configuration d'un mount déclaratif. Exposée aux
 * {@link MountHandlerProvider} pour qu'ils n'aient pas à reconstruire la clé
 * complète {@code vidocq.mount.<name>.<suffix>}.
 *
 * <h3>Méthodes utilitaires</h3>
 * <ul>
 *   <li>{@link #property(String)} — valeur brute scopée</li>
 *   <li>{@link #property(String, Class)} — valeur typée scopée</li>
 *   <li>{@link #property(String, Class, Object)} — valeur typée + défaut</li>
 *   <li>{@link #properties(String, Class)} — liste typée (CSV)</li>
 * </ul>
 */
public final class MountConfig {

    private final String name;
    private final String prefix;
    private final String listener;
    private final int priority;
    private final VidocqConfig config;
    private final ExtensionContext context;
    private final String keyPrefix;

    public MountConfig(String name, String prefix, String listener, int priority,
                       VidocqConfig config, ExtensionContext context) {
        this.name = name;
        this.prefix = prefix;
        this.listener = listener;
        this.priority = priority;
        this.config = config;
        this.context = context;
        this.keyPrefix = "vidocq.mount." + name + ".";
    }

    /** Identifiant logique du mount (clé déclarative). */
    public String name() { return name; }

    /** Préfixe HTTP de mount (ex. {@code "/api"}, {@code ""} pour la racine). */
    public String prefix() { return prefix; }

    /** Listener Chappe cible (par défaut {@code "default"}). */
    public String listener() { return listener; }

    /** Priorité d'enregistrement (plus haut = monté en premier, donc plus prioritaire). */
    public int priority() { return priority; }

    /** Accès brut au {@link VidocqConfig} sous-jacent. */
    public VidocqConfig vidocqConfig() { return config; }

    /** Contexte d'extension (pour {@code beanManager()}, {@code container()}, etc.). */
    public ExtensionContext extensionContext() { return context; }

    /** Valeur brute de {@code vidocq.mount.<name>.<suffix>}. */
    public Optional<String> property(String suffix) {
        return config.getValue(keyPrefix + suffix);
    }

    /** Valeur typée de {@code vidocq.mount.<name>.<suffix>}. */
    public <T> Optional<T> property(String suffix, Class<T> type) {
        return config.getValue(keyPrefix + suffix, type);
    }

    /** Valeur typée avec défaut de {@code vidocq.mount.<name>.<suffix>}. */
    public <T> T property(String suffix, Class<T> type, T defaultValue) {
        return config.getValue(keyPrefix + suffix, type, defaultValue);
    }

    /** Liste typée (CSV) de {@code vidocq.mount.<name>.<suffix>}. */
    public <T> List<T> properties(String suffix, Class<T> elementType) {
        return config.getValues(keyPrefix + suffix, elementType);
    }
}
