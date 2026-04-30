package io.vidocq.mpserver.ext.chappe.spi;

import io.vidocq.chappe.api.Handler;

/**
 * SPI Vidocq permettant à une extension de fournir un {@link Handler} Chappe
 * configurable par properties — invoqué par {@code ChappeMountConfigExtension}
 * pour chaque entrée {@code vidocq.http.mount.<name>.type=<type>}.
 *
 * <p>Découverte par {@link java.util.ServiceLoader} via le module path
 * (déclaration {@code provides … with …} dans {@code module-info.java}) ou
 * via {@code META-INF/services/io.vidocq.mpserver.ext.chappe.spi.MountHandlerProvider}.</p>
 *
 * <h3>Convention de nommage</h3>
 * <p>{@link #type()} doit décrire le <b>contrat standard</b> servi (ex.
 * {@code "restful"} pour Jakarta RESTful Web Services, {@code "servlet"}
 * pour Jakarta Servlet, {@code "static"} pour du contenu statique), pas
 * l'implémentation. Plusieurs providers peuvent ainsi partager le même type ;
 * la sélection se fera plus tard via une property {@code .impl} optionnelle
 * (à introduire le jour où la cohabitation devient nécessaire).</p>
 *
 * <h3>Exemple</h3>
 * <pre>{@code
 * public final class CassiniMountHandlerProvider implements MountHandlerProvider {
 *     public String type() { return "restful"; }
 *     public Handler create(MountConfig cfg) {
 *         return new ChappeHttpAdapter(buildCassiniStack(cfg).adapter());
 *     }
 * }
 * }</pre>
 */
public interface MountHandlerProvider {

    /**
     * Identifiant logique du <b>contrat standard</b> servi par ce provider
     * (clé {@code vidocq.http.mount.<name>.type}).
     *
     * <p>Pour l'instant, un seul provider doit déclarer un {@code type} donné
     * (les conflits sont signalés à la résolution). Une future property
     * {@code .impl} permettra de partager un type entre plusieurs
     * implémentations.</p>
     */
    String type();

    /**
     * Construit le {@link Handler} pour un mount donné, avec accès à la config
     * scopée sous {@code vidocq.mount.<name>.}.
     */
    Handler create(MountConfig config);
}
