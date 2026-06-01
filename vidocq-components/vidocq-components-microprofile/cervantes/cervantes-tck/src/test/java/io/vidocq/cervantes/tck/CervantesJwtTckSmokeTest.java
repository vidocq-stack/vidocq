package io.vidocq.cervantes.tck;

import io.vidocq.cervantes.api.JwtValidator;
import io.vidocq.cervantes.cdi.JsonWebTokenContext;
import io.vidocq.cervantes.cdi.internal.JwtAuthConfigProducer;
import io.vidocq.cervantes.jaxrs.JwtAuthenticationFilter;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.jwt.Claims;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Smoke test: checks that Cervantes modules and JWT 2.1 spec MP
 * are of course classpath and that the main classes are accessible.
 * Run by the "smoke" profile (default active) without Arquillian.
 */
class CervantesJwtTckSmokeTest {

    @Test
    void mpJwtApiOnClasspath() {
        // org.eclipse.microprofile.jwt.JsonWebToken must be accessible
        assertNotNull(JsonWebToken.class.getAnnotation(java.lang.annotation.Retention.class) != null
                        ? JsonWebToken.class
                        : JsonWebToken.class,
                "JsonWebToken interface must be on classpath");

        // Claims enum must be accessible
        assertNotNull(Claims.class, "@Claims enum must be accessible");
    }

    @Test
    void cervantesModulesOnClasspath() {
        assertNotNull(JwtValidator.class, "JwtValidator (cervantes-api) must be on classpath");
        assertNotNull(JsonWebTokenContext.class, "JsonWebTokenContext (cervantes-cdi-vauban) must be on classpath");
        assertNotNull(JwtAuthConfigProducer.class, "JwtAuthConfigProducer must be on classpath");
        assertNotNull(JwtAuthenticationFilter.class, "JwtAuthenticationFilter (cervantes-jaxrs) must be on classpath");
    }
}
