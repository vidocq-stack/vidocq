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
package io.vidocq.runtime.dev.agent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.constant.ModuleDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The agent in a JVM of its own, started the way {@code vidocq:dev} starts the child: on the module path, resolved,
 * and as {@code -javaagent}. The child redefines the classes of a module it loaded in a child layer.
 */
class HotSwapProcessTest {

    private static final ClassDesc OTHER = ClassDesc.of("p.Other");
    private static final ClassDesc UNLINKABLE = ClassDesc.of("p.Unlinkable");
    private static final MethodTypeDesc RETURNS_STRING = MethodTypeDesc.of(ConstantDescs.CD_String);
    private static final MethodTypeDesc RETURNS_GONE_IMPL = MethodTypeDesc.of(ClassDesc.of("p.GoneImpl"));

    @TempDir
    static Path dir;

    private static List<String> lines;
    private static String output;

    @BeforeAll
    static void runTheChild() throws Exception {
        Path v1 = greeter("v1", "v1", false);
        Path v2 = greeter("v2", "v2", false);
        Path refused = greeter("refused", "v3", true);
        Path agent = agentJar();

        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(coverageAgent());
        command.addAll(List.of(
                "--module-path", agent.toString(), "--add-modules", "io.vidocq.runtime.dev.agent",
                "-javaagent:" + agent,
                "-cp", basedir().resolve("target/test-classes").toString(),
                "io.vidocq.runtime.dev.agent.child.HotSwapChildMain",
                v1.toString(), v2.toString(), refused.toString()));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        for (String inherited : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
            builder.environment().remove(inherited);
        }
        Path out = dir.resolve("child.txt");
        builder.redirectOutput(out.toFile());
        Process process = builder.start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("the child JVM did not finish within 60 s");
        }
        output = Files.readString(out, StandardCharsets.UTF_8);
        lines = output.lines().toList();
        assertEquals(0, process.exitValue(), output);
    }

    /**
     * The coverage agent of this test JVM, when the build runs one: the child runs the agent's code, and that run
     * counts in the module's coverage too. Surefire may put the options in an {@code @} argument file. The agent
     * instruments this module's package only: it does not instrument a redefinition, so a redefined class it had
     * instrumented would lose {@code $jacocoInit}, which the JVM refuses.
     */
    private static List<String> coverageAgent() {
        String[] arguments = ProcessHandle.current().info().arguments().orElse(new String[0]);
        return Stream.of(arguments).flatMap(HotSwapProcessTest::expanded)
                .filter(argument -> argument.startsWith("-javaagent:") && argument.contains("jacoco"))
                .map(argument -> argument + (argument.contains("=") ? "," : "=")
                        + "includes=" + HotSwap.class.getPackageName() + ".*")
                .toList();
    }

    private static Stream<String> expanded(String argument) {
        if (!argument.startsWith("@")) {
            return Stream.of(argument);
        }
        try {
            return Files.readAllLines(Path.of(argument.substring(1))).stream()
                    .map(line -> line.strip().replace("\"", ""));
        } catch (IOException _) {
            return Stream.empty();
        }
    }

    @Test
    void premainRunsInTheNamedModule() {
        assertEquals("INSTALLED true IN io.vidocq.runtime.dev.agent", line("INSTALLED "));
    }

    @Test
    void aBodyChangeOfAChildLayerClassIsLive() {
        assertEquals("BEFORE v1", line("BEFORE "));
        assertEquals("AFTER v2", line("AFTER "));
    }

    @Test
    void anAddedMethodIsRefusedAndNothingChanged() {
        String refused = line("REFUSED ");
        assertTrue(refused.contains("add a method"), "the JVM's reason: " + refused);
        assertEquals("AFTER-REFUSAL v1", line("AFTER-REFUSAL "), "the body change of p.Greeter was not applied");
    }

    @Test
    void aClassNotLoadedIsReportedNotLoaded() {
        assertEquals("REDEFINED [p.Greeter, p.Other] NOT-LOADED [p.NeverLoaded]", line("REDEFINED "));
    }

    /**
     * The JVM links a loaded class before it redefines it. When it cannot, it answers an {@code InternalError}, which
     * is no {@code LinkageError}: a refusal like the others, not an error that ends the caller (BUG-20261117-09).
     */
    @Test
    void aLoadedClassTheJvmCannotLinkIsRefused() {
        String refused = line("UNLINKABLE ");
        assertTrue(refused.startsWith("UNLINKABLE refused: "), "a refusal: " + refused);
        assertTrue(refused.contains("invalid class"), "the JVM's reason: " + refused);
    }

    private static String line(String prefix) {
        return lines.stream().filter(l -> l.startsWith(prefix)).findFirst()
                .orElseGet(() -> fail("no line " + prefix + "in:\n" + output));
    }

    /**
     * The module {@code greeter}: {@code p.Greeter}, a {@code Supplier} service whose {@code get()} calls
     * {@code p.Other.name()} and answers {@code text}; {@code p.Other}, which gains a method when {@code extraMethod};
     * {@code p.NeverLoaded}, which nothing loads; {@code p.Unlinkable}, which loads but cannot be linked: to verify
     * that its {@code widen()} may return a {@code p.GoneImpl} as a {@code p.Gone}, the JVM needs {@code p.Gone},
     * which no archive holds.
     */
    private static Path greeter(String name, String text, boolean extraMethod) throws IOException {
        Path root = Files.createDirectories(dir.resolve(name));
        Files.write(root.resolve("module-info.class"), ClassFile.of().buildModule(ModuleAttribute.of(
                ModuleDesc.of("greeter"), module -> module
                        .requires(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null)
                        .provides(ClassDesc.of("java.util.function.Supplier"), ClassDesc.of("p.Greeter")))));
        Path p = Files.createDirectories(root.resolve("p"));
        Files.write(p.resolve("Greeter.class"), ClassFile.of().build(ClassDesc.of("p.Greeter"), type -> {
            type.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                    .withInterfaceSymbols(ClassDesc.of("java.util.function.Supplier"));
            constructor(type);
            type.withMethodBody("get", MethodTypeDesc.of(ConstantDescs.CD_Object), ClassFile.ACC_PUBLIC, code -> code
                    .invokestatic(OTHER, "name", RETURNS_STRING).pop().ldc(text).areturn());
        }));
        Files.write(p.resolve("Other.class"), ClassFile.of().build(OTHER, type -> {
            type.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            constructor(type);
            type.withMethodBody("name", RETURNS_STRING, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                    code -> code.ldc("other").areturn());
            if (extraMethod) {
                type.withMethodBody("extra", RETURNS_STRING, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                        code -> code.ldc("extra").areturn());
            }
        }));
        Files.write(p.resolve("NeverLoaded.class"), ClassFile.of().build(ClassDesc.of("p.NeverLoaded"), type -> {
            type.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            constructor(type);
        }));
        Files.write(p.resolve("Unlinkable.class"), ClassFile.of().build(UNLINKABLE, type -> {
            type.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            constructor(type);
            type.withMethodBody("narrow", RETURNS_GONE_IMPL, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                    code -> code.aconst_null().areturn());
            type.withMethodBody("widen", MethodTypeDesc.of(ClassDesc.of("p.Gone")),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                    code -> code.invokestatic(UNLINKABLE, "narrow", RETURNS_GONE_IMPL).areturn());
        }));
        return root;
    }

    private static void constructor(ClassBuilder type) {
        type.withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, code -> code
                .aload(0).invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                .return_());
    }

    /** This module's classes in a jar whose manifest is the one the build writes. */
    private static Path agentJar() throws IOException {
        Path classes = basedir().resolve("target/classes");
        Manifest manifest = new Manifest();
        Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.put(new Attributes.Name("Premain-Class"), HotSwap.class.getName());
        main.put(new Attributes.Name("Can-Redefine-Classes"), "true");
        Path jar = dir.resolve("vidocq-runtime-dev-agent.jar");
        try (OutputStream file = Files.newOutputStream(jar); var out = new JarOutputStream(file, manifest);
             Stream<Path> files = Files.walk(classes)) {
            for (Path entry : files.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(entry).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(entry));
                out.closeEntry();
            }
        }
        return jar;
    }

    private static Path basedir() {
        return Path.of(System.getProperty("basedir", System.getProperty("user.dir")));
    }
}
