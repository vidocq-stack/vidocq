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
package io.vidocq.runtime.devservices.keycloak;

import io.vidocq.runtime.devservices.spi.DevServiceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import com.github.dockerjava.api.command.CreateContainerCmd;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.core.CreateContainerCmdModifier;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class KeycloakDevServiceTest {

    @Test
    void appliesWhenNoIssuerIsConfigured() {
        assertTrue(new KeycloakDevService().appliesWhen(ctx(Map.of())));
    }

    @Test
    void optsOutWhenIssuerIsAlreadyConfigured() {
        DevServiceContext ctx = ctx(Map.of("mp.jwt.verify.issuer", "https://kc.example/realms/app"));
        assertFalse(new KeycloakDevService().appliesWhen(ctx));
    }

    @Test
    @Timeout(300)
    void startsKeycloakAndPublishesIssuerJwksAndAppMappings() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker not available — skipping");

        KeycloakDevService svc = new KeycloakDevService();
        // No realm import -> Keycloak's built-in 'master' realm. Exercise the app-mapping helpers.
        DevServiceContext ctx = ctx(Map.of(
                "vidocq.dev.keycloak.issuer-keys", "arago.oidc.issuer",
                "vidocq.dev.keycloak.public-url-key", "arago.public.url",
                "vidocq.dev.public-url", "http://localhost:9999"));
        try {
            Map<String, String> props = svc.start(ctx);

            String issuer = props.get("mp.jwt.verify.issuer");
            assertTrue(issuer != null && issuer.endsWith("/realms/master"), "got " + issuer);
            assertEquals(issuer + "/protocol/openid-connect/certs",
                    props.get("mp.jwt.verify.publickey.location"));
            // App mappings: issuer fanned out + public URL injected.
            assertEquals(issuer, props.get("arago.oidc.issuer"));
            assertEquals("http://localhost:9999", props.get("arago.public.url"));

            // Prove the issuer is live: its realm endpoint answers 200 and exposes the realm's public
            // key. (The ephemeral master realm guards the deeper OIDC endpoints — discovery/certs — with
            // a 403; an imported realm, as Arago uses, does not. The issuer root is public either way and
            // is exactly what the container's wait strategy already validated.)
            HttpResponse<String> realmInfo = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(issuer))
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, realmInfo.statusCode());
            assertTrue(realmInfo.body().contains("public_key"), "realm info: " + realmInfo.body());
        } finally {
            svc.stop();
        }
    }

    @Test
    void theContainerIsNamedAfterVidocqTheApplicationAndKeycloak() {
        GenericContainer<?> c = KeycloakDevService.container(ctx(Map.of()), "quay.io/keycloak/keycloak:26.0",
                Optional.empty(), "master", false);

        assertTrue(createdName(c).matches("vidocq-dev-[a-z0-9_.-]+-keycloak-[0-9a-f]{8}"), createdName(c));
        assertEquals("true", c.getLabels().get("io.vidocq.dev"));
        assertEquals("keycloak", c.getLabels().get("io.vidocq.dev.service"));
    }

    @Test
    void aReusedContainerChangesNameWhenTheImportedRealmChanges(@TempDir Path dir) throws Exception {
        Path realm = Files.writeString(dir.resolve("realm.json"), "{\"realm\":\"vidocq\"}");
        Optional<String> realmImport = Optional.of(realm.toString());
        String name = createdName(KeycloakDevService.container(ctx(Map.of()), "kc:26", realmImport, "vidocq", true));

        assertEquals(name,
                createdName(KeycloakDevService.container(ctx(Map.of()), "kc:26", realmImport, "vidocq", true)));
        Files.writeString(realm, "{\"realm\":\"vidocq\",\"enabled\":true}");
        assertNotEquals(name,
                createdName(KeycloakDevService.container(ctx(Map.of()), "kc:26", realmImport, "vidocq", true)));
    }

    /** The name the container's create-command modifiers give, read through a stand-in for Docker's command. */
    private static String createdName(GenericContainer<?> container) {
        String[] name = new String[1];
        CreateContainerCmd cmd = (CreateContainerCmd) Proxy.newProxyInstance(CreateContainerCmd.class.getClassLoader(),
                new Class<?>[] {CreateContainerCmd.class}, (proxy, method, args) -> {
                    if (method.getName().equals("withName")) {
                        name[0] = (String) args[0];
                    }
                    return method.getReturnType().isInstance(proxy) ? proxy : null;
                });
        for (CreateContainerCmdModifier modifier : container.getCreateContainerCmdModifiers()) {
            cmd = modifier.modify(cmd);
        }
        return name[0];
    }

    private static DevServiceContext ctx(Map<String, String> props) {
        return new DevServiceContext() {
            @Override public Optional<String> property(String key) {
                String v = props.get(key);
                return (v == null || v.isBlank()) ? Optional.empty() : Optional.of(v);
            }
            @Override public Map<String, String> properties() { return props; }
            @Override public Path basedir() { return Path.of("."); }
            @Override public Path resolve(String relative) { return Path.of(".").resolve(relative); }
            @Override public System.Logger log() { return System.getLogger("test"); }
        };
    }
}
