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
import io.vidocq.cervantes.api.KeyResolver;
import io.vidocq.cervantes.api.SignatureAlgorithm;

import java.security.PublicKey;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link KeyResolver} single key (M1): {@code mp.jwt.verify.publickey} / {@code.location}
 * pointing to a single public key. {@code kid} is ignored (resolution by {@code kid} via
 * JWKS reaches M2 The match key family 
 * {@link JwtSignatureVerifier}.
 */
public final class ConfiguredKeyResolver implements KeyResolver {

    private final PublicKey key;

    public ConfiguredKeyResolver(PublicKey key) {
        this.key = Objects.requireNonNull(key, "key");
    }

    @Override
    public Optional<PublicKey> resolve(String kid, SignatureAlgorithm algorithm) throws JwtValidationException {
        return Optional.of(key);
    }
}
