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

import jakarta.json.JsonObject;

import java.util.Arrays;
import java.util.Objects;

/**
 * Result of the decoding of a compact JWT, before signature verification.
 *
 * <p>The byte arrays take part in {@link #equals(Object)} and {@link #hashCode()} by content.
 * {@link #toString()} prints their lengths and never the raw token, a bearer credential that must
 * not end up in a log.
 *
 * @param header header decoded JOSE ({@code alg}, {@code kid}, {@code typ},...)
 * @param claims payload decoded (claims)
 * @param signingInput bytes {@code base64url(header).base64url(payload)} covered by the signature
 * @param signature decoded signature (base64url), raw form (JOSE {@code R‖S} for ECDSA)
 * @param rawToken     the original compact JWT
 */
record ParsedJwt(JsonObject header, JsonObject claims, byte[] signingInput, byte[] signature, String rawToken) {

    @Override
    public boolean equals(Object other) {
        return other instanceof ParsedJwt that
                && header.equals(that.header)
                && claims.equals(that.claims)
                && Arrays.equals(signingInput, that.signingInput)
                && Arrays.equals(signature, that.signature)
                && Objects.equals(rawToken, that.rawToken);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(header, claims, rawToken);
        result = 31 * result + Arrays.hashCode(signingInput);
        return 31 * result + Arrays.hashCode(signature);
    }

    @Override
    public String toString() {
        return "ParsedJwt[header=" + header + ", claims=" + claims
                + ", signingInput=" + signingInput.length + " bytes, signature=" + signature.length + " bytes]";
    }
}
