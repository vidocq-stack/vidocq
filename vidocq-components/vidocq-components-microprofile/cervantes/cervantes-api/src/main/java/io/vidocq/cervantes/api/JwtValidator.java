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

import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Valide un JWT compact ({@code header.payload.signature}) et en produit un {@link JsonWebToken}.
 *
 * <p>Continuous validation: parsing, key resolution ({@link KeyResolver}), verification of
 * signature, then validation of claims according to a {@link JwtConfig}. Any failure shall result in a
 * {@link JwtValidationException} — a valid token never returns ZZPH1ZZ.ZZPH2ZZ
 */
public interface JwtValidator {

    /**
     * @param token compact JWT (without {@code "Bearer "} prefix)
     * @return the {@link JsonWebToken} principal if the token is valid
     * @throws JwtValidationException if token is poorly formed, not signed correctly, or invalid
     */
    JsonWebToken validate(String token) throws JwtValidationException;
}
