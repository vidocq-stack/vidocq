package io.vidocq.cervantes.cdi;

import io.vidocq.cervantes.api.JwtValidator;
import io.vidocq.cervantes.cdi.internal.JwtAuthConfigProducer;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Intégration sous container Vauban (CDI SE) : le {@code JwtValidator} produit par
 * {@link JwtAuthConfigProducer} à partir de {@code mp.jwt.verify.*} est injectable et opérationnel.
 */
class VaubanJwtValidatorIntegrationTest {

    @AfterEach
    void cleanup() {
        CdiTestSupport.releaseGlobalConfig();
    }

    @Test
    void jwtValidatorIsProducedAndInjectableUnderVauban() throws Exception {
        KeyPair rsa = CdiTestSupport.rsaKeyPair();
        CdiTestSupport.registerGlobalConfig(Map.of(
                "mp.jwt.verify.issuer", "https://issuer.vidocq.dev",
                "mp.jwt.verify.publickey", CdiTestSupport.publicKeyBase64(rsa.getPublic())));

        SeContainerInitializer initializer = SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addBeanClasses(JwtAuthConfigProducer.class, ValidatorConsumer.class);

        try (SeContainer container = initializer.initialize()) {
            ValidatorConsumer consumer = container.select(ValidatorConsumer.class).get();
            assertNotNull(consumer.validator, "JwtValidator must be injected");

            JsonObject claims = Json.createObjectBuilder()
                    .add("iss", "https://issuer.vidocq.dev")
                    .add("sub", "u-7")
                    .add("exp", Instant.now().getEpochSecond() + 3600)
                    .build();
            JsonWebToken jwt = consumer.validator.validate(CdiTestSupport.signRs256(claims, rsa.getPrivate()));
            assertEquals("u-7", jwt.getSubject());
        }
    }

    @Dependent
    public static class ValidatorConsumer {
        @Inject
        public JwtValidator validator;
    }
}
