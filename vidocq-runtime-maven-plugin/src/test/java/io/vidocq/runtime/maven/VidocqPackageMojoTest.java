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

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleRequireInfo;
import java.lang.constant.ClassDesc;
import java.lang.constant.ModuleDesc;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the optionality of {@code <jvmArgs>} in the {@code package} goal.
 *
 * <p>{@code @Parameter(defaultValue = "")} does not make Maven inject an empty string: an omitted
 * {@code <jvmArgs>} leaves the field {@code null}, and the launcher generators used to dereference
 * it unconditionally. Reported downstream as a {@code NullPointerException} on the very first
 * project a newcomer writes, whose natural configuration is {@code mainClass} plus
 * {@code scriptName} — see BUG-20260710-01 and Vidocq/vidocq#40.
 */
class VidocqPackageMojoTest {

    @Test
    void launchers_areGeneratedWhenJvmArgsIsNotConfigured(@TempDir Path binDir) throws Exception {
        VidocqPackageMojo mojo = mojoWith(null);

        mojo.generateShScript(binDir);
        mojo.generateCmdScript(binDir);

        String sh = Files.readString(binDir.resolve("app.sh"));
        assertTrue(sh.contains("exec java \\"),
                "an absent <jvmArgs> must leave the java invocation bare: " + sh);
        assertTrue(sh.contains("--module io.repro.app/io.repro.app.Main"), sh);

        String cmd = Files.readString(binDir.resolve("app.cmd"));
        assertTrue(cmd.contains("java ^"), "an absent <jvmArgs> must leave the java invocation bare: " + cmd);
    }

    @Test
    void launchers_carryConfiguredJvmArgs(@TempDir Path binDir) throws Exception {
        VidocqPackageMojo mojo = mojoWith("-Dfile.encoding=UTF-8 -Xmx512m");

        mojo.generateShScript(binDir);
        mojo.generateCmdScript(binDir);

        assertTrue(Files.readString(binDir.resolve("app.sh"))
                .contains("exec java -Dfile.encoding=UTF-8 -Xmx512m \\"));
        assertTrue(Files.readString(binDir.resolve("app.cmd"))
                .contains("java -Dfile.encoding=UTF-8 -Xmx512m ^"));
    }

    @Test
    void blankJvmArgsAreTreatedAsAbsent(@TempDir Path binDir) throws Exception {
        VidocqPackageMojo mojo = mojoWith("   ");

        mojo.generateShScript(binDir);

        String sh = Files.readString(binDir.resolve("app.sh"));
        assertTrue(sh.contains("exec java \\"), sh);
        assertFalse(sh.contains("java    "), "whitespace-only jvmArgs must not leak into the launcher: " + sh);
    }

    @Test
    void shLauncherIsExecutable(@TempDir Path binDir) throws Exception {
        mojoWith(null).generateShScript(binDir);

        assertTrue(Files.isExecutable(binDir.resolve("app.sh")));
        assertEquals("app.sh", binDir.resolve("app.sh").getFileName().toString());
    }

    /**
     * The layer launchers name the application main class through the rule shared with
     * {@code vidocq:idea} ({@link ApplicationMainClass}): a legacy {@code module/class} value keeps its
     * class, and the runtime's own main class is no application main class at all.
     */
    @Test
    void layerLaunchers_nameTheApplicationMainClassThroughTheSharedRule(@TempDir Path binDir) throws Exception {
        VidocqPackageMojo mojo = mojoWith(null);
        setBoolean(mojo, "layerMode", true);

        mojo.generateShScript(binDir);
        assertTrue(Files.readString(binDir.resolve("app.sh")).contains("-Dvidocq.app.main=io.repro.app.Main \\"));

        set(mojo, "mainClass", "io.vidocq.runtime.core.Vidocq");
        mojo.generateShScript(binDir);
        assertFalse(Files.readString(binDir.resolve("app.sh")).contains("-Dvidocq.app.main="),
                "the runtime's own main class must not be passed as the application main class");
    }

