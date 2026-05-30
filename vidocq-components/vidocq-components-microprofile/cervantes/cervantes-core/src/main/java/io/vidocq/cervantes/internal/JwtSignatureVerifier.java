package io.vidocq.cervantes.internal;

import io.vidocq.cervantes.api.JwtValidationException;
import io.vidocq.cervantes.api.SignatureAlgorithm;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;

/**
 * Checks the signature of a JWT via the JCA ({@code java.security.Signature}) — zero third-party cryptic dependency.
 *
 * <p>RSA ({@code RS256/384/512}): direct verification. EC ({@code ES256/384/512}): the signature
 * {@code R‖S} is transcoded to DER ({@link EcdsaSignatures}) before {@code verify}.
 * The family of the provided key must match that of the declared algorithm.</p>
 */
final class JwtSignatureVerifier {

    boolean verify(ParsedJwt jwt, PublicKey key, SignatureAlgorithm alg) throws JwtValidationException {
        requireKeyFamily(key, alg);
        try {
            Signature signature = Signature.getInstance(alg.jcaName());
            signature.initVerify(key);
            signature.update(jwt.signingInput());
            byte[] sigBytes = jwt.signature();
            if (alg.family() == SignatureAlgorithm.Family.EC) {
                sigBytes = EcdsaSignatures.joseToDer(sigBytes, alg.ecCoordinateOctets());
            }
            return signature.verify(sigBytes);
        } catch (IllegalArgumentException e) {
            //poorly sized EC signature (transcoding impossible) → invalid signature, not server error
            return false;
        } catch (GeneralSecurityException e) {
            throw new JwtValidationException("signature verification error for " + alg.name(), e);
        }
    }

    private void requireKeyFamily(PublicKey key, SignatureAlgorithm alg) throws JwtValidationException {
        boolean ok = switch (alg.family()) {
            case RSA -> key instanceof RSAPublicKey;
            case EC -> key instanceof ECPublicKey;
        };
        if (!ok) {
            throw new JwtValidationException(
                    "key type " + key.getAlgorithm() + " does not match algorithm " + alg.name());
        }
    }
}
