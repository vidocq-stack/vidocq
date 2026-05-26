package io.vidocq.cervantes.cdi.internal;

import io.vidocq.cervantes.cdi.JsonWebTokenContext;
import io.vidocq.cervantes.internal.DefaultJsonWebToken;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Producteur du principal {@code JsonWebToken} depuis le contexte de requête. */
class JsonWebTokenProducerTest {

    private final JsonWebTokenProducer producer = new JsonWebTokenProducer();

    @Test
    void producesTheTokenSetOnTheContext() {
        JsonObject claims = Json.createObjectBuilder().add("sub", "u1").add("upn", "alice").build();
        JsonWebToken token = new DefaultJsonWebToken(claims, "raw-token");

        JsonWebTokenContext context = new JsonWebTokenContext();
        context.setToken(token);

        assertSame(token, producer.currentToken(context));
    }

    @Test
    void producesAnonymousPrincipalWhenNoToken() {
        JsonWebToken jwt = producer.currentToken(new JsonWebTokenContext());

        assertNull(jwt.getName(), "anonymous principal has no name");
        // MP JWT TCK EmptyTokenTest: an unauthenticated/empty token exposes null claim names
        // (not an empty set) — the DefaultJsonWebToken.anonymous() contract.
        assertNull(jwt.getClaimNames(), "anonymous principal exposes no claim names");
    }

    @Test
    void contextClearRemovesToken() {
        JsonWebTokenContext context = new JsonWebTokenContext();
        context.setToken(new DefaultJsonWebToken(Json.createObjectBuilder().add("sub", "u1").build(), "raw"));
        context.clear();

        assertNull(producer.currentToken(context).getName(), "cleared context yields an anonymous principal");
    }
}
