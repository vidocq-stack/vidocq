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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The application main of a NAMED module, run the way {@code vidocq:run} and {@code vidocq:dev}
 * run it: a real JVM, the application handed over as {@code -Dvidocq.app.path} so the Vauban
 * loader defines it in the application layer, and {@code -Dvidocq.app.main} naming the class.
 *
 * <p>This is where a main that is not {@code public} needs more than an export: {@code
 * setAccessible} on a member of a named module requires its package to be OPEN, which
 * {@code VidocqAppLayer.openToRuntime} asks the layer controller for. Every other test of the
 * selection runs on the class path, in the unnamed module, which is open to everyone — remove
 * {@code openToRuntime} and they all stay green, while this one fails (vidocq#88).
 */
@DisplayName("vidocq#88 — a non-public main in a named module, in a real JVM")
class VidocqAppLayerMainProcessTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("a package-private static main(String[]) of a named module starts the application")
    void packagePrivateStaticMainInANamedModule() throws Exception {
        var marker = "STARTED-package-private-static";
        var application = application(marker, 0, false);

        var run = child(application);

        assertEquals(0, run.exitCode(), run.stderr() + run.stdout());
        assertTrue(run.stdout().contains(marker), "the application main did not run: " + run.stdout());
    }

    @Test
    @DisplayName("a package-private instance main(String[]) of a named module starts the application")
    void packagePrivateInstanceMainInANamedModule() throws Exception {
        var marker = "STARTED-package-private-instance";
        var application = application(marker, 0, true);

        var run = child(application);

        assertEquals(0, run.exitCode(), run.stderr() + run.stdout());
        assertTrue(run.stdout().contains(marker), "the application main did not run: " + run.stdout());
    }

    @Test
    @DisplayName("a main that cannot be selected fails the JVM with the reason, not with a stack of reflection")
    void aMainThatCannotBeSelectedSaysWhy() throws Exception {
        var application = application("NEVER", java.lang.classfile.ClassFile.ACC_PRIVATE, false);

        var run = child(application);

        assertTrue(run.exitCode() != 0, "the JVM must fail when no main can be selected");
        var output = run.stdout() + run.stderr();
        assertTrue(output.contains("acme.app.Main#main(String[]) is private"), output);
        assertTrue(output.contains("Java 25 accepts main(String[]) or main()"), output);
    }

    // ---------------------------------------------------------------- fixtures

    private record Run(int exitCode, String stdout, String stderr) {}

    /**
     * An exploded named module {@code acme.app} whose {@code acme.app.Main} prints the marker.
     *
     * <p>The class is emitted rather than compiled: the test module does not read {@code
     * java.compiler}, and a fixture compiled with the test sources would also sit on the child's
     * class path, where the main would be reachable without the module ever being opened — the
     * test would then pass for the wrong reason.
     */
    private Path application(String marker, int accessFlags, boolean instanceMethod) throws Exception {
        var classes = Files.createDirectories(dir.resolve("classes-" + marker));
        Files.write(classes.resolve("module-info.class"), ClassFile.of().buildModule(
                ModuleAttribute.of(ModuleDesc.of("acme.app"), mb -> mb.requires(
                        ModuleRequireInfo.of(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null)))));

        var mainClass = ClassDesc.of("acme.app.Main");
        var printStream = ClassDesc.of("java.io.PrintStream");
        var bytes = ClassFile.of().build(mainClass, cb -> {
            cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            // A no-argument constructor: an instance main is run on an instance the launcher
            // builds with it, and the emitted class gets no default constructor of its own.
            cb.withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC,
                    code -> code.aload(0)
                            .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                            .return_());
            var flags = instanceMethod ? accessFlags : accessFlags | ClassFile.ACC_STATIC;
            cb.withMethodBody("main", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String.arrayType()),
                    flags,
                    code -> code.getstatic(ClassDesc.of("java.lang.System"), "out", printStream)
                            .ldc(marker)
                            .invokevirtual(printStream, "println",
                                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String))
                            .return_());
        });
        var target = classes.resolve("acme/app/Main.class");
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
        return classes;
    }

    /** Runs {@code Vidocq.main} with the application handed over as a path, as the plugin does. */
    private Run child(Path applicationPath) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Dvidocq.app.path=" + applicationPath);
        command.add("-Dvidocq.app.main=acme.app.Main");
        command.add("-Dvidocq.banner.mode=off");
        command.add("-cp");
        command.add(childClassPath());
        command.add(Vidocq.class.getName());

        var builder = new ProcessBuilder(command);
        var out = dir.resolve("stdout-" + System.nanoTime() + ".txt");
        var err = dir.resolve("stderr-" + System.nanoTime() + ".txt");
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