    /** The legacy layout (vidocq.package.layer=false) launches {@code <module>/<class>} (BUG-20260710-02, #198). */
    @Test
    void legacyLaunchers_runTheRuntimeMainThroughItsModuleByDefault(@TempDir Path binDir) throws Exception {
        VidocqPackageMojo mojo = mojoWith(null);
        set(mojo, "mainClass", "io.vidocq.runtime.core.Vidocq");

        mojo.generateShScript(binDir);
        mojo.generateCmdScript(binDir);

        assertTrue(Files.readString(binDir.resolve("app.sh"))
                .contains("--module io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq \\"));
        assertTrue(Files.readString(binDir.resolve("app.cmd"))
                .contains("--module io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq ^"));
    }

    @Test
    void legacyLaunchers_prefixAPlainMainClassWithTheMainModule(@TempDir Path binDir) throws Exception {
        VidocqPackageMojo mojo = mojoWith(null);
        set(mojo, "mainClass", "io.repro.app.Main");
        set(mojo, "mainModule", "io.repro.app");

        mojo.generateShScript(binDir);
        mojo.generateCmdScript(binDir);

        assertTrue(Files.readString(binDir.resolve("app.sh")).contains("--module io.repro.app/io.repro.app.Main \\"));
        assertTrue(Files.readString(binDir.resolve("app.cmd")).contains("--module io.repro.app/io.repro.app.Main ^"));
    }

    @Test
    void legacyLaunchers_refuseAPlainMainClassWithoutAModule(@TempDir Path binDir) throws Exception {
        VidocqPackageMojo mojo = mojoWith(null);
        set(mojo, "mainClass", "io.repro.app.Main");

        var failure = assertThrows(MojoExecutionException.class, () -> mojo.generateShScript(binDir));
        assertTrue(failure.getMessage().contains("vidocq.mainModule"), failure.getMessage());
    }

    /** {@code vidocq.app.layer=all}: the parent layer stays in {@code lib/}, the rest ships in {@code app/} (#247). */
    @Test
    void allPutsTheLibrariesInApp(@TempDir Path dir) throws Exception {
        VidocqPackageMojo mojo = packageMojo(dir, "all", null);

        mojo.execute();

        Path dist = dir.resolve("target/app-1.0");
        assertTrue(Files.exists(dist.resolve("lib/vidocq-runtime-core.jar")));
        assertFalse(Files.exists(dist.resolve("lib/flow-server.jar")));
        assertTrue(Files.exists(dist.resolve("app/flow-server.jar")));
        assertTrue(Files.exists(dist.resolve("app/app-1.0.jar")), "the application jar rides in app/");
        assertFalse(Files.exists(dist.resolve("lib/app-1.0.jar")));
    }

    @Test
    void classesKeepsTodaysLayout(@TempDir Path dir) throws Exception {
        VidocqPackageMojo mojo = packageMojo(dir, null, null);

        mojo.execute();

        Path dist = dir.resolve("target/app-1.0");
        assertTrue(Files.exists(dist.resolve("lib/vidocq-runtime-core.jar")));
        assertTrue(Files.exists(dist.resolve("lib/flow-server.jar")));
        assertFalse(Files.exists(dist.resolve("app/flow-server.jar")));
    }

