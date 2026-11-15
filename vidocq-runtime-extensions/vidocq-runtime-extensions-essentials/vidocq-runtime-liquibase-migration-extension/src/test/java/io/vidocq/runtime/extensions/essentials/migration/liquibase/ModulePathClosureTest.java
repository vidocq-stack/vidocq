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
package io.vidocq.runtime.extensions.essentials.migration.liquibase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An application with this extension runs on the module path, where every explicit module must find each module it
 * requires. liquibase-core declares {@code javax.xml.bind:jaxb-api}, which it never uses, without the
 * {@code java.activation} module that jaxb-api's descriptor requires: the JVM refused to start (tutorial,
 * 2026-11-15: {@code FindException: Module java.activation not found, required by java.xml.bind}).
 */
@DisplayName("Liquibase extension: every explicit module of the runtime path finds what it requires")
class ModulePathClosureTest {

    private static List<Path> runtimeJars() {
        return Stream.of(System.getProperty("java.class.path", ""), System.getProperty("jdk.module.path", ""))
                .flatMap(path -> Stream.of(path.split(File.pathSeparator)))
                .filter(entry -> entry.endsWith(".jar"))
                .map(Path::of)
                .filter(Files::isRegularFile)
                .toList();
    }

    @Test
    @DisplayName("no explicit module requires a module the path lacks")
    void everyRequiresIsOnThePath() {
        var finder = ModuleFinder.of(runtimeJars().toArray(Path[]::new));
        Set<String> available = Stream.concat(finder.findAll().stream(), ModuleFinder.ofSystem().findAll().stream())
                .map(reference -> reference.descriptor().name())
                .collect(Collectors.toSet());
        List<String> missing = new ArrayList<>();
        for (ModuleReference reference : finder.findAll()) {
            ModuleDescriptor descriptor = reference.descriptor();
            if (descriptor.isAutomatic()) {
                continue;
            }
            descriptor.requires().stream()
                    .filter(requires -> !requires.modifiers().contains(ModuleDescriptor.Requires.Modifier.STATIC))
                    .filter(requires -> !available.contains(requires.name()))
                    .forEach(requires -> missing.add(requires.name() + " required by " + descriptor.name()));
        }

        assertEquals(List.of(), missing);
    }
}
