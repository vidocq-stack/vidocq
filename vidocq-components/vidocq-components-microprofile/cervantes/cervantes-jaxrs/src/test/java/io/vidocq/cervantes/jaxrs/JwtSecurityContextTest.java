package io.vidocq.cervantes.jaxrs;

import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SecurityContext adossé au JsonWebToken (rôles = claim groups). */
class JwtSecurityContextTest {

    @Test
    void exposesPrincipalRolesAndScheme() {
        JsonWebToken jwt = CassiniTestDoubles.token("alice", Set.of("admin", "user"));
        JwtSecurityContext sc = new JwtSecurityContext(jwt, true);

        assertSame(jwt, sc.getUserPrincipal());
        assertTrue(sc.isUserInRole("admin"));
        assertTrue(sc.isUserInRole("user"));
        assertFalse(sc.isUserInRole("root"));
        assertTrue(sc.isSecure());
        assertEquals(JwtSecurityContext.MP_JWT, sc.getAuthenticationScheme());
    }

    @Test
    void insecureTransportReportedAsNotSecure() {
        JwtSecurityContext sc = new JwtSecurityContext(CassiniTestDoubles.token("bob", Set.of()), false);
        assertFalse(sc.isSecure());
        assertFalse(sc.isUserInRole("any"));
    }
}
