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
package io.vidocq.runtime.extensions.microprofile.cervantes;

import io.vidocq.cervantes.cdi.CervantesClaimExtension;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.module.ModuleDescriptor;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The JWT extension brings Cervantes' CDI integration through: one {@code BuildCompatibleExtension}, the brick's own {@link CervantesClaimExtension}, which its module
 * declares itself. The wrapper used to republish it through an empty subclass, so that Vauban ran two extensions doing
 * the same work, and a codegen bundle putting the brick on the processor path saw both.
 */
class CervantesSingleExtensionTest {

    private static final String SERVICE = "jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension";

    @Test
    void theBrickDeclaresTheOnlyExtensionOfItsKind() throws IOException, ClassNotFoundException {
        Set<String> sameKind = new TreeSet<>();
        for (String provider : declaredProviders()) {
            Class<?> type = Class.forName(provider, false, CervantesSingleExtensionTest.class.getClassLoader());
            if (CervantesClaimExtension.class.isAssignableFrom(type)) {
                sameKind.add(provider);
            }
        }

        assertEquals(Set.of(CervantesClaimExtension.class.getName()), sameKind);
    }

    /** The providers the boot layer's modules declare, and those {@code META-INF/services} names on the class path. */
    private static Set<String> declaredProviders() throws IOException {
        Set<String> providers = new TreeSet<>();
        for (Module module : ModuleLayer.boot().modules()) {
            for (ModuleDescriptor.Provides provides : module.getDescriptor().provides()) {
                if (SERVICE.equals(provides.service())) {
                    providers.addAll(provides.providers());
                }
            }
        }
        for (URL url : Collections.list(CervantesSingleExtensionTest.class.getClassLoader().getResources("META-INF/services/" + SERVICE))) {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
                in.lines().map(line -> line.replaceAll("#.*", "").strip()).filter(line -> !line.isEmpty())
                        .forEach(providers::add);
            }
        }
        return providers;
    }
}
