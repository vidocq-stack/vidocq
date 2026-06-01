package io.vidocq.cervantes.jaxrs;

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
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.Map;

/**
 * Filtre d'authentification MicroProfile JWT : extrait le bearer token depuis :
 * <ol>
 *   <li>l'en-tête {@code Authorization: Bearer …} (comportement par défaut) ;</li>
 *   <li>un cookie nommé par {@code mp.jwt.token.cookie} quand {@code mp.jwt.token.header=Cookie}.</li>
 * </ol>
 *
 * <p>{@code @PreMatching} (requis pour pouvoir appeler {@code setSecurityContext}, JAX-RS §6.6) et
 * priorité {@link Priorities#AUTHENTICATION} (s'exécute avant l'autorisation). Un échec de
 * validation interrompt la requête en {@code 401}. En l'absence de token, la requête reste anonyme
 * — c'est l'autorisation ({@code @RolesAllowed}, etc.) qui tranchera.</p>
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
    /** MP JWT spec §9.2.3: default token header name. */
    private static final String DEFAULT_TOKEN_HEADER = "Authorization";

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
        String rawToken = extractToken(requestContext);
        if (rawToken == null || validator == null) {
            // No token, or MP-JWT not configured (no JwtValidator) → leave the request anonymous.
            // Without the validator-null guard, an app that ships cervantes but configures no
            // mp.jwt.verify.* (e.g. a second, non-OIDC Bearer issuer) would NPE on every Bearer.
            return;
        }
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

    /**
     * Extrait le token brut depuis la requête.
     * MP JWT spec §9.2.3 :
     * - {@code mp.jwt.token.header=Authorization} (default) → {@code Authorization: Bearer <token>}
     * - {@code mp.jwt.token.header=Cookie} → cookie named by {@code mp.jwt.token.cookie} (default "Bearer")
     * Returns {@code null} if no token is present (anonymous request).
     */
    private static String extractToken(ContainerRequestContext requestContext) {
        String tokenHeader = DEFAULT_TOKEN_HEADER;
        String cookieName = "Bearer";
        try {
            var cfg = ConfigProvider.getConfig();
            tokenHeader = cfg.getOptionalValue("mp.jwt.token.header", String.class).orElse(DEFAULT_TOKEN_HEADER);
            cookieName = cfg.getOptionalValue("mp.jwt.token.cookie", String.class).orElse("Bearer");
        } catch (Exception ignored) {
            // Config not available during test doubles — fall through to default
        }

        if ("Cookie".equalsIgnoreCase(tokenHeader)) {
            Map<String, Cookie> cookies = requestContext.getCookies();
            if (cookies != null) {
                Cookie cookie = cookies.get(cookieName);
                if (cookie != null) {
                    String value = cookie.getValue();
                    return (value != null && !value.isBlank()) ? value : null;
                }
            }
            return null;
        }

        // Default: Authorization: Bearer <token>
        String authorization = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        return authorization.substring(BEARER_PREFIX.length()).trim();
    }
}
