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
package io.vidocq.runtime.core.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turns the silent-configuration failure mode into a startup warning.
 *
 * <p>Vidocq/chappe#7: a documented {@code vidocq.http.port} was read by no extension, so an
 * application setting it stayed on the default port and nothing said so. Any {@code vidocq.*} key
 * nobody consumes has that same shape, which is what this audit generalises.
 */
class ConfigKeyAuditTest {

    @Test
    void reportsAKeyNobodyConsumesUnderAClaimedNamespace() {
        List<String> unread = ConfigKeyAudit.unconsumedKeys(
                List.of("vidocq.chappe.listner.default.port"),      // typo: listner
                Set.of("vidocq.chappe.listeners", "vidocq.chappe.listener.*"));

        assertEquals(List.of("vidocq.chappe.listner.default.port"), unread);
    }

    @Test
    void staysSilentOnAConsumedKey() {
        assertTrue(ConfigKeyAudit.unconsumedKeys(
                List.of("vidocq.chappe.listener.admin.port", "vidocq.chappe.listeners"),
                Set.of("vidocq.chappe.listeners", "vidocq.chappe.listener.*")).isEmpty());
    }

    @Test
    void staysSilentOnANamespaceNoExtensionClaims() {
        // Auditing is opt-in: an extension that declares nothing must never cause a false warning.
        assertTrue(ConfigKeyAudit.unconsumedKeys(
                List.of("vidocq.mansart.datasource.url"),
                Set.of("vidocq.chappe.listeners")).isEmpty());
    }

    @Test
    void ignoresKeysOutsideTheVidocqNamespace() {
        assertTrue(ConfigKeyAudit.unconsumedKeys(
                List.of("app.name", "java.version", "quarkus.http.port"),
                Set.of("vidocq.chappe.listeners")).isEmpty());
    }

    @Test
    void ignoresBuildTimeKeysThatLeakIntoSystemProperties() {
        // vidocq:package / vidocq:dev / the CLI publish build-time keys under vidocq.*; they are
        // not runtime configuration and must not be reported when an app runs through them.
        assertTrue(ConfigKeyAudit.unconsumedKeys(
                List.of("vidocq.mainClass", "vidocq.mainModule", "vidocq.jvmArgs",
                        "vidocq.scriptName", "vidocq.dev.debug", "vidocq.distName"),
                Set.of("vidocq.chappe.listeners")).isEmpty());
    }

    @Test
    void aliasDeclaredByAnExtensionIsConsumed() {
        // The fix for chappe#7: the Chappe extension now claims the vidocq.http.* aliases.
        assertTrue(ConfigKeyAudit.unconsumedKeys(
                List.of("vidocq.http.port", "vidocq.http.host"),
                Set.of("vidocq.http.host", "vidocq.http.port", "vidocq.http.mount.*")).isEmpty());
    }

    @Test
    void reportsAKeyUnderAClaimedNamespaceEvenWhenAnotherPrefixMatchesPartially() {
        List<String> unread = ConfigKeyAudit.unconsumedKeys(
                List.of("vidocq.http.prot"),                       // typo on the claimed alias
                Set.of("vidocq.http.host", "vidocq.http.port", "vidocq.http.mount.*"));

        assertEquals(List.of("vidocq.http.prot"), unread);
    }

    @Test
    void resultIsSortedAndDeduplicated() {
        List<String> unread = ConfigKeyAudit.unconsumedKeys(
                List.of("vidocq.chappe.zzz", "vidocq.chappe.aaa", "vidocq.chappe.zzz"),
                Set.of("vidocq.chappe.listeners"));

        assertEquals(List.of("vidocq.chappe.aaa", "vidocq.chappe.zzz"), unread);
    }
}
