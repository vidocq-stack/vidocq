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
package io.vidocq.runtime.extensions.jakartaee.web.kerckhoffs;

import io.vidocq.runtime.extensions.jakartaee.web.foy.spi.ServletAuthentication;
import io.vidocq.runtime.spi.VidocqExtension;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The services the module declares are also listed in {@code META-INF/services}: a deployment that runs on the class
 * path, as the Arquillian TCK runners do, finds the extension through those files, never through
 * {@code module-info.java}.
 */
class ClassPathServicesTest {

    /** Every provider the services files of the class path list for {@code service}, from all the jars. */
    private static List<String> listed(Class<?> service) throws IOException {
        var providers = new ArrayList<String>();
        var files = ClassPathServicesTest.class.getClassLoader().getResources("META-INF/services/" + service.getName());
        while (files.hasMoreElements()) {
            try (var in = files.nextElement().openStream()) {
                new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().map(String::strip)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#")).forEach(providers::add);
            }
        }
        return providers;
    }

    @Test
    void theExtensionIsListedForTheClassPath() throws IOException {
        var providers = listed(VidocqExtension.class);

        assertTrue(providers.contains(KerckhoffsSecurityExtension.class.getName()), providers::toString);
    }

    @Test
    void theServletAuthenticationIsListedForTheClassPath() throws IOException {
        var providers = listed(ServletAuthentication.class);

        assertTrue(providers.contains(KerckhoffsServletAuthentication.class.getName()), providers::toString);
    }
}
