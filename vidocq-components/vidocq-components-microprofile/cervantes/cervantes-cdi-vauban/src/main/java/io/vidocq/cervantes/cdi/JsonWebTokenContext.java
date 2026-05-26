package io.vidocq.cervantes.cdi;

import jakarta.enterprise.context.RequestScoped;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.Optional;

/**
 * Contexte de requête portant le {@link JsonWebToken} validé de la requête courante.
 *
 * <p>Posé par le filtre d'authentification JAX-RS (module {@code cervantes-cassini}, M4) après
 * validation du bearer token, puis lu par {@link io.vidocq.cervantes.cdi.internal.JsonWebTokenProducer}
 * pour produire le principal injectable. {@code @RequestScoped} : une instance par requête, isolée
 * entre requêtes concurrentes par le contexte de requête Vauban.</p>
 */
@RequestScoped
public class JsonWebTokenContext {

    private JsonWebToken token;

    /** Renseigne le token validé de la requête (appelé par le filtre d'authentification). */
    public void setToken(JsonWebToken token) {
        this.token = token;
    }

    /** @return le token de la requête courante s'il a été validé, sinon vide (requête anonyme). */
    public Optional<JsonWebToken> current() {
        return Optional.ofNullable(token);
    }

    /** Réinitialise le contexte (fin de requête). */
    public void clear() {
        this.token = null;
    }
}
