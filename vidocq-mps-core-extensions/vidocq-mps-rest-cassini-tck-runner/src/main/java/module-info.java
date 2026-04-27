/**
 * Module de harness TCK pour l'extension Cassini (JAX-RS 4.0 sur Chappe).
 *
 * <p>Module <b>hors reactor</b> — utilise Maven Model 4.0.0 pour
 * compatibilité ShrinkWrap (dép. transitive du TCK Jakarta).</p>
 */
module io.vidocq.mpserver.ext.rest.cassini.tck {
    requires io.vidocq.mpserver.ext.rest.cassini;
    requires fr.vidocq.chappe.api;
    requires jakarta.ws.rs;

    requires static java.net.http;

    exports io.vidocq.mpserver.ext.rest.cassini.tck;
}
