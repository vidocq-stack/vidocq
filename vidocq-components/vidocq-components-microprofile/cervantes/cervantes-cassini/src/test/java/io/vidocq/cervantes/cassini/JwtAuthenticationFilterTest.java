package io.vidocq.cervantes.cassini;

import io.vidocq.cervantes.api.JwtValidationException;
import io.vidocq.cervantes.api.JwtValidator;
import io.vidocq.cervantes.cassini.CassiniTestDoubles.FakeRequestContext;
import io.vidocq.cervantes.cdi.JsonWebTokenContext;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Filtre d'authentification : extraction Bearer, validation, SecurityContext / abort 401. */
class JwtAuthenticationFilterTest {

    private final JsonWebToken validToken = CassiniTestDoubles.token("alice", Set.of("admin"));

    private final JwtValidator validator = raw -> {
        if ("good".equals(raw)) return validToken;
        throw new JwtValidationException("invalid: " + raw);
    };

    @Test
    void validBearer_setsSecurityContextAndCdiToken() {
        JsonWebTokenContext context = new JsonWebTokenContext();
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(validator, context);

        FakeRequestContext rc = new FakeRequestContext().authorization("Bearer good");
        filter.filter(rc);

        assertFalse(rc.isAborted());
        assertInstanceOf(JwtSecurityContext.class, rc.getSecurityContext());
        assertSame(validToken, rc.getSecurityContext().getUserPrincipal());
        assertSame(validToken, context.current().orElseThrow());
    }

    @Test
    void invalidBearer_abortsWith401() {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(validator, new JsonWebTokenContext());

        FakeRequestContext rc = new FakeRequestContext().authorization("Bearer rubbish");
        filter.filter(rc);

        assertTrue(rc.isAborted());
        assertEquals(401, rc.abortedStatus());
    }

    @Test
    void missingAuthorization_leavesRequestAnonymous() {
        JsonWebTokenContext context = new JsonWebTokenContext();
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(validator, context);

        FakeRequestContext rc = new FakeRequestContext(); // no Authorization header
        filter.filter(rc);

        assertFalse(rc.isAborted(), "anonymous request must not be aborted by authentication");
        assertNull(rc.getSecurityContext());
        assertTrue(context.current().isEmpty());
    }

    @Test
    void nonBearerScheme_isIgnored() {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(validator, new JsonWebTokenContext());

        FakeRequestContext rc = new FakeRequestContext().authorization("Basic dXNlcjpwYXNz");
        filter.filter(rc);

        assertFalse(rc.isAborted());
        assertNull(rc.getSecurityContext());
    }
}
