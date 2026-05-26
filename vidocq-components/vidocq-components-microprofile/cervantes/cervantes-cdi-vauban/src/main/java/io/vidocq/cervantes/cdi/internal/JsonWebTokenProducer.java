package io.vidocq.cervantes.cdi.internal;

import io.vidocq.cervantes.cdi.JsonWebTokenContext;
import io.vidocq.cervantes.internal.DefaultJsonWebToken;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Producteur CDI du principal {@code @RequestScoped JsonWebToken} (MicroProfile JWT 2.1).
 *
 * <p>Expose le token validé de la requête courante (via {@link JsonWebTokenContext}). En l'absence
 * de token (requête anonyme), produit un principal anonyme sans claim plutôt que {@code null}
 * (interdit pour un bean à portée normale).</p>
 */
@ApplicationScoped
public class JsonWebTokenProducer {

    @Produces
    @RequestScoped
    public JsonWebToken currentToken(JsonWebTokenContext context) {
        return context.current().orElseGet(DefaultJsonWebToken::anonymous);
    }
}
