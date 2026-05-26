package io.vidocq.cervantes.internal;

import io.vidocq.cervantes.api.JwtValidationException;
import io.vidocq.cervantes.api.KeyResolver;
import io.vidocq.cervantes.api.SignatureAlgorithm;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fabrique publique de {@link KeyResolver} à partir de la configuration MicroProfile JWT
 * ({@code mp.jwt.verify.publickey} / {@code .location}). Façade stable pour les intégrations
 * (CDI, JAX-RS) : garde {@code JwksSource}/{@code JwkParser} internes au package core.
 */
public final class KeyResolvers {

    private KeyResolvers() {}

    /**
     * Clé publique inline depuis {@code mp.jwt.verify.publickey}.
     * Accepte : PEM (PKIX), JWK JSON ({…}), JWK Set JSON ({"keys":[…]}), ou base64-encoded JWK/JWKS.
     * MP JWT spec §9.2.1 : la valeur peut être une clé PEM, base64 X.509, JWK ou JWKS.
     */
    public static KeyResolver fromInlinePem(String value, SignatureAlgorithm.Family family) throws JwtValidationException {
        String trimmed = value.trim();
        if (trimmed.startsWith("{")) {
            // JWK or JWKS JSON inline — route to JwksKeyResolver with a static byte supplier
            byte[] bytes = trimmed.getBytes(StandardCharsets.UTF_8);
            return new JwksKeyResolver(() -> bytes);
        }
        // Try base64 decode: if the decoded content is JSON (starts with '{'), treat as JWK/JWKS
        if (!trimmed.startsWith("-----")) {
            try {
                byte[] decoded = Base64.getDecoder().decode(trimmed.replaceAll("\\s", ""));
                String decodedStr = new String(decoded, StandardCharsets.UTF_8).trim();
                if (decodedStr.startsWith("{")) {
                    byte[] bytes = decodedStr.getBytes(StandardCharsets.UTF_8);
                    return new JwksKeyResolver(() -> bytes);
                }
            } catch (IllegalArgumentException ignored) {
                // Not valid base64 — fall through to PEM parsing
            }
        }
        return new ConfiguredKeyResolver(PemKeys.fromPem(trimmed, family));
    }

    /**
     * Depuis {@code mp.jwt.verify.publickey.location} : URL HTTP(S) → JWKS distant ou PEM (lazy,
     * détection de format au premier accès) ; fichier JSON ({}) → JWKS local ; sinon PEM.
     *
     * <p>Pour les URL HTTP(S), le chargement est <em>lazy</em> afin que le producteur CDI
     * puisse être construit avant que le serveur exposant la clé soit prêt. Le format (JWKS JSON
     * ou PEM) est détecté à la première résolution de clé. L'URL est relue depuis le supplier
     * au moment de la détection afin de capturer les rewrites effectuées par le harness TCK après
     * démarrage du serveur ({@code mp.jwt.verify.publickey.location} peut être mis à jour dans les
     * system properties entre la construction du résolveur et la première requête).</p>
     */
    public static KeyResolver fromLocation(String location, SignatureAlgorithm.Family family) throws JwtValidationException {
        if (location.startsWith("http://") || location.startsWith("https://")) {
            // Lazy auto-detect resolver: URL is re-read from system properties at detection time
            // to capture port rewrites applied after server startup (TCK harness pattern).
            return new LazyHttpKeyResolver(location, family);
        }
        byte[] bytes = JwksSource.fromLocation(location).fetch();
        String content = new String(bytes, StandardCharsets.UTF_8).trim();
        if (content.startsWith("{")) {
            return new JwksKeyResolver(() -> bytes);
        }
        return new ConfiguredKeyResolver(PemKeys.fromPem(content, family));
    }

    /**
     * Résolveur lazy pour les URL HTTP(S) : auto-détecte JWKS JSON ou PEM au premier accès.
     * Mémorise le résolveur délégué après détection pour éviter de re-fetcher.
     *
     * <p>La location est relue via la system property à chaque nouvelle détection (i.e. quand le
     * delegate est null) afin de capturer les rewrites effectués par le harness après démarrage.</p>
     */
    private static final class LazyHttpKeyResolver implements KeyResolver {

        /** The config key that holds the actual current URL (may change after rewrite). */
        private final String initialLocation;
        private final SignatureAlgorithm.Family family;
        /** Initialized on first resolve(); reset on URL change. */
        private final AtomicReference<KeyResolver> delegate = new AtomicReference<>();

        LazyHttpKeyResolver(String initialLocation, SignatureAlgorithm.Family family) {
            this.initialLocation = initialLocation;
            this.family = family;
        }

        @Override
        public Optional<PublicKey> resolve(String kid, SignatureAlgorithm algorithm) throws JwtValidationException {
            KeyResolver r = delegate.get();
            if (r == null) {
                r = detect();
                delegate.compareAndSet(null, r);
                r = delegate.get();
            }
            return r.resolve(kid, algorithm);
        }

        private KeyResolver detect() throws JwtValidationException {
            // Re-read location from system property at detection time to capture post-startup rewrites.
            String location = System.getProperty("mp.jwt.verify.publickey.location", initialLocation);
            JwksSource source = JwksSource.fromLocation(location);
            byte[] bytes = source.fetch();
            String content = new String(bytes, StandardCharsets.UTF_8).trim();
            if (content.startsWith("{")) {
                // JWKS JSON — re-use fresh source for each delegation (supports key rotation)
                return new JwksKeyResolver(JwksSource.fromLocation(location));
            }
            // PEM served over HTTP
            return new ConfiguredKeyResolver(PemKeys.fromPem(content, family));
        }
    }
}
