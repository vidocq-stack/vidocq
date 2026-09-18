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
package io.vidocq.runtime.core.report;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleRequireInfo;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.constant.ModuleDesc;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/**
 * Startup report contributors that only a {@link java.util.ServiceLoader} can find, written with the class-file
 * API: on the class path of a loader of their own, or as one module defined twice, in a layer and in its child
 * layer, the way the application layer of {@code Vidocq.run} loads a library a second time.
 *
 * <p>They are emitted rather than compiled with the tests: a test class belongs to the named module
 * {@code io.vidocq.runtime.core}, where a {@code META-INF/services} file is ignored, and it is defined once,
 * by the test's own loader.
 *
 * <p>A contributor emitted here has an {@code id()} and a {@code contribute} that writes one summary line;
 * its constructor may throw instead, to tell whether Vidocq instantiated it.
 */
public final class ContributorFixtures {

    /** The name of the module that {@link #twinLayers(Path)} defines twice. */
    public static final String TWIN_MODULE = "fixture.twin";
    /** The contributor class of that module. */
    public static final String TWIN_CLASS = "fixture.twin.TwinContributor";
    /** The id of that contributor, in both layers. */
    public static final String TWIN_ID = "twin";
    /** The summary line the copy of the child layer writes; the copy of the parent layer cannot be created. */
    public static final String TWIN_SUMMARY = "written by the copy of the child layer";

    private static final String SERVICE = "io.vidocq.runtime.spi.report.StartupReportContributor";
    private static final ClassDesc CONTRIBUTOR = ClassDesc.of(SERVICE);
    private static final ClassDesc CONTEXT = ClassDesc.of("io.vidocq.runtime.spi.report.StartupReportContext");
    private static final ClassDesc SECTION = ClassDesc.of("io.vidocq.runtime.spi.report.StartupReportSection");
    private static final ClassDesc ILLEGAL_STATE = ClassDesc.of("java.lang.IllegalStateException");
    /** The order a contributor has when it does not override {@code order()}. */
    private static final int DEFAULT_ORDER = 1000;

    private ContributorFixtures() {}

    /**
     * Writes a contributor class into the class output {@code root}, and lists it in {@code root}'s
     * {@code META-INF/services} file.
     *
     * @param root      a directory of classes
     * @param className the binary name of the class
     * @param id        what its {@code id()} returns
     * @param order     what its {@code order()} returns
     * @param summary   the summary line its {@code contribute} writes
     */
    public static void onClassPath(Path root, String className, String id, int order, String summary)
            throws IOException {
        write(root, className, contributor(className, id, order, summary, false));
        listed(root, className);
    }

    /**
     * Writes a contributor class whose constructor throws an {@link IllegalStateException} into {@code root}, and
     * lists it in {@code root}'s {@code META-INF/services} file.
     */
    public static void throwingOnClassPath(Path root, String className) throws IOException {
        write(root, className, contributor(className, "never", DEFAULT_ORDER, "never", true));
        listed(root, className);
    }

    /** Adds {@code className} to {@code root}'s {@code META-INF/services} file, whether it exists or not. */
    public static void listed(Path root, String className) throws IOException {
        Path services = root.resolve("META-INF/services/" + SERVICE);
        Files.createDirectories(services.getParent());
        Files.writeString(services, className + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    /** A loader of the classes of {@code root}, the test's loader as its parent. */
    public static URLClassLoader classPathLoader(Path root) throws IOException {
        return new URLClassLoader("contributors", new URL[] {root.toUri().toURL()},
                ContributorFixtures.class.getClassLoader());
    }

    /**
     * Defines the module {@value #TWIN_MODULE}, which provides the contributor {@value #TWIN_CLASS}, in a layer
     * over the boot layer, then again in a child layer of that one, each copy with a loader of its own: two
     * classes of the same name, as the application layer of {@code Vidocq.run} makes them. The copy of the
     * parent layer throws when it is created.
     *
     * @param dir a directory to write both modules in
     * @return the loader of the child layer, whose {@link java.util.ServiceLoader} finds both copies
     */
    public static ClassLoader twinLayers(Path dir) throws IOException {
        Path parent = twinModule(dir.resolve("parent"), true);
        Path child = twinModule(dir.resolve("child"), false);
        Configuration parentConfiguration = ModuleLayer.boot().configuration()
                .resolve(ModuleFinder.of(parent), ModuleFinder.of(), Set.of(TWIN_MODULE));
        ModuleLayer parentLayer = ModuleLayer.boot()
                .defineModulesWithOneLoader(parentConfiguration, ContributorFixtures.class.getClassLoader());
        Configuration childConfiguration = parentConfiguration
                .resolve(ModuleFinder.of(child), ModuleFinder.of(), Set.of(TWIN_MODULE));
        ModuleLayer childLayer = parentLayer
                .defineModulesWithOneLoader(childConfiguration, parentLayer.findLoader(TWIN_MODULE));
        return childLayer.findLoader(TWIN_MODULE);
    }

    private static Path twinModule(Path root, boolean constructorThrows) throws IOException {
        Files.createDirectories(root);
        Files.write(root.resolve("module-info.class"), ClassFile.of().buildModule(
                ModuleAttribute.of(ModuleDesc.of(TWIN_MODULE), module -> module
                        .requires(ModuleRequireInfo.of(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null))
                        .requires(ModuleDesc.of("io.vidocq.runtime.spi"), 0, null)
                        .provides(CONTRIBUTOR, ClassDesc.of(TWIN_CLASS)))));
        write(root, TWIN_CLASS, contributor(TWIN_CLASS, TWIN_ID, DEFAULT_ORDER, TWIN_SUMMARY, constructorThrows));
        return root;
    }

    private static void write(Path root, String className, byte[] bytes) throws IOException {
        Path file = root.resolve(className.replace('.', '/') + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    /**
     * {@code public final class <className> implements StartupReportContributor}: {@code id()} returns
     * {@code id}, {@code order()} returns {@code order} (the inherited default when it is that default),
     * {@code contribute} calls {@code section.summary(summary)}.
     */
    private static byte[] contributor(String className, String id, int order, String summary,
                                      boolean constructorThrows) {
        return ClassFile.of().build(ClassDesc.of(className), type -> {
            type.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER)
                    .withInterfaceSymbols(CONTRIBUTOR)
                    .withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, code -> {
                        code.aload(0).invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                ConstantDescs.MTD_void);
                        if (constructorThrows) {
                            code.new_(ILLEGAL_STATE)
                                    .dup()
                                    .ldc(className + " must not be created")
                                    .invokespecial(ILLEGAL_STATE, ConstantDescs.INIT_NAME,
                                            MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String))
                                    .athrow();
                        } else {
                            code.return_();
                        }
                    })
                    .withMethodBody("id", MethodTypeDesc.of(ConstantDescs.CD_String), ClassFile.ACC_PUBLIC,
                            code -> code.ldc(id).areturn())
                    .withMethodBody("contribute", MethodTypeDesc.of(ConstantDescs.CD_void, CONTEXT, SECTION),
                            ClassFile.ACC_PUBLIC, code -> code
                                    .aload(2)
                                    .ldc(summary)
                                    .invokeinterface(SECTION, "summary",
                                            MethodTypeDesc.of(SECTION, ConstantDescs.CD_String))
                                    .pop()
                                    .return_());
            if (order != DEFAULT_ORDER) {
                type.withMethodBody("order", MethodTypeDesc.of(ConstantDescs.CD_int), ClassFile.ACC_PUBLIC,
                        code -> code.ldc(order).ireturn());
            }
        });
    }
}
