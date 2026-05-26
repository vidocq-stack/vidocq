package io.vidocq.cervantes.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Configuration de vérification d'un JWT (sous-ensemble des propriétés MicroProfile Config
 * {@code mp.jwt.verify.*} pertinentes pour la validation des claims).
 *
 * <ul>
 *   <li>{@code issuer} — {@code mp.jwt.verify.issuer} : émetteur attendu ({@code iss}). Si présent,
 *       le claim {@code iss} du token doit l'égaler exactement.</li>
 *   <li>{@code audiences} — {@code mp.jwt.verify.audiences} : audiences acceptées. Si non vide,
 *       l'intersection avec le claim {@code aud} du token doit être non vide.</li>
 *   <li>{@code clockSkew} — tolérance d'horloge appliquée à {@code exp} et {@code nbf}.</li>
 *   <li>{@code requireExpiration} — si vrai, l'absence de {@code exp} est un échec (défaut spec).</li>
 * </ul>
 *
 * <p>Record immuable ; {@code audiences} est défensivement copié. La construction depuis
 * MicroProfile Config (Ravel) est branchée au jalon M3.</p>
 */
public record JwtConfig(
        Optional<String> issuer,
        Set<String> audiences,
        Duration clockSkew,
        boolean requireExpiration) {

    /** Tolérance d'horloge par défaut (60 s), conforme à l'usage courant des implémentations MP JWT. */
    public static final Duration DEFAULT_CLOCK_SKEW = Duration.ofSeconds(60);

    public JwtConfig {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(clockSkew, "clockSkew");
        if (clockSkew.isNegative()) throw new IllegalArgumentException("clockSkew must be >= 0");
        audiences = audiences == null ? Set.of() : Set.copyOf(audiences);
    }

    /** Configuration n'exigeant qu'un émetteur (audiences libres, skew par défaut, exp requise). */
    public static JwtConfig forIssuer(String issuer) {
        return new JwtConfig(Optional.ofNullable(issuer), Set.of(), DEFAULT_CLOCK_SKEW, true);
    }

    /** Configuration émetteur + audiences (skew par défaut, exp requise). */
    public static JwtConfig of(String issuer, Set<String> audiences) {
        return new JwtConfig(Optional.ofNullable(issuer), audiences, DEFAULT_CLOCK_SKEW, true);
    }
}
