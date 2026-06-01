package io.vidocq.cervantes.examples;

import io.vidocq.cervantes.cdi.CervantesClaimExtension;
import io.vidocq.cervantes.cdi.JsonWebTokenContext;
import io.vidocq.cervantes.internal.DefaultJsonWebToken;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link ProtectedResource} end-to-end at the identity layer: an embedded Vauban container
 * resolves its {@code @Inject @Claim} fields from the current request's token, exactly as Cervantes'
 * CDI integration does at runtime behind the JAX-RS auth filter. Authorization ({@code @RolesAllowed})
 * is enforced by cervantes-jaxrs at the HTTP layer (covered end-to-end by the Vidocq
 * {@code vidocq-runtime-it-cervantes-jwt} integration test); here we focus on the claim injection the
 * resource methods rely on.
 */
class ProtectedResourceExampleTest {

    private static JsonObject aliceToken() {
        return Json.createObjectBuilder()
                .add("iss", "https://issuer.vidocq.dev")
                .add("sub", "u-42")
                .add("upn", "alice")
                .add("groups", Json.createArrayBuilder().add("admin").add("user"))
                .build();
    }

    @Test
    void resolvesClaimsIntoTheSecuredResource() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(CervantesClaimExtension.class)
                .addBeanClass(JsonWebTokenContext.class)
                .addBeanClass(ProtectedResource.class)
                .build()) {

            container.requestContext().runInScope(() -> {
                container.select(JsonWebTokenContext.class)
                        .setToken(new DefaultJsonWebToken(aliceToken(), "raw-token"));

                ProtectedResource resource = container.select(ProtectedResource.class);

                // @PermitAll endpoint — no token needed.
                assertEquals("public — no token required", resource.publicInfo());

                // @Inject @Claim(upn) String + @Claim(groups) Set<String>.
                String me = resource.whoAmI();
                assertTrue(me.contains("alice"), "whoAmI must surface the upn claim: " + me);
                assertTrue(me.contains("admin") && me.contains("user"),
                        "whoAmI must surface the groups claim: " + me);

                // @Inject @Claim ClaimValue<String> resolved lazily within the request scope.
                assertEquals("Welcome, administrator alice", resource.adminOnly());
            });
        }
    }
}
