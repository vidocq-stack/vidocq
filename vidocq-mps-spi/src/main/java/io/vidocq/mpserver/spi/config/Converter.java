package io.vidocq.mpserver.spi.config;

/**
 * Convertisseur de chaîne brute vers un type cible.
 * <p>
 * Les implémentations sont enregistrées dans la couche d'implémentation de
 * {@link VidocqConfig}. L'API est alignée sur {@code org.eclipse.microprofile.config.spi.Converter}.
 * </p>
 *
 * <p><b>Raw string to target type converter.</b>
 * API aligned with MicroProfile Config {@code Converter}.</p>
 *
 * @param <T> type cible / target type
 */
@FunctionalInterface
public interface Converter<T> {

    /**
     * Convertit la valeur brute. Peut lever une {@link IllegalArgumentException} si invalide.
     * Retourner {@code null} signifie "valeur vide".
     * <p>Convert the raw value. May throw {@link IllegalArgumentException} if invalid.
     * Returning {@code null} means "empty value".</p>
     */
    T convert(String value);
}
