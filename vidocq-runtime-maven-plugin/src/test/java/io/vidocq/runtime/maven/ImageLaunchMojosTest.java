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

import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.spi.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code vidocq:jlink} and {@code vidocq:jpackage} turn {@code vidocq.mainModule} / {@code vidocq.mainClass} into
 * an {@link ImageLaunch} before any work, and start the application the way it says.
 */
class ImageLaunchMojosTest {

    private static final String RUNTIME_MAIN = "io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq";
    private static final String NO_LAYER = "-Dvidocq.app.layer.off=true";
    /** The thread factory of the common pool every launch names (BUG-20261118-93). */
    private static final String POOL = CommonPoolOption.OPTION;
    /** The options of an image, in one {@code --add-options}: no layer, then the thread factory of the common pool. */
    private static final String NO_LAYER_OPTIONS = NO_LAYER + " " + POOL;

    @TempDir
    Path dir;

    /** The info lines and the warnings a goal logs. */
    static final class Infos extends SystemStreamLog {
        final List<String> lines = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();

        @Override
        public void info(CharSequence content) {
            lines.add(content.toString());
        }

        @Override
        public void warn(CharSequence content) {
            warnings.add(content.toString());
        }
    }

    // ---------------------------------------------------------------- vidocq:jlink

    @Test
    void jlinkWithoutAMainModuleFailsBeforeStagingAnything() throws Exception {
        VidocqJlinkMojo mojo = jlink(null, null, new Infos());

        var failure = assertThrows(MojoExecutionException.class, mojo::execute);

        assertTrue(failure.getMessage().contains("vidocq.mainModule"), failure.getMessage());
        assertTrue(Files.notExists(dir.resolve("jlink-mods")), "nothing is staged before the launch is known");
    }

    @Test
    void jlinkReportsAFailedStaging() throws Exception {
        VidocqJlinkMojo mojo = jlink("acme.app", null, new Infos());

        var failure = assertThrows(MojoExecutionException.class, mojo::execute);

        assertEquals("Failed to create jlink image", failure.getMessage());
    }

    /**
     * The image has one layer: every linked module is rooted, a resource-only one too (BUG-20261114-05), and the
     * runtime builds no application layer (Vidocq/vidocq#580). Logged, since this line is what to read when a module
     * is missing at start.
     */
    @Test
    void theBakedLaunchRootsEveryModuleAndIsLogged() throws Exception {
        var log = new Infos();
        VidocqJlinkMojo mojo = jlink("acme.app", null, log);

        ImageLaunch launch = mojo.bakedLaunch("acme.app,aura.theme,flow.push,io.vidocq.foy.core,java.base,java.sql");

        assertEquals(List.of("--add-modules=acme.app,aura.theme,flow.push,io.vidocq.foy.core,java.sql", NO_LAYER, POOL,
                "-Dvidocq.app.module=acme.app"), launch.vmOptions());
        assertTrue(log.lines.contains("Vidocq jlink — main: " + RUNTIME_MAIN
                + " --add-modules=acme.app,aura.theme,flow.push,io.vidocq.foy.core,java.sql " + NO_LAYER_OPTIONS
                + " -Dvidocq.app.module=acme.app"), log.lines.toString());
        assertEquals(List.of(), log.warnings);
    }

    /**
     * An image has one layer (Vidocq/vidocq#580), and the settings of the application layer do not apply to it:
     * {@code vidocq:jlink} and {@code vidocq:jpackage} declare none. They took {@code vidocq.app.layer} and
     * {@code vidocq.app.layer.keep} only to say so in an information line (Vidocq/vidocq#663).
     */
    @Test
    void theImageGoalsTakeNoSettingOfTheApplicationLayer() throws Exception {
        String xml = Files.readString(Path.of("target", "classes", "META-INF", "maven", "plugin.xml"));
        for (var goal : Map.of("jlink", VidocqJlinkMojo.class, "jpackage", VidocqJpackageMojo.class).entrySet()) {
            for (String setting : List.of("appLayer", "appLayerKeep")) {
                assertThrows(NoSuchFieldException.class, () -> goal.getValue().getDeclaredField(setting),
                        goal.getKey() + " declares " + setting);
            }
            int start = xml.indexOf("<goal>" + goal.getKey() + "</goal>");
            String mojo = xml.substring(start, xml.indexOf("</mojo>", start));
            assertFalse(mojo.contains("appLayer"), goal.getKey() + ": " + mojo);
            assertFalse(mojo.contains("vidocq.app.layer"), goal.getKey() + ": " + mojo);
        }
    }

