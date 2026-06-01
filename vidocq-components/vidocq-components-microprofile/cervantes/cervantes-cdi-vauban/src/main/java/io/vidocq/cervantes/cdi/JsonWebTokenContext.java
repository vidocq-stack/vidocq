package io.vidocq.cervantes.cdi;

import jakarta.enterprise.context.RequestScoped;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.Optional;

/**
 * Query context with {@link JsonWebToken} validated from the current query.
 *
 * <p>Posted by JAX-RS authentication filter (module {@code cervantes-jaxrs}, M4) after
 * validation du bearer token, puis lu par {@link io.vidocq.cervantes.cdi.internal.JsonWebTokenProducer}
 * to produce the main injection. {@code @RequestScoped}: one instance per query, isolated
 * between competing requests by the Vauban.ZZPH0ZZ query context
 */
@RequestScoped
public class JsonWebTokenContext {

    private JsonWebToken token;

    /** Stores the validated token for the request (called by the authentication filter). */
    public void setToken(JsonWebToken token) {
        this.token = token;
    }

    /** @return the token for the current request if it was validated, otherwise empty (anonymous request). */
    public Optional<JsonWebToken> current() {
        return Optional.ofNullable(token);
    }

    /** Resets the context (end of request). */
    public void clear() {
        this.token = null;
    }
}
