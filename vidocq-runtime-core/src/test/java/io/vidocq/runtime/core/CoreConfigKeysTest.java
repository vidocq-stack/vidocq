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
package io.vidocq.runtime.core;

import io.vidocq.runtime.core.config.ConfigKeyAudit;
import io.vidocq.runtime.spi.VidocqExtension;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The keys the core reads itself take part in the unconsumed-key audit. */
class CoreConfigKeysTest {

    @Test
    void consoleKeysAreNotReportedAsUnconsumed() {
        Set<String> declared = VidocqBootstrap.declaredConfigKeys(List.of());

        assertTrue(ConfigKeyAudit.unconsumedKeys(List.of("vidocq.log.console", "vidocq.console.color"), declared)
                .isEmpty());
    }

    @Test
    void aTypoUnderACoreNamespaceIsReported() {
        Set<String> declared = VidocqBootstrap.declaredConfigKeys(List.of());

        assertEquals(List.of("vidocq.console.colour"),
                ConfigKeyAudit.unconsumedKeys(List.of("vidocq.console.colour", "vidocq.log.console"), declared));
    }

    @Test
    void bannerKeysAreDeclaredAndAMistakenKeyNamesTheCandidates() {
        Set<String> declared = VidocqBootstrap.declaredConfigKeys(List.of());

        List<String> unconsumed = ConfigKeyAudit.unconsumedKeys(List.of("vidocq.banner.mode", "vidocq.banner.location",
                "vidocq.banner.enabled", "vidocq.app.path", "vidocq.app.port"), declared);

        assertEquals(List.of("vidocq.banner.enabled"), unconsumed, "vidocq.app.* is claimed by nobody: not audited");
        assertEquals("Configuration key 'vidocq.banner.enabled' is read by no extension and has no effect. "
                + "Known keys in this namespace: vidocq.banner.location, vidocq.banner.mode",
                ConfigKeyAudit.warningFor("vidocq.banner.enabled", declared));
    }

    @Test
    void coreKeysAreMergedWithTheExtensionsKeys() {
        VidocqExtension chappe = new VidocqExtension() {
            @Override
            public String name() {
                return "chappe";
            }

            @Override
            public Set<String> configKeys() {
                return Set.of("vidocq.http.port");
            }
        };

        Set<String> declared = VidocqBootstrap.declaredConfigKeys(List.of(chappe));

        assertTrue(declared.containsAll(Set.of("vidocq.http.port", "vidocq.log.console", "vidocq.console.color")));
    }
}
