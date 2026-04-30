package io.vidocq.mpserver.ext.chappe.spi;

import io.vidocq.chappe.api.Handler;

/**
 * SPI Vidocq permettant à une extension de fournir un {@link Handler} Chappe
 * configurable par properties — invoqué par {@code ChappeMountConfigExtension}
 * pour chaque entrée {@code vidocq.mount.<name>.type=<type>}.
 *
 * <p>Découverte par {@link java.util.ServiceLoader} via le module path
 * (déclaration {@code provides … with …} dans {@code module-info.java}) ou
 * via {@code META-INF/services/io.vidocq.mpserver.ext.chappe.spi.MountHandlerProvider}.</p>
 *
 * <h3>Exemple</h3>
 * <pre>{@code
 * public final class CassiniMountHandlerProvider implements MountHandlerProvider {
 *     public String type() { return "cassini"; }
 *     public Handler create(MountConfig cfg) {
 *         return new ChappeHttpAdapter(buildCassiniStack(cfg).adapter());
 *     }
 * }
 * }</pre>
 */
public interface MountHandlerProvider {

    /**
     * Identifiant logique du type de mount (clé {@code vidocq.mount.<name>.type}).
     * Doit être unique parmi tous les providers découverts.
     */
    String type();

    /**
     * Construit le {@link Handler} pour un mount donné, avec accès à la config
     * scopée sous {@code vidocq.mount.<name>.}.
     */
    Handler create(MountConfig config);
}
