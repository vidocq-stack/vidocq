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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code vidocq:jlink} and {@code vidocq:jpackage} turn {@code vidocq.mainModule} / {@code vidocq.mainClass} into
 * an {@link ImageLaunch} before any work, and start the application the way it says.
 */
class ImageLaunchMojosTest {

    private static final String RUNTIME_MAIN = "io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq";

    @TempDir
    Path dir;

    /** The info lines a goal logs. */
    static final class Infos extends SystemStreamLog {
        final List<String> lines = new ArrayList<>();

        @Override
        public void info(CharSequence content) {
            lines.add(content.toString());
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
     * The build splits the stage as vidocq:package splits its jars, and the image starts with that split: the parent
     * layer and the JDK modules rooted in the boot layer, every other staged module listed for the application layer,
     * a resource-only one too (BUG-20261114-05), and never rooted (BUG-20261114-11). Logged, since this line is what
     * to read when a module is missing at start.
     */
    @Test
    void theBakedLaunchSplitsTheStageAndIsLogged() throws Exception {
        var log = new Infos();
        VidocqJlinkMojo mojo = jlink("acme.app", null, log);
        stage("flow.push", "acme.app", "aura.theme", "io.vidocq.foy.core");

        ImageLaunch launch = mojo.bakedLaunch("acme.app,aura.theme,flow.push,io.vidocq.foy.core,java.base,java.sql");

        assertEquals(List.of("--add-modules=io.vidocq.foy.core,java.sql", "-Dvidocq.app.module=acme.app",
                "-Dvidocq.app.modules=acme.app,aura.theme,flow.push", "-Dvidocq.app.layer=all"), launch.vmOptions());
        assertTrue(log.lines.contains("Vidocq jlink — main: " + RUNTIME_MAIN
                + " --add-modules=io.vidocq.foy.core,java.sql -Dvidocq.app.module=acme.app"
                + " -Dvidocq.app.modules=acme.app,aura.theme,flow.push -Dvidocq.app.layer=all"), log.lines.toString());
    }

    /** Under classes, the libraries stay in the boot layer, with the parent layer: the layer holds the application. */
    @Test
    void underClassesTheLayerHoldsTheApplicationModuleAlone() throws Exception {
        VidocqJlinkMojo mojo = jlink("acme.app", "acme.app.App", new Infos());
        set(VidocqJlinkMojo.class, mojo, "appLayer", "classes");
        stage("flow.push", "acme.app", "io.vidocq.foy.core");

        ImageLaunch launch = mojo.bakedLaunch("acme.app,flow.push,io.vidocq.foy.core,java.base");

        assertEquals(List.of("--add-modules=flow.push,io.vidocq.foy.core", "-Dvidocq.app.module=acme.app",
                "-Dvidocq.app.modules=acme.app", "-Dvidocq.app.main=acme.app.App", "-Dvidocq.app.layer=classes"),
                launch.vmOptions());
    }

    /**
     * vidocq.app.layer.keep keeps a module in the parent layer of the image as it does in vidocq:package, by module
     * name or by the groupId:artifactId of the artifact it was staged from.
     */
    @Test
    void theKeptModulesStayInTheParentLayerOfTheImage() throws Exception {
        stage("acme.app", "flow.push", "aura.theme");
        var flowPush = new DefaultArtifact("com.vaadin", "flow-push", "25.3.3", "compile", "jar", "",
                new DefaultArtifactHandler("jar"));
        flowPush.setFile(dir.resolve("jlink-mods").resolve("flow.push").toFile());
        for (String keep : new String[] {"com.vaadin:flow-push", "flow.push"}) {
            VidocqJlinkMojo mojo = jlink("acme.app", null, new Infos());
            ((MavenProject) get(mojo, "project")).setArtifacts(java.util.Set.of(flowPush));
            set(VidocqJlinkMojo.class, mojo, "appLayerKeep", keep);

            ImageLaunch launch = mojo.bakedLaunch("acme.app,aura.theme,flow.push,java.base");

            assertEquals(List.of("acme.app", "aura.theme"), launch.layerModules(), keep);
            assertTrue(launch.vmOptions().contains("--add-modules=flow.push"), keep + ": " + launch.vmOptions());
        }
    }

    /** The jlink run, the recipe of vidocq:docker and both launch shapes carry the split and the layer's modules. */
    @Test
    void theJlinkRunAndTheDockerRecipeCarryTheSplit() throws Exception {
        stage("acme.app", "flow.push");
        for (String mainClass : new String[] {null, "acme.app.App"}) {
            VidocqJlinkMojo mojo = jlink("acme.app", mainClass, new Infos());
            ImageLaunch launch = mojo.bakedLaunch("acme.app,flow.push,java.base");
            String options = mainClass == null
                    ? "--add-options=-Dvidocq.app.module=acme.app -Dvidocq.app.modules=acme.app,flow.push"
                            + " -Dvidocq.app.layer=all"
                    : "--add-options=-Dvidocq.app.module=acme.app -Dvidocq.app.modules=acme.app,flow.push"
                            + " -Dvidocq.app.main=acme.app.App -Dvidocq.app.layer=all";
            String release = "add:" + ImageLaunch.RELEASE_KEY + "=acme.app,flow.push";

            List<String> jlink = mojo.jlinkArgs("acme.app,flow.push,java.base", launch);
            assertTrue(jlink.contains(options) && jlink.contains(release), "jlink: " + jlink);
            // Every staged module is linked: the application layer reads them from the image.
            assertEquals("acme.app,flow.push,java.base", jlink.get(jlink.indexOf("--add-modules") + 1));
            List<String> recipe = mojo.portableJlinkArgs("acme.app,flow.push,java.base", launch);
            assertTrue(recipe.contains(options) && recipe.contains(release), "recipe: " + recipe);
            PortableImage.write(dir.resolve("build-" + mainClass), recipe, launch.entryPoint(), List.of());
            String file = Files.readString(dir.resolve("build-" + mainClass).resolve(PortableImage.DIRECTORY)
                    .resolve(PortableImage.ARGUMENTS));
            // One argument per line, in double quotes when it holds a space.
            assertTrue(file.lines().anyMatch(("\"" + options + "\"")::equals), "argument file: " + file);
        }
    }

    /**
     * Before jlink runs, the staged modules of the application layer lose their provides to their services files, so
     * that no service binds them into the boot layer of the image; a module of the parent layer keeps its own
     * (BUG-20261114-11).
     */
    @Test
    void theApplicationLayerModulesReachTheImageWithoutProvides() throws Exception {
        var log = new Infos();
        VidocqJlinkMojo mojo = jlink("acme.app", null, log);
        stageProvider("acme.app");
        stageProvider("io.vidocq.foy.core");
        ImageLaunch launch = mojo.bakedLaunch("acme.app,io.vidocq.foy.core,java.base");

        mojo.moveLayerProvides(launch);

        var stage = java.lang.module.ModuleFinder.of(dir.resolve("jlink-mods"));
        assertEquals(java.util.Set.of(), stage.find("acme.app").orElseThrow().descriptor().provides());
        assertEquals(List.of("acme.app.Tool"), Files.readAllLines(dir.resolve("jlink-mods/acme.app/META-INF/services")
                .resolve(ToolProvider.class.getName())));
        assertEquals(1, stage.find("io.vidocq.foy.core").orElseThrow().descriptor().provides().size());
        assertTrue(log.lines.contains("Vidocq jlink — acme.app: provides moved to META-INF/services ["
                + ToolProvider.class.getName() + "]"), log.lines.toString());
    }

    /** Compiles an empty module of each name into the stage of {@code vidocq:jlink}. */
    private void stage(String... modules) throws Exception {
        for (String module : modules) {
            compile(module, Map.of("module-info.java", "module " + module + " {}\n"));
        }
    }

    /** Compiles a module that provides a {@link ToolProvider}, {@code <module>.Tool}, into the stage. */
    private void stageProvider(String module) throws Exception {
        compile(module, Map.of(
                "module-info.java", "module " + module + " { provides java.util.spi.ToolProvider with " + module
                        + ".Tool; }\n",
                module.replace('.', '/') + "/Tool.java", "package " + module + ";\n"
                        + "public class Tool implements java.util.spi.ToolProvider {\n"
                        + "  public String name() { return \"tool\"; }\n"
                        + "  public int run(java.io.PrintWriter out, java.io.PrintWriter err, String... args) {"
                        + " return 0; }\n}\n"));
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

        List<String> args = mojo.jlinkArgs("acme.app,java.base", ImageLaunch.of("acme.app", null, io.vidocq.runtime.core.LayerSplit.Mode.ALL));

        assertEquals(dir.resolve("jlink-mods") + File.pathSeparator + System.getProperty("java.home") + "/jmods",
                args.get(args.indexOf("--module-path") + 1));
        assertEquals("acme.app,java.base", args.get(args.indexOf("--add-modules") + 1));
        assertEquals("app=" + RUNTIME_MAIN, args.get(args.indexOf("--launcher") + 1));
        assertTrue(args.contains("--add-options=-Dvidocq.app.module=acme.app -Dvidocq.app.modules=acme.app"
                + " -Dvidocq.app.layer=all"), args.toString());
        assertEquals(dir.resolve("dist").toString(), args.get(args.indexOf("--output") + 1));
        assertTrue(args.contains("--strip-debug"), args.toString());
        assertTrue(args.contains("--compress=zip-6"), args.toString());
    }

    /** The recipe vidocq:docker links again inside a Linux JDK names no path of the build host (BUG-20261030-17). */
    @Test
    void theRecipeArgumentsNameNoPathOfTheBuildHost() throws Exception {
        VidocqJlinkMojo mojo = jlink("acme.app", null, new Infos());
        set(VidocqJlinkMojo.class, mojo, "stripDebug", true);

        List<String> args = mojo.portableJlinkArgs("acme.app,java.base", ImageLaunch.of("acme.app", null, io.vidocq.runtime.core.LayerSplit.Mode.ALL));

        assertTrue(args.stream().noneMatch(a -> a.equals("--module-path") || a.equals("--output")
                || a.contains(dir.toString())), args.toString());
        assertEquals("acme.app,java.base", args.get(args.indexOf("--add-modules") + 1));
        assertEquals("app=" + RUNTIME_MAIN, args.get(args.indexOf("--launcher") + 1));
        assertTrue(args.contains("--add-options=-Dvidocq.app.module=acme.app -Dvidocq.app.modules=acme.app"
                + " -Dvidocq.app.layer=all"), args.toString());
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

    /** The launcher runs the runtime's main, which runs the trampoline inside the layer (BUG-20261114-11). */
    @Test
    void theJlinkRunOfATrampolineRunsItInsideTheLayer() throws Exception {
        VidocqJlinkMojo mojo = jlink("acme.app", "acme.app.App", new Infos());
        set(VidocqJlinkMojo.class, mojo, "compress", " ");

        List<String> args = mojo.jlinkArgs("acme.app", ImageLaunch.of("acme.app", "acme.app.App", io.vidocq.runtime.core.LayerSplit.Mode.ALL));

        assertEquals("app=" + RUNTIME_MAIN, args.get(args.indexOf("--launcher") + 1));
        assertTrue(args.contains("--add-options=-Dvidocq.app.module=acme.app -Dvidocq.app.modules=acme.app"
                + " -Dvidocq.app.main=acme.app.App -Dvidocq.app.layer=all"), args.toString());
        assertTrue(args.stream().noneMatch(a -> a.startsWith("--compress") || a.equals("--strip-debug")),
                args.toString());
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
     * The wiring: the goal starts the image it packages as vidocq:jlink baked it, the layer's modules read from the
     * key vidocq:jlink wrote in the image's release file, the other modules of the image rooted.
     */
    @Test
    void jpackageArgumentsStartTheImageAsVidocqJlinkBakedIt() throws Exception {
        VidocqJpackageMojo mojo = jpackage("acme.app", new Infos());
        Files.writeString(dir.resolve("image").resolve("release"),
                "MODULES=\"java.base flow.push io.vidocq.foy.core acme.app\"\n"
                        + ImageLaunch.RELEASE_KEY + "=acme.app,flow.push\n");

        ImageLaunch launch = mojo.launchOf();

        assertEquals(List.of("--add-modules=io.vidocq.foy.core", "-Dvidocq.app.module=acme.app",
                "-Dvidocq.app.modules=acme.app,flow.push", "-Dvidocq.app.layer=all"), launch.vmOptions());
        assertTrue(VidocqJpackageMojo.entryArgs(launch).containsAll(List.of("--java-options",
                "-Dvidocq.app.modules=acme.app,flow.push")), VidocqJpackageMojo.entryArgs(launch).toString());
    }

    /** An image vidocq:jlink did not build lists no layer: the application module alone goes to the layer. */
    @Test
    void jpackageOfAnImageWithoutTheLayerKeyLayersTheApplicationModule() throws Exception {
        VidocqJpackageMojo mojo = jpackage("acme.app", new Infos());
        Files.writeString(dir.resolve("image").resolve("release"), "MODULES=\"java.base flow.push acme.app\"\n");

        assertEquals(List.of("--add-modules=flow.push", "-Dvidocq.app.module=acme.app",
                "-Dvidocq.app.modules=acme.app", "-Dvidocq.app.layer=all"), mojo.launchOf().vmOptions());
        assertEquals(List.of(), VidocqJpackageMojo.layerModulesOf(dir.resolve("image")));
        // Quoted, as the other keys of the file are.
        Files.writeString(dir.resolve("image").resolve("release"), ImageLaunch.RELEASE_KEY + "=\"acme.app,flow.push\"\n");
        assertEquals(List.of("acme.app", "flow.push"), VidocqJpackageMojo.layerModulesOf(dir.resolve("image")));
        assertEquals(List.of(), VidocqJpackageMojo.layerModulesOf(dir.resolve("no-image")));
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

    private static Object get(VidocqJlinkMojo mojo, String field) throws Exception {
        Field f = VidocqJlinkMojo.class.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(mojo);
    }
}
