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
package io.vidocq.runtime.it.applayer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The same application, built once, shipped as a {@code vidocq:package} distribution and as a {@code vidocq:jlink}
 * image, and used over HTTP in both. Each form must authenticate (401 without a token), authorize
 * (403 for a token without the role, 200 with it), run the interceptor of the resource (its header), start from the
 * record of its frozen build compatible extensions with no fallback to run-time code generation, and refuse to start
 * when the verification key cannot be read. No other IT makes an HTTP request to a distribution or an image.
 */
class AppLayerDeliveryIT {

    private static final String STARTED = "Vidocq - Started in";
    private static final String ISSUER = "https://delivery.acme";
    private static final String KEY_LOCATION = "MP_JWT_VERIFY_PUBLICKEY_LOCATION";
    private static final String STAMP = "X-Acme-Stamped";
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    @Test
    void theDistributionAndTheImageServeTheSameSecuredApplication(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir).resolve("delivery");
        Path build = Fixture.logs().resolve("delivery-build.log");
        Fixture.awaitSuccess(Fixture.start(project, build, "package", "vidocq:package", "vidocq:jlink",
                "-DskipTests"), build, "vidocq:package and vidocq:jlink");
        List<String> distribution = List.of("sh", distributionScript(project.resolve("target")).toString());
        Path image = project.resolve("target/dist/bin/app-layer-delivery");
        assertTrue(Files.isRegularFile(image), "vidocq:jlink wrote no launcher:\n" + Fixture.read(build));

        KeyPair keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        Path key = Files.writeString(dir.resolve("key.pem"), "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(keys.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n");

        // Under the application layer a thread of the common pool misses the application's configuration: the
        // distribution prints CFG-ASYNC MISSING (BUG-20261118-93, open), the image CFG-ASYNC from-app.
        servesTheSecuredResource(distribution, "delivery-package", key, keys.getPrivate(), "CFG-ASYNC ");
        servesTheSecuredResource(List.of(image.toString()), "delivery-image", key, keys.getPrivate(),
                "CFG-ASYNC from-app");

        Path missing = dir.resolve("missing.pem");
        refusesAnUnreadableKey(distribution, "delivery-package-bad-key", missing);
        refusesAnUnreadableKey(List.of(image.toString()), "delivery-image-bad-key", missing);
    }

    private static void servesTheSecuredResource(List<String> command, String name, Path key, PrivateKey signer,
            String configProbe) throws Exception {
        Path log = Fixture.logs().resolve(name + "-run.log");
        Process run = launch(command, log, key);
        try {
            Fixture.awaitLog(run, log, STARTED);
            String url = Fixture.served(log) + "/api/secured/admin";
            HttpClient client = HttpClient.newHttpClient();

            assertEquals(401, call(client, url, null).statusCode(), name + ": no token");
            assertEquals(403, call(client, url, token(signer, "eve", "user")).statusCode(), name + ": no role");
            HttpResponse<String> allowed = call(client, url, token(signer, "bob", "admin"));
            assertEquals(200, allowed.statusCode(), () -> name + ": " + allowed.body());
            assertEquals("admin-ok user=bob", allowed.body(), name);
            assertEquals("yes", allowed.headers().firstValue(STAMP).orElse(null), name + ": the interceptor ran");

            String out = Fixture.read(log);
            assertTrue(out.contains("frozen at build"), name + ": the extensions are frozen:\n" + out);
            assertFalse(out.contains("run at start") || out.contains("ran at start"),
                    name + ": no extension ran at start:\n" + out);
            assertFalse(out.contains("RUNTIME CODE GENERATION FALLBACK"), name + ": no fallback:\n" + out);
            assertTrue(out.contains(configProbe), name + ": the configuration from the common pool:\n" + out);
            assertTrue(java.util.regex.Pattern.compile("anomalies +none").matcher(out).find(), name + ":\n" + out);
        } finally {
            Fixture.stop(run);
        }
    }

    /** Cervantes' start check reads the key: a key it cannot read stops the start, and the launch fails. */
    private static void refusesAnUnreadableKey(List<String> command, String name, Path missing) throws Exception {
        Path log = Fixture.logs().resolve(name + "-run.log");
        Process run = launch(command, log, missing);
        try {
            if (!run.waitFor(Fixture.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                fail(name + ": the start did not stop:\n" + Fixture.read(log));
            }
            String out = Fixture.read(log);
            assertNotEquals(0, run.exitValue(), () -> name + ": the launch must fail:\n" + out);
            assertTrue(out.contains("Vidocq startup failed"), name + ":\n" + out);
            assertTrue(out.contains("DeploymentException: invalid MicroProfile JWT configuration (mp.jwt.verify.*")
                    && out.contains("unreadable: " + missing), name + ": the message names the key:\n" + out);
            assertFalse(out.contains(STARTED), name + ":\n" + out);
        } finally {
            Fixture.stop(run);
        }
    }

    private static Process launch(List<String> command, Path log, Path key) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        Map<String, String> environment = builder.environment();
        environment.put(KEY_LOCATION, key.toUri().toString());
        environment.remove("JAVA_OPTS");
        environment.remove("JDK_JAVA_OPTIONS");
        return builder.start();
    }

    private static HttpResponse<String> call(HttpClient client, String url, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A compact RS256 token with the claims MicroProfile JWT requires. */
    private static String token(PrivateKey key, String user, String group) throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
        String claims = "{\"iss\":\"" + ISSUER + "\",\"sub\":\"" + user + "\",\"upn\":\"" + user + "\",\"jti\":\""
                + user + "-" + now + "\",\"groups\":[\"" + group + "\"],\"iat\":" + now + ",\"exp\":" + (now + 300)
                + "}";
        String signed = B64URL.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + '.'
                + B64URL.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key);
        signature.update(signed.getBytes(StandardCharsets.US_ASCII));
        return signed + '.' + B64URL.encodeToString(signature.sign());
    }

    /** The launch script of the distribution, the one {@code .sh} under a {@code bin} outside the image. */
    private static Path distributionScript(Path target) throws Exception {
        try (Stream<Path> files = Files.walk(target, 3)) {
            List<Path> scripts = files.filter(file -> file.getFileName().toString().endsWith(".sh")
                    && file.getParent().getFileName().toString().equals("bin")
                    && !file.startsWith(target.resolve("dist"))).toList();
            assertEquals(1, scripts.size(), () -> "one launch script expected under " + target + ": " + scripts);
            return scripts.getFirst();
        }
    }
}
