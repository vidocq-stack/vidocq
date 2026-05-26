package io.vidocq.cervantes.cdi.internal;

import io.vidocq.cervantes.api.JwtConfig;
import io.vidocq.cervantes.api.JwtValidationException;
import io.vidocq.cervantes.api.JwtValidator;
import io.vidocq.cervantes.api.KeyResolver;
import io.vidocq.cervantes.api.SignatureAlgorithm;
import io.vidocq.cervantes.internal.DefaultJwtValidator;
import io.vidocq.cervantes.internal.Jwe;
import io.vidocq.cervantes.internal.JweDecryptor;
import io.vidocq.cervantes.internal.KeyResolvers;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Construit le {@link JwtValidator} de l'application à partir des propriétés MicroProfile Config
 * {@code mp.jwt.verify.*} (lues via Ravel) :
 *
 * <ul>
 *   <li>{@code mp.jwt.verify.issuer} → émetteur attendu</li>
 *   <li>{@code mp.jwt.verify.audiences} → audiences (séparées par virgule)</li>
 *   <li>{@code mp.jwt.verify.publickey} → clé publique inline (PEM/base64 X.509)</li>
 *   <li>{@code mp.jwt.verify.publickey.location} → fichier PEM / fichier JWKS / URL JWKS</li>
 *   <li>{@code mp.jwt.verify.publickey.algorithm} → {@code RS256} (défaut) ou {@code ES256}, …</li>
 * </ul>
 *
 * <p>Producteur et produit sont {@code @Dependent} (pattern Ravel {@code RavelConfigProducer} ;
 * un produit normal-scopé déclenche une mauvaise résolution de proxy côté Vauban actuel —
 * à repasser en {@code @ApplicationScoped} quand le défaut Vauban sera corrigé). Le filtre
 * d'authentification JAX-RS (M4) injectera le validateur pour valider chaque bearer token.</p>
 */
@Dependent
public class JwtAuthConfigProducer {

    @Produces
    @Dependent
    public JwtValidator jwtValidator() {
        Config config = ConfigProvider.getConfig();
        try {
            return new DefaultJwtValidator(
                    buildKeyResolver(config),
                    buildConfig(config),
                    java.time.Clock.systemUTC(),
                    buildDecryptor(config));
        } catch (JwtValidationException e) {
            throw new IllegalStateException("invalid MicroProfile JWT configuration (mp.jwt.verify.* / mp.jwt.decrypt.*)", e);
        }
    }

    /**
     * Déchiffrement JWE optionnel : {@code mp.jwt.decrypt.key} (inline) ou {@code .location}.
     * {@code null} si absent. Lit optionnellement {@code mp.jwt.decrypt.key.algorithm} pour
     * valider que l'algorithme dans le JWE correspond à celui configuré.
     */
    static JweDecryptor buildDecryptor(Config config) throws JwtValidationException {
        Optional<String> algorithm = config.getOptionalValue("mp.jwt.decrypt.key.algorithm", String.class);
        String requiredAlgorithm = algorithm.orElse(null);
        Optional<String> inline = config.getOptionalValue("mp.jwt.decrypt.key", String.class);
        if (inline.isPresent()) {
            return Jwe.decryptorFromInlinePem(inline.get(), requiredAlgorithm);
        }
        Optional<String> location = config.getOptionalValue("mp.jwt.decrypt.key.location", String.class);
        if (location.isPresent()) {
            return Jwe.decryptorFromLocation(location.get(), requiredAlgorithm);
        }
        return null;
    }

    static JwtConfig buildConfig(Config config) {
        Optional<String> issuer = config.getOptionalValue("mp.jwt.verify.issuer", String.class);
        Set<String> audiences = config.getOptionalValue("mp.jwt.verify.audiences", String.class)
                .map(JwtAuthConfigProducer::splitCsv)
                .orElseGet(Set::of);
        Optional<Long> tokenAge = config.getOptionalValue("mp.jwt.verify.token.age", Long.class);
        // Encryption required when a decryption key is configured (mp.jwt.decrypt.key or .location)
        boolean encryptionRequired = config.getOptionalValue("mp.jwt.decrypt.key", String.class).isPresent()
                || config.getOptionalValue("mp.jwt.decrypt.key.location", String.class).isPresent();
        return new JwtConfig(issuer, audiences, JwtConfig.DEFAULT_CLOCK_SKEW, true, tokenAge, encryptionRequired);
    }

    static KeyResolver buildKeyResolver(Config config) throws JwtValidationException {
        SignatureAlgorithm.Family family = config.getOptionalValue("mp.jwt.verify.publickey.algorithm", String.class)
                .flatMap(SignatureAlgorithm::fromJoseName)
                .map(SignatureAlgorithm::family)
                .orElse(SignatureAlgorithm.Family.RSA);

        Optional<String> inline = config.getOptionalValue("mp.jwt.verify.publickey", String.class);
        if (inline.isPresent()) {
            return KeyResolvers.fromInlinePem(inline.get(), family);
        }
        Optional<String> location = config.getOptionalValue("mp.jwt.verify.publickey.location", String.class);
        if (location.isPresent()) {
            return KeyResolvers.fromLocation(location.get(), family);
        }
        throw new JwtValidationException(
                "no verification key configured: set mp.jwt.verify.publickey or mp.jwt.verify.publickey.location");
    }

    private static Set<String> splitCsv(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
