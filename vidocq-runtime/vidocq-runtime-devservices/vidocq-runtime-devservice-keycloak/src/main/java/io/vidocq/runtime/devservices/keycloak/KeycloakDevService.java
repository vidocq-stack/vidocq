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

import io.vidocq.runtime.devservices.spi.DevContainers;
import io.vidocq.runtime.devservices.spi.DevService;
import io.vidocq.runtime.devservices.spi.DevServiceContext;
import io.vidocq.runtime.devservices.spi.DevServiceState;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Starts a Keycloak container for a {@code vidocq:dev} session and publishes the OIDC coordinates the
 * application needs: {@code mp.jwt.verify.issuer} and {@code mp.jwt.verify.publickey.location}.
 *
 * <p>Because the dev JVM reaches Keycloak through the Testcontainers-mapped port, the issuer host the
 * token is signed with and the host the app validates against are the same — there is no front/back
 * channel split to reconcile (unlike a Docker-network compose).</p>
 *
 * <p>Opts out when {@code mp.jwt.verify.issuer} is already configured. Tunables (all optional):
 * {@code vidocq.dev.keycloak.image|realm|realm-import} and the shared {@code vidocq.dev.reuse} and
 * {@code vidocq.dev.container-prefix} ({@link DevContainers}); plus two app-mapping helpers so a generic
 * provider can feed app-specific keys without any app-side code:</p>
 * <ul>
 *   <li>{@code vidocq.dev.keycloak.issuer-keys} — comma-separated extra keys to also receive the
 *       resolved issuer (e.g. {@code arago.oidc.issuer});</li>
 *   <li>{@code vidocq.dev.keycloak.public-url-key} — a key to receive the app's public URL (from
 *       {@code vidocq.dev.public-url}, else {@code http://localhost:<chappe port>}).</li>
 * </ul>
 */
public final class KeycloakDevService implements DevService {

    private static final String DEFAULT_IMAGE = "quay.io/keycloak/keycloak:26.0";
    private static final int KC_PORT = 8080;
    /** The id of this dev service, also the name and label of its container. */
    private static final String ID = "keycloak";
    /** User name and password of the throwaway container's bootstrap administrator. */
    private static final String BOOTSTRAP_ADMIN = "admin";
    /** The path, and the endpoint name, of Keycloak's administration console. */
    private static final String ADMIN_CONSOLE = "admin";
    /** The prefix of the coordinates published for the developer, not for the application. */
    private static final String DEV_PREFIX = "vidocq.dev.keycloak.";

    /** The path of a realm under the server root, before its name. */
    private static final String REALMS = "/realms/";

    private GenericContainer<?> container;
    private String image;
    private String issuer;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int order() {
        return 200;
    }

    @Override
    public boolean appliesWhen(DevServiceContext ctx) {
        return ctx.property("mp.jwt.verify.issuer").isEmpty();
    }

    @Override
    public Map<String, String> start(DevServiceContext ctx) {
        image = ctx.property("vidocq.dev.keycloak.image").orElse(DEFAULT_IMAGE);
        Optional<String> realmImport = ctx.property("vidocq.dev.keycloak.realm-import");
        // With a realm import, the issuer realm is the imported one; without, only the built-in
        // 'master' realm exists, so we wait on (and issue against) that.
        String realm = realmImport.isPresent()
                ? ctx.property("vidocq.dev.keycloak.realm").orElse("vidocq")
                : "master";

        container = container(ctx, image, realmImport, realm, reuse(ctx));
        container.start();

        issuer = "http://" + container.getHost() + ":" + container.getMappedPort(KC_PORT)
                + REALMS + realm;
        ctx.log().log(System.Logger.Level.INFO, "Keycloak dev service ready, issuer " + issuer);

        Map<String, String> props = new LinkedHashMap<>();
        props.put("mp.jwt.verify.issuer", issuer);
        props.put("mp.jwt.verify.publickey.location", issuer + "/protocol/openid-connect/certs");
        // The developer's own coordinates, for target/vidocq-dev-services.properties and the tests: the token
        // endpoint and the admin console a curl or a browser needs (BUG-20261030-16).
        props.put(DEV_PREFIX + "issuer", issuer);
        props.put(DEV_PREFIX + "token-url", issuer + "/protocol/openid-connect/token");
        props.put(DEV_PREFIX + "admin-console", issuer.substring(0, issuer.indexOf(REALMS)) + "/" + ADMIN_CONSOLE);
        props.put(DEV_PREFIX + "admin-user", BOOTSTRAP_ADMIN);
        // App-mapping: duplicate the issuer into extra app-specific keys (e.g. arago.oidc.issuer).
        ctx.property("vidocq.dev.keycloak.issuer-keys").ifPresent(csv -> {
            for (String key : csv.split(",")) {
                String trimmed = key.trim();
                if (!trimmed.isEmpty()) {
                    props.put(trimmed, issuer);
                }
            }
        });
        ctx.property("vidocq.dev.keycloak.public-url-key").ifPresent(key -> {
            String publicUrl = ctx.property("vidocq.dev.public-url").orElse(
                    "http://localhost:" + ctx.property("vidocq.chappe.listener.default.port").orElse("8080"));
            props.put(key.trim(), publicUrl);
        });
        return props;
    }

    /**
     * The Keycloak container, not started: named and labelled by {@link DevContainers}. A reused one is named after
     * its image, realm and the content of the realm it imports, which Testcontainers' reuse hash also covers.
     */
    static GenericContainer<?> container(DevServiceContext ctx, String image, Optional<String> realmImport,
            String realm, boolean reuse) {
        Path realmFile = realmImport.map(ctx::resolve).orElse(null);
        String reuseKey = reuse ? image + "\n" + realm + "\n" + realmDigest(realmFile) : null;
        Optional<String> fixedPort = ctx.property(DEV_PREFIX + "port").map(String::trim).filter(p -> !p.isEmpty());
        if (reuseKey != null && fixedPort.isPresent()) {
            reuseKey += "\n" + fixedPort.get();
        }
        String name = DevContainers.name(ctx, ID, null, reuseKey);
        // Configured on the instance itself, not through the fluent chain: the caller owns (and stops) this very
        // container, which a chained expression would hide behind the builder's return values.
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(image));
        container.withExposedPorts(KC_PORT)
                .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", BOOTSTRAP_ADMIN)
                .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", BOOTSTRAP_ADMIN)
                .withLabels(DevContainers.labels(ctx, ID))
                .withCreateContainerCmdModifier(cmd -> cmd.withName(name));

        if (realmFile != null) {
            container.withCopyFileToContainer(MountableFile.forHostPath(realmFile),
                            "/opt/keycloak/data/import/" + realmFile.getFileName())
                    .withCommand("start-dev", "--import-realm");
        } else {
            container.withCommand("start-dev");
        }
        container.waitingFor(Wait.forHttp(REALMS + realm).forPort(KC_PORT).forStatusCode(200)
                .withStartupTimeout(Duration.ofMinutes(3)));
        // vidocq.dev.keycloak.port pins the host port: the issuer, and every token request of a tutorial or a
        // script, stays the same across starts (BUG-20261030-16).
        fixedPort.ifPresent(port -> container.setPortBindings(List.of(port + ":" + KC_PORT)));
        if (reuse) {
            container.withReuse(true);
        }
        return container;
    }

    /** The realm file's path and content hash, so an edited realm gets a new container; empty without one. */
    private static String realmDigest(Path realmFile) {
        if (realmFile == null) {
            return "";
        }
        try {
            return realmFile.toAbsolutePath() + "#" + Arrays.hashCode(Files.readAllBytes(realmFile));
        } catch (IOException _) {
            return realmFile.toAbsolutePath().toString(); // unreadable: the copy will say so when it starts
        }
    }

    @Override
    public void stop() {
        if (container != null) {
            container.stop();
            container = null;
        }
    }

    @Override
    public DevServiceState describe(Map<String, String> injected) {
        Map<String, String> endpoints = new LinkedHashMap<>();
        if (container != null) {
            String base = "http://" + container.getHost() + ":" + container.getMappedPort(KC_PORT);
            endpoints.put("issuer", issuer);
            endpoints.put("token", issuer + "/protocol/openid-connect/token");
            endpoints.put(ADMIN_CONSOLE, base + "/" + ADMIN_CONSOLE);
            endpoints.put("admin login", BOOTSTRAP_ADMIN + " / " + BOOTSTRAP_ADMIN);
        }
        return new DevServiceState(id(), image, endpoints, List.copyOf(injected.keySet()));
    }

    private static boolean reuse(DevServiceContext ctx) {
        return ctx.property("vidocq.dev.reuse").map(Boolean::parseBoolean).orElse(false);
    }
}
