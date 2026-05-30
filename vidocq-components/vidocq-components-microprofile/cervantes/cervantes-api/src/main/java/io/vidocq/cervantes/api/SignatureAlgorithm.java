package io.vidocq.cervantes.api;

import java.util.Optional;

/**
 * JWS signature algorithms supported by JWT 2.1 MicroProfile.
 *
 * <p>Each value combines the name JOSE ({@code "RS256"},...) with its JCA algorithm name
 * ({@code java.security.Signature}) and the expected key family. For EC algorithms,
 * {@code ecCoordinateOctets} is the size (in bytes) of each coordinate {@code R}/{@code S}
 * of the signature JOSE concatenee (P-256 → 32, P-384 → 48, P-521 → 66), necessary for transcoding
 * JOSE {@code R‖S} 
 */
public enum SignatureAlgorithm {
    RS256("SHA256withRSA", Family.RSA, 0),
    RS384("SHA384withRSA", Family.RSA, 0),
    RS512("SHA512withRSA", Family.RSA, 0),
    ES256("SHA256withECDSA", Family.EC, 32),
    ES384("SHA384withECDSA", Family.EC, 48),
    ES512("SHA512withECDSA", Family.EC, 66);

    /** Expected public key family. */
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

    /** Public key family ({@code RSA} or {@code EC}). */
    public Family family() { return family; }

    /** Taille en octets d'une coordonnée {@code R}/{@code S} (algorithmes EC), 0 sinon. */
    public int ecCoordinateOctets() { return ecCoordinateOctets; }

    /**
     * Resolves the algorithm from its JOSE name (JWT header {@code alg}).
     *
     * @return the algorithm, or empty if the name is unknown/unsupported (e.g. {@code "none"}, {@code "HS256"}).
     */
    public static Optional<SignatureAlgorithm> fromJoseName(String alg) {
        if (alg == null) return Optional.empty();
        for (SignatureAlgorithm a : values()) {
            if (a.name().equals(alg)) return Optional.of(a);
        }
        return Optional.empty();
    }
}
