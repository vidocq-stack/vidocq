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
package io.vidocq.runtime.maven.idea;

import io.vidocq.runtime.maven.idea.RunConfigurationRenderer.Kind;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards what {@code vidocq:idea} does to {@code .run/} for a whole reactor.
 *
 * <p>The contract, in order of importance:
 * <ul>
 *   <li>it writes one file per Vidocq application, and only into {@code .run/};</li>
 *   <li>configuration errors, file name collisions and a run from inside a module fail before anything
 *       is written;</li>
 *   <li>a file the user wrote or edited is never overwritten: the goal warns and leaves it alone, in
 *       write mode and in check mode;</li>
 *   <li>check mode fails exactly where a write would change a file, and passes right after a write; its
 *       summary names the files it did not verify, and a strict check fails on those too;</li>
 *   <li>a second write changes nothing, not even a modification time.</li>
 * </ul>
 */
class VidocqIdeaMojoTest {

    @TempDir
    Path root;

    private final RecordingLog log = new RecordingLog();
    private final List<MavenProject> projects = new ArrayList<>();
    private VidocqIdeaMojo mojo;

    @BeforeEach
    void reactor() throws IOException {
        Files.writeString(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modules><module>alpha</module><module>apps/beta</module><module>library</module></modules>
                </project>
                """);
        MavenProject parent = project("", "pom");
        MavenProject alpha = application("alpha", "com.example.alpha.AlphaApp");
        MavenProject beta = application("apps/beta", "com.example.beta.BetaApp");
        beta.getProperties().setProperty("vidocq.idea.configurationName", "Beta server");
        MavenProject library = project("library", "jar");
        library.getModel().getBuild().addPlugin(IdeaApplicationsTest.vidocqPlugin());
        projects.addAll(List.of(parent, alpha, beta, library));
        mojo = newMojo();
    }

    @Test
    void writesOneFilePerApplicationAndTheCheckThenPasses() throws Exception {
        run();

        try (Stream<Path> files = Files.list(root.resolve(".run"))) {
            assertEquals(List.of("AlphaApp.run.xml", "Beta server.run.xml"),
                    files.map(p -> p.getFileName().toString()).sorted().toList());
        }
        assertEquals(RunConfigurationRenderer.file(new IdeaApplication("com.example:alpha", "com.example.alpha.AlphaApp",
                        "AlphaApp", "alpha", "alpha/pom.xml", "vidocq:generate"), Kind.APPLICATION, null, true),
                read("AlphaApp.run.xml"));
        assertTrue(read("Beta server.run.xml").contains(
                "file=\"$PROJECT_DIR$/apps/beta/pom.xml\" goal=\"vidocq:generate\""));
        assertFalse(Files.exists(root.resolve("alpha/.run")), "nothing is written inside a module");
        assertFalse(Files.exists(root.resolve(".idea")), "no project file is ever written");
        assertTrue(log.has("INFO", "Vidocq idea: created .run/AlphaApp.run.xml (com.example:alpha, main class"
                + " com.example.alpha.AlphaApp, IntelliJ module alpha)"), log.toString());
        assertTrue(log.has("INFO", "Vidocq idea: 2 run configuration(s): 2 created"), log.toString());

        check();

        assertTrue(log.has("INFO", "Vidocq idea: 2 run configuration(s) in " + root.toRealPath() + "/.run are up to date."),
                log.toString());
    }

    @Test
    void aSecondWriteChangesNothing() throws Exception {
        run();
        Path alpha = root.resolve(".run/AlphaApp.run.xml");
        FileTime written = FileTime.fromMillis(Files.getLastModifiedTime(alpha).toMillis() - 60_000);
        Files.setLastModifiedTime(alpha, written);
        byte[] before = Files.readAllBytes(alpha);

        run();

        assertArrayEquals(before, Files.readAllBytes(alpha));
        assertEquals(written, Files.getLastModifiedTime(alpha));
        assertTrue(log.has("INFO", "Vidocq idea: 2 run configuration(s): 2 unchanged"), log.toString());
    }

    @Test
    void theCheckFailsWithADiffWhenTheMainClassChanged() throws Exception {
        projects.get(1).getProperties().setProperty("vidocq.mainClass", "com.example.alpha.OldApp");
        run();
        projects.get(1).getProperties().setProperty("vidocq.mainClass", "com.example.alpha.AlphaApp");
        String before = read("OldApp.run.xml");
        Files.move(root.resolve(".run/OldApp.run.xml"), root.resolve(".run/AlphaApp.run.xml"));

        MojoFailureException failure = assertThrows(MojoFailureException.class, this::check);

        assertEquals("Vidocq idea: 1 of 2 run configuration(s) in .run/ do not match the Maven projects."
                + " Run \"mvn vidocq:idea\" in " + root.toRealPath() + " and commit .run/.", failure.getMessage());
        assertTrue(log.has("ERROR", "Vidocq idea: .run/AlphaApp.run.xml is out of date for com.example:alpha:"), log.toString());
        assertTrue(log.has("ERROR", "  -     <option name=\"MAIN_CLASS_NAME\" value=\"com.example.alpha.OldApp\" />"), log.toString());
        assertTrue(log.has("ERROR", "  +     <option name=\"MAIN_CLASS_NAME\" value=\"com.example.alpha.AlphaApp\" />"), log.toString());
        assertEquals(before, read("AlphaApp.run.xml"), "check mode writes nothing");
    }

    @Test
    void theCheckFailsForAMissingFile() throws Exception {
        MojoFailureException failure = assertThrows(MojoFailureException.class, this::check);

        assertTrue(failure.getMessage().startsWith("Vidocq idea: 2 of 2 run configuration(s)"), failure.getMessage());
        assertTrue(log.has("ERROR", "Vidocq idea: .run/AlphaApp.run.xml is missing for com.example:alpha."), log.toString());
        assertFalse(Files.exists(root.resolve(".run")));
    }

    @Test
    void aHandWrittenFileIsLeftAloneInBothModes() throws Exception {
        Files.createDirectories(root.resolve(".run"));
        String handWritten = RunConfigurationRenderer.body(new IdeaApplication("com.example:alpha",
                "com.example.alpha.AlphaApp", "AlphaApp", "alpha", "alpha/pom.xml", "vidocq:generate"),
                Kind.APPLICATION, "temurin-25", true);
        Files.writeString(root.resolve(".run/AlphaApp.run.xml"), handWritten);

        run();

        assertEquals(handWritten, read("AlphaApp.run.xml"));
        assertTrue(Files.exists(root.resolve(".run/Beta server.run.xml")), "the other application is still written");
        String warning = "Vidocq idea: .run/AlphaApp.run.xml belongs to you: it was not generated by vidocq:idea"
                + " (written by hand, or saved by IntelliJ after an edit), so vidocq:idea leaves it untouched."
                + " It differs from what vidocq:idea writes only by its JDK 'temurin-25': declare"
                + " <vidocq.idea.jre>temurin-25</vidocq.idea.jre> in the top-level pom and run \"mvn vidocq:idea\""
                + " to adopt it as it is.";
        assertTrue(log.has("WARN", warning), log.toString());
        assertTrue(log.has("INFO", "Vidocq idea: 2 run configuration(s): 1 created, 1 left untouched"), log.toString());

        check();

        assertTrue(log.has("WARN", "Vidocq idea: 1 run configuration(s) in " + root.toRealPath() + "/.run are up to"
                + " date; 1 NOT verified because it belongs to you: .run/AlphaApp.run.xml."
                + " -Dvidocq.idea.check=strict fails on such files."), log.toString());
    }

    /**
     * Migrating lc4jcdi-on-vidocq: its committed, hand-written configuration pins {@code temurin-25}. Deleting
     * it and regenerating without {@code vidocq.idea.jre} would silently drop the pin, so the goal says how to
     * keep it, and adopts the file as it is once the property is set.
     */
    @Test
    void aHandWrittenFileThatPinsItsJdkIsAdoptedOnceTheJreIsDeclared() throws Exception {
        Files.createDirectories(root.resolve(".run"));
        String handWritten = RunConfigurationRenderer.body(new IdeaApplication("com.example:alpha",
                "com.example.alpha.AlphaApp", "AlphaApp", "alpha", "alpha/pom.xml", "vidocq:generate"),
                Kind.APPLICATION, "temurin-25", true);
        Files.writeString(root.resolve(".run/AlphaApp.run.xml"), handWritten);
        mojo.setJre("temurin-25");

        run();

        assertEquals(RunConfigurationRenderer.markerLine(handWritten) + "\n" + handWritten, read("AlphaApp.run.xml"));
        assertTrue(log.hasContaining("INFO", "Vidocq idea: updated .run/AlphaApp.run.xml, marker added"), log.toString());
    }

    @Test
    void aFileThatPinsItsJdkAndHasOtherChangesKeepsItsPinWhenRegenerated() throws Exception {
        Files.createDirectories(root.resolve(".run"));
        String handWritten = RunConfigurationRenderer.body(new IdeaApplication("com.example:alpha",
                "com.example.alpha.AlphaApp", "AlphaApp", "alpha", "alpha/pom.xml", "vidocq:generate"),
                Kind.APPLICATION, "a&b", true)
                .replace("<module name=\"alpha\" />", "<option name=\"VM_PARAMETERS\" value=\"-Xmx1g\" />\n    <module name=\"alpha\" />");
        Files.writeString(root.resolve(".run/AlphaApp.run.xml"), handWritten);

        run();

        assertTrue(log.has("WARN", "Vidocq idea: .run/AlphaApp.run.xml belongs to you: it was not generated by"
                + " vidocq:idea (written by hand, or saved by IntelliJ after an edit), so vidocq:idea leaves it"
                + " untouched. It pins the JDK 'a&b', which vidocq:idea only writes when vidocq.idea.jre is set:"
                + " declare <vidocq.idea.jre>a&amp;b</vidocq.idea.jre> in the top-level pom before you delete"
                + " it and run \"mvn vidocq:idea\" to regenerate it."), log.toString());
    }

    @Test
    void aGeneratedFileEditedSinceIsLeftAlone() throws Exception {
        run();
        String edited = read("AlphaApp.run.xml").replace("<module name=\"alpha\" />",
                "<option name=\"VM_PARAMETERS\" value=\"-Xmx1g\" />\n    <module name=\"alpha\" />");
        Files.writeString(root.resolve(".run/AlphaApp.run.xml"), edited);

        run();
        check();

        assertEquals(edited, read("AlphaApp.run.xml"));
        assertTrue(log.hasContaining("WARN", ".run/AlphaApp.run.xml belongs to you: it was edited after vidocq:idea"
                + " generated it, so vidocq:idea leaves it untouched."), log.toString());
    }

    /**
     * The default check never fails on a file that belongs to the user, so that an intended customisation does
     * not break CI forever. A strict check fails on it too, with its diff, when the content differs from what
     * the goal would write: a team that wants every file in {@code .run/} to follow the poms asks for it.
     */
    @Test
    void aStrictCheckFailsOnAFileEditedSinceWithItsDiff() throws Exception {
        run();
        String edited = read("AlphaApp.run.xml").replace("<module name=\"alpha\" />",
                "<option name=\"VM_PARAMETERS\" value=\"-Xmx1g\" />\n    <module name=\"alpha\" />");
        Files.writeString(root.resolve(".run/AlphaApp.run.xml"), edited);
        mojo.setCheck("strict");

        MojoFailureException failure = assertThrows(MojoFailureException.class,
                () -> mojo.run(projects, projects, new Properties(), root));

        assertEquals("Vidocq idea: 1 of 2 run configuration(s) in .run/ do not match the Maven projects. Run"
                + " \"mvn vidocq:idea\" in " + root.toRealPath() + " and commit .run/. 1 of them belong(s) to you,"
                + " and vidocq:idea leaves such files untouched: delete them first to regenerate them, or check with"
                + " -Dvidocq.idea.check=true to keep your changes.", failure.getMessage());
        assertTrue(log.has("ERROR", "Vidocq idea: .run/AlphaApp.run.xml was edited after vidocq:idea generated it and"
                + " differs from what vidocq:idea writes for com.example:alpha:"), log.toString());
        assertTrue(log.has("ERROR", "  -     <option name=\"VM_PARAMETERS\" value=\"-Xmx1g\" />"), log.toString());
        assertEquals(edited, read("AlphaApp.run.xml"), "a check writes nothing");
    }

    @Test
    void aStrictCheckNamesTheJdkThatAHandWrittenFileAlonePins() throws Exception {
        Files.createDirectories(root.resolve(".run"));
        Files.writeString(root.resolve(".run/AlphaApp.run.xml"), RunConfigurationRenderer.body(new IdeaApplication(
                "com.example:alpha", "com.example.alpha.AlphaApp", "AlphaApp", "alpha", "alpha/pom.xml",
                "vidocq:generate"), Kind.APPLICATION, "temurin-25", true));
        mojo.setCheck("strict");

        assertThrows(MojoFailureException.class, () -> mojo.run(projects, projects, new Properties(), root));

        assertTrue(log.has("ERROR", "  -     <option name=\"ALTERNATIVE_JRE_PATH\" value=\"temurin-25\" />"), log.toString());
        assertTrue(log.has("ERROR", "Vidocq idea: .run/AlphaApp.run.xml differs from what vidocq:idea writes only by its"
                + " JDK 'temurin-25': declare <vidocq.idea.jre>temurin-25</vidocq.idea.jre> in the top-level pom and run"
                + " \"mvn vidocq:idea\" to adopt it as it is."), log.toString());
    }

    @Test
    void aStrictCheckFailsOnAHandWrittenFile() throws Exception {
        run();
        Files.writeString(root.resolve(".run/AlphaApp.run.xml"), "<component name=\"ProjectRunConfigurationManager\"/>");
        mojo.setCheck("strict");

        assertThrows(MojoFailureException.class, () -> mojo.run(projects, projects, new Properties(), root));

        assertTrue(log.has("ERROR", "Vidocq idea: .run/AlphaApp.run.xml was not generated by vidocq:idea and differs"
                + " from what vidocq:idea writes for com.example:alpha:"), log.toString());
    }

    /** Only the marker line was touched: the content is what the goal writes, so even a strict check passes. */
    @Test
    void aStrictCheckPassesAFileThatBelongsToYouWithTheExpectedContent() throws Exception {
        run();
        String generated = read("AlphaApp.run.xml");
        Files.writeString(root.resolve(".run/AlphaApp.run.xml"), generated.replaceFirst("sha256:[0-9a-f]{64}",
                "sha256:" + "0".repeat(64)));
        mojo.setCheck("strict");

        mojo.run(projects, projects, new Properties(), root);

        assertTrue(log.has("INFO", "Vidocq idea: 2 run configuration(s) in " + root.toRealPath() + "/.run are up to date."),
                log.toString());
    }

    @Test
    void anUnknownCheckValueFailsBeforeAnythingIsWritten() {
        mojo.setCheck("yes");

        MojoFailureException failure = assertThrows(MojoFailureException.class,
                () -> mojo.run(projects, projects, new Properties(), root));

        assertEquals("Vidocq idea: vidocq.idea.check must be false, true or strict, not \"yes\".", failure.getMessage());
        assertFalse(Files.exists(root.resolve(".run")));
    }

    @Test
    void anUnmarkedFileWithTheExpectedContentIsAdopted() throws Exception {
        run();
        String generated = read("AlphaApp.run.xml");
        Files.writeString(root.resolve(".run/AlphaApp.run.xml"), generated.substring(generated.indexOf('\n') + 1));

        assertThrows(MojoFailureException.class, this::check);
        assertTrue(log.has("ERROR", "Vidocq idea: .run/AlphaApp.run.xml has the expected content but no vidocq:idea"
                + " marker; run \"mvn vidocq:idea\" to adopt it."), log.toString());

        run();

        assertEquals(generated, read("AlphaApp.run.xml"));
        assertTrue(log.has("INFO", "Vidocq idea: updated .run/AlphaApp.run.xml, marker added (com.example:alpha,"
                + " main class com.example.alpha.AlphaApp, IntelliJ module alpha)"), log.toString());
    }

    @Test
    void collisionsFailBeforeAnythingIsWritten() throws Exception {
        MavenProject other = application("other", "com.example.other.alphaapp");
        projects.add(other);

        MojoFailureException failure = assertThrows(MojoFailureException.class, this::run);

        assertEquals("Vidocq idea: com.example:alpha and com.example:other both map to .run/alphaapp.run.xml"
                + " (configuration name \"alphaapp\"). Set <vidocq.idea.configurationName> in one of their poms.",
                failure.getMessage());
        assertFalse(Files.exists(root.resolve(".run")));
    }

    @Test
    void configurationErrorsFailBeforeAnythingIsWritten() throws Exception {
        projects.get(2).getProperties().setProperty("vidocq.mainClass", "not a class");

        MojoFailureException failure = assertThrows(MojoFailureException.class, this::run);

        assertEquals("Vidocq idea: 1 configuration error(s); nothing was written.", failure.getMessage());
        assertTrue(log.has("ERROR", "Vidocq idea: com.example:beta: the main class \"not a class\" is not a Java class name."),
                log.toString());
        assertFalse(Files.exists(root.resolve(".run")));
    }

    @Test
    void runningInsideAModuleFails() throws Exception {
        Path alpha = root.resolve("alpha");

        MojoFailureException failure = assertThrows(MojoFailureException.class,
                () -> mojo.run(projects.subList(1, 2), projects.subList(1, 2), new Properties(), alpha));

        assertEquals("Vidocq idea: " + alpha.toRealPath() + " is a module of " + root.toRealPath().resolve("pom.xml")
                + ". IntelliJ usually opens the reactor root, where the generated $PROJECT_DIR$ paths would point at the"
                + " wrong pom: run vidocq:idea from " + root.toRealPath() + ", or add -Dvidocq.idea.projectDirectory="
                + alpha.toRealPath() + " if IntelliJ opens " + alpha.toRealPath() + " itself.", failure.getMessage());
        assertFalse(Files.exists(alpha.resolve(".run")));
    }

    @Test
    void anExplicitProjectDirectoryIsTheWayOut() throws Exception {
        Path alpha = root.resolve("alpha");
        mojo.setProjectDirectory(alpha.toFile());

        mojo.run(projects.subList(1, 2), projects.subList(1, 2), new Properties(), alpha);

        assertTrue(read(alpha.resolve(".run/AlphaApp.run.xml")).contains("file=\"$PROJECT_DIR$/pom.xml\""));
        assertFalse(log.hasContaining("WARN", "is not the directory of a project of this build"), log.toString());
    }

    @Test
    void anExplicitProjectDirectoryOutsideTheReactorIsReported(@TempDir Path workspace) throws Exception {
        Path above = workspace.resolve("above");
        Path reactor = Files.createDirectories(above.resolve("reactor"));
        Files.writeString(reactor.resolve("pom.xml"), "<project/>");
        MavenProject app = projectAt(reactor.resolve("app"), "app", "jar");
        app.getModel().getBuild().addPlugin(IdeaApplicationsTest.vidocqPlugin());
        app.getProperties().setProperty("vidocq.mainClass", "com.example.App");
        mojo.setProjectDirectory(above.toFile());

        mojo.run(List.of(app), List.of(app), new Properties(), reactor);

        assertTrue(read(above.resolve(".run/App.run.xml")).contains("file=\"$PROJECT_DIR$/reactor/app/pom.xml\""));
        assertTrue(log.has("WARN", "Vidocq idea: " + above.toRealPath() + " is not the directory of a project of"
                + " this build. IntelliJ loads .run/*.run.xml files only inside the project content: make sure the"
                + " IntelliJ project includes " + above.toRealPath() + "."), log.toString());
    }

    @Test
    void orphansAreReportedAndNeverDeleted() throws Exception {
        run();
        String gone = RunConfigurationRenderer.file(new IdeaApplication("com.example:gone", "com.example.Gone",
                "Gone", "gone", "gone/pom.xml", "vidocq:generate"), Kind.APPLICATION, null, true);
        Files.writeString(root.resolve(".run/Gone.run.xml"), gone);
        Files.writeString(root.resolve(".run/Mine.run.xml"), "<component name=\"ProjectRunConfigurationManager\"/>");

        run();
        check();

        assertEquals(gone, read("Gone.run.xml"));
        assertTrue(log.has("WARN", "Vidocq idea: .run/Gone.run.xml was generated by vidocq:idea but no application"
                + " of this build maps to it (main class or vidocq.idea.configurationName changed, application"
                + " removed, or module left out with -pl). Delete it if it is obsolete."), log.toString());
        assertFalse(log.hasContaining("WARN", "Mine.run.xml"), "a file without marker is never reported: " + log);
    }

    @Test
    void theJdkAndTheBeforeLaunchStepCanBeChosen() throws Exception {
        mojo.setJre("temurin-25");
        mojo.setGenerateBeforeLaunch(false);

        run();

        String alpha = read("AlphaApp.run.xml");
        assertTrue(alpha.contains("<option name=\"ALTERNATIVE_JRE_PATH\" value=\"temurin-25\" />"), alpha);
        assertFalse(alpha.contains("Maven.BeforeRunTask"), alpha);
        assertTrue(log.has("INFO", "Vidocq idea: IntelliJ needs JDK 25 or newer as the SDK used by Make."
                + " The application runs on the 'temurin-25' SDK, which must exist under that name on every machine."),
                log.toString());
        assertFalse(log.hasContaining("WARN", "vidocq.idea.jre is not set"), log.toString());
    }

    @Test
    void anAbsoluteJrePathIsReported() throws Exception {
        mojo.setJre("/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home");

        run();

        assertTrue(log.has("WARN", "Vidocq idea: vidocq.idea.jre \"/Library/Java/JavaVirtualMachines/temurin-25.jdk/"
                + "Contents/Home\" is an absolute path. It is written into shared files and only exists on this"
                + " machine; prefer an IntelliJ SDK name such as temurin-25."), log.toString());
    }

    @Test
    void theJdkReminderAndReloadHintFollowAWrite() throws Exception {
        run();

        assertTrue(log.has("INFO", "Vidocq idea: IntelliJ needs JDK 25 or newer as the SDK used by Make and as the"
                + " Maven runner JRE used by the vidocq:generate step (Settings > Build, Execution, Deployment >"
                + " Build Tools > Maven > Runner)."), log.toString());
        assertTrue(log.has("INFO", "Vidocq idea: IntelliJ reloads a changed .run.xml file by itself, but it may"
                + " miss files in a new .run/ directory or several files written at once: if a configuration does"
                + " not appear, use File > Reload All from Disk."), log.toString());

        log.lines.clear();
        projects.get(1).getProperties().setProperty("vidocq.idea.moduleName", "alpha.main");
        run();

        assertTrue(log.has("INFO", "Vidocq idea: 2 run configuration(s): 1 updated, 1 unchanged"), log.toString());
        assertFalse(log.hasContaining("INFO", "Reload All from Disk"), "one file in an existing .run/: " + log);

        log.lines.clear();
        run();

        assertFalse(log.hasContaining("INFO", "IntelliJ needs JDK 25"), "nothing written, no reminder: " + log);
        assertFalse(log.hasContaining("WARN", "do not pin a JDK"), "nothing written, no warning: " + log);
    }

    /**
     * The configuration measured to work in IntelliJ pins its JDK ({@code ALTERNATIVE_JRE_PATH}); without
     * {@code vidocq.idea.jre} the generated one does not, and IntelliJ launches the application on the module
     * SDK. That launch has not been verified in IntelliJ, so writing such a file is a warning, not a detail.
     */
    @Test
    void writingConfigurationsThatDoNotPinAJdkIsAWarning() throws Exception {
        run();

        assertTrue(log.has("WARN", "Vidocq idea: vidocq.idea.jre is not set, so the run configurations written do"
                + " not pin a JDK: IntelliJ launches the application on the module SDK, which can be another JDK than"
                + " the one it is built and tested with. To launch on a known JDK, declare <vidocq.idea.jre> in the"
                + " top-level pom with the name of an IntelliJ SDK of Java 25 or newer that exists on every machine,"
                + " for example <vidocq.idea.jre>temurin-25</vidocq.idea.jre>."), log.toString());
    }

    @Test
    void aPartialReactorIsReported() throws Exception {
        mojo.run(projects.subList(1, 3), projects, new Properties(), root);

        assertTrue(log.has("INFO", "Vidocq idea: partial build (-pl, -rf or similar): only the applications of the"
                + " selected projects are covered."), log.toString());
    }

    @Test
    void noApplicationIsAWarningNotAFailure() throws Exception {
        mojo.run(List.of(projects.get(0), projects.get(3)), List.of(projects.get(0), projects.get(3)), new Properties(), root);

        assertTrue(log.has("WARN", "Vidocq idea: no Vidocq application in this build: a module needs"
                + " vidocq-runtime-maven-plugin in its <build><plugins> and a vidocq.mainClass property."), log.toString());
        assertFalse(Files.exists(root.resolve(".run")));
    }

    @Test
    void aRegularFileNamedRunIsAnIoError() throws Exception {
        Files.writeString(root.resolve(".run"), "not a directory");

        MojoExecutionException failure = assertThrows(MojoExecutionException.class, this::run);

        assertEquals("Cannot write " + root.toRealPath().resolve(".run"), failure.getMessage());
    }

    @Test
    void aMissingProjectDirectoryFails() {
        mojo.setProjectDirectory(root.resolve("missing").toFile());

        MojoFailureException failure = assertThrows(MojoFailureException.class, this::run);

        assertEquals("Vidocq idea: project directory " + root.resolve("missing")
                + " does not exist or is not a directory.", failure.getMessage());
    }

    @Test
    void skipWritesNothing() throws Exception {
        mojo.setSkip(true);

        mojo.execute();

        assertTrue(log.has("INFO", "Vidocq idea: skipped (<skip>true</skip> in the configuration of"
                + " vidocq-runtime-maven-plugin)"), log.toString());
        assertFalse(Files.exists(root.resolve(".run")));
    }

    /**
     * Maven evaluates {@code ${vidocq.idea.skip}} against the command line, then against the properties of the
     * top-level project: the message names the source that is actually true, so that a skip caused by a pom is
     * not blamed on a {@code -D} nobody passed.
     */
    @Test
    void theSkipMessageNamesWhereTheValueCameFrom() {
        Properties commandLine = new Properties();
        commandLine.setProperty("vidocq.idea.skip", "true");
        Properties system = new Properties();
        system.setProperty("vidocq.idea.skip", "true");
        Properties falseOnTheCommandLine = new Properties();
        falseOnTheCommandLine.setProperty("vidocq.idea.skip", "false");
        MavenProject alpha = projects.get(1);
        alpha.getProperties().setProperty("vidocq.idea.skip", "true");

        assertEquals("-Dvidocq.idea.skip=true on the command line",
                VidocqIdeaMojo.skipSource(commandLine, system, alpha));
        assertEquals("system property vidocq.idea.skip=true",
                VidocqIdeaMojo.skipSource(new Properties(), system, alpha));
        assertEquals("vidocq.idea.skip=true in the properties of com.example:alpha, the top-level project of this build",
                VidocqIdeaMojo.skipSource(falseOnTheCommandLine, new Properties(), alpha));
        assertEquals("<skip>true</skip> in the configuration of vidocq-runtime-maven-plugin",
                VidocqIdeaMojo.skipSource(null, null, projects.get(2)));
    }

    /**
     * With {@code -pl alpha,apps/beta}, alpha is the top-level project. Its own opt-out leaves alpha out and
     * nothing else: before {@code vidocq.idea.exclude} existed, the module setting had the goal's property
     * name and skipped the whole goal there.
     */
    @Test
    void theFirstSelectedProjectLeavesOnlyItselfOut() throws Exception {
        MavenProject alpha = projects.get(1);
        alpha.getProperties().setProperty("vidocq.idea.exclude", "true");

        mojo.run(List.of(alpha, projects.get(2)), projects, new Properties(), root);

        try (Stream<Path> files = Files.list(root.resolve(".run"))) {
            assertEquals(List.of("Beta server.run.xml"), files.map(p -> p.getFileName().toString()).toList());
        }
        assertTrue(log.has("INFO", "Vidocq idea: com.example:alpha excluded (vidocq.idea.exclude=true in its pom)"),
                log.toString());
    }

    /**
     * Maven 3 ignores {@code requiresDirectInvocation}, and an aggregator bound to a phase runs once per
     * module of the reactor: every run would plan and write the same {@code .run/} files.
     */
    @Test
    void aBindingToALifecyclePhaseIsRejected() {
        MojoDescriptor descriptor = new MojoDescriptor();
        descriptor.setGoal("idea");
        mojo.setMojoExecution(new MojoExecution(descriptor, "ide-files", MojoExecution.Source.LIFECYCLE));

        MojoFailureException failure = assertThrows(MojoFailureException.class, mojo::execute);

        assertEquals("Vidocq idea: this goal runs from the command line only (\"mvn vidocq:idea\"), but execution"
                + " 'ide-files' binds it to the build. Remove that <execution> from the pom.", failure.getMessage());
    }

    // ---- fixtures ----

    private void run() throws MojoExecutionException, MojoFailureException {
        mojo.setCheck("false");
        mojo.run(projects, projects, new Properties(), root);
    }

    private void check() throws MojoExecutionException, MojoFailureException {
        mojo.setCheck("true");
        try {
            mojo.run(projects, projects, new Properties(), root);
        } finally {
            mojo.setCheck("false");
        }
    }

    private VidocqIdeaMojo newMojo() {
        VidocqIdeaMojo created = new VidocqIdeaMojo();
        created.setLog(log);
        // The Application kind: what a file on disk is, and what the goal does to it, does not depend on the
        // kind, and its XML is the one these expectations name. The Maven kind, which is the default, has its
        // own tests at the end of this class.
        created.setKind("application");
        created.setGenerateBeforeLaunch(true);
        return created;
    }

    private MavenProject application(String directory, String mainClass) throws IOException {
        MavenProject project = project(directory, "jar");
        Plugin plugin = IdeaApplicationsTest.vidocqPlugin();
        project.getModel().getBuild().addPlugin(plugin);
        project.getProperties().setProperty("vidocq.mainClass", mainClass);
        return project;
    }

    private MavenProject project(String directory, String packaging) throws IOException {
        String artifactId = directory.isEmpty() ? "parent" : directory.substring(directory.lastIndexOf('/') + 1);
        return projectAt(root.resolve(directory), artifactId, packaging);
    }

    private static MavenProject projectAt(Path basedir, String artifactId, String packaging) throws IOException {
        Model model = new Model();
        model.setGroupId("com.example");
        model.setArtifactId(artifactId);
        model.setVersion("1.0");
        model.setPackaging(packaging);
        model.setBuild(new Build());
        MavenProject project = new MavenProject(model);
        Files.createDirectories(basedir);
        Path pom = basedir.resolve("pom.xml");
        if (!Files.exists(pom)) {
            Files.writeString(pom, "<project/>");
        }
        project.setFile(pom.toFile());
        return project;
    }

    private String read(String fileName) throws IOException {
        return read(root.resolve(".run").resolve(fileName));
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    // --- the Maven kind, which is the default -----------------------------------------------------------

    /**
     * Clicking Run in IntelliJ builds with the IDE, which never runs {@code vidocq:generate}: the bean index
     * then misses the beans of the dependency jars and the server answers 404 (Vidocq/vidocq#83). The default
     * configuration is therefore a Maven run of {@code vidocq:run}, which compiles, indexes and forks the JVM
     * exactly as the command line does.
     */
    @Test
    void theDefaultKindIsAMavenRunOfVidocqRun() throws Exception {
        VidocqIdeaMojo maven = new VidocqIdeaMojo();
        maven.setLog(log);
        maven.setGenerateBeforeLaunch(true);
        maven.setCheck("false");

        maven.run(projects, projects, new Properties(), root);

        String alpha = read("AlphaApp.run.xml");
        assertEquals(RunConfigurationRenderer.file(new IdeaApplication("com.example:alpha",
                "com.example.alpha.AlphaApp", "AlphaApp", "alpha", "alpha/pom.xml", "vidocq:generate"),
                Kind.MAVEN, null, true), alpha);
        assertTrue(alpha.contains("type=\"MavenRunConfiguration\" factoryName=\"Maven\""), alpha);
        assertTrue(alpha.contains("<option value=\"vidocq:run\" />"), alpha);
        assertTrue(alpha.contains("<option name=\"pomFileName\" value=\"alpha/pom.xml\" />"), alpha);
        assertFalse(alpha.contains("MAIN_CLASS_NAME"), "the Maven kind runs a goal, not a main class: " + alpha);
        assertFalse(alpha.contains("Maven.BeforeRunTask"), "vidocq:run indexes by itself: " + alpha);
        assertTrue(log.has("INFO", "Vidocq idea: writing Maven run configurations of 2 application(s) in "
                + root.toRealPath() + "/.run"), log.toString());
        assertTrue(log.has("INFO", "Vidocq idea: IntelliJ needs JDK 25 or newer as the Maven runner JRE (Settings >"
                + " Build, Execution, Deployment > Build Tools > Maven > Runner), which is also the JDK the"
                + " application runs on: vidocq:run forks it from the JVM running Maven."), log.toString());

        maven.setCheck("strict");
        maven.run(projects, projects, new Properties(), root);

        assertTrue(log.has("INFO", "Vidocq idea: 2 run configuration(s) in " + root.toRealPath()
                + "/.run are up to date."), log.toString());
    }

    @Test
    void theMavenKindPinsTheJdkAsTheMavenRunnerJre() throws Exception {
        VidocqIdeaMojo maven = newMojo();
        maven.setKind("maven");
        maven.setJre("temurin-25");
        maven.setCheck("false");

        maven.run(projects, projects, new Properties(), root);

        String alpha = read("AlphaApp.run.xml");
        assertTrue(alpha.contains("<option name=\"jreName\" value=\"temurin-25\" />"), alpha);
        assertFalse(alpha.contains("ALTERNATIVE_JRE_PATH"), alpha);
        assertFalse(log.hasContaining("WARN", "do not pin a JDK"), log.toString());
    }

    @Test
    void theMavenKindWarnsWhenItPinsNoJdk() throws Exception {
        mojo.setKind("maven");

        run();

        assertTrue(log.has("WARN", "Vidocq idea: vidocq.idea.jre is not set, so the run configurations written do"
                + " not pin a JDK: IntelliJ runs Maven on its Maven runner JRE, which can be another JDK than the"
                + " one it is built and tested with. To launch on a known JDK, declare <vidocq.idea.jre> in the"
                + " top-level pom with the name of an IntelliJ SDK of Java 25 or newer that exists on every machine,"
                + " for example <vidocq.idea.jre>temurin-25</vidocq.idea.jre>."), log.toString());
    }

    /** The kind is a property of the build, not of a file: changing it updates the files the goal owns. */
    @Test
    void changingTheKindUpdatesTheFilesTheGoalOwns() throws Exception {
        run();
        String application = read("AlphaApp.run.xml");
        mojo.setKind("maven");

        assertThrows(MojoFailureException.class, this::check);
        assertTrue(log.has("ERROR", "Vidocq idea: .run/AlphaApp.run.xml is out of date for com.example:alpha:"),
                log.toString());

        run();

        assertNotEquals(application, read("AlphaApp.run.xml"));
        assertTrue(read("AlphaApp.run.xml").contains("type=\"MavenRunConfiguration\""), read("AlphaApp.run.xml"));
        assertTrue(log.has("INFO", "Vidocq idea: 2 run configuration(s): 2 updated"), log.toString());
    }

    @Test
    void anUnknownKindFailsBeforeAnythingIsWritten() {
        mojo.setKind("gradle");

        MojoFailureException failure = assertThrows(MojoFailureException.class,
                () -> mojo.run(projects, projects, new Properties(), root));

        assertEquals("Vidocq idea: vidocq.idea.kind must be maven or application, not \"gradle\".",
                failure.getMessage());
        assertFalse(Files.exists(root.resolve(".run")));
    }
}
