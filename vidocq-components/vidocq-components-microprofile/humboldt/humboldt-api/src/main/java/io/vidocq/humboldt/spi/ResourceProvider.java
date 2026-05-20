package io.vidocq.humboldt.spi;

/**
 * SPI {@code Resource} attributes — découvert via {@link java.util.ServiceLoader}.
 *
 * <p>Stub M0 — fournira les attributs OTel Resource (service.name, host.name, etc.)
 * en M2.</p>
 */
public interface ResourceProvider {

    /**
     * @return l'identifiant du fournisseur (ex. {@code "host"}, {@code "process"},
     *         {@code "container"}).
     */
    String id();
}
