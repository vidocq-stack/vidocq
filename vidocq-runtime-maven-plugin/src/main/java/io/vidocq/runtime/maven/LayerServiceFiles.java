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

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.attribute.ModuleAttribute;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Moves the {@code provides} of a module that a jlink image links for its application layer into
 * {@code META-INF/services} files (BUG-20261114-11).
 *
 * <p>The JVM binds into the boot layer every observable module that provides a service a resolved module uses:
 * {@code java.base} uses {@code ToolProvider}, Vauban uses {@code VaubanComponentProvider}. A module of the
 * application layer that declares such a {@code provides} would be resolved into the boot layer as well, and every
 * class and provider of it would exist twice. Without the {@code provides}, nothing binds it; Vauban's application
 * layer reads the services files back into {@code provides} when it defines the module, as it does for any module.
 * Only the staged copies change.
 */
final class LayerServiceFiles {

    private static final String MODULE_INFO = "module-info.class";
    private static final String SERVICES = "META-INF/services/";

    private LayerServiceFiles() {}

    /**
     * Moves the {@code provides} of the module at {@code jarOrDir} into its services files: every
     * {@code module-info.class} of it, the versioned ones of a multi-release jar too, loses its {@code provides}.
     *
     * @return the providers moved, by service
     */
    static Map<String, List<String>> move(Path jarOrDir) throws IOException {
        if (Files.isDirectory(jarOrDir)) {
            return move(jarOrDir, jarOrDir);
        }
        try (FileSystem jar = FileSystems.newFileSystem(jarOrDir)) {
            return move(jar.getPath("/"), jarOrDir);
        }
    }

    private static Map<String, List<String>> move(Path root, Path module) throws IOException {
        List<Path> descriptors;
        try (Stream<Path> files = Files.walk(root)) {
            descriptors = files.filter(f -> f.getFileName() != null
                    && MODULE_INFO.equals(f.getFileName().toString())).toList();
        }
        if (descriptors.isEmpty()) {
            throw new IOException(module + " has no " + MODULE_INFO);
        }
        Map<String, Set<String>> moved = new LinkedHashMap<>();
        for (Path descriptor : descriptors) {
            byte[] bytes = Files.readAllBytes(descriptor);
            provides(bytes).forEach((service, providers) ->
                    moved.computeIfAbsent(service, s -> new LinkedHashSet<>()).addAll(providers));
            Files.write(descriptor, withoutProvides(bytes));
        }
        for (var service : moved.entrySet()) {
            Path file = root.resolve(SERVICES + service.getKey());
            Set<String> lines = new LinkedHashSet<>();
            if (Files.exists(file)) {
                Files.readAllLines(file, StandardCharsets.UTF_8).stream().map(String::strip)
                        .filter(l -> !l.isEmpty() && !l.startsWith("#")).forEach(lines::add);
            } else {
                Files.createDirectories(file.getParent());
            }
            lines.addAll(service.getValue());
            Files.write(file, lines, StandardCharsets.UTF_8);
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        moved.forEach((service, providers) -> result.put(service, List.copyOf(providers)));
        return result;
    }

    /** The {@code provides} of a {@code module-info.class}: binary names, by service. */
    static Map<String, List<String>> provides(byte[] moduleInfo) {
        Map<String, List<String>> provides = new LinkedHashMap<>();
        for (var entry : module(moduleInfo).provides()) {
            List<String> providers = new ArrayList<>();
            entry.providesWith().forEach(p -> providers.add(binaryName(p.asInternalName())));
            provides.put(binaryName(entry.provides().asInternalName()), providers);
        }
        return provides;
    }

    /** {@code moduleInfo} without its {@code provides}; everything else is kept. */
    static byte[] withoutProvides(byte[] moduleInfo) {
        var cf = ClassFile.of();
        var model = cf.parse(moduleInfo);
        ModuleAttribute old = module(moduleInfo);
        if (old.provides().isEmpty()) {
            return moduleInfo;
        }
        var replacement = ModuleAttribute.of(old.moduleName(), builder -> {
            builder.moduleFlags(old.moduleFlagsMask());
            old.moduleVersion().ifPresent(version -> builder.moduleVersion(version.stringValue()));
            old.requires().forEach(builder::requires);
            old.exports().forEach(builder::exports);
            old.opens().forEach(builder::opens);
            old.uses().forEach(builder::uses);
        });
        return cf.transformClass(model, ClassTransform.dropping(ModuleAttribute.class::isInstance)
                .andThen(ClassTransform.endHandler(builder -> builder.with(replacement))));
    }

    private static ModuleAttribute module(byte[] moduleInfo) {
        return ClassFile.of().parse(moduleInfo).findAttribute(java.lang.classfile.Attributes.module())
                .orElseThrow(() -> new IllegalArgumentException("not a module-info.class"));
    }

    private static String binaryName(String internalName) {
        return internalName.replace('/', '.');
    }
}