    /** The jlink run, the recipe of vidocq:docker and both launch shapes carry the same options. */
    @Test
    void theJlinkRunAndTheDockerRecipeCarryTheLaunch() throws Exception {
        for (String mainClass : new String[] {null, "acme.app.App"}) {
            VidocqJlinkMojo mojo = jlink("acme.app", mainClass, new Infos());
            ImageLaunch launch = mojo.bakedLaunch("acme.app,flow.push,java.base");
            String options = mainClass == null
                    ? "--add-options=--add-modules=acme.app,flow.push " + NO_LAYER_OPTIONS + " -Dvidocq.app.module=acme.app"
                    : "--add-options=--add-modules=flow.push " + NO_LAYER_OPTIONS;

            List<String> jlink = mojo.jlinkArgs("acme.app,flow.push,java.base", launch);
            assertTrue(jlink.contains(options), "jlink: " + jlink);
            assertEquals("acme.app,flow.push,java.base", jlink.get(jlink.indexOf("--add-modules") + 1));
            List<String> recipe = mojo.portableJlinkArgs("acme.app,flow.push,java.base", launch);
            assertTrue(recipe.contains(options), "recipe: " + recipe);
            assertTrue(recipe.stream().noneMatch("--release-info"::equals), "recipe: " + recipe);
            PortableImage.write(dir.resolve("build-" + mainClass), recipe, launch.entryPoint(), List.of());
            String file = Files.readString(dir.resolve("build-" + mainClass).resolve(PortableImage.DIRECTORY)
                    .resolve(PortableImage.ARGUMENTS));
            // One argument per line, in double quotes when it holds a space.
            assertTrue(file.lines().anyMatch(("\"" + options + "\"")::equals), "argument file: " + file);
        }
    }

    /**
     * Before jlink runs, a staged module gains the provides its services files declare: the image has no application
     * layer to promote them at start. A provides the module declared itself is kept.
     */
    @Test
    void theStagedModulesReachTheImageWithTheirProvides() throws Exception {
        var log = new Infos();
        VidocqJlinkMojo mojo = jlink("acme.app", null, log);
        stageProvider("acme.app", false);
        stageProvider("acme.lib", true);

        mojo.promoteProvides();

        var stage = java.lang.module.ModuleFinder.of(dir.resolve("jlink-mods"));
        for (String module : new String[] {"acme.app", "acme.lib"}) {
            var provides = stage.find(module).orElseThrow().descriptor().provides();
            assertEquals(1, provides.size(), module);
            assertEquals(List.of(module + ".Tool"), provides.iterator().next().providers(), module);
        }
        assertEquals(List.of("Vidocq jlink — acme.app: provides added to module-info.class ["
                + ToolProvider.class.getName() + "]"), log.lines);
    }

    /**
     * Compiles into the stage a module with a {@link ToolProvider}, {@code <module>.Tool}: declared by a provides of
     * its descriptor, or by a services file alone, as an annotation processor declares what it generates.
     */
    private void stageProvider(String module, boolean declared) throws Exception {
        compile(module, Map.of(
                "module-info.java", "module " + module + " { " + (declared
                        ? "provides java.util.spi.ToolProvider with " + module + ".Tool; " : "") + "}\n",
                module.replace('.', '/') + "/Tool.java", "package " + module + ";\n"
                        + "public class Tool implements java.util.spi.ToolProvider {\n"
                        + "  public String name() { return \"tool\"; }\n"
                        + "  public int run(java.io.PrintWriter out, java.io.PrintWriter err, String... args) {"
                        + " return 0; }\n}\n"));
        if (!declared) {
            Path services = Files.createDirectories(dir.resolve("jlink-mods").resolve(module)
                    .resolve("META-INF/services"));
            Files.writeString(services.resolve(ToolProvider.class.getName()), module + ".Tool\n");
        }
    }

