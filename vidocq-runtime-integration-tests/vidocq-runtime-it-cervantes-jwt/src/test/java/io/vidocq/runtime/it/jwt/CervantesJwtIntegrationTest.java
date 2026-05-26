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
 * Tests E2E qui valident l'intégration complète Cervantes (MicroProfile JWT 2.1) dans Vidocq :
 * <ol>
 *   <li><b>Authentification + autorisation JAX-RS</b> — {@code @RolesAllowed("admin")} via
 *       {@code RolesAllowedDynamicFeature} + {@code JwtAuthenticationFilter} (cervantes-cassini,
 *       @Provider beans découverts par le VaubanBeanProvider de Cassini).</li>
 *   <li><b>Injection CDI {@code @Inject JsonWebToken}</b> — producteur {@code @RequestScoped}
 *       de cervantes-cdi-vauban, publié par la BCE re-exposée par
 *       {@code vidocq-runtime-cervantes-jwt-extension}.</li>
 *   <li><b>Validation crypto réelle</b> — RS256, clé publique configurée via
 *       {@code mp.jwt.verify.publickey} + {@code mp.jwt.verify.issuer} (system properties,
 *       lues par Ravel/MP-Config dans le même JVM).</li>
 * </ol>
 *
 * <p>Stack démarrée par Arquillian : Chappe (HTTP) → Cassini (JAX-RS) → Vauban (CDI) →
 * cervantes-jwt-extension (BCE @Claim/@RequestScoped JsonWebToken) + cervantes-cassini (sécurité).</p>
 *
 * <p>Assertions clés :
 * <ul>
 *   <li>pas de token → {@code 401} sur {@code /secured/admin} ;</li>
 *   <li>token valide sans le groupe {@code admin} → {@code 403} ;</li>
 *   <li>token valide avec le groupe {@code admin} → {@code 200} et la ressource voit le
 *       principal/claims du JWT ;</li>
 *   <li>{@code @PermitAll} accessible sans token (principal anonyme).</li>
 * </ul></p>
 */
@ExtendWith(ArquillianExtension.class)
@RunAsClient
class CervantesJwtIntegrationTest {

    static final String ISSUER = "https://issuer.vidocq.dev";

    // Paire RSA générée une fois pour toute la suite. La clé publique est posée en system
    // property AVANT le boot du container (l'@Deployment static + ce bloc s'exécutent dans
    // le JVM embarqué qui démarre Vidocq). Le filtre d'auth Cervantes lit
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
                // Classes Cervantes à inclure pour que Cassini/Vauban les scanne dans
                // le Deployment Arquillian isolé (elles sont sur le classpath via les deps
                // Maven, mais le scoping Arquillian Vidocq exige leur présence explicite —
                // même pattern que vidocq-runtime-it-humboldt-cassini).
                .addClass(io.vidocq.cervantes.cassini.JwtAuthenticationFilter.class)
                .addClass(io.vidocq.cervantes.cassini.RolesAllowedDynamicFeature.class)
                .addClass(io.vidocq.cervantes.cdi.JsonWebTokenContext.class)
                .addClass(io.vidocq.cervantes.cdi.internal.JsonWebTokenProducer.class)
                .addClass(io.vidocq.cervantes.cdi.internal.JwtAuthConfigProducer.class);
    }

    // ============================================================
    //  Test 1 — aucun token → 401 sur un endpoint @RolesAllowed
    // ============================================================
    @Test
    void noToken_onRolesAllowed_returns401() throws IOException {
        var resp = httpGet("/secured/admin", null);
        assertEquals(401, resp.statusCode,
                "Une requête @RolesAllowed sans token doit être rejetée 401 : " + resp.body);
    }

    // ============================================================
    //  Test 2 — token valide sans le rôle → 403
    // ============================================================
    @Test
    void validToken_withoutRole_returns403() throws IOException, Exception {
        String token = TestJwt.signRs256(PRIVATE_KEY, ISSUER, "alice", List.of("user"));
        var resp = httpGet("/secured/admin", token);
        assertEquals(403, resp.statusCode,
                "Un token valide sans le groupe 'admin' doit être rejeté 403 : " + resp.body);
    }

    // ============================================================
    //  Test 3 — token valide avec le rôle admin → 200 + claims visibles
    // ============================================================
    @Test
    void validToken_withAdminRole_returns200_andSeesClaims() throws Exception {
        String token = TestJwt.signRs256(PRIVATE_KEY, ISSUER, "bob", List.of("admin", "user"));
        var resp = httpGet("/secured/admin", token);
        assertEquals(200, resp.statusCode,
                "Un token valide avec le groupe 'admin' doit être accepté 200 : " + resp.body);
        assertTrue(resp.body.startsWith("admin-ok"), "Réponse inattendue : " + resp.body);
        assertTrue(resp.body.contains("name=bob"),
                "La ressource doit voir le principal du JWT (upn=bob) : " + resp.body);
        assertTrue(resp.body.contains("admin"),
                "La ressource doit voir le groupe 'admin' dans le JWT : " + resp.body);
    }

    // ============================================================
    //  Test 4 — @PermitAll accessible sans token (principal anonyme)
    // ============================================================
    @Test
    void permitAll_withoutToken_returns200() throws IOException {
        var resp = httpGet("/secured/public", null);
        assertEquals(200, resp.statusCode, "@PermitAll doit être accessible sans token : " + resp.body);
        assertTrue(resp.body.startsWith("public-ok"), "Réponse inattendue : " + resp.body);
    }

    // ============================================================
    //  Helpers HTTP
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
