package io.vidocq.cervantes.api;

import java.util.Optional;

/**
 * Algorithmes de signature JWS supportés par MicroProfile JWT 2.1.
 *
 * <p>Chaque valeur associe le nom JOSE ({@code "RS256"}, …) à son nom d'algorithme JCA
 * ({@code java.security.Signature}) et à la famille de clé attendue. Pour les algorithmes EC,
 * {@code ecCoordinateOctets} est la taille (en octets) de chaque coordonnée {@code R}/{@code S}
 * de la signature JOSE concaténée (P-256 → 32, P-384 → 48, P-521 → 66), nécessaire au transcodage
 * JOSE {@code R‖S} ⇄ DER attendu par la JCA.</p>
 */
public enum SignatureAlgorithm {
    RS256("SHA256withRSA", Family.RSA, 0),
    RS384("SHA384withRSA", Family.RSA, 0),
    RS512("SHA512withRSA", Family.RSA, 0),
    ES256("SHA256withECDSA", Family.EC, 32),
    ES384("SHA384withECDSA", Family.EC, 48),
    ES512("SHA512withECDSA", Family.EC, 66);

    /** Famille de clé publique attendue. */
    public enum Family { RSA, EC }

    private final String jcaName;
    private final Family family;
    private final int ecCoordinateOctets;

    SignatureAlgorithm(String jcaName, Family family, int ecCoordinateOctets) {
        this.jcaName = jcaName;
        this.family = family;
        this.ecCoordinateOctets = ecCoordinateOctets;
    }

    /** Nom d'algorithme {@code java.security.Signature} (ex. {@code "SHA256withRSA"}). */
    public String jcaName() { return jcaName; }

    /** Famille de clé publique ({@code RSA} ou {@code EC}). */
    public Family family() { return family; }

    /** Taille en octets d'une coordonnée {@code R}/{@code S} (algorithmes EC), 0 sinon. */
    public int ecCoordinateOctets() { return ecCoordinateOctets; }

    /**
     * Résout l'algorithme depuis son nom JOSE (en-tête {@code alg} du JWT).
     *
     * @return l'algorithme, ou vide si le nom est inconnu/non supporté (ex. {@code "none"}, {@code "HS256"}).
     */
    public static Optional<SignatureAlgorithm> fromJoseName(String alg) {
        if (alg == null) return Optional.empty();
        for (SignatureAlgorithm a : values()) {
            if (a.name().equals(alg)) return Optional.of(a);
        }
        return Optional.empty();
    }
}
