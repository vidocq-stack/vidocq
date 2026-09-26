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
import io.vidocq.runtime.maven.idea.RunConfigurationRenderer.Kind;
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
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * <b>Experimental.</b> Writes IntelliJ IDEA shared run configurations for the Vidocq applications of the
 * reactor into {@code .run/}, or checks them. Invoke it from the command line, in the directory IntelliJ
 * opens as the project (usually the reactor root):
 *
 * <pre>mvn vidocq:idea
 * mvn vidocq:idea -Dvidocq.idea.check=true
 * mvn vidocq:idea -Dvidocq.idea.check=strict</pre>
 *
 * <p>By default each application gets three files (Vidocq/vidocq#143): the <i>Dev</i> configuration, an
 * IntelliJ <b>Maven</b> run of {@code vidocq:dev} (console, reload, continuous testing) on the application's
 * own pom, which keeps the file name and the configuration name a developer already uses; a <i>(packaged)</i>
 * configuration, the same Maven run of {@code vidocq:run} instead, as the single file did before this
 * application had a Dev configuration; and a <i>(debug)</i> configuration, a Remote JVM Debug run that
 * attaches to the debug agent {@code vidocq:dev} starts. A Maven run compiles, indexes and forks the JVM
 * exactly as on the command line, so it can never miss the bean index of the dependency jars
 * (Vidocq/vidocq#83) — the IDE's own build never runs {@code vidocq:generate}.
 * {@code -Dvidocq.idea.kind=application} writes a single <b>Application</b> run of
 * the main class instead, with two before-launch steps: IntelliJ's Make, then {@code vidocq:generate} on
 * the application's pom; {@code -Dvidocq.idea.generateBeforeLaunch=false} leaves that second step out.
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

    /**
     * {@link #kind}, parsed once at the start of {@link #run}: every method that renders a body reads it, and
     * the goal runs once per lookup.
     */
    private Kind renderKind = Kind.MAVEN;

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

    /**
     * {@code false} writes the files. {@code true} writes nothing and fails where a write would change
     * {@code .run/}; a file that belongs to the user (written by hand, or edited since it was generated) is
     * reported as not verified. {@code strict} also fails on such a file when its content differs from what
     * the goal would write.
     */
    @Parameter(property = "vidocq.idea.check", defaultValue = "false")
    private String check;

    /**
     * {@code maven} (default): three IntelliJ Maven/Remote run configurations per application (Vidocq/vidocq#143)
     * — Dev ({@code vidocq:dev}), {@code (packaged)} ({@code vidocq:run}) and {@code (debug)} (Remote JVM Debug) —
     * each of which compiles, indexes and forks the JVM as the command line does. {@code application}: a single
     * IntelliJ Application run configuration of the main class, with Make and {@code vidocq:generate} before
     * launch.
     */
    @Parameter(property = "vidocq.idea.kind", defaultValue = "maven")
    private String kind;

    /** Add the {@code vidocq:generate} Maven step before launch; the {@code application} kind only. */
    @Parameter(property = "vidocq.idea.generateBeforeLaunch", defaultValue = "true")
    private boolean generateBeforeLaunch;

    /**
     * An IntelliJ SDK name (for example {@code temurin-25}) written as the configurations' JDK: the
     * alternative JRE of an Application configuration, the Maven runner JRE of a Maven one — which is the JDK
     * the application runs on too, since {@code vidocq:run} forks it from the JVM running Maven.
     * Declare it in the top-level pom: the name must exist on every machine that uses the files. Unset,
     * IntelliJ launches the application on the module SDK; the configuration verified in IntelliJ pinned its
     * JDK, so the goal warns after writing one that does not.
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
        List<MavenProject> allProjects = session.getAllProjects() != null ? session.getAllProjects() : reactorProjects;
        run(reactorProjects, allProjects, session.getUserProperties(), Path.of(session.getExecutionRootDirectory()));
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

    /**
     * The goal without its Maven session.
     *
     * @param projects    the selected projects of the build, in reactor order
     * @param allProjects every project of the build; more than {@code projects} with {@code -pl}, {@code -rf}
     *                    and the like
     */
    void run(List<MavenProject> projects, List<MavenProject> allProjects, Properties userProperties, Path executionRoot)
            throws MojoExecutionException, MojoFailureException {
        Mode mode = mode(check);
        renderKind = kind(kind);
        Path directory = projectDirectory(projects, executionRoot);
        boolean partialReactor = allProjects.size() > projects.size();

        Discovery discovery = IdeaApplications.discover(projects, allProjects, directory, userProperties);
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
        getLog().info(PREFIX + switch (mode) {
            case WRITE -> "writing";
            case CHECK -> "checking";
            case STRICT_CHECK -> "strictly checking";
        } + " " + (renderKind == Kind.MAVEN ? "Maven" : "Application") + " run configurations of "
                + discovery.applications().size() + " application(s) in " + runDirectory);

        if (mode == Mode.WRITE) {
            write(targets.values(), runDirectory, targets);
        } else {
            check(directory, targets.values(), runDirectory, targets, mode == Mode.STRICT_CHECK);
        }
    }

    /** What {@code vidocq.idea.check} asks for. */
    enum Mode { WRITE, CHECK, STRICT_CHECK }

    static Kind kind(String kind) throws MojoFailureException {
        return Kind.parse(kind).orElseThrow(() -> new MojoFailureException(PREFIX
                + "vidocq.idea.kind must be maven or application, not \"" + kind + "\"."));
    }

    static Mode mode(String check) throws MojoFailureException {
        String value = check == null ? "" : check.strip().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "", "false" -> Mode.WRITE;
            case "true" -> Mode.CHECK;
            case "strict" -> Mode.STRICT_CHECK;
            default -> throw new MojoFailureException(PREFIX + "vidocq.idea.check must be false, true or strict, not \""
                    + check + "\".");
        };
    }

    /**
     * One generated file and the application it belongs to. {@code withJre} re-renders its body for another
     * JRE, keeping everything else about this particular file (its goal, its name, or its debug host and
     * port) unchanged; {@link #pinHint} uses it to tell whether a user-owned file differs only by its JDK.
     */
    private record Target(IdeaApplication application, String shownPath, Path file, String body, byte[] onDisk,
                          State state, UnaryOperator<String> withJre) {
    }

    private Map<String, Target> targets(List<IdeaApplication> applications, Path runDirectory)
            throws MojoExecutionException, MojoFailureException {
        Map<String, Target> targets = new LinkedHashMap<>();
        for (IdeaApplication application : applications) {
            if (renderKind == Kind.MAVEN) {
                // Vidocq/vidocq#143: the Dev configuration keeps the existing name and file, and now runs
                // vidocq:dev; the packaged and debug configurations are new, additional files.
                addTarget(targets, runDirectory, application, application.configurationName(),
                        candidateJre -> RunConfigurationRenderer.mavenBody(application, candidateJre,
                                RunConfigurationRenderer.DEV_GOAL, application.configurationName()));
                String packagedName = application.configurationName() + RunConfigurationRenderer.PACKAGED_SUFFIX;
                addTarget(targets, runDirectory, application, packagedName,
                        candidateJre -> RunConfigurationRenderer.mavenBody(application, candidateJre,
                                RunConfigurationRenderer.RUN_GOAL, packagedName));
                String debugName = application.configurationName() + RunConfigurationRenderer.DEBUG_SUFFIX;
                addTarget(targets, runDirectory, application, debugName,
                        candidateJre -> RunConfigurationRenderer.debugBody(debugName, application.debugHost(),
                                application.debugPort()));
            } else {
                addTarget(targets, runDirectory, application, application.configurationName(),
                        candidateJre -> RunConfigurationRenderer.body(application, renderKind, candidateJre,
                                generateBeforeLaunch));
            }
        }
        return targets;
    }

    /** Adds one file to {@code targets}, failing on a name collision with a file already added. */
    private void addTarget(Map<String, Target> targets, Path runDirectory, IdeaApplication application,
                           String configurationName, UnaryOperator<String> withJre)
            throws MojoExecutionException, MojoFailureException {
        String fileName = RunConfigurationRenderer.fileName(configurationName);
        // Case-insensitive: two names that differ only by case are one file on macOS and Windows.
        String key = fileName.toLowerCase(Locale.ROOT);
        Target other = targets.get(key);
        if (other != null) {
            throw new MojoFailureException(PREFIX + other.application().coordinates() + " and "
                    + application.coordinates() + " both map to .run/" + fileName + " (configuration name \""
                    + configurationName + "\"). Set <vidocq.idea.configurationName> in one of their poms.");
        }
        Path file = runDirectory.resolve(fileName);
        String body = withJre.apply(jre);
        byte[] onDisk = Files.isRegularFile(file) ? read(file) : null;
        targets.put(key, new Target(application, ".run/" + fileName, file, body, onDisk,
                RunConfigurationFiles.classify(onDisk, body), withJre));
    }

    /**
     * Fails exactly where a write would change a file. A file that belongs to the user is never written, so the
     * default check only lists it as not verified; a strict check also fails on it when its content differs.
     */
    private void check(Path directory, Iterable<Target> targets, Path runDirectory, Map<String, Target> byKey,
                       boolean strict) throws MojoExecutionException, MojoFailureException {
        int failures = 0;
        int userOwnedFailures = 0;
        int checked = 0;
        List<String> unverified = new ArrayList<>();
        for (Target target : targets) {
            String about = target.application().coordinates();
            if (target.state().userOwned()) {
                if (!strict) {
                    warnUserOwned(target);
                    unverified.add(target.shownPath());
                    continue;
                }
                checked++;
                if (!RunConfigurationFiles.shownBody(target.onDisk()).equals(target.body())) {
                    getLog().error(PREFIX + target.shownPath() + (target.state() == State.EDITED
                            ? " was edited after vidocq:idea generated it"
                            : " was not generated by vidocq:idea") + " and differs from what vidocq:idea writes for "
                            + about + ":");
                    logDiff(target);
                    String pinHint = pinHint(target, target.shownPath());
                    if (pinHint != null) {
                        getLog().error(PREFIX + pinHint);
                    }
                    failures++;
                    userOwnedFailures++;
                }
                continue;
            }
            checked++;
            switch (target.state()) {
                case MISSING -> getLog().error(PREFIX + target.shownPath() + " is missing for " + about + ".");
                case OUTDATED -> {
                    getLog().error(PREFIX + target.shownPath() + " is out of date for " + about + ":");
                    logDiff(target);
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
                    + " and commit .run/." + (userOwnedFailures == 0 ? "" : " " + userOwnedFailures + " of them"
                    + " belong(s) to you, and vidocq:idea leaves such files untouched: delete them first to regenerate"
                    + " them, or check with -Dvidocq.idea.check=true to keep your changes."));
        }
        if (unverified.isEmpty()) {
            getLog().info(PREFIX + checked + " run configuration(s) in " + runDirectory + " are up to date.");
        } else {
            getLog().warn(PREFIX + checked + " run configuration(s) in " + runDirectory + " are up to date; "
                    + unverified.size() + " NOT verified because " + (unverified.size() == 1 ? "it belongs" : "they belong")
                    + " to you: " + String.join(", ", unverified) + ". -Dvidocq.idea.check=strict fails on such files.");
        }
    }

    private void logDiff(Target target) {
        getLog().error("  --- on disk");
        getLog().error("  +++ expected");
        for (String line : LineDiff.diff(RunConfigurationFiles.shownBody(target.onDisk()), target.body())) {
            getLog().error("  " + line);
        }
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
            if (renderKind == Kind.MAVEN) {
                getLog().info(PREFIX + "IntelliJ needs JDK 25 or newer as the Maven runner JRE (Settings > Build,"
                        + " Execution, Deployment > Build Tools > Maven > Runner), which is also the JDK the"
                        + " application runs on: vidocq:run forks it from the JVM running Maven."
                        + (jre == null ? "" : " These configurations use the '" + jre + "' SDK, which must exist under"
                        + " that name on every machine."));
            } else {
                getLog().info(PREFIX + "IntelliJ needs JDK 25 or newer as the SDK used by Make"
                        + (generateBeforeLaunch ? " and as the Maven runner JRE used by the vidocq:generate step"
                        + " (Settings > Build, Execution, Deployment > Build Tools > Maven > Runner)" : "") + "."
                        + (jre == null ? "" : " The application runs on the '" + jre + "' SDK, which must exist under"
                        + " that name on every machine."));
            }
            if (jre == null) {
                // The configuration measured to work in IntelliJ pinned its JDK; a launch on the JDK IntelliJ
                // picks by itself has not been verified there.
                getLog().warn(PREFIX + "vidocq.idea.jre is not set, so the run configurations written do not pin a"
                        + " JDK: IntelliJ " + (renderKind == Kind.MAVEN
                        ? "runs Maven on its Maven runner JRE" : "launches the application on the module SDK")
                        + ", which can be another JDK than the"
                        + " one it is built and tested with. To launch on a known JDK, declare <vidocq.idea.jre> in the"
                        + " top-level pom with the name of an IntelliJ SDK of Java 25 or newer that exists on every"
                        + " machine, for example <vidocq.idea.jre>temurin-25</vidocq.idea.jre>.");
            }
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
        String pinHint = pinHint(target, "It");
        getLog().warn(PREFIX + target.shownPath() + " belongs to you: it "
                + (target.state() == State.EDITED
                ? "was edited after vidocq:idea generated it"
                : "was not generated by vidocq:idea (written by hand, or saved by IntelliJ after an edit)")
                + ", so vidocq:idea leaves it untouched. "
                + (pinHint != null ? pinHint : "Delete it and run \"mvn vidocq:idea\" to regenerate it."));
    }

    /**
     * What to do with a file that belongs to the user and pins a JDK while {@code vidocq.idea.jre} is not set:
     * regenerating it would silently drop the pin. {@code null} otherwise.
     *
     * @param subject how the sentence names the file
     */
    private String pinHint(Target target, String subject) {
        String pinned = jre == null ? RunConfigurationFiles.pinnedJre(target.onDisk()) : null;
        if (pinned == null) {
            return null;
        }
        String declaration = "<vidocq.idea.jre>" + pinned.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;") + "</vidocq.idea.jre>";
        // Without a marker, a file whose content becomes the expected one is adopted as it is.
        if (target.state() == State.FOREIGN
                && RunConfigurationFiles.shownBody(target.onDisk()).equals(target.withJre().apply(pinned))) {
            return subject + " differs from what vidocq:idea writes only by its JDK '" + pinned + "': declare "
                    + declaration + " in the top-level pom and run \"mvn vidocq:idea\" to adopt it as it is.";
        }
        return subject + " pins the JDK '" + pinned + "', which vidocq:idea only writes when vidocq.idea.jre is set:"
                + " declare " + declaration + " in the top-level pom before you delete it and run \"mvn vidocq:idea\""
                + " to regenerate it.";
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
    void setCheck(String check) { this.check = check; }
    void setKind(String kind) { this.kind = kind; }
    void setGenerateBeforeLaunch(boolean generateBeforeLaunch) { this.generateBeforeLaunch = generateBeforeLaunch; }
    void setJre(String jre) { this.jre = jre; }
    void setSkip(boolean skip) { this.skip = skip; }
    void setMojoExecution(MojoExecution mojoExecution) { this.mojoExecution = mojoExecution; }
}
