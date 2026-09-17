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

import io.vidocq.runtime.maven.idea.IdeaApplications.Diagnostic;
import io.vidocq.runtime.maven.idea.IdeaApplications.Discovery;
import io.vidocq.runtime.maven.idea.RunConfigurationFiles.State;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * <b>Experimental.</b> Writes one IntelliJ IDEA shared run configuration per Vidocq application of the
 * reactor into {@code .run/}, or checks them. Invoke it from the command line, in the directory IntelliJ
 * opens as the project (usually the reactor root):
 *
 * <pre>mvn vidocq:idea
 * mvn vidocq:idea -Dvidocq.idea.check=true</pre>
 *
 * <p>Each configuration runs the application's main class after two before-launch steps: IntelliJ's Make,
 * then {@code vidocq:generate} on the application's pom, which completes the bean index for dependencies
 * that Vauban's annotation processor never saw. That second step exists only until the runtime indexes
 * them at launch (Vidocq/vidocq#83); {@code -Dvidocq.idea.generateBeforeLaunch=false} leaves it out.
 *
 * <p>The goal writes nothing else: never {@code .idea/}, {@code *.iml} or any other project file, which
 * belong to IntelliJ's own Maven import (the retired {@code maven-idea-plugin} competed with it). It never
 * overwrites a file it did not generate, nor one edited since: those belong to the user, and the goal only
 * warns about them. Configuration errors, file name collisions and a run from inside a module of a larger
 * reactor fail the build before anything is written.
 *
 * <p>An aggregator with no default phase: it reads every module of the build once, and refuses to run
 * when a pom binds it to a phase (Maven 3 would run a bound aggregator once per module).
 */
@Mojo(name = "idea",
      aggregator = true,
      threadSafe = true,
      requiresDirectInvocation = true,
      requiresDependencyResolution = ResolutionScope.NONE)
public class VidocqIdeaMojo extends AbstractMojo {

    private static final String PREFIX = "Vidocq idea: ";

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true)
    private List<MavenProject> reactorProjects;

    @Parameter(defaultValue = "${mojoExecution}", readonly = true, required = true)
    private MojoExecution mojoExecution;

    /**
     * The directory IntelliJ opens as the project: {@code .run/} is written there and the
     * {@code $PROJECT_DIR$} paths are relative to it. Unset, it is the directory Maven was launched in,
     * and the goal fails when that directory is a module of an enclosing reactor; set it only when
     * IntelliJ really opens another directory.
     */
    @Parameter(property = "vidocq.idea.projectDirectory")
    private File projectDirectory;

    /** Write nothing; fail when {@code .run/} differs from what the goal would write. */
    @Parameter(property = "vidocq.idea.check", defaultValue = "false")
    private boolean check;

    /** Add the {@code vidocq:generate} Maven step before launch. */
    @Parameter(property = "vidocq.idea.generateBeforeLaunch", defaultValue = "true")
    private boolean generateBeforeLaunch;

    /**
     * An IntelliJ SDK name (for example {@code temurin-25}) written as the configurations' alternative JRE.
     * Unset, IntelliJ runs the application on the module SDK. Declare it in the top-level pom: the name
     * must exist on every machine that uses the files.
     */
    @Parameter(property = "vidocq.idea.jre")
    private String jre;

    /**
     * Skip the goal entirely. Maven reads the property from the command line, then from the properties of the
     * top-level project, which is the first selected project when {@code -pl} leaves the root out; to leave one
     * module out, its pom sets {@code vidocq.idea.exclude} instead.
     */
    @Parameter(property = "vidocq.idea.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info(PREFIX + "skipped (" + skipSource() + ")");
            return;
        }
        if (mojoExecution != null && mojoExecution.getSource() != MojoExecution.Source.CLI) {
            throw new MojoFailureException(PREFIX + "this goal runs from the command line only (\"mvn vidocq:idea\"),"
                    + " but execution '" + mojoExecution.getExecutionId() + "' binds it to the build. Remove that"
                    + " <execution> from the pom.");
        }
        boolean partialReactor = session.getAllProjects() != null
                && session.getProjects().size() < session.getAllProjects().size();
        run(reactorProjects, session.getUserProperties(), Path.of(session.getExecutionRootDirectory()),
                partialReactor);
    }

    private String skipSource() {
        if (session == null) {
            return skipSource(null, null, null);
        }
        MavenProject project = session.getCurrentProject() != null
                ? session.getCurrentProject()
                : session.getTopLevelProject();
        return skipSource(session.getUserProperties(), session.getSystemProperties(), project);
    }

    /**
     * Where a true {@code vidocq.idea.skip} came from, in the order Maven evaluates the parameter: the command
     * line, the system properties, the properties of the project the goal runs on (the top-level project of
     * the build), otherwise the plugin configuration.
     */
    static String skipSource(Properties userProperties, Properties systemProperties, MavenProject project) {
        String key = IdeaApplications.SKIP_PROPERTY;
        if (userProperties != null && Boolean.parseBoolean(userProperties.getProperty(key))) {
            return "-D" + key + "=" + userProperties.getProperty(key) + " on the command line";
        }
        if (systemProperties != null && Boolean.parseBoolean(systemProperties.getProperty(key))) {
            return "system property " + key + "=" + systemProperties.getProperty(key);
        }
        if (project != null && Boolean.parseBoolean(project.getProperties().getProperty(key))) {
            return key + "=" + project.getProperties().getProperty(key) + " in the properties of "
                    + project.getGroupId() + ":" + project.getArtifactId() + ", the top-level project of this build";
        }
        return "<skip>true</skip> in the configuration of vidocq-runtime-maven-plugin";
    }

    /** The goal without its Maven session: the projects of the build, in reactor order. */
    void run(List<MavenProject> projects, Properties userProperties, Path executionRoot, boolean partialReactor)
            throws MojoExecutionException, MojoFailureException {
        Path directory = projectDirectory(projects, executionRoot);

        Discovery discovery = IdeaApplications.discover(projects, directory, userProperties);
        discovery.diagnostics().forEach(this::log);
        if (!discovery.errors().isEmpty()) {
            discovery.errors().forEach(getLog()::error);
            throw new MojoFailureException(PREFIX + discovery.errors().size()
                    + " configuration error(s); nothing was written.");
        }
        if (jre != null && isAbsolutePath(jre)) {
            getLog().warn(PREFIX + "vidocq.idea.jre \"" + jre + "\" is an absolute path. It is written into shared"
                    + " files and only exists on this machine; prefer an IntelliJ SDK name such as temurin-25.");
        }

        Path runDirectory = directory.resolve(".run");
        Map<String, Target> targets = targets(discovery.applications(), runDirectory);
        if (partialReactor) {
            getLog().info(PREFIX + "partial build (-pl, -rf or similar): only the applications of the selected"
                    + " projects are covered.");
        }
        if (targets.isEmpty()) {
            getLog().warn(PREFIX + "no Vidocq application in this build: a module needs vidocq-runtime-maven-plugin"
                    + " in its <build><plugins> and a vidocq.mainClass property.");
            reportOrphans(runDirectory, targets);
            return;
        }
        getLog().info(PREFIX + (check ? "checking" : "writing") + " run configurations of " + targets.size()
                + " application(s) in " + runDirectory);

        if (check) {
            check(directory, targets.values(), runDirectory, targets);
        } else {
            write(targets.values(), runDirectory, targets);
        }
    }

    /** One application and the file that holds its configuration. */
    private record Target(IdeaApplication application, String shownPath, Path file, String body, byte[] onDisk,
                          State state) {
    }

    private Map<String, Target> targets(List<IdeaApplication> applications, Path runDirectory)
            throws MojoExecutionException, MojoFailureException {
        Map<String, Target> targets = new LinkedHashMap<>();
        for (IdeaApplication application : applications) {
            String fileName = RunConfigurationRenderer.fileName(application.configurationName());
            // Case-insensitive: two names that differ only by case are one file on macOS and Windows.
            String key = fileName.toLowerCase(Locale.ROOT);
            Target other = targets.get(key);
            if (other != null) {
                throw new MojoFailureException(PREFIX + other.application().coordinates() + " and "
                        + application.coordinates() + " both map to .run/" + fileName + " (configuration name \""
                        + application.configurationName() + "\"). Set <vidocq.idea.configurationName> in one of"
                        + " their poms.");
            }
            Path file = runDirectory.resolve(fileName);
            String body = RunConfigurationRenderer.body(application, jre, generateBeforeLaunch);
            byte[] onDisk = Files.isRegularFile(file) ? read(file) : null;
            targets.put(key, new Target(application, ".run/" + fileName, file, body, onDisk,
                    RunConfigurationFiles.classify(onDisk, body)));
        }
        return targets;
    }

    private void check(Path directory, Iterable<Target> targets, Path runDirectory, Map<String, Target> byKey)
            throws MojoExecutionException, MojoFailureException {
        int failures = 0;
        int checked = 0;
        int userOwned = 0;
        for (Target target : targets) {
            if (target.state().userOwned()) {
                warnUserOwned(target);
                userOwned++;
                continue;
            }
            checked++;
            String about = target.application().coordinates();
            switch (target.state()) {
                case MISSING -> getLog().error(PREFIX + target.shownPath() + " is missing for " + about + ".");
                case OUTDATED -> {
                    getLog().error(PREFIX + target.shownPath() + " is out of date for " + about + ":");
                    getLog().error("  --- on disk");
                    getLog().error("  +++ expected");
                    for (String line : LineDiff.diff(RunConfigurationFiles.shownBody(target.onDisk()), target.body())) {
                        getLog().error("  " + line);
                    }
                }
                case UNMARKED -> getLog().error(PREFIX + target.shownPath() + " has the expected content but no"
                        + " vidocq:idea marker; run \"mvn vidocq:idea\" to adopt it.");
                default -> {
                    // UNCHANGED
                }
            }
            if (target.state().writes()) {
                failures++;
            }
        }
        reportOrphans(runDirectory, byKey);
        int total = byKey.size();
        if (failures > 0) {
            throw new MojoFailureException(PREFIX + failures + " of " + total + " run configuration(s) in .run/"
                    + " do not match the Maven projects. Run \"mvn vidocq:idea\" in " + directory
                    + " and commit .run/.");
        }
        getLog().info(PREFIX + checked + " run configuration(s) in " + runDirectory + " are up to date"
                + (userOwned > 0 ? "; " + userOwned + " belong(s) to you and were not checked." : "."));
    }

    private void write(Iterable<Target> targets, Path runDirectory, Map<String, Target> byKey)
            throws MojoExecutionException {
        boolean runDirectoryExisted = Files.isDirectory(runDirectory);
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int untouched = 0;
        for (Target target : targets) {
            IdeaApplication application = target.application();
            String details = " (" + application.coordinates() + ", main class " + application.mainClass()
                    + ", IntelliJ module " + application.moduleName() + ")";
            switch (target.state()) {
                case UNCHANGED -> {
                    getLog().info(PREFIX + "unchanged " + target.shownPath() + details);
                    unchanged++;
                }
                case EDITED, FOREIGN -> {
                    warnUserOwned(target);
                    untouched++;
                }
                case MISSING -> {
                    replace(runDirectory, target);
                    getLog().info(PREFIX + "created " + target.shownPath() + details);
                    created++;
                }
                case OUTDATED, UNMARKED -> {
                    replace(runDirectory, target);
                    getLog().info(PREFIX + "updated " + target.shownPath()
                            + (target.state() == State.UNMARKED ? ", marker added" : "") + details);
                    updated++;
                }
            }
        }
        reportOrphans(runDirectory, byKey);

        List<String> counts = new ArrayList<>();
        if (created > 0) counts.add(created + " created");
        if (updated > 0) counts.add(updated + " updated");
        if (unchanged > 0) counts.add(unchanged + " unchanged");
        if (untouched > 0) counts.add(untouched + " left untouched");
        getLog().info(PREFIX + byKey.size() + " run configuration(s): " + String.join(", ", counts));

        int written = created + updated;
        if (written > 0) {
            getLog().info(PREFIX + "IntelliJ needs JDK 25 or newer as the SDK used by Make"
                    + (generateBeforeLaunch ? " and as the Maven runner JRE used by the vidocq:generate step (Settings"
                    + " > Build, Execution, Deployment > Build Tools > Maven > Runner)" : "") + "."
                    + (jre == null
                    ? " The application runs on the module SDK unless vidocq.idea.jre names an IntelliJ SDK."
                    : " The application runs on the '" + jre + "' SDK, which must exist under that name on every"
                    + " machine."));
            if (!runDirectoryExisted || written > 1) {
                // RCInArbitraryFileListener only reacts to events on *.run.xml paths and stops at the first
                // matching event of a batch: a new .run/ directory or several files may go unnoticed.
                getLog().info(PREFIX + "IntelliJ reloads a changed .run.xml file by itself, but it may miss files"
                        + " in a new .run/ directory or several files written at once: if a configuration does not"
                        + " appear, use File > Reload All from Disk.");
            }
        }
    }

    private void warnUserOwned(Target target) {
        getLog().warn(PREFIX + target.shownPath() + " belongs to you: it "
                + (target.state() == State.EDITED
                ? "was edited after vidocq:idea generated it"
                : "was not generated by vidocq:idea (written by hand, or saved by IntelliJ after an edit)")
                + ", so vidocq:idea leaves it untouched. Delete it and run \"mvn vidocq:idea\" to regenerate it.");
    }

    /** Warns about generated files that no application of this build maps to; never deletes them. */
    private void reportOrphans(Path runDirectory, Map<String, Target> targets) throws MojoExecutionException {
        if (!Files.isDirectory(runDirectory)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> listing = Files.list(runDirectory)) {
            files = listing.filter(p -> p.getFileName().toString().endsWith(".run.xml"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot read " + runDirectory, e);
        }
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (!targets.containsKey(name.toLowerCase(Locale.ROOT)) && RunConfigurationFiles.isGenerated(read(file))) {
                getLog().warn(PREFIX + ".run/" + name + " was generated by vidocq:idea but no application of this"
                        + " build maps to it (main class or vidocq.idea.configurationName changed, application"
                        + " removed, or module left out with -pl). Delete it if it is obsolete.");
            }
        }
    }

    /**
     * The project directory, as a real path. When it is the launch directory by default, a module of an
     * enclosing reactor is refused: IntelliJ would open that reactor, and every {@code $PROJECT_DIR$} path
     * written here would resolve against it.
     */
    private Path projectDirectory(List<MavenProject> projects, Path executionRoot)
            throws MojoExecutionException, MojoFailureException {
        Path given = projectDirectory != null ? projectDirectory.toPath() : executionRoot;
        if (!Files.isDirectory(given)) {
            throw new MojoFailureException(PREFIX + "project directory " + given
                    + " does not exist or is not a directory.");
        }
        Path directory;
        try {
            directory = given.toRealPath();
            if (projectDirectory == null) {
                Path aggregator = ProjectDirectories.aggregatorOf(directory);
                if (aggregator != null) {
                    throw new MojoFailureException(PREFIX + directory + " is a module of " + aggregator + ". IntelliJ"
                            + " usually opens the reactor root, where the generated $PROJECT_DIR$ paths would point at"
                            + " the wrong pom: run vidocq:idea from " + aggregator.getParent() + ", or add"
                            + " -Dvidocq.idea.projectDirectory=" + directory + " if IntelliJ opens " + directory
                            + " itself.");
                }
                return directory;
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot read " + given, e);
        }
        boolean reactorDirectory = false;
        for (MavenProject project : projects) {
            File basedir = project.getBasedir();
            try {
                if (basedir != null && basedir.isDirectory() && basedir.toPath().toRealPath().equals(directory)) {
                    reactorDirectory = true;
                    break;
                }
            } catch (IOException e) {
                getLog().debug(PREFIX + "cannot resolve " + basedir + ": " + e.getMessage());
            }
        }
        if (!reactorDirectory) {
            getLog().warn(PREFIX + directory + " is not the directory of a project of this build. IntelliJ loads"
                    + " .run/*.run.xml files only inside the project content: make sure the IntelliJ project"
                    + " includes " + directory + ".");
        }
        return directory;
    }

    /** Writes through a temporary file and a move, so that IntelliJ, which reloads on change, never reads half a file. */
    private void replace(Path runDirectory, Target target) throws MojoExecutionException {
        try {
            Files.createDirectories(runDirectory);
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot write " + runDirectory, e);
        }
        // Not a *.run.xml name: IntelliJ ignores it.
        Path temporary = runDirectory.resolve(".vidocq-idea-" + UUID.randomUUID() + ".tmp");
        String content = RunConfigurationRenderer.markerLine(target.body()) + "\n" + target.body();
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            try {
                Files.move(temporary, target.file(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target.file(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot write " + target.file(), e);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException e) {
                getLog().debug(PREFIX + "cannot delete " + temporary + ": " + e.getMessage());
            }
        }
    }

    private static byte[] read(Path file) throws MojoExecutionException {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot read " + file, e);
        }
    }

    private static boolean isAbsolutePath(String value) {
        return value.startsWith("/") || value.startsWith("\\") || value.startsWith("~")
                || value.matches("[A-Za-z]:[\\\\/].*");
    }

    private void log(Diagnostic diagnostic) {
        switch (diagnostic.level()) {
            case DEBUG -> getLog().debug(diagnostic.message());
            case INFO -> getLog().info(diagnostic.message());
            case WARN -> getLog().warn(diagnostic.message());
        }
    }

    // Package-private accessors used in unit tests — keep at the bottom so the
    // execute() flow is the first thing a reader sees.
    void setProjectDirectory(File projectDirectory) { this.projectDirectory = projectDirectory; }
    void setCheck(boolean check) { this.check = check; }
    void setGenerateBeforeLaunch(boolean generateBeforeLaunch) { this.generateBeforeLaunch = generateBeforeLaunch; }
    void setJre(String jre) { this.jre = jre; }
    void setSkip(boolean skip) { this.skip = skip; }
    void setMojoExecution(MojoExecution mojoExecution) { this.mojoExecution = mojoExecution; }
}
