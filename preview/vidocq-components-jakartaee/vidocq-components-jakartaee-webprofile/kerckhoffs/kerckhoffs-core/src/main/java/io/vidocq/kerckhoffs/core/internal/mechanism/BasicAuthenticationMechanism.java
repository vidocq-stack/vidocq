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
package io.vidocq.kerckhoffs.core.internal.mechanism;

import jakarta.security.enterprise.AuthenticationStatus;
import jakarta.security.enterprise.authentication.mechanism.http.HttpAuthenticationMechanism;
import jakarta.security.enterprise.authentication.mechanism.http.HttpMessageContext;
import jakarta.security.enterprise.credential.Password;
import jakarta.security.enterprise.credential.UsernamePasswordCredential;
import jakarta.security.enterprise.identitystore.CredentialValidationResult;
import jakarta.security.enterprise.identitystore.IdentityStoreHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The built-in BASIC mechanism of {@code @BasicAuthenticationMechanismDefinition} (Jakarta Security 4.0, section
 * 3.8.1.1, RFC 7617). It fails closed: whatever it cannot read as a valid {@code Authorization} header proves nobody,
 * and credentials that fail validation are challenged, never ignored.
 */
public final class BasicAuthenticationMechanism implements HttpAuthenticationMechanism {

    private static final String SCHEME = "basic";

    private final String challenge;
    private final Supplier<IdentityStoreHandler> handler;

    public BasicAuthenticationMechanism(String realmName, Supplier<IdentityStoreHandler> handler) {
        this.challenge = "Basic realm=\"" + quote(Objects.requireNonNull(realmName, "realmName")) + "\"";
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    @Override
    public AuthenticationStatus validateRequest(HttpServletRequest request, HttpServletResponse response,
                                                HttpMessageContext context) {
        String[] parsed = credentials(request.getHeader("Authorization"));
        if (parsed == null) {
            return context.isProtected() ? challenge(response, context) : context.doNothing();
        }
        var credential = new UsernamePasswordCredential(parsed[0], new Password(parsed[1].toCharArray()));
        try {
            CredentialValidationResult result = handler.get().validate(credential);
            if (result.getStatus() != CredentialValidationResult.Status.VALID) {
                return challenge(response, context);
            }
            return context.notifyContainerAboutLogin(result);
        } finally {
            credential.clear();
        }
    }

    private AuthenticationStatus challenge(HttpServletResponse response, HttpMessageContext context) {
        response.setHeader("WWW-Authenticate", challenge);
        return context.responseUnauthorized();
    }

    /** The user name and the password of a {@code Basic} header, or {@code null} when it is not a valid one. */
    private static String[] credentials(String header) {
        if (header == null || header.length() <= SCHEME.length() + 1
                || !header.regionMatches(true, 0, SCHEME, 0, SCHEME.length())
                || header.charAt(SCHEME.length()) != ' ') {
            return null;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(header.substring(SCHEME.length() + 1).trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        try {
            String pair = new String(decoded, StandardCharsets.UTF_8);
            int colon = pair.indexOf(':');
            return colon <= 0 ? null : new String[] {pair.substring(0, colon), pair.substring(colon + 1)};
        } finally {
            Arrays.fill(decoded, (byte) 0);
        }
    }

    /** A realm name is a quoted string of the challenge: no control character, and quotes escaped. */
    private static String quote(String realm) {
        var clean = new StringBuilder(realm.length());
        for (char c : realm.toCharArray()) {
            if (c < 0x20 || c == 0x7f) continue;
            if (c == '"' || c == '\\') clean.append('\\');
            clean.append(c);
        }
        return clean.toString();
    }
}
