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
package io.vidocq.runtime.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.classfile.Annotation;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModulePackagesAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.PackageDesc;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleRequireInfo;
import java.lang.constant.ModuleDesc;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stamp {@code vidocq:generate} leaves in the application's classes, by which the boot tells an application
 * that went through it from one an IDE built alone (Vidocq/vidocq#241).
 */
@DisplayName("GenerateStamp — did vidocq:generate run on the application?")
class GenerateStampTest {

    private static final String INDEX = "acme.app.Greeter\nacme.lib.Clock\n";

    @Test
    @DisplayName("an application module without the stamp missed vidocq:generate")
    void aModuleWithoutTheStampMissedGenerate(@TempDir Path dir) throws Exception {
        assertTrue(GenerateStamp.staleIn(module(dir, INDEX, null)));
    }

    @Test
    @DisplayName("a stamp written for the index the module holds: vidocq:generate's output is still there")
    void aStampForTheCurrentIndexIsUpToDate(@TempDir Path dir) throws Exception {
        assertFalse(GenerateStamp.staleIn(module(dir, INDEX, GenerateStamp.content(INDEX.getBytes(UTF_8)))));
    }

    @Test
    @DisplayName("an index rewritten since the stamp, as an IDE's incremental build rewrites it: stale")
    void anIndexRewrittenSinceTheStampIsStale(@TempDir Path dir) throws Exception {
        String rewritten = "acme.app.Greeter\n";
        assertTrue(GenerateStamp.staleIn(module(dir, rewritten, GenerateStamp.content(INDEX.getBytes(UTF_8)))));
    }

    @Test
    @DisplayName("no index when vidocq:generate ran and none now: up to date")
    void noIndexThenAndNowIsUpToDate(@TempDir Path dir) throws Exception {
        assertFalse(GenerateStamp.staleIn(module(dir, null, GenerateStamp.content(null))));
    }

    @Test
    @DisplayName("no application module, or an unnamed one: nothing to tell, no fallback")
    void noNamedApplicationModuleTellsNothing() {
        assertFalse(GenerateStamp.staleIn(null));
        assertFalse(GenerateStamp.staleIn(getClass().getClassLoader().getUnnamedModule()));
    }

    @Test
    @DisplayName("bean classes and no Vauban index: compiled without Vauban's processor, the beans are lost")
    void beanClassesWithoutAnIndexWereCompiledWithoutTheProcessor(@TempDir Path dir) throws Exception {
        assertTrue(GenerateStamp.beansWithoutIndex(module(dir, null, null, "jakarta/enterprise/context/ApplicationScoped")));
    }

    @Test
    @DisplayName("a REST resource class and no index: lost as well")
    void aResourceClassWithoutAnIndexIsLost(@TempDir Path dir) throws Exception {
        assertTrue(GenerateStamp.beansWithoutIndex(module(dir, null, null, "jakarta/ws/rs/Path")));
    }

    @Test
    @DisplayName("bean classes and an index: the processor ran")
    void beanClassesWithAnIndexAreFine(@TempDir Path dir) throws Exception {
        assertFalse(GenerateStamp.beansWithoutIndex(module(dir, INDEX, null, "jakarta/enterprise/context/ApplicationScoped")));
    }

    @Test
    @DisplayName("no bean class and no index: the processor writes nothing for such a module, nothing is lost")
    void noBeanClassAndNoIndexIsFine(@TempDir Path dir) throws Exception {
        assertFalse(GenerateStamp.beansWithoutIndex(module(dir, null, null, "java/lang/Deprecated")));
        assertFalse(GenerateStamp.beansWithoutIndex(null));
        assertFalse(GenerateStamp.beansWithoutIndex(getClass().getClassLoader().getUnnamedModule()));
    }

    @Test
    @DisplayName("an application launched without a main class of its own: the exploded modules of its layer are checked")
    void theExplodedModulesOfTheApplicationLayerAreChecked(@TempDir Path dir) throws Exception {
        Module lost = module(dir, null, null, "jakarta/ws/rs/Path");

        assertEquals(java.util.List.of(lost), GenerateStamp.beanModulesWithoutIndex(null, lost.getLayer()));
        assertEquals(java.util.List.of(lost), GenerateStamp.beanModulesWithoutIndex(lost, lost.getLayer()),
                "a module named twice is reported once");
        assertEquals(java.util.List.of(), GenerateStamp.beanModulesWithoutIndex(null, null));
    }

    @Test
    @DisplayName("the stamp is a fixed file whose content depends on the index only")
    void theStampDependsOnTheIndexOnly() {
        assertEquals("META-INF/vidocq/generate.stamp", GenerateStamp.PATH);
        assertEquals("META-INF/vauban-beans.list", GenerateStamp.INDEX);
        assertEquals(GenerateStamp.content(INDEX.getBytes(UTF_8)), GenerateStamp.content(INDEX.getBytes(UTF_8)));
        assertEquals("vidocq:generate\nindex=none\n", GenerateStamp.content(null));
        assertTrue(GenerateStamp.content(INDEX.getBytes(UTF_8)).matches("vidocq:generate\nindex=sha256:[0-9a-f]{64}\n"));
    }

    /** An exploded module {@code acme.app} with the given index and stamp ({@code null}: none), in a layer of its own. */
    private static Module module(Path dir, String index, String stamp) throws Exception {
        return module(dir, index, stamp, null);
    }

    /** As {@link #module(Path, String, String)}, with a class {@code acme.app.Greeter} annotated {@code annotation}. */
    private static Module module(Path dir, String index, String stamp, String annotation) throws Exception {
        Path root = Files.createDirectories(dir.resolve("acme.app"));
        if (annotation != null) {
            Files.createDirectories(root.resolve("acme/app"));
            Files.write(root.resolve("acme/app/Greeter.class"), ClassFile.of().build(ClassDesc.of("acme.app.Greeter"),
                    cb -> cb.withFlags(ClassFile.ACC_PUBLIC).with(RuntimeVisibleAnnotationsAttribute.of(
                            Annotation.of(ClassDesc.ofInternalName(annotation))))));
        }
        Files.write(root.resolve("module-info.class"), ClassFile.of().buildModule(
                ModuleAttribute.of(ModuleDesc.of("acme.app"), mb -> mb.requires(
                        ModuleRequireInfo.of(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null))),
                clb -> {
                    if (annotation != null) {
                        clb.with(ModulePackagesAttribute.ofNames(PackageDesc.of("acme.app")));
                    }
                }));
        write(root.resolve(GenerateStamp.INDEX), index);
        write(root.resolve(GenerateStamp.PATH), stamp);
        var config = ModuleLayer.boot().configuration().resolve(ModuleFinder.of(root), ModuleFinder.of(),
                Set.of("acme.app"));
        var layer = ModuleLayer.boot().defineModulesWithOneLoader(config, ClassLoader.getSystemClassLoader());
        return layer.findModule("acme.app").orElseThrow();
    }

    private static void write(Path file, String content) throws Exception {
        if (content != null) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        }
    }
}
