/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
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
 * MicroProfile JWT authentication filter: extracts the bearer token from:
 * <ol>
 *   <li>the {@code Authorization: Bearer …} header (default behaviour);</li>
 *   <li>a cookie named by {@code mp.jwt.token.cookie} when {@code mp.jwt.token.header=Cookie}.</li>
 * </ol>
 *
 * <p>{@code @PreMatching} (required to call {@code setSecurityContext}, JAX-RS §6.6) and
 * priority {@link Priorities#AUTHENTICATION} (runs before authorization). A validation failure
 * aborts the request with {@code 401}. When no token is present the request remains anonymous
 * — authorization ({@code @RolesAllowed}, etc.) will decide.</p>
 *
 * <p>Also sets the token on the {@link JsonWebTokenContext} (request scope) to make the
 * principal injectable via CDI ({@code @Inject JsonWebToken}).</p>
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
        // required by CDI (normal-scoped bean)
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
     * Extracts the raw token from the request.
     * MP JWT spec §9.2.3:
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
