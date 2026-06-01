package io.vidocq.cervantes.jaxrs;

import jakarta.ws.rs.core.SecurityContext;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.security.Principal;
import java.util.Set;

/**
 * {@link SecurityContext} JAX-RS adossé à un {@link JsonWebToken} validé (MicroProfile JWT 2.1 §7).
 *
 * <ul>
 *   <li>{@link #getUserPrincipal()} → le {@code JsonWebToken} lui-même (qui est un {@link Principal}).</li>
 *   <li>{@link #isUserInRole(String)} → vrai si le rôle figure dans le claim {@code groups}.</li>
 *   <li>{@link #getAuthenticationScheme()} → {@value #MP_JWT}.</li>
 * </ul>
 */
public final class JwtSecurityContext implements SecurityContext {

    /** Schéma d'authentification exposé pour un token MicroProfile JWT. */
    public static final String MP_JWT = "MP-JWT";

    private final JsonWebToken token;
    private final boolean secure;

    public JwtSecurityContext(JsonWebToken token, boolean secure) {
        this.token = token;
        this.secure = secure;
    }

    @Override
    public Principal getUserPrincipal() {
        return token;
    }

    @Override
    public boolean isUserInRole(String role) {
        Set<String> groups = token.getGroups();
        return groups != null && groups.contains(role);
    }

    @Override
    public boolean isSecure() {
        return secure;
    }

    @Override
    public String getAuthenticationScheme() {
        return MP_JWT;
    }
}
