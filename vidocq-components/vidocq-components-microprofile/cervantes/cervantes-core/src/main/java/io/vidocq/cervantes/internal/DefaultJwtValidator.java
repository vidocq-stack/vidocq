package io.vidocq.cervantes.internal;

import io.vidocq.cervantes.api.JwtConfig;
import io.vidocq.cervantes.api.JwtValidationException;
import io.vidocq.cervantes.api.JwtValidator;
import io.vidocq.cervantes.api.KeyResolver;
import io.vidocq.cervantes.api.SignatureAlgorithm;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.security.PublicKey;
import java.time.Clock;
import java.util.Objects;

/**
 * Implémentation de référence de {@link JwtValidator} : enchaîne parsing → résolution de clé →
 * vérification de signature → validation des claims, puis construit le {@link JsonWebToken}.
 *
 * <p>Immuable et thread-safe (les collaborateurs sont sans état). Une {@link Clock} injectable
 * rend les validations temporelles testables.</p>
 */
public final class DefaultJwtValidator implements JwtValidator {

    private final JwtParser parser = new JwtParser();
    private final JwtSignatureVerifier verifier = new JwtSignatureVerifier();
    private final JwtClaimsValidator claimsValidator = new JwtClaimsValidator();

    private final KeyResolver keyResolver;
    private final JwtConfig config;
    private final Clock clock;
    private final JweDecryptor decryptor; // nullable : déchiffrement JWE (M5)

    public DefaultJwtValidator(KeyResolver keyResolver, JwtConfig config) {
        this(keyResolver, config, Clock.systemUTC(), null);
    }

    public DefaultJwtValidator(KeyResolver keyResolver, JwtConfig config, Clock clock) {
        this(keyResolver, config, clock, null);
    }

    public DefaultJwtValidator(KeyResolver keyResolver, JwtConfig config, Clock clock, JweDecryptor decryptor) {
        this.keyResolver = Objects.requireNonNull(keyResolver, "keyResolver");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.decryptor = decryptor;
    }

    @Override
    public JsonWebToken validate(String token) throws JwtValidationException {
        ParsedJwt jwt = parser.parse(decryptIfEncrypted(token));

        String algName = headerString(jwt, "alg");
        SignatureAlgorithm alg = SignatureAlgorithm.fromJoseName(algName)
                .orElseThrow(() -> new JwtValidationException("unsupported or missing 'alg' header: " + algName));

        String kid = headerString(jwt, "kid");
        PublicKey key = keyResolver.resolve(kid, alg)
                .orElseThrow(() -> new JwtValidationException("no verification key found (kid=" + kid + ")"));

        if (!verifier.verify(jwt, key, alg)) {
            throw new JwtValidationException("invalid signature");
        }

        claimsValidator.validate(jwt.claims(), config, clock);
        return new DefaultJsonWebToken(jwt.claims(), jwt.rawToken());
    }

    /**
     * Si le token est un JWE compact (5 parties), le déchiffre en son JWS imbriqué ; sinon le
     * renvoie tel quel. Un JWE reçu sans clé de déchiffrement configurée est rejeté.
     */
    private String decryptIfEncrypted(String token) throws JwtValidationException {
        if (token == null) {
            return null; // le parser lèvera "empty token"
        }
        long dots = token.chars().filter(c -> c == '.').count();
        if (dots == 4) { // 5 parties → JWE
            if (decryptor == null) {
                throw new JwtValidationException("received an encrypted JWT (JWE) but no decryption key is configured");
            }
            return decryptor.decryptToCompactJws(token);
        }
        return token; // JWS (3 parties) — le parser valide la forme exacte
    }

    private static String headerString(ParsedJwt jwt, String name) {
        JsonValue v = jwt.header().get(name);
        return (v instanceof JsonString s) ? s.getString() : null;
    }
}