    /**
     * Under {@code all} the scripts hand {@code app/} to the runtime through {@code -Dvidocq.app.path} even when a
     * trampoline main is known: the trampoline's module path would keep the automatic libraries in the boot layer.
     */
    @Test
    void allLaunchesThroughTheAppPathEvenWithATrampolineMain(@TempDir Path binDir) throws Exception {
        VidocqPackageMojo mojo = trampolineMojo("all");

        mojo.generateShScript(binDir);
        mojo.generateCmdScript(binDir);

        String sh = Files.readString(binDir.resolve("app.sh"));
        assertTrue(sh.contains("--module-path \"$BASEDIR/lib\" \\"), sh);
        assertTrue(sh.contains("-Dvidocq.app.path=\"$BASEDIR/app\" \\"), sh);
        assertTrue(sh.contains("-Dvidocq.app.main=io.repro.app.Main \\"), sh);
        assertTrue(sh.contains("-Dvidocq.app.layer=all \\"), sh);
        assertTrue(sh.contains("--module io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq \\"), sh);
        assertFalse(sh.contains("$BASEDIR/lib:$BASEDIR/app"), sh);
        String cmd = Files.readString(binDir.resolve("app.cmd"));
        assertTrue(cmd.contains("--module-path \"%BASEDIR%\\lib\" ^"), cmd);
        assertTrue(cmd.contains("-Dvidocq.app.path=\"%BASEDIR%\\app\" ^"), cmd);
        assertTrue(cmd.contains("-Dvidocq.app.main=io.repro.app.Main ^"), cmd);
        assertTrue(cmd.contains("-Dvidocq.app.layer=all ^"), cmd);
        assertFalse(cmd.contains("%BASEDIR%\\lib;%BASEDIR%\\app"), cmd);
    }

    /** {@code classes} keeps today's trampoline scripts byte for byte, without any {@code -Dvidocq.app.layer} line. */
    @Test
    void classesKeepsTheTrampolineScripts(@TempDir Path binDir) throws Exception {
        VidocqPackageMojo mojo = trampolineMojo("classes");

        mojo.generateShScript(binDir);
        mojo.generateCmdScript(binDir);

        assertEquals("""
                #!/bin/sh
                BASEDIR=$(cd "$(dirname "$0")/.." && pwd)
                exec java \\
                  --module-path "$BASEDIR/lib:$BASEDIR/app" \\
                  --add-modules ALL-MODULE-PATH \\
                  --module io.repro.app/io.repro.app.Main \\
                  "$@"
                """, Files.readString(binDir.resolve("app.sh")));
        assertEquals("""
                @echo off
                set BASEDIR=%~dp0..
                java ^
                  --module-path "%BASEDIR%\\lib;%BASEDIR%\\app" ^
                  --add-modules ALL-MODULE-PATH ^
                  --module io.repro.app/io.repro.app.Main ^
                  %*
                """, Files.readString(binDir.resolve("app.cmd")));
    }

    /** {@code classes} adds no {@code -Dvidocq.app.layer} line to the runtime-main scripts either. */
    @Test
    void classesAddsNoModeLineToTheAppPathScripts(@TempDir Path binDir) throws Exception {
        VidocqPackageMojo mojo = mojoWith(null);
        setBoolean(mojo, "layerMode", true);
        set(mojo, "appLayer", "classes");

        mojo.generateShScript(binDir);
        mojo.generateCmdScript(binDir);

        assertFalse(Files.readString(binDir.resolve("app.sh")).contains("-Dvidocq.app.layer"));
        assertFalse(Files.readString(binDir.resolve("app.cmd")).contains("-Dvidocq.app.layer"));
    }

    /**
     * The package enriches a jar whose classes vidocq:generate parked (no {@code --patch-module} at launch), so under
     * {@code all} it moves to {@code app/} like any other library, without a pin.
     */
    @Test
    void allMovesAJarWithParkedClassesToApp(@TempDir Path dir) throws Exception {
        VidocqPackageMojo mojo = packageMojo(dir, "all", null);
        Path parked = dir.resolve("target/vidocq-patches/flow-server/flow/server/Generated.class");
        Files.createDirectories(parked.getParent());
        Files.write(parked, ClassFile.of().build(ClassDesc.of("flow.server.Generated"),
                cb -> cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)));

        mojo.execute();

