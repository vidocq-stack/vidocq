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
package io.vidocq.runtime.maven;

import org.junit.jupiter.api.Test;

import java.lang.module.ModuleDescriptor;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The modules a jlink image carries beyond what jdeps finds: the CRaC one a module requires, and the configured ones. */
class JlinkExtraModulesTest {

    private static ModuleDescriptor module(String name, String... requires) {
        ModuleDescriptor.Builder b = ModuleDescriptor.newModule(name);
        for (String r : requires) {
            b.requires(r);
        }
        return b.build();
    }

    @Test
    void nothingExtraWhenNoModuleRequiresJdkCrac() {
        assertEquals(Set.of(), VidocqJlinkMojo.extraModules(List.of(module("app", "java.base")), null));
    }

    @Test
    void jdkCracIsAddedWhenAStagedModuleRequiresIt() {
        assertEquals(Set.of("jdk.crac"),
                VidocqJlinkMojo.extraModules(List.of(module("app"), module("crac", "jdk.crac")), ""));
    }

    @Test
    void configuredModulesAreAddedInOrder_blanksIgnored() {
        assertEquals(List.of("jdk.jcmd", "jdk.attach"),
                List.copyOf(VidocqJlinkMojo.extraModules(List.of(module("app")), " jdk.jcmd, ,jdk.attach ")));
    }

    @Test
    void aModuleBothRequiredAndConfiguredIsListedOnce() {
        assertEquals(List.of("jdk.crac", "jdk.jcmd"),
                List.copyOf(VidocqJlinkMojo.extraModules(List.of(module("crac", "jdk.crac")), "jdk.crac,jdk.jcmd")));
    }
}
