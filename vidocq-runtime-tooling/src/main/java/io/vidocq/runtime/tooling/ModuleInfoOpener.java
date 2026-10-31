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
package io.vidocq.runtime.tooling;

import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleOpenInfo;
import java.lang.classfile.attribute.ModulePackagesAttribute;
import java.lang.classfile.constantpool.PackageEntry;
import java.lang.constant.PackageDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Opens packages in {@code module-info.java} and in the {@code module-info.class} compiled from it, at once. Under
 * {@code vidocq dev}, {@code vidocq:check-module-info} opens this way a package the runtime reaches by reflection,
 * so the application starts on the build that found it: the descriptor the JVM reads already opens the package, and
 * the next compile writes the same one from the source (BUG-20261031-10).
 */
public final class ModuleInfoOpener {

    private ModuleInfoOpener() {}

    /**
     * Opens each of {@code packages} that the source does not open yet, in the block {@code vidocq doctor --fix} and
     * {@code vidocq:complete-module-info} write, and in {@code moduleInfoClass}.
     *
     * @return the directives written to the source, such as {@code opens com.acme.book;}; empty when all were there
     */
    public static List<String> open(Path moduleInfoSource, Path moduleInfoClass, Collection<String> packages)
            throws IOException {
        String source = Files.readString(moduleInfoSource);
        List<String> toOpen = new ArrayList<>();
        for (String pkg : new LinkedHashSet<>(packages)) {
            if (!ModuleInfoEditor.missing(source, List.of("opens " + pkg)).isEmpty()) {
                toOpen.add(pkg);
            }
        }
        if (toOpen.isEmpty()) {
            return List.of();
        }
        List<String> directives = toOpen.stream().map(pkg -> "opens " + pkg + ";").toList();
        // The descriptor first: one that cannot be read leaves the source as it was.
        byte[] patched = withOpens(Files.readAllBytes(moduleInfoClass), toOpen);
        Files.writeString(moduleInfoSource,
                ModuleInfoEditor.appendBlock(source, ProjectDoctor.REFLECTION_MARKER, directives));
        Files.write(moduleInfoClass, patched);
        return directives;
    }

    /**
     * {@code moduleInfoClass} with an unqualified {@code opens} of each of {@code packages} it does not open yet, and
     * those packages in its {@code ModulePackages} attribute when it has one.
     */
    static byte[] withOpens(byte[] moduleInfoClass, Collection<String> packages) {
        ClassFile classFile = ClassFile.of();
        ClassModel model = classFile.parse(moduleInfoClass);
        ModuleAttribute module = model.findAttribute(Attributes.module()).orElseThrow(
                () -> new IllegalArgumentException("not a module-info.class: no Module attribute"));
        Set<String> opened = new LinkedHashSet<>();
        module.opens().forEach(o -> opened.add(o.openedPackage().name().stringValue().replace('/', '.')));
        List<ModuleOpenInfo> opens = new ArrayList<>(module.opens());
        for (String pkg : packages) {
            if (opened.add(pkg)) {
                opens.add(ModuleOpenInfo.of(PackageDesc.of(pkg), 0));
            }
        }
        ModuleAttribute patched = ModuleAttribute.of(module.moduleName(), module.moduleFlagsMask(),
                module.moduleVersion().orElse(null), module.requires(), module.exports(), opens, module.uses(),
                module.provides());
        return classFile.transformClass(model, (builder, element) -> {
            switch (element) {
                case ModuleAttribute _ -> builder.with(patched);
                case ModulePackagesAttribute attribute -> builder.with(withPackages(attribute, packages));
                default -> builder.with(element);
            }
        });
    }

    private static ModulePackagesAttribute withPackages(ModulePackagesAttribute attribute, Collection<String> packages) {
        Set<PackageDesc> all = new LinkedHashSet<>();
        for (PackageEntry entry : attribute.packages()) {
            all.add(PackageDesc.ofInternalName(entry.name().stringValue()));
        }
        packages.forEach(pkg -> all.add(PackageDesc.of(pkg)));
        return ModulePackagesAttribute.ofNames(List.copyOf(all));
    }
}