        Path dist = dir.resolve("target/app-1.0");
        assertTrue(Files.exists(dist.resolve("app/flow-server.jar")), "the enriched copy rides in app/");
        assertFalse(Files.exists(dist.resolve("lib/flow-server.jar")));
        String sh = Files.readString(dist.resolve("bin/app.sh"));
        assertFalse(sh.contains("vidocq.app.layer.pinned"), sh);
    }

    private static VidocqPackageMojo trampolineMojo(String appLayer) throws Exception {
        VidocqPackageMojo mojo = mojoWith(null);
        setBoolean(mojo, "layerMode", true);
        set(mojo, "mainClass", "io.repro.app.Main");
        set(mojo, "mainModule", "io.repro.app");
        set(mojo, "appLayer", appLayer);
        return mojo;
    }

    @Test
    void pinnedListGoesToTheScripts(@TempDir Path dir) throws Exception {
        VidocqPackageMojo mojo = packageMojo(dir, "all", "flow.server");

        mojo.execute();

        Path bin = dir.resolve("target/app-1.0/bin");
        String expected = "\"-Dvidocq.app.layer.pinned=flow.server=vidocq.app.layer.keep\"";
        assertTrue(Files.readString(bin.resolve("app.sh")).contains(expected));
        assertTrue(Files.readString(bin.resolve("app.cmd")).contains(expected));
        assertTrue(Files.exists(dir.resolve("target/app-1.0/lib/flow-server.jar")), "a pin keeps the jar in lib/");
    }

    /** The real dependencies of a kept automatic module follow it into {@code lib/} (mirrors ApplicationLaunch#layered). */
    @Test
    void allKeepsTheDependenciesOfAKeptAutomaticModuleInLib(@TempDir Path dir) throws Exception {
        Path extension = moduleJar(dir, "liquibase-extension.jar",
                "io.vidocq.runtime.extensions.essentials.migration.liquibase", true, "liquibase.core");
        Path liquibase = moduleJar(dir, "liquibase-core.jar", "liquibase.core", false);
        Path opencsv = moduleJar(dir, "opencsv.jar", "opencsv", false);
        Path flow = moduleJar(dir, "flow-server.jar", "flow.server", false);
        Artifact extensionArtifact = artifact("vidocq-runtime-liquibase-extension", extension);
        Artifact liquibaseArtifact = artifact("liquibase-core", liquibase);
        Artifact opencsvArtifact = artifact("opencsv", opencsv);
        Artifact flowArtifact = artifact("flow-server", flow);
        extensionArtifact.setDependencyTrail(List.of(PROJECT, coordinates(extensionArtifact)));
        liquibaseArtifact.setDependencyTrail(
                List.of(PROJECT, coordinates(extensionArtifact), coordinates(liquibaseArtifact)));
        opencsvArtifact.setDependencyTrail(List.of(PROJECT, coordinates(extensionArtifact),
                coordinates(liquibaseArtifact), coordinates(opencsvArtifact)));
        flowArtifact.setDependencyTrail(List.of(PROJECT, coordinates(flowArtifact)));
        VidocqPackageMojo mojo = packageMojo(dir, "all", null,
                extensionArtifact, liquibaseArtifact, opencsvArtifact, flowArtifact);

        mojo.execute();

        Path dist = dir.resolve("target/app-1.0");
        assertTrue(Files.exists(dist.resolve("lib/liquibase-core.jar")));
        assertTrue(Files.exists(dist.resolve("lib/opencsv.jar")), "a dependency of a kept automatic module stays");
        assertTrue(Files.exists(dist.resolve("app/flow-server.jar")));
    }

    @Test
    void allFailsOnADependencyWithoutAModule(@TempDir Path dir) throws Exception {
        Path bad = dir.resolve("1-bad.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(bad))) {
            out.putNextEntry(new ZipEntry("x/Y.class"));
            out.write(new byte[] {(byte) 0xCA});
            out.closeEntry();
        }
        VidocqPackageMojo mojo = packageMojo(dir, "all", null, artifact("bad", bad));

        var e = assertThrows(MojoExecutionException.class, mojo::execute);
        assertTrue(e.getMessage().contains("vidocq.app.layer=all reads the module of every dependency: "), e.getMessage());
        assertTrue(e.getMessage().contains("1-bad.jar"), e.getMessage());
        assertFalse(e.getMessage().contains("vidocq.app.layer.keep"), e.getMessage());
    }

    private static final String PROJECT = "com.example:app:jar:1.0";

    private static String coordinates(Artifact a) {
        return a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getType() + ":" + a.getVersion();
    }

    /** A packaging mojo over a project with the runtime core (explicit module) and {@code flow-server} (automatic). */
    private static VidocqPackageMojo packageMojo(Path dir, String appLayer, String keep) throws Exception {
        return packageMojo(dir, appLayer, keep,
                artifact("vidocq-runtime-core", moduleJar(dir, "vidocq-runtime-core.jar", "io.vidocq.runtime.core", true)),
                artifact("flow-server", moduleJar(dir, "flow-server.jar", "flow.server", false)));
    }

    private static VidocqPackageMojo packageMojo(Path dir, String appLayer, String keep, Artifact... artifacts)
            throws Exception {
        Model model = new Model();
        model.setGroupId("com.example");
        model.setArtifactId("app");
        model.setVersion("1.0");
        model.setBuild(new Build());
        MavenProject project = new MavenProject(model);
        Artifact main = new DefaultArtifact("com.example", "app", "1.0", "runtime", "jar", "",
                new DefaultArtifactHandler("jar"));
        main.setFile(moduleJar(dir, "app-1.0.jar", "io.repro.app", true).toFile());
        project.setArtifact(main);
        project.setArtifacts(new LinkedHashSet<>(List.of(artifacts)));
        VidocqPackageMojo mojo = mojoWith(null);
        set(mojo, "distName", "app-1.0");
        set(mojo, "appLayer", appLayer);
        set(mojo, "appLayerKeep", keep);
        setBoolean(mojo, "layerMode", true);
        setField(mojo, "project", project);
        setField(mojo, "buildDir", dir.resolve("target").toFile());
        setField(mojo, "projectHelper", Proxy.newProxyInstance(VidocqPackageMojoTest.class.getClassLoader(),
                new Class<?>[] {MavenProjectHelper.class}, (proxy, method, args) -> null));
        Files.createDirectories(dir.resolve("target"));
        return mojo;
    }

    private static Artifact artifact(String artifactId, Path file) {
        Artifact artifact = new DefaultArtifact("io.vidocq.runtime", artifactId, "0.4.0-SNAPSHOT", "runtime", "jar",
                "", new DefaultArtifactHandler("jar"));
        artifact.setFile(file.toFile());
        return artifact;
    }

    private static void setField(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** A jar with an explicit descriptor requiring {@code requires}, or an automatic one when {@code explicit} is false. */
    private static Path moduleJar(Path dir, String fileName, String module, boolean explicit, String... requires)
            throws Exception {
        Path jar = dir.resolve(fileName);
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (!explicit) {
            manifest.getMainAttributes().putValue("Automatic-Module-Name", module);
        }
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            if (explicit) {
                out.putNextEntry(new ZipEntry("module-info.class"));
                out.write(ClassFile.of().buildModule(ModuleAttribute.of(ModuleDesc.of(module), mb -> {
                    mb.requires(ModuleRequireInfo.of(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null));
                    for (String r : requires) {
                        mb.requires(ModuleRequireInfo.of(ModuleDesc.of(r), 0, null));
                    }
                })));
                out.closeEntry();
            }
            String pkg = module.replace('.', '/');
            out.putNextEntry(new ZipEntry(pkg + "/Marker.class"));
            out.write(ClassFile.of().build(ClassDesc.of(module + ".Marker"),
                    cb -> cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)));
            out.closeEntry();
        }
        return jar;
    }

    private static VidocqPackageMojo mojoWith(String jvmArgs) throws Exception {
        VidocqPackageMojo mojo = new VidocqPackageMojo();
        set(mojo, "scriptName", "app");
        set(mojo, "mainClass", "io.repro.app/io.repro.app.Main");
        set(mojo, "jvmArgs", jvmArgs);
        return mojo;
    }

    private static void set(VidocqPackageMojo mojo, String field, String value) throws Exception {
        Field f = VidocqPackageMojo.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(mojo, value);
    }

    private static void setBoolean(VidocqPackageMojo mojo, String field, boolean value) throws Exception {
        Field f = VidocqPackageMojo.class.getDeclaredField(field);
        f.setAccessible(true);
        f.setBoolean(mojo, value);
    }
}
