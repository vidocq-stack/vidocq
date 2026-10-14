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

import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleExportInfo;
import java.lang.classfile.attribute.ModuleOpenInfo;
import java.lang.classfile.attribute.ModuleProvideInfo;
import java.lang.classfile.attribute.ModuleRequireInfo;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.constant.ModuleDesc;
import java.lang.constant.PackageDesc;
import java.lang.module.ModuleDescriptor;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleDescriptorAugmenterTest {

    static final String SVC = "org.spi.Service";

    /** {@code module org.dep@1.2 { requires java.base; requires org.x; exports org.dep; opens org.dep.impl;
     * uses org.spi.Other; provides org.spi.Service with org.dep.A; }} */
    static byte[] descriptor() {
        return ClassFile.of().buildModule(ModuleAttribute.of(ModuleDesc.of("org.dep"), mb -> mb
                .moduleVersion("1.2")
                .requires(ModuleRequireInfo.of(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null))
                .requires(ModuleRequireInfo.of(ModuleDesc.of("org.x"), ClassFile.ACC_TRANSITIVE, null))
                .exports(ModuleExportInfo.of(PackageDesc.of("org.dep"), 0))
                .opens(ModuleOpenInfo.of(PackageDesc.of("org.dep.impl"), 0))
                .uses(ClassDesc.of("org.spi.Other"))
                .provides(ModuleProvideInfo.of(ClassDesc.of(SVC), ClassDesc.of("org.dep.A")))));
    }

    static ModuleDescriptor read(byte[] bytes) {
        return ModuleDescriptor.read(ByteBuffer.wrap(bytes));
    }

    static List<String> providersOf(ModuleDescriptor md, String service) {
        return md.provides().stream().filter(p -> p.service().equals(service)).findFirst()
                .map(ModuleDescriptor.Provides::providers).orElse(List.of());
    }

    @Test
    void addsANewProvides() {
        ModuleDescriptor md = read(ModuleDescriptorAugmenter.augment(descriptor(),
                Map.of("org.spi.New", List.of("org.dep.N$$Gen")), Set.of()));

        assertEquals(List.of("org.dep.N$$Gen"), providersOf(md, "org.spi.New"));
        assertEquals(List.of("org.dep.A"), providersOf(md, SVC));
    }

    @Test
    void mergesIntoAnExistingProvidesWithoutDuplicates() {
        ModuleDescriptor md = read(ModuleDescriptorAugmenter.augment(descriptor(),
                Map.of(SVC, List.of("org.dep.A", "org.dep.B")), Set.of()));

        assertEquals(List.of("org.dep.A", "org.dep.B"), providersOf(md, SVC));
        assertEquals(1, md.provides().size());
    }

    @Test
    void addsEachRequiresOncePlain() {
        ModuleDescriptor md = read(ModuleDescriptorAugmenter.augment(descriptor(), Map.of(),
                Set.of("org.x", "org.y")));

        Map<String, ModuleDescriptor.Requires> requires = md.requires().stream()
                .collect(Collectors.toMap(ModuleDescriptor.Requires::name, r -> r));
        assertEquals(Set.of("java.base", "org.x", "org.y"), requires.keySet());
        assertEquals(Set.of(ModuleDescriptor.Requires.Modifier.TRANSITIVE), requires.get("org.x").modifiers(),
                "an existing requires keeps its flags");
        assertEquals(Set.of(), requires.get("org.y").modifiers());
    }

    @Test
    void keepsEverythingElse() {
        ModuleDescriptor md = read(ModuleDescriptorAugmenter.augment(descriptor(),
                Map.of("org.spi.New", List.of("org.dep.N")), Set.of("org.y")));

        assertEquals("org.dep", md.name());
        assertEquals("1.2", md.rawVersion().orElseThrow());
        assertEquals(Set.of("org.dep"), md.exports().stream().map(ModuleDescriptor.Exports::source)
                .collect(Collectors.toSet()));
        assertEquals(Set.of("org.dep.impl"), md.opens().stream().map(ModuleDescriptor.Opens::source)
                .collect(Collectors.toSet()));
        assertEquals(Set.of("org.spi.Other"), md.uses());
        assertTrue(md.requires().stream().anyMatch(r -> r.name().equals("java.base")
                && r.modifiers().contains(ModuleDescriptor.Requires.Modifier.MANDATED)));
    }

    @Test
    void nothingToAddLeavesTheBytesAlone() {
        byte[] original = descriptor();

        assertEquals(original, ModuleDescriptorAugmenter.augment(original, Map.of(), Set.of()));
    }

    @Test
    void readsTheModuleName() {
        assertEquals("org.dep", ModuleDescriptorAugmenter.moduleName(descriptor()));
    }

    @Test
    void referencedPackagesComeFromTheConstantPoolAndTheDescriptors() {
        byte[] cls = ClassFile.of().build(ClassDesc.of("org.dep.Gen"), clb -> clb
                .withInterfaceSymbols(ClassDesc.of("org.spi.Component"))
                .withField("f", ClassDesc.of("org.field.Type").arrayType(), ClassFile.ACC_PRIVATE)
                .withMethodBody("m", MethodTypeDesc.of(ConstantDescs.CD_void, ClassDesc.of("org.param.P")),
                        ClassFile.ACC_PUBLIC, cob -> cob.return_()));

        assertEquals(Set.of("org.dep", "java.lang", "org.spi", "org.field", "org.param"),
                ModuleDescriptorAugmenter.referencedPackages(List.of(cls)));
    }
}
