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
package io.vidocq.runtime.it.foyservlet;

import io.vidocq.foy.spi.security.Identity;
import io.vidocq.foy.spi.security.IdentityStore;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The application's user base, a CDI bean: alice holds the {@code user} role, bob only {@code guest}. */
@ApplicationScoped
public class AccountStore implements IdentityStore {

    private static final byte[] PASSWORD = "secret".getBytes(StandardCharsets.UTF_8);
    private static final Map<String, Set<String>> ROLES = Map.of("alice", Set.of("user"), "bob", Set.of("guest"));

    @Override
    public Optional<Identity> validate(String username, char[] password) {
        boolean match = MessageDigest.isEqual(PASSWORD, new String(password).getBytes(StandardCharsets.UTF_8));
        Set<String> roles = ROLES.get(username);
        return match && roles != null ? Optional.of(Identity.of(username, roles)) : Optional.empty();
    }
}
