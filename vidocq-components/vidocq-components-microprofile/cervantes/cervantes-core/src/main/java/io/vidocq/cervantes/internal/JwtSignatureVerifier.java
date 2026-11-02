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
package io.vidocq.cervantes.internal;

import io.vidocq.cervantes.api.JwtValidationException;
import io.vidocq.cervantes.api.SignatureAlgorithm;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Checks the signature of a JWT via the JCA ({@code java.security.Signature}) — zero third-party cryptography dependency.
 *
 * <p>RSA ({@code RS256/384/512}): direct verification. EC ({@code ES256/384/512}): the signature
 * {@code R‖S} is transcoded to DER ({@link EcdsaSignatures}) before {@code verify}.
 * The family of the provided key must match that of the declared algorithm.</p>
 */
final class JwtSignatureVerifier {

    /** How many idle {@link Signature} objects are kept per algorithm: more than that is only the peak of a burst. */
    private static final int IDLE_PER_ALGORITHM = 32;

    /** Asks the JCA for a new {@link Signature}. */
    @FunctionalInterface
    interface SignatureFactory {
        Signature create(String jcaName) throws GeneralSecurityException;
    }

    private final SignatureFactory factory;
    /**
     * Idle signatures by JCA name. {@code Signature.getInstance} looks the provider up and builds the engine
     * reflectively on every call; {@code initVerify} resets a signature completely, so one is reused by the next token.
     */
    private final ConcurrentHashMap<String, BlockingQueue<Signature>> idle = new ConcurrentHashMap<>();

    JwtSignatureVerifier() {
        this(Signature::getInstance);
    }

    JwtSignatureVerifier(SignatureFactory factory) {
        this.factory = factory;
    }

    boolean verify(ParsedJwt jwt, PublicKey key, SignatureAlgorithm alg) throws JwtValidationException {
        requireKeyFamily(key, alg);
        try {
            BlockingQueue<Signature> pool = idle.computeIfAbsent(alg.jcaName(), _ -> new ArrayBlockingQueue<>(IDLE_PER_ALGORITHM));
            Signature signature = pool.poll();
            if (signature == null) {
                signature = factory.create(alg.jcaName());
            }
            signature.initVerify(key);
            signature.update(jwt.signingInput());
            byte[] sigBytes = jwt.signature();
            if (alg.family() == SignatureAlgorithm.Family.EC) {
                sigBytes = EcdsaSignatures.joseToDer(sigBytes, alg.ecCoordinateOctets());
            }
            boolean valid = signature.verify(sigBytes);
            pool.offer(signature); // only after a clean run: a signature that threw is dropped
            return valid;
        } catch (IllegalArgumentException _) {
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