    private void compile(String module, Map<String, String> sources) throws Exception {
        var javac = ToolProvider.findFirst("javac").orElseThrow();
        Path src = Files.createDirectories(dir.resolve("src-" + module));
        List<String> args = new ArrayList<>(List.of("-d",
                Files.createDirectories(dir.resolve("jlink-mods").resolve(module)).toString()));
        for (var source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        assertEquals(0, javac.run(System.out, System.err, args.toArray(String[]::new)), module);
    }

    @Test
    void theJlinkRunBakesTheLaunchIntoTheImage() throws Exception {
        VidocqJlinkMojo mojo = jlink("acme.app", null, new Infos());
        set(VidocqJlinkMojo.class, mojo, "stripDebug", true);

        List<String> args = mojo.jlinkArgs("acme.app,java.base", ImageLaunch.of("acme.app", null));

        assertEquals(dir.resolve("jlink-mods") + File.pathSeparator + System.getProperty("java.home") + "/jmods",
                args.get(args.indexOf("--module-path") + 1));
        assertEquals("acme.app,java.base", args.get(args.indexOf("--add-modules") + 1));
        assertEquals("app=" + RUNTIME_MAIN, args.get(args.indexOf("--launcher") + 1));
        assertTrue(args.contains("--add-options=" + NO_LAYER_OPTIONS + " -Dvidocq.app.module=acme.app"), args.toString());
        assertEquals(dir.resolve("dist").toString(), args.get(args.indexOf("--output") + 1));
        assertTrue(args.contains("--strip-debug"), args.toString());
        assertTrue(args.contains("--compress=zip-6"), args.toString());
    }

    /** The recipe vidocq:docker links again inside a Linux JDK names no path of the build host (BUG-20261030-17). */
    @Test
    void theRecipeArgumentsNameNoPathOfTheBuildHost() throws Exception {
        VidocqJlinkMojo mojo = jlink("acme.app", null, new Infos());
        set(VidocqJlinkMojo.class, mojo, "stripDebug", true);

        List<String> args = mojo.portableJlinkArgs("acme.app,java.base", ImageLaunch.of("acme.app", null));

        assertTrue(args.stream().noneMatch(a -> a.equals("--module-path") || a.equals("--output")
                || a.contains(dir.toString())), args.toString());
        assertEquals("acme.app,java.base", args.get(args.indexOf("--add-modules") + 1));
        assertEquals("app=" + RUNTIME_MAIN, args.get(args.indexOf("--launcher") + 1));
        assertTrue(args.contains("--add-options=" + NO_LAYER_OPTIONS + " -Dvidocq.app.module=acme.app"), args.toString());
        assertTrue(args.contains("--strip-debug") && args.contains("--compress=zip-6"), args.toString());
    }

    /** The configuration files reach the image's conf/ and the recipe vidocq:docker links again (BUG-20261030-17). */
    @Test
    void theConfigurationFilesGoToTheImageAndToTheRecipe() throws Exception {
        VidocqJlinkMojo mojo = jlink("acme.app", null, new Infos());
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Files.writeString(classes.resolve("vidocq.properties"), "vidocq.http.port=8080\n");
        set(VidocqJlinkMojo.class, mojo, "classesDir", classes.toFile());

        List<Path> copied = mojo.copyResources();

        assertEquals(List.of(classes.resolve("vidocq.properties")), copied);
        assertTrue(Files.isRegularFile(dir.resolve("dist/conf/vidocq.properties")));
    }

    /** The launcher runs the trampoline itself: the image has one layer, so one copy of it (BUG-20261114-11). */
    @Test
    void theJlinkRunOfATrampolineLaunchesIt() throws Exception {
        VidocqJlinkMojo mojo = jlink("acme.app", "acme.app.App", new Infos());
        set(VidocqJlinkMojo.class, mojo, "compress", " ");

        List<String> args = mojo.jlinkArgs("acme.app", ImageLaunch.of("acme.app", "acme.app.App"));

        assertEquals("app=acme.app/acme.app.App", args.get(args.indexOf("--launcher") + 1));
        assertTrue(args.contains("--add-options=" + NO_LAYER_OPTIONS), args.toString());
        assertTrue(args.stream().noneMatch(a -> a.startsWith("--compress") || a.equals("--strip-debug")),
                args.toString());
    }

    /**
     * {@code vidocq.jvmArgs}, which the {@code vidocq:package} scripts carry, is baked into the image too: before the
     * options of the image, as in the scripts, so that the image's own win. The jlink run and the recipe of
     * {@code vidocq:docker} carry them, and one line says so.
     */
    @Test
    void theJvmArgsOfThePomAreBakedIntoTheImage() throws Exception {
        var log = new Infos();
        VidocqJlinkMojo mojo = jlink("acme.app", "acme.app.App", log);
        set(VidocqJlinkMojo.class, mojo, "jvmArgs", "  -Dvaadin.productionMode=true -Xmx256m ");
        ImageLaunch launch = ImageLaunch.of("acme.app", "acme.app.App", List.of("flow.push"));
        String options = "--add-options=-Dvaadin.productionMode=true -Xmx256m --add-modules=flow.push " + NO_LAYER_OPTIONS;

        assertTrue(mojo.jlinkArgs("acme.app,flow.push", launch).contains(options), log.lines.toString());
        assertTrue(mojo.portableJlinkArgs("acme.app,flow.push", launch).contains(options));
        assertTrue(log.lines.contains("Vidocq jlink — JVM options of vidocq.jvmArgs, baked into the image:"
                + " -Dvaadin.productionMode=true -Xmx256m"), log.lines.toString());
    }

    /** Without {@code vidocq.jvmArgs}, or with a blank one, the image carries its own options only. */
    @Test
    void aBlankJvmArgsAddsNothing() throws Exception {
        for (String jvmArgs : new String[] {null, " "}) {
            var log = new Infos();
            VidocqJlinkMojo mojo = jlink("acme.app", "acme.app.App", log);
            set(VidocqJlinkMojo.class, mojo, "jvmArgs", jvmArgs);

            List<String> args = mojo.portableJlinkArgs("acme.app", ImageLaunch.of("acme.app", "acme.app.App"));

            assertTrue(args.contains("--add-options=" + NO_LAYER_OPTIONS), args.toString());
            assertTrue(log.lines.stream().noneMatch(l -> l.contains("vidocq.jvmArgs")), log.lines.toString());
        }
    }

    private VidocqJlinkMojo jlink(String mainModule, String mainClass, Infos log) throws Exception {
        var project = new MavenProject();
        var artifact = new DefaultArtifact("acme", "app", "1.0", "compile", "jar", "", new DefaultArtifactHandler("jar"));
        artifact.setFile(dir.resolve("absent-app-1.0.jar").toFile());
        project.setArtifact(artifact);

        var mojo = new VidocqJlinkMojo();
        set(VidocqJlinkMojo.class, mojo, "project", project);
        set(VidocqJlinkMojo.class, mojo, "mainModule", mainModule);
        set(VidocqJlinkMojo.class, mojo, "mainClass", mainClass);
        set(VidocqJlinkMojo.class, mojo, "launcher", "app");
        set(VidocqJlinkMojo.class, mojo, "distDir", dir.resolve("dist").toFile());
        set(VidocqJlinkMojo.class, mojo, "modsDir", dir.resolve("jlink-mods").toFile());
        set(VidocqJlinkMojo.class, mojo, "buildDir", dir.toFile());
        set(VidocqJlinkMojo.class, mojo, "compress", "zip-6");
        mojo.setLog(log);
        return mojo;
    }

    // ---------------------------------------------------------------- vidocq:jpackage

    @Test
    void jpackageWithoutAMainModuleFailsBeforePackagingAnything() throws Exception {
        VidocqJpackageMojo mojo = jpackage(null, new Infos());

        var failure = assertThrows(MojoExecutionException.class, mojo::execute);

        assertTrue(failure.getMessage().contains("vidocq.mainModule"), failure.getMessage());
        assertTrue(Files.notExists(dir.resolve("installer")), "nothing is packaged before the launch is known");
    }

    @Test
    void jpackageStartsAMainLessApplicationWithTheRuntimeMain() throws Exception {
        var log = new Infos();
        // An unknown --type makes jpackage stop right after reading the arguments.
        VidocqJpackageMojo mojo = jpackage("acme.app", log);

        assertThrows(MojoExecutionException.class, mojo::execute);

        assertEquals("Vidocq jpackage — type=no-such-type name=app version=1.0.0 main=" + RUNTIME_MAIN,
                log.lines.getFirst());
    }

    @Test
    void jpackageRootsTheModulesOfTheJdkThatTheBuildDoesNotShipOnly() throws Exception {
        Path image = Files.createDirectories(dir.resolve("foreign"));
        Files.writeString(image.resolve("release"),
                "JAVA_VERSION=\"25\"\nMODULES=\"java.base jdk.jfr jdk.acme.custom flow.push acme.app\"\n");

        // A module named like the JDK's but not in it is the application's.
        assertEquals(List.of("jdk.acme.custom", "flow.push", "acme.app"), VidocqJpackageMojo.nonJdkModulesOf(image));
        assertEquals(List.of(), VidocqJpackageMojo.nonJdkModulesOf(dir.resolve("no-image")));
    }

    /**
     * The wiring: the goal starts the image it packages as vidocq:jlink baked it, with one layer, every module of
     * the image that is not the JDK's rooted.
     */
    @Test
    void jpackageArgumentsStartTheImageAsVidocqJlinkBakedIt() throws Exception {
        var log = new Infos();
        VidocqJpackageMojo mojo = jpackage("acme.app", log);
        Files.writeString(dir.resolve("image").resolve("release"),
                "MODULES=\"java.base flow.push io.vidocq.foy.core acme.app\"\n");

        ImageLaunch launch = mojo.launchOf();

        assertEquals(List.of("--add-modules=acme.app,flow.push,io.vidocq.foy.core", NO_LAYER, POOL,
                "-Dvidocq.app.module=acme.app"), launch.vmOptions());
        assertTrue(VidocqJpackageMojo.entryArgs(launch).containsAll(List.of("--java-options", NO_LAYER)),
                VidocqJpackageMojo.entryArgs(launch).toString());
        assertEquals(List.of(), log.warnings);
    }

    private VidocqJpackageMojo jpackage(String mainModule, Infos log) throws Exception {
        Path image = Files.createDirectories(dir.resolve("image"));
        var mojo = new VidocqJpackageMojo();
        set(VidocqJpackageMojo.class, mojo, "mainModule", mainModule);
        set(VidocqJpackageMojo.class, mojo, "appName", "app");
        set(VidocqJpackageMojo.class, mojo, "appVersion", "1.0.0");
        set(VidocqJpackageMojo.class, mojo, "type", "no-such-type");
        set(VidocqJpackageMojo.class, mojo, "runtimeImage", image.toFile());
        set(VidocqJpackageMojo.class, mojo, "installerDir", dir.resolve("installer").toFile());
        mojo.setLog(log);
        return mojo;
    }

    private static <T> void set(Class<T> type, T mojo, String field, Object value) throws Exception {
        Field f = type.getDeclaredField(field);
        f.setAccessible(true);
        f.set(mojo, value);
    }
}
