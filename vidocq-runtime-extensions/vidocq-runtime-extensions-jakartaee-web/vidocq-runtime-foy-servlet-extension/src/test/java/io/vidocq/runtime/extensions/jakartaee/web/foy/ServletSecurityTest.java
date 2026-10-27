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
package io.vidocq.runtime.extensions.jakartaee.web.foy;

import io.vidocq.foy.chappe.FoyChappeBoot;
import io.vidocq.foy.spi.security.IdentityStore;
import io.vidocq.runtime.spi.config.VidocqConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ServletSecurityTest {

    @Test
    void nothingConfiguredKeepsFoysDefaults() {
        ServletSecurity security = ServletSecurity.from(FoyExtensionTest.config(Map.of()));

        assertTrue(security.users().isEmpty());
        assertTrue(security.confidentialPort().isEmpty());
        assertTrue(security.rotateSessionIdOnLogin().isEmpty());
    }

    @Test
    void readsUsersWithTheirRoles() {
        ServletSecurity security = ServletSecurity.from(FoyExtensionTest.config(Map.of(
                "vidocq.servlet.users.alice.password", "s3cret,with,commas",
                "vidocq.servlet.users.alice.roles", "admin, user",
                "vidocq.servlet.users.bob.password", "pw")));

        assertEquals(List.of("alice", "bob"), security.users().stream().map(ServletSecurity.User::name).toList());
        ServletSecurity.User alice = security.users().getFirst();
        assertEquals("s3cret,with,commas", new String(alice.password()));
        assertEquals(List.of("admin", "user"), alice.roles());
        assertEquals(List.of(), security.users().get(1).roles());
    }

    @Test
    void aUserWithoutPasswordIsRefused() {
        VidocqConfig config = FoyExtensionTest.config(Map.of("vidocq.servlet.users.carol.roles", "admin"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ServletSecurity.from(config));

        assertTrue(e.getMessage().contains("vidocq.servlet.users.carol.password"), e.getMessage());
    }

    @Test
    void anUnknownUserPropertyIsRefused() {
        VidocqConfig config = FoyExtensionTest.config(Map.of(
                "vidocq.servlet.users.dave.password", "pw", "vidocq.servlet.users.dave.role", "admin"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ServletSecurity.from(config));

        assertTrue(e.getMessage().contains("vidocq.servlet.users.dave.role"), e.getMessage());
    }

    @Test
    void readsTheConfidentialPortAndTheSessionIdRotation() {
        ServletSecurity security = ServletSecurity.from(FoyExtensionTest.config(Map.of(
                "vidocq.servlet.confidential-port", "8443",
                "vidocq.servlet.rotate-session-id-on-login", "false")));

        assertEquals(8443, security.confidentialPort().getAsInt());
        assertEquals(Optional.of(false), security.rotateSessionIdOnLogin());
    }

    @Test
    void aBadConfidentialPortNamesItsKey() {
        VidocqConfig config = FoyExtensionTest.config(Map.of("vidocq.servlet.confidential-port", "https"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ServletSecurity.from(config));

        assertTrue(e.getMessage().contains("vidocq.servlet.confidential-port"), e.getMessage());
    }

    @Test
    void aBadRotationFlagNamesItsKey() {
        VidocqConfig config = FoyExtensionTest.config(Map.of("vidocq.servlet.rotate-session-id-on-login", "maybe"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ServletSecurity.from(config));

        assertTrue(e.getMessage().contains("vidocq.servlet.rotate-session-id-on-login"), e.getMessage());
    }

    @Test
    void anIdentityStoreBeanAndConfiguredUsersAreExclusive() {
        ServletSecurity security = ServletSecurity.from(FoyExtensionTest.config(Map.of(
                "vidocq.servlet.users.alice.password", "pw")));

        FoyChappeBoot.Builder builder = FoyChappeBoot.builder();
        Optional<IdentityStore> bean = Optional.of(IdentityStore.none());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> security.applyTo(builder, bean));

        assertTrue(e.getMessage().contains("IdentityStore bean"), e.getMessage());
        assertTrue(e.getMessage().contains("vidocq.servlet.users"), e.getMessage());
    }

    @Test
    void anOutOfRangeConfidentialPortNamesItsKey() {
        ServletSecurity security = ServletSecurity.from(FoyExtensionTest.config(Map.of(
                "vidocq.servlet.confidential-port", "70000")));

        FoyChappeBoot.Builder builder = FoyChappeBoot.builder();
        Optional<IdentityStore> noBean = Optional.empty();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> security.applyTo(builder, noBean));

        assertTrue(e.getMessage().contains("vidocq.servlet.confidential-port"), e.getMessage());
    }
}
