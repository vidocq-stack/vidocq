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
package io.vidocq.runtime.arquillian;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Discovers the {@code jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension}
 * implementations registered through the standard {@code META-INF/services} mechanism.
 * <p>
 * A real Vidocq application gets its build-compatible extensions applied at compile
 * time (APT codegen); pre-processed jars only replay {@code @Enhancement} at boot.
 * Dynamic Arquillian archives are never APT-processed, so the embedded container must
 * hand the discovered BCE classes to the CDI boot explicitly — that puts them through
 * the full build-compatible lifecycle ({@code @Discovery} .. {@code @Synthesis})
 * scoped to the archive classes, which is what the specs' TCK deployments expect.
 * </p>
 */
final class BuildCompatibleExtensions {

    private static final String SERVICE_RESOURCE =
            "META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension";

    private BuildCompatibleExtensions() {
    }

    /**
     * Returns the fully-qualified names of every BCE registered in a
     * {@code META-INF/services} file visible to the given class loader (runtime
     * jars through the parent, plus the materialized archive itself), in
     * discovery order, deduplicated.
     */
    static List<String> discover(ClassLoader classLoader) {
        Set<String> names = new LinkedHashSet<>();
        try {
            Enumeration<URL> resources = classLoader.getResources(SERVICE_RESOURCE);
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(resource.openStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        int comment = line.indexOf('#');
                        if (comment >= 0) {
                            line = line.substring(0, comment);
                        }
                        line = line.trim();
                        if (!line.isEmpty()) {
                            names.add(line);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan BuildCompatibleExtension service files", e);
        }
        return List.copyOf(names);
    }
}
