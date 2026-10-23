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

import java.io.File;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleRequireInfo;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.constant.ModuleDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@value VidocqAppLayer#APP_MODULE_PROPERTY}: the runtime's own main, launched with the application's module in
 * the boot layer (a jlink image without an application main class), re-layers the application as a
 * {@code @VidocqMain} trampoline does.
 */
@DisplayName("vidocq.app.module — the runtime's main re-layers a boot-layer application")
class VidocqAppLayerModuleTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("without the property, nothing is installed")
    void withoutThePropertyNothingIsInstalled() {
        String previous = System.clearProperty(VidocqAppLayer.APP_MODULE_PROPERTY);
        try {
            assertFalse(VidocqAppLayer.installFromModuleIfConfigured());
        } finally {
            restore(previous);
        }
    }

    @Test
    @DisplayName("a module missing from the boot layer fails with the --add-modules that fixes it")
    void aModuleMissingFromTheBootLayerSaysHowToResolveIt() {
        String previous = System.setProperty(VidocqAppLayer.APP_MODULE_PROPERTY, "acme.absent");
        try {
            var failure = assertThrows(IllegalStateException.class, VidocqAppLayer::installFromModuleIfConfigured);
            assertTrue(failure.getMessage().contains("acme.absent"), failure.getMessage());
            assertTrue(failure.getMessage().contains("--add-modules acme.absent"), failure.getMessage());
        } finally {
            restore(previous);
        }
    }

    @Test
    @DisplayName("a named boot-layer module without an application archive is left in place")
    void aModuleWithoutApplicationArchiveIsLeftInPlace() {
        // java.logging lives in the runtime image (jrt:): there is no archive to re-layer.
        String previous = System.setProperty(VidocqAppLayer.APP_MODULE_PROPERTY, "java.logging");
        try {
            assertFalse(VidocqAppLayer.installFromModuleIfConfigured());
            assertFalse(VidocqAppLayer.alreadyInLayer());
        } finally {
            restore(previous);
        }
    }

    @Test
    @DisplayName("the trampoline path detects from the caller's module as the property path does from the named one")
    void theTrampolinePathDetectsFromTheCallerModule() {
        assertFalse(VidocqAppLayer.installFromBootLayer(java.util.logging.Logger.class));
        assertFalse(VidocqAppLayer.alreadyInLayer());
    }

    @Test
    @DisplayName("the named module is the application module the banner and the startup report name")
    void theNamedModuleIsTheApplicationModule() {
        String previous = System.setProperty(VidocqAppLayer.APP_MODULE_PROPERTY, "java.logging");
        try {
            assertEquals("java.logging", Vidocq.applicationModule().getName());
        } finally {
            restore(previous);
        }
    }

    @Test
    @DisplayName("Vidocq.main re-layers the named boot-layer module and runs the application inside the Vauban layer")
    void theRuntimeMainReLayersTheNamedModule() throws Exception {
        var marker = "STARTED-from-the-runtime-main";
        var module = application(marker);
        var run = child(module);
        var output = run.stdout() + run.stderr();
        assertEquals(0, run.exitCode(), output);
        assertTrue(output.contains("Application layer ready: modules [acme.app] (boot-layer detection from acme.app)"),
                "the runtime's main did not re-layer the application: " + output);
        assertTrue(run.stdout().contains(marker + " io.vidocq.vauban.classloader.VaubanClassLoader"),
                "the application did not run inside the Vauban layer: " + run.stdout());
    }

    // ---------------------------------------------------------------- fixtures

    private record Run(int exitCode, String stdout, String stderr) {}

    private static void restore(String previous) {
        if (previous == null) {
            System.clearProperty(VidocqAppLayer.APP_MODULE_PROPERTY);
        } else {
            System.setProperty(VidocqAppLayer.APP_MODULE_PROPERTY, previous);
        }
    }

    /**
     * An exploded named module {@code acme.app} whose {@code acme.app.Main} prints the marker and the class of its
     * own defining loader, so the test sees which layer it ran in. Emitted rather than compiled, as in
     * {@link VidocqAppLayerMainProcessTest}.
     */
    private Path application(String marker) throws Exception {
        var classes = Files.createDirectories(dir.resolve("acme.app"));
        Files.write(classes.resolve("module-info.class"), ClassFile.of().buildModule(
                ModuleAttribute.of(ModuleDesc.of("acme.app"), mb -> mb.requires(
                        ModuleRequireInfo.of(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null)))));
        var mainClass = ClassDesc.of("acme.app.Main");
        var printStream = ClassDesc.of("java.io.PrintStream");
        var classDesc = ConstantDescs.CD_Class;
        var bytes = ClassFile.of().build(mainClass, cb -> {
            cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            cb.withMethodBody("main", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String.arrayType()),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                    code -> code.getstatic(ClassDesc.of("java.lang.System"), "out", printStream)
                            .ldc(marker + " ")
                            .ldc(mainClass)
                            .invokevirtual(classDesc, "getClassLoader",
                                    MethodTypeDesc.of(ClassDesc.of("java.lang.ClassLoader")))
                            .invokevirtual(ConstantDescs.CD_Object, "getClass", MethodTypeDesc.of(classDesc))
                            .invokevirtual(classDesc, "getName", MethodTypeDesc.of(ConstantDescs.CD_String))
                            .invokevirtual(ConstantDescs.CD_String, "concat",
                                    MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String))
                            .invokevirtual(printStream, "println",
                                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String))
                            .return_());
        });
        var target = classes.resolve("acme/app/Main.class");
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
        return classes;
    }

    /**
     * Runs {@code Vidocq.main} with {@code acme.app} resolved into the boot layer, as a jlink image without an
     * application main class launches it. {@code vidocq.app.main} lets the JVM end once the application ran.
     */
    private Run child(Path module) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("--module-path");
        command.add(module.toString());
        command.add("--add-modules");
        command.add("acme.app");
        command.add("-D" + VidocqAppLayer.APP_MODULE_PROPERTY + "=acme.app");
        command.add("-Dvidocq.app.main=acme.app.Main");
        command.add("-Dvidocq.banner.mode=off");
        command.add("-cp");
        command.add(childClassPath());
        command.add(Vidocq.class.getName());
        var builder = new ProcessBuilder(command);
        var out = dir.resolve("stdout.txt");
        var err = dir.resolve("stderr.txt");
        builder.redirectOutput(out.toFile()).redirectError(err.toFile());
        var process = builder.start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("child JVM did not finish within 60 s");
        }
        return new Run(process.exitValue(), Files.readString(out, StandardCharsets.UTF_8),
                Files.readString(err, StandardCharsets.UTF_8));
    }

    private static String childClassPath() {
        Path basedir = Path.of(System.getProperty("basedir", System.getProperty("user.dir")));
        Set<String> entries = new LinkedHashSet<>();
        entries.add(basedir.resolve("target/test-classes").toString());
        entries.add(basedir.resolve("target/classes").toString());
        for (String property : List.of("jdk.module.path", "java.class.path")) {
            String value = System.getProperty(property);
            if (value != null && !value.isBlank()) {
                entries.addAll(List.of(value.split(Pattern.quote(File.pathSeparator))));
            }
        }
        return String.join(File.pathSeparator, entries);
    }
}
