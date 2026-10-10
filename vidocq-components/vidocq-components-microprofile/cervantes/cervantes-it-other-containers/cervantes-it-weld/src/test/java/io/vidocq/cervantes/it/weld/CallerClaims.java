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
package io.vidocq.cervantes.it.weld;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import java.util.Set;
import org.eclipse.microprofile.jwt.Claim;
import org.eclipse.microprofile.jwt.ClaimValue;
import org.eclipse.microprofile.jwt.Claims;
import org.eclipse.microprofile.jwt.JsonWebToken;

/** An application bean reading the caller's token, as a resource would (MP JWT 2.1 §7). */
@RequestScoped
public class CallerClaims {

    @Inject
    JsonWebToken jwt;

    @Inject
    @Claim(standard = Claims.upn)
    String upn;

    @Inject
    @Claim(standard = Claims.groups)
    Set<String> groups;

    @Inject
    @Claim("sub")
    ClaimValue<String> subject;

    public String tokenName() {
        return jwt.getName();
    }

    public String upn() {
        return upn;
    }

    public Set<String> groups() {
        return groups;
    }

    public String subject() {
        return subject.getValue();
    }
}
