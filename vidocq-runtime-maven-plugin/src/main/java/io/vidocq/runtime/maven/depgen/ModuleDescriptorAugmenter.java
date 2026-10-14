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
package io.vidocq.runtime.maven.depgen;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleProvideInfo;
import java.lang.classfile.attribute.ModuleRequireInfo;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MethodTypeEntry;
import java.lang.classfile.constantpool.NameAndTypeEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.constant.ClassDesc;
import java.lang.constant.ModuleDesc;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Declares a brick's output in a module descriptor: the services its generated classes provide and the modules they
 * call into. A module on the module path is only asked for its providers through its descriptor, so a generated
 * class listed in a {@code META-INF/services} file alone is never found there.
 */
public final class ModuleDescriptorAugmenter {

    private ModuleDescriptorAugmenter() {}

    /**
     * {@code moduleInfo} with each {@code provides service with impls} added, merged into the existing
     * {@code provides} of the same service without duplicates, and each module of {@code requires} it does not
     * require yet as a plain {@code requires}. Everything else is kept; nothing to add returns the same bytes.
     *
     * @param provides service binary name → provider binary names
     * @param requires module names
     */
    public static byte[] augment(byte[] moduleInfo, Map<String, List<String>> provides, Set<String> requires) {
        ClassFile cf = ClassFile.of();
        ClassModel model = cf.parse(moduleInfo);
        ModuleAttribute module = model.findAttribute(Attributes.module())
                .orElseThrow(() -> new IllegalArgumentException("not a module descriptor"));

        List<ModuleRequireInfo> newRequires = new ArrayList<>(module.requires());
        Set<String> required = new LinkedHashSet<>();
        module.requires().forEach(r -> required.add(r.requires().name().stringValue()));
        for (String name : new TreeSet<>(requires)) {
            if (required.add(name)) {
                newRequires.add(ModuleRequireInfo.of(ModuleDesc.of(name), 0, null));
            }
        }

        Map<String, LinkedHashSet<String>> merged = new LinkedHashMap<>();
        for (ModuleProvideInfo p : module.provides()) {
            LinkedHashSet<String> impls = merged.computeIfAbsent(binaryName(p.provides()), s -> new LinkedHashSet<>());
            p.providesWith().forEach(impl -> impls.add(binaryName(impl)));
        }
        boolean providesChanged = false;
        for (var e : new TreeMap<>(provides).entrySet()) {
            providesChanged |= merged.computeIfAbsent(e.getKey(), s -> new LinkedHashSet<>()).addAll(e.getValue());
        }

        if (!providesChanged && newRequires.size() == module.requires().size()) {
            return moduleInfo;
        }
        List<ModuleProvideInfo> newProvides = merged.entrySet().stream()
                .map(e -> ModuleProvideInfo.of(ClassDesc.of(e.getKey()),
                        e.getValue().stream().map(ClassDesc::of).toList()))
                .toList();
        ModuleAttribute updated = ModuleAttribute.of(module.moduleName(), module.moduleFlagsMask(),
                module.moduleVersion().orElse(null), newRequires, module.exports(), module.opens(), module.uses(),
                newProvides);
        return cf.transformClass(model, (cb, ce) -> {
            if (ce instanceof ModuleAttribute) {
                cb.with(updated);
            } else {
                cb.with(ce);
            }
        });
    }

    /** The name of the module {@code moduleInfo} describes. */
    public static String moduleName(byte[] moduleInfo) {
        return ClassFile.of().parse(moduleInfo).findAttribute(Attributes.module())
                .orElseThrow(() -> new IllegalArgumentException("not a module descriptor"))
                .moduleName().name().stringValue();
    }

    /**
     * The packages {@code classes} refer to: those of the classes of their constant pools, and of the types named in
     * their field, method and call-site descriptors.
     */
    public static Set<String> referencedPackages(Collection<byte[]> classes) {
        Set<String> packages = new TreeSet<>();
        for (byte[] bytes : classes) {
            ClassModel model = ClassFile.of().parse(bytes);
            for (PoolEntry entry : model.constantPool()) {
                switch (entry) {
                    case ClassEntry c -> addInternalName(c.asInternalName(), packages);
                    case NameAndTypeEntry nt -> addDescriptor(nt.type().stringValue(), packages);
                    case MethodTypeEntry mt -> addDescriptor(mt.descriptor().stringValue(), packages);
                    default -> { }
                }
            }
            for (FieldModel f : model.fields()) {
                addDescriptor(f.fieldType().stringValue(), packages);
            }
            for (MethodModel m : model.methods()) {
                addDescriptor(m.methodType().stringValue(), packages);
            }
        }
        return packages;
    }

    /** {@code org/x/A} or an array descriptor {@code [Lorg/x/A;} as a class entry holds it. */
    private static void addInternalName(String internalName, Set<String> packages) {
        if (internalName.startsWith("[")) {
            addDescriptor(internalName, packages);
            return;
        }
        int slash = internalName.lastIndexOf('/');
        if (slash > 0) {
            packages.add(internalName.substring(0, slash).replace('/', '.'));
        }
    }

    /** Every {@code Lorg/x/A;} of a field or method descriptor. */
    private static void addDescriptor(String descriptor, Set<String> packages) {
        int start = descriptor.indexOf('L');
        while (start >= 0) {
            int end = descriptor.indexOf(';', start);
            if (end < 0) {
                return;
            }
            addInternalName(descriptor.substring(start + 1, end), packages);
            start = descriptor.indexOf('L', end);
        }
    }

    private static String binaryName(ClassEntry entry) {
        return entry.asInternalName().replace('/', '.');
    }
}
