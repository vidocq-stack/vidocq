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
package io.vidocq.cervantes.api;

import java.security.PublicKey;
import java.util.Optional;

/**
 * Resolves the public JWT verification key from the token header.
 *
 * <p>Planned implementations: unique key configured (PEM/inline, M1) then JWKS with resolution
 * by {@code kid} and rotation (M2). The {@code kid} can be {@code null} (header without {@code kid},
 * a single key).</p>
 */
@FunctionalInterface
public interface KeyResolver {

    /**
     * @param kid value of the header {@code kid} (may be {@code null})
     * @param algorithm declared in the {@code alg} header
     * @return the corresponding public key, or empty if none is suitable
     * @throws JwtValidationException if resolution fails irrecoverably (e.g. JWKS unattainable)
     */
    Optional<PublicKey> resolve(String kid, SignatureAlgorithm algorithm) throws JwtValidationException;
}
