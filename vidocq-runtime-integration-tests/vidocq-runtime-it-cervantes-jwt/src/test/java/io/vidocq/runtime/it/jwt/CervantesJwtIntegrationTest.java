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
package io.vidocq.runtime.it.jwt;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E tests that validate the full Cervantes integration (MicroProfile JWT 2.2) in Vidocq:
 * <ol>
 *   <li><b>JAX-RS Authentication + Authorization</b> — {@code @RolesAllowed("admin")} via
 *       {@code RolesAllowedDynamicFeature} + {@code JwtAuthenticationFilter} (cervantes-jaxrs,
 *       @Provider beans discovered by Cassini's VaubanBeanProvider).</li>
 *   <li><b>CDI Injection {@code @Inject JsonWebToken}</b> — {@code @RequestScoped} producer
 *       from cervantes-cdi-vauban, republished by the BCE exposed by
 *       {@code vidocq-runtime-cervantes-jwt-extension}.</li>
 *   <li><b>Real crypto validation</b> — RS256, public key configured via
 *       {@code mp.jwt.verify.publickey} + {@code mp.jwt.verify.issuer} (system properties,
 *       read by Ravel/MP Config in the same JVM).</li>
 * </ol>
 *
 * <p>Stack started by Arquillian: Chappe (HTTP) → Cassini (JAX-RS) → Vauban (CDI) →
 * cervantes-jwt-extension (BCE @Claim/@RequestScoped JsonWebToken) + cervantes-jaxrs (security).</p>
 *
 * <p>Key assertions:
 * <ul>
 *   <li>no token → {@code 401} on {@code /secured/admin};</li>
 *   <li>token valid without the group {@code admin} → {@code 403};</li>
 *   <li>valid token with group {@code admin} → {@code 200} and the resource sees the
 *       principal/claims of the JWT;</li>
 *   <li>{@code @PermitAll} accessible without token (anonymous principal).</li>
 * </ul></p>
 */
@ExtendWith(ArquillianExtension.class)
@RunAsClient
class CervantesJwtIntegrationTest {

    static final String ISSUER = "https://issuer.vidocq.dev";

    // RSA key pair generated once for the whole suite. The public key is placed in a system
    // property BEFORE booting the container (the static @Deployment + this block execute in
    // the embedded JVM that starts Vidocq). The Cervantes auth filter reads
    // mp.jwt.verify.publickey via ConfigProvider.getConfig() (Ravel SystemPropertiesConfigSource).
    static final KeyPair KEY_PAIR;
    static final PrivateKey PRIVATE_KEY;

    static {
        try {
            KEY_PAIR = TestJwt.rsaKeyPair();
            PRIVATE_KEY = KEY_PAIR.getPrivate();
            System.setProperty("mp.jwt.verify.publickey", TestJwt.publicKeyBase64(KEY_PAIR.getPublic()));
            System.setProperty("mp.jwt.verify.issuer", ISSUER);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @ArquillianResource
    private URL baseUrl;

    @Deployment
    public static JavaArchive createDeployment() {
        return ShrinkWrap.create(JavaArchive.class, "cervantes-jwt-it.jar")
                .addClass(SecuredResource.class)
                // Cervantes classes to include so Cassini/Vauban can scan them in
                // the isolated Arquillian deployment (they are on the classpath via Maven deps,
                // but Vidocq Arquillian scoping requires their explicit presence —
                // same pattern as vidocq-runtime-it-humboldt-cassini).
                .addClass(io.vidocq.cervantes.jaxrs.JwtAuthenticationFilter.class)
                .addClass(io.vidocq.cervantes.jaxrs.RolesAllowedDynamicFeature.class)
                .addClass(io.vidocq.cervantes.cdi.JsonWebTokenContext.class)
                .addClass(io.vidocq.cervantes.cdi.internal.JsonWebTokenProducer.class)
                .addClass(io.vidocq.cervantes.cdi.internal.JwtAuthConfigProducer.class);
    }

    // ============================================================
    //  Test 1 — no token -> 401 on a @RolesAllowed endpoint
    // ============================================================
    @Test
    void noToken_onRolesAllowed_returns401() throws IOException {
        var resp = httpGet("/secured/admin", null);
        assertEquals(401, resp.statusCode,
                "A @RolesAllowed request without a token should be rejected with 401: " + resp.body);
    }

    // ============================================================
    //  Test 2 — valid token without the role → 403
    // ============================================================
    @Test
    void validToken_withoutRole_returns403() throws IOException, Exception {
        String token = TestJwt.signRs256(PRIVATE_KEY, ISSUER, "alice", List.of("user"));
        var resp = httpGet("/secured/admin", token);
        assertEquals(403, resp.statusCode,
                "A valid token without the 'admin' group should be rejected with 403: " + resp.body);
    }

    // ============================================================
    //  Test 3 — valid token with admin role → 200 + visible claims
    // ============================================================
    @Test
    void validToken_withAdminRole_returns200_andSeesClaims() throws Exception {
        String token = TestJwt.signRs256(PRIVATE_KEY, ISSUER, "bob", List.of("admin", "user"));
        var resp = httpGet("/secured/admin", token);
        assertEquals(200, resp.statusCode,
                "A valid token with the 'admin' group should be accepted with 200: " + resp.body);
        assertTrue(resp.body.startsWith("admin-ok"), "Unexpected response: " + resp.body);
        assertTrue(resp.body.contains("name=bob"),
                "The resource should see the JWT principal (upn=bob): " + resp.body);
        assertTrue(resp.body.contains("admin"),
                "The resource should see the 'admin' group in the JWT: " + resp.body);
    }

    // ============================================================
    //  Test 4 — @PermitAll accessible without token (anonymous principal)
    // ============================================================
    @Test
    void permitAll_withoutToken_returns200() throws IOException {
        var resp = httpGet("/secured/public", null);
        assertEquals(200, resp.statusCode, "@PermitAll should be accessible without a token: " + resp.body);
        assertTrue(resp.body.startsWith("public-ok"), "Unexpected response: " + resp.body);
    }

    // ============================================================
    //  HTTP helpers
    // ============================================================
    private SimpleResponse httpGet(String path, String bearerOrNull) throws IOException {
        URL url = new URL(baseUrl, path.startsWith("/") ? path.substring(1) : path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        if (bearerOrNull != null) {
            conn.setRequestProperty("Authorization", "Bearer " + bearerOrNull);
        }
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        int statusCode = conn.getResponseCode();
        String body = "";
        InputStream is = statusCode < 400 ? conn.getInputStream() : conn.getErrorStream();
        if (is != null) {
            try (is) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        conn.disconnect();
        return new SimpleResponse(statusCode, body);
    }

    private record SimpleResponse(int statusCode, String body) {}
}
