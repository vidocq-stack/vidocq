package io.vidocq.cervantes.cassini;

import io.vidocq.cervantes.api.JwtValidationException;
import io.vidocq.cervantes.api.JwtValidator;
import io.vidocq.cervantes.cdi.JsonWebTokenContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Filtre d'authentification MicroProfile JWT : extrait le bearer token de l'en-tête
 * {@code Authorization}, le valide, et établit un {@link JwtSecurityContext}.
 *
 * <p>{@code @PreMatching} (requis pour pouvoir appeler {@code setSecurityContext}, JAX-RS §6.6) et
 * priorité {@link Priorities#AUTHENTICATION} (s'exécute avant l'autorisation). Un échec de
 * validation interrompt la requête en {@code 401}. En l'absence d'en-tête {@code Authorization},
 * la requête reste anonyme — c'est l'autorisation ({@code @RolesAllowed}, etc.) qui tranchera.</p>
 *
 * <p>Pose aussi le token sur le {@link JsonWebTokenContext} (portée requête) pour rendre le
 * principal injectable en CDI ({@code @Inject JsonWebToken}).</p>
 */
@Provider
@PreMatching
@Priority(Priorities.AUTHENTICATION)
@ApplicationScoped
public class JwtAuthenticationFilter implements ContainerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    @Inject
    JwtValidator validator;

    @Inject
    JsonWebTokenContext tokenContext;

    public JwtAuthenticationFilter() {
        // requis par CDI (bean à portée normale)
    }

    JwtAuthenticationFilter(JwtValidator validator, JsonWebTokenContext tokenContext) {
        this.validator = validator;
        this.tokenContext = tokenContext;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String authorization = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return; // pas de bearer → requête anonyme
        }
        String rawToken = authorization.substring(BEARER_PREFIX.length()).trim();
        try {
            JsonWebToken jwt = validator.validate(rawToken);
            tokenContext.setToken(jwt);
            SecurityContext previous = requestContext.getSecurityContext();
            boolean secure = previous != null && previous.isSecure();
            requestContext.setSecurityContext(new JwtSecurityContext(jwt, secure));
        } catch (JwtValidationException e) {
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED).build());
        }
    }
}
