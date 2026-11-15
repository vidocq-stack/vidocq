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

import io.vidocq.vauban.classloader.VaubanLayerFactory;

import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.constant.ClassDesc;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Writes into the {@code module-info.class} of the modules a jlink image links the {@code provides} that their
 * {@code META-INF/services} files declare (Vidocq/vidocq#580).
 *
 * <p>The annotation processors declare what they generate in services files only ({@code _VaubanComponents},
 * Cassini's routes): a processor cannot edit {@code module-info.java}. Under the application layer, Vauban turns
 * those files into {@code provides} when it defines the layer, at each start. An image has no application layer,
 * and {@code ServiceLoader} reads the descriptor of a named module, never its services files: the build does the
 * promotion once, here, with Vauban's own rule ({@link VaubanLayerFactory#promotingServices}), so a module provides
 * the same services in an image as in the layer. Only the staged copies change; the services files stay.
 */
final class StagedProvides {

    private static final String MODULE_INFO = "module-info.class";

    private StagedProvides() {}

    /**
     * Promotes the services files of every explicit module of {@code stage}. An automatic module is left as it is:
     * the module system reads its services files itself.
     *
     * @return for each module that changed, the providers added, by service
     */
    static Map<String, Map<String, List<String>>> promote(Path stage) throws IOException {
        ModuleFinder staged = ModuleFinder.of(stage);
        ModuleFinder promoted = VaubanLayerFactory.promotingServices(staged, ModuleLayer.boot());
        Map<String, Map<String, List<String>>> added = new TreeMap<>();
        for (ModuleReference reference : staged.findAll()) {
            ModuleDescriptor before = reference.descriptor();
            Map<String, List<String>> gained = before.isAutomatic() || reference.location().isEmpty()
                    ? Map.of()
                    : promote(reference, provides(promoted.find(before.name()).orElseThrow().descriptor()));
            if (!gained.isEmpty()) {
                added.put(before.name(), gained);
            }
        }
        return added;
    }

    /** Writes {@code wanted} into the module when it differs from what it provides; returns what the module gained. */
    private static Map<String, List<String>> promote(ModuleReference reference, Map<String, List<String>> wanted)
            throws IOException {
        Map<String, List<String>> had = provides(reference.descriptor());
        if (wanted.equals(had)) {
            return Map.of();
        }
        write(Path.of(reference.location().orElseThrow()), wanted);
        Map<String, List<String>> gained = new TreeMap<>();
        wanted.forEach((service, providers) -> {
            List<String> fresh = providers.stream()
                    .filter(provider -> !had.getOrDefault(service, List.of()).contains(provider)).toList();
            if (!fresh.isEmpty()) {
                gained.put(service, fresh);
            }
        });
        return gained;
    }

    private static Map<String, List<String>> provides(ModuleDescriptor descriptor) {
        Map<String, List<String>> provides = new TreeMap<>();
        descriptor.provides().forEach(p -> provides.put(p.service(), List.copyOf(p.providers())));
        return provides;
    }

    /** Sets the {@code provides} of every {@code module-info.class} of the module, the versioned ones too. */
    private static void write(Path jarOrDir, Map<String, List<String>> provides) throws IOException {
        if (Files.isDirectory(jarOrDir)) {
            writeUnder(jarOrDir, provides);
            return;
        }
        try (FileSystem jar = FileSystems.newFileSystem(jarOrDir)) {
            writeUnder(jar.getPath("/"), provides);
        }
    }

    private static void writeUnder(Path root, Map<String, List<String>> provides) throws IOException {
        List<Path> descriptors;
        try (Stream<Path> files = Files.walk(root)) {
            descriptors = files.filter(f -> f.getFileName() != null
                    && MODULE_INFO.equals(f.getFileName().toString())).toList();
        }
        for (Path descriptor : descriptors) {
            Files.write(descriptor, withProvides(Files.readAllBytes(descriptor), provides));
        }
    }

    /** {@code moduleInfo} with {@code provides} as its only {@code provides}; everything else is kept. */
    static byte[] withProvides(byte[] moduleInfo, Map<String, List<String>> provides) {
        var cf = ClassFile.of();
        var model = cf.parse(moduleInfo);
        ModuleAttribute old = model.findAttribute(Attributes.module())
                .orElseThrow(() -> new IllegalArgumentException("not a module-info.class"));
        Map<String, List<String>> ordered = new LinkedHashMap<>(provides);
        var replacement = ModuleAttribute.of(old.moduleName(), builder -> {
            builder.moduleFlags(old.moduleFlagsMask());
            old.moduleVersion().ifPresent(version -> builder.moduleVersion(version.stringValue()));
            old.requires().forEach(builder::requires);
            old.exports().forEach(builder::exports);
            old.opens().forEach(builder::opens);
            old.uses().forEach(builder::uses);
            ordered.forEach((service, providers) -> builder.provides(ClassDesc.of(service),
                    providers.stream().map(ClassDesc::of).toArray(ClassDesc[]::new)));
        });
        return cf.transformClass(model, ClassTransform.dropping(ModuleAttribute.class::isInstance)
                .andThen(ClassTransform.endHandler(builder -> builder.with(replacement))));
    }
}
