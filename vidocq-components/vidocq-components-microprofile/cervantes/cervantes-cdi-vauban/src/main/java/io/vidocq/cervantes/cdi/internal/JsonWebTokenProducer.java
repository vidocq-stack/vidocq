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
 * <p>Expose the validated token of the current request (via {@link JsonWebTokenContext}). In the absence
 * token (anonymous request), produces an anonymous principal without claim rather than {@code null}
 * (forbidden for normal range bean).</p>
 */
@ApplicationScoped
public class JsonWebTokenProducer {

    @Produces
    @RequestScoped
    public JsonWebToken currentToken(JsonWebTokenContext context) {
        return context.current().orElseGet(DefaultJsonWebToken::anonymous);
    }
}
