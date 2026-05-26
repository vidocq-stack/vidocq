package io.vidocq.cervantes.internal;

import io.vidocq.cervantes.api.JwtValidationException;
import io.vidocq.cervantes.api.SignatureAlgorithm;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;

/**
 * Vérifie la signature d'un JWT via la JCA ({@code java.security.Signature}) — zéro dépendance crypto tierce.
 *
 * <p>RSA ({@code RS256/384/512}) : vérification directe. EC ({@code ES256/384/512}) : la signature
 * JOSE brute {@code R‖S} est transcodée en DER ({@link EcdsaSignatures}) avant {@code verify}.
 * La famille de la clé fournie doit correspondre à celle de l'algorithme déclaré.</p>
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
            // signature EC mal dimensionnée (transcodage impossible) → signature invalide, pas une erreur serveur
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
