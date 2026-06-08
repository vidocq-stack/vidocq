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
 * Integration under Vauban container (CDI SE): the {@code JwtValidator} produced by
 * {@link JwtAuthConfigProducer} from {@code mp.jwt.verify.*} is for injection and operational.
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
