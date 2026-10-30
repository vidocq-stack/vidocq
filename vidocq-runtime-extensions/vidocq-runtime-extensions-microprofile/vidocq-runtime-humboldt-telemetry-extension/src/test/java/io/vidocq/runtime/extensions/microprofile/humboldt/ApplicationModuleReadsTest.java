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
package io.vidocq.runtime.extensions.microprofile.humboldt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An application that requires this extension reads the OpenTelemetry API ({@code Span}, {@code Tracer}) and {@code @WithSpan}; without them a created application had to add both {@code requires} by hand (BUG-20261030-09).
 */
class ApplicationModuleReadsTest {

    @Test
    void theApplicationReadsTheApisItAnnotatesWith() throws IOException {
        // The compiled descriptor itself: these tests may run on the class path, where the module has none.
        ModuleDescriptor descriptor;
        try (InputStream in = Files.newInputStream(Path.of("target", "classes", "module-info.class"))) {
            descriptor = ModuleDescriptor.read(in);
        }
        Set<String> transitive = new TreeSet<>();
        for (ModuleDescriptor.Requires requires : descriptor.requires()) {
            if (requires.modifiers().contains(ModuleDescriptor.Requires.Modifier.TRANSITIVE)) {
                transitive.add(requires.name());
            }
        }

        assertEquals(Set.of("io.opentelemetry.api", "io.opentelemetry.instrumentation_annotations"), transitive.stream().filter(m -> m.startsWith("io.opentelemetry.")).collect(java.util.stream.Collectors.toSet()));
    }
}
