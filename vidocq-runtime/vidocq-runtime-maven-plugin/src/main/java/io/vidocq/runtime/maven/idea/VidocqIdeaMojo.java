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

import io.vidocq.runtime.maven.VidocqTestPathMojo;
import io.vidocq.runtime.maven.depgen.AetherArtifactResolver;
import io.vidocq.runtime.maven.depgen.ArtifactResolver;
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
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
 * <p>By default each application gets three files (Vidocq/vidocq#143), named after the application (its
 * main class, or its artifactId when the runtime's own main boots it) and suffixed: a <i>(dev)</i> configuration, an IntelliJ <b>Maven</b> run of {@code process-classes vidocq:dev}
 * (console, reload, continuous testing) on the application's own pom, as {@code vidocq dev} runs it; a
 * <i>(run)</i> configuration, the same Maven run of {@code vidocq:run} instead; and a <i>(debug)</i>
 * configuration, a Remote JVM Debug run that
 * attaches to the debug agent {@code vidocq:dev} starts. A Maven run compiles, indexes and forks the JVM
 * exactly as on the command line, so it can never miss the bean index of the dependency jars
 * (Vidocq/vidocq#83) — the IDE's own build never runs {@code vidocq:generate}.
 * {@code -Dvidocq.idea.kind=application} writes a single <b>Application</b> run of
 * the main class instead, with two before-launch steps: IntelliJ's Make, then {@code vidocq:generate} on
 * the application's pom; {@code -Dvidocq.idea.generateBeforeLaunch=false} leaves that second step out. An
 * application without a main class of its own gets no Application run, and a warning.
 *
 * <p>Next to them, it writes the shared JUnit template {@code .run/Template JUnit.run.xml}: IntelliJ IDEA 2026.3
 * fails to start the tests of a module with a descriptor (IDEA-391927), and the template starts every test with
 * {@code io.vidocq.runtime:vidocq-runtime-test-agent} of this plugin's version, taken from IntelliJ's local Maven
 * repository, which completes the module's access from inside the test JVM. A write resolves the agent so that its
 * jar is there; {@code -Dvidocq.idea.junitTemplate=false} leaves the template out.
 *
 * <p>The goal writes nothing else into the project: never {@code .idea/}, {@code *.iml} or any other project file, which
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
    /** How a message names a file of the run configuration directory. */
    private static final String RUN_DIRECTORY = ".run/";
    /** How a message names what the JUnit template is for. */
    private static final String JUNIT_TEMPLATE_OWNER = "the JUnit tests of this project";

    /**
     * {@link #kind}, parsed once at the start of {@link #run}: every method that renders a body reads it, and
     * the goal runs once per lookup.
     */
    private Kind renderKind = Kind.MAVEN;
    /** The files the other kind writes for this build's applications: see {@link #otherKindFiles}. */
    private Map<String, String> otherKindFiles = Map.of();

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
     * — {@code (dev)} ({@code vidocq:dev}), {@code (run)} ({@code vidocq:run}) and {@code (debug)} (Remote JVM
     * Debug) — each of which compiles, indexes and forks the JVM as the command line does. {@code application}:
     * a single IntelliJ Application run configuration of the main class, with Make and {@code vidocq:generate}
     * before launch.
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

    /**
     * Also write the shared JUnit template, {@code .run/Template JUnit.run.xml}, whose VM options start every test
     * with {@code io.vidocq.runtime:vidocq-runtime-test-agent} of this plugin's version, from IntelliJ's local Maven
     * repository: IntelliJ IDEA 2026.3 fails to start the tests of a module with a descriptor otherwise
     * (IDEA-391927). A write resolves that agent, so that the jar is there; when it cannot, the template is written
     * all the same, with a warning. {@code false} leaves the template out.
     */
    @Parameter(property = "vidocq.idea.junitTemplate", defaultValue = "true")
    private boolean junitTemplate;

    /** This plugin's own version, which is the runtime's, and the version of the test agent the template names. */
    @Parameter(defaultValue = "${plugin.version}", readonly = true, required = true)
    private String pluginVersion;

    /**
     * Resolves the test agent. Injected by the {@code <requirement>} of the hand-written descriptor
     * ({@code META-INF/maven/plugin.xml}), which Maven reads instead of annotations.
     */
    private RepositorySystem repoSystem;

    /** Aether repository session, to resolve the test agent the JUnit template names. */
    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    /** The project's plugin repositories, where the test agent is looked for first: it is released with this plugin. */
    @Parameter(defaultValue = "${project.remotePluginRepositories}", readonly = true)
    private List<RemoteRepository> pluginRepos;

    /** The project's own remote repositories, where the test agent is looked for too. */
    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
    private List<RemoteRepository> remoteRepos;

    /** Resolves the test agent; built from the Aether objects above in {@link #execute}, a fake in the tests. */
    private ArtifactResolver agentResolver;

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
        if (agentResolver == null) {
            agentResolver = new AetherArtifactResolver(repoSystem, repoSession,
                    AetherArtifactResolver.repositories(remoteRepos, pluginRepos));
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

        Discovery all = IdeaApplications.discover(projects, allProjects, directory, userProperties);
        Discovery discovery = IdeaApplications.forKind(all, renderKind);
        otherKindFiles = otherKindFiles(all.applications());
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
            if (all.applications().isEmpty()) {
                getLog().warn(PREFIX + "no Vidocq application in this build: a module needs vidocq-runtime-maven-plugin"
                        + " in its <build><plugins> and a "
                        + (renderKind == Kind.MAVEN ? "vidocq.mainModule" : "vidocq.mainClass") + " property.");
            } else {
                // Each one was reported above; saying the build has no application would contradict it (BUG-20261116-01).
                getLog().info(PREFIX + "no Application run configuration to write: none of the "
                        + all.applications().size() + " application(s) of this build declares a main class.");
            }
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
            resolveTestAgent();
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
     * {@code application} is {@code null} for the JUnit template, which belongs to no application.
     */
    // onDisk is the file's bytes as read; equals, hashCode and toString below compare them by content.
    @SuppressWarnings("ArrayRecordComponent")
    record Target(IdeaApplication application, String shownPath, Path file, String body, byte[] onDisk,
                          State state, UnaryOperator<String> withJre) {

        /** What the file is for, as a message names it: the application's coordinates, or the JUnit tests. */
        String owner() {
            return application == null ? JUNIT_TEMPLATE_OWNER : application.coordinates();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Target(var otherApplication, var otherShownPath, var otherFile, var otherBody,
                                           var otherOnDisk, var otherState, var otherWithJre)
                    && Objects.equals(application, otherApplication) && shownPath.equals(otherShownPath)
                    && file.equals(otherFile) && body.equals(otherBody) && Arrays.equals(onDisk, otherOnDisk)
                    && state == otherState && withJre.equals(otherWithJre);
        }

        @Override
        public int hashCode() {
            return Objects.hash(application, shownPath, file, body, Arrays.hashCode(onDisk), state, withJre);
        }

        @Override
        public String toString() {
            return "Target[" + shownPath + ", " + state + "]";
        }
    }

    private Map<String, Target> targets(List<IdeaApplication> applications, Path runDirectory)
            throws MojoExecutionException, MojoFailureException {
        Map<String, Target> targets = new LinkedHashMap<>();
        for (IdeaApplication application : applications) {
            if (renderKind == Kind.MAVEN) {
                // Vidocq/vidocq#143: one configuration per goal, each named after it, so that the IDE's list
                // tells vidocq:dev from vidocq:run.
                String devName = application.configurationName() + RunConfigurationRenderer.DEV_SUFFIX;
                addTarget(targets, runDirectory, application, devName,
                        candidateJre -> RunConfigurationRenderer.mavenBody(application, candidateJre,
                                RunConfigurationRenderer.DEV_GOALS, devName));
                String runName = application.configurationName() + RunConfigurationRenderer.RUN_SUFFIX;
                addTarget(targets, runDirectory, application, runName,
                        candidateJre -> RunConfigurationRenderer.mavenBody(application, candidateJre,
                                RunConfigurationRenderer.RUN_GOALS, runName));
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
        if (junitTemplate && !targets.isEmpty()) {
            // IDEA-391927: the template names the test agent; it pins no JDK, so the JRE leaves it as it is.
            String body = RunConfigurationRenderer.junitTemplateBody(
                    Objects.requireNonNull(pluginVersion, "the plugin version"));
            addTarget(targets, runDirectory, null, RunConfigurationRenderer.JUNIT_TEMPLATE_NAME, _ -> body);
        }
        return targets;
    }

    /** The coordinates of the test agent the JUnit template names. */
    private String testAgentCoordinates() {
        return RunConfigurationRenderer.TEST_AGENT_GROUP_ID + ":" + RunConfigurationRenderer.TEST_AGENT_ARTIFACT_ID
                + ":" + pluginVersion;
    }

    /**
     * Resolves the test agent the JUnit template names, so that its jar is in the local repository when IntelliJ
     * starts a test. A failure (offline, agent not published) is a warning: the template is right all the same.
     */
    private void resolveTestAgent() {
        if (!junitTemplate) {
            return;
        }
        String coordinates = testAgentCoordinates();
        try {
            Path jar = agentResolver.resolve(RunConfigurationRenderer.TEST_AGENT_GROUP_ID,
                    RunConfigurationRenderer.TEST_AGENT_ARTIFACT_ID, pluginVersion, "jar", null);
            getLog().info(PREFIX + "the JUnit template starts every test with the test agent " + coordinates
                    + ", resolved to " + jar + ".");
        } catch (IOException e) {
            getLog().warn(PREFIX + "cannot resolve " + coordinates + " (" + e.getMessage() + "). The JUnit template"
                    + " names its jar in the local Maven repository: until it is there, a JUnit run created from the"
                    + " template fails to start. Run \"mvn vidocq:idea\" again online to download it.");
        }
        // The agent is for the test JVM. The application a @VidocqTest test starts needs the path of
        // vidocq:test-path, and IntelliJ runs no Maven build (BUG-20261122-02).
        getLog().info(PREFIX + "a test annotated @VidocqTest also needs the runtime path of its application, which a"
                + " Maven build writes to target/" + VidocqTestPathMojo.FILE + ": run \"mvn process-test-classes\""
                + " once in the application, and again after a change of dependencies.");
    }

    /** Adds one file to {@code targets}, failing on a name collision with a file already added. */
    private void addTarget(Map<String, Target> targets, Path runDirectory, IdeaApplication application,
                           String configurationName, UnaryOperator<String> withJre)
            throws MojoExecutionException, MojoFailureException {
        String fileName = RunConfigurationRenderer.fileName(configurationName);
        // Case-insensitive: two names that differ only by case are one file on macOS and Windows.
        String key = fileName.toLowerCase(Locale.ROOT);
        Path file = runDirectory.resolve(fileName);
        String body = withJre.apply(jre);
        byte[] onDisk = Files.isRegularFile(file) ? read(file) : null;
        Target target = new Target(application, RUN_DIRECTORY + fileName, file, body, onDisk,
                RunConfigurationFiles.classify(onDisk, body), withJre);
        Target other = targets.get(key);
        if (other != null) {
            throw new MojoFailureException(PREFIX + other.owner() + " and " + target.owner() + " both map to .run/"
                    + fileName + " (configuration name \"" + configurationName + "\"). Set"
                    + " <vidocq.idea.configurationName> in "
                    + (application == null ? "the pom of " + other.owner() : "one of their poms") + ".");
        }
        targets.put(key, target);
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
            if (!target.state().userOwned()) {
                checked++;
                logState(target);
                if (target.state().writes()) {
                    failures++;
                }
            } else if (!strict) {
                warnUserOwned(target);
                unverified.add(target.shownPath());
            } else {
                checked++;
                if (userOwnedDiffers(target)) {
                    failures++;
                    userOwnedFailures++;
                }
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
        reportUpToDate(runDirectory, checked, unverified);
    }

    /** Logs why a target vidocq:idea owns does not match, if it does not. */
    private void logState(Target target) {
        String about = target.owner();
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
    }

    /** Whether a file that belongs to the user differs from what vidocq:idea writes; logs the difference if so. */
    private boolean userOwnedDiffers(Target target) {
        if (RunConfigurationFiles.shownBody(target.onDisk()).equals(target.body())) {
            return false;
        }
        getLog().error(PREFIX + target.shownPath() + (target.state() == State.EDITED
                ? " was edited after vidocq:idea generated it"
                : " was not generated by vidocq:idea") + " and differs from what vidocq:idea writes for "
                + target.owner() + ":");
        logDiff(target);
        String pinHint = pinHint(target, target.shownPath());
        if (pinHint != null) {
            getLog().error(PREFIX + pinHint);
        }
        return true;
    }

    /** Logs how many run configurations are up to date, and those not verified because they belong to the user. */
    private void reportUpToDate(Path runDirectory, int checked, List<String> unverified) {
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

    /** What {@link #write} did with one file, in the order the summary line lists them. */
    private enum Outcome {
        CREATED("created"), UPDATED("updated"), UNCHANGED("unchanged"), UNTOUCHED("left untouched");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }
    }

    private void write(Iterable<Target> targets, Path runDirectory, Map<String, Target> byKey)
            throws MojoExecutionException {
        boolean runDirectoryExisted = Files.isDirectory(runDirectory);
        Map<Outcome, Integer> outcomes = new EnumMap<>(Outcome.class);
        for (Target target : targets) {
            outcomes.merge(writeFile(target, runDirectory), 1, Integer::sum);
        }
        reportOrphans(runDirectory, byKey);

        List<String> counts = new ArrayList<>();
        outcomes.forEach((outcome, count) -> counts.add(count + " " + outcome.label));
        getLog().info(PREFIX + byKey.size() + " run configuration(s): " + String.join(", ", counts));

        int written = outcomes.getOrDefault(Outcome.CREATED, 0) + outcomes.getOrDefault(Outcome.UPDATED, 0);
        if (written > 0) {
            logRunnerJre();
            if (jre == null) {
                warnUnpinnedJre();
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

    /** What a write message says of the file: the application it runs, or the test agent of the JUnit template. */
    private String details(IdeaApplication application) {
        if (application == null) {
            return " (JUnit template: every test starts with the test agent " + testAgentCoordinates() + ")";
        }
        String mainClass = application.mainClass() == null
                ? ", no main class of its own" : ", main class " + application.mainClass();
        return " (" + application.coordinates() + mainClass + ", IntelliJ module " + application.moduleName() + ")";
    }

    private Outcome writeFile(Target target, Path runDirectory) throws MojoExecutionException {
        String details = details(target.application());
        return switch (target.state()) {
            case UNCHANGED -> {
                getLog().info(PREFIX + "unchanged " + target.shownPath() + details);
                yield Outcome.UNCHANGED;
            }
            case EDITED, FOREIGN -> {
                warnUserOwned(target);
                yield Outcome.UNTOUCHED;
            }
            case MISSING -> {
                replace(runDirectory, target);
                getLog().info(PREFIX + "created " + target.shownPath() + details);
                yield Outcome.CREATED;
            }
            case OUTDATED, UNMARKED -> {
                replace(runDirectory, target);
                getLog().info(PREFIX + "updated " + target.shownPath()
                        + (target.state() == State.UNMARKED ? ", marker added" : "") + details);
                yield Outcome.UPDATED;
            }
        };
    }

    /** The JDK IntelliJ must run the written configurations on, which depends on their kind. */
    private void logRunnerJre() {
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
    }

    private void warnUnpinnedJre() {
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
        // The JUnit template never pins a JDK, whatever vidocq.idea.jre says: nothing to keep.
        String pinned = jre == null && target.application() != null
                ? RunConfigurationFiles.pinnedJre(target.onDisk()) : null;
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
            String key = name.toLowerCase(Locale.ROOT);
            if (targets.containsKey(key) || !RunConfigurationFiles.isGenerated(read(file))) {
                continue;
            }
            String otherKind = otherKindFiles.get(key);
            if (key.equals(fileKey(RunConfigurationRenderer.JUNIT_TEMPLATE_NAME))) {
                // Written only next to the configurations of an application: without one, it is not an orphan.
                if (!junitTemplate) {
                    getLog().info(PREFIX + RUN_DIRECTORY + name + " is the JUnit template, which"
                            + " vidocq.idea.junitTemplate=false does not write: delete it if you no longer use it.");
                }
            } else if (otherKind != null) {
                getLog().info(PREFIX + RUN_DIRECTORY + name + " is " + otherKind + ", which vidocq.idea.kind="
                        + renderKind.property() + " does not write: delete it if you no longer use it.");
            } else {
                getLog().warn(PREFIX + RUN_DIRECTORY + name + " was generated by vidocq:idea but no application of this"
                        + " build maps to it (main class or vidocq.idea.configurationName changed, application"
                        + " removed, or module left out with -pl). Delete it if it is obsolete.");
            }
        }
    }

    /**
     * The files the other kind writes for {@code applications}, by lower-case file name, each with what it is: a
     * file of the Maven kind is not an orphan of a run of the Application kind, nor the reverse
     * (BUG-20261116-01).
     */
    private Map<String, String> otherKindFiles(List<IdeaApplication> applications) {
        Map<String, String> files = new HashMap<>();
        for (IdeaApplication application : applications) {
            String name = application.configurationName();
            String of = " of " + application.coordinates();
            if (renderKind == Kind.APPLICATION) {
                files.put(fileKey(name + RunConfigurationRenderer.DEV_SUFFIX), "a Maven run configuration" + of);
                files.put(fileKey(name + RunConfigurationRenderer.RUN_SUFFIX), "a Maven run configuration" + of);
                files.put(fileKey(name + RunConfigurationRenderer.DEBUG_SUFFIX), "the debug configuration" + of);
            } else if (application.mainClass() != null) {
                files.put(fileKey(name), "an Application run configuration" + of);
            }
        }
        return files;
    }

    private static String fileKey(String configurationName) {
        return RunConfigurationRenderer.fileName(configurationName).toLowerCase(Locale.ROOT);
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
                            + " the wrong pom: run vidocq:idea from " + ProjectDirectories.reactorRootOf(directory)
                            + ", or add"
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
            moveInPlace(temporary, target.file());
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

    /** Moves {@code temporary} over {@code file}, atomically when the file system can. */
    private static void moveInPlace(Path temporary, Path file) throws IOException {
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException _) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
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
    void setJunitTemplate(boolean junitTemplate) { this.junitTemplate = junitTemplate; }
    void setPluginVersion(String pluginVersion) { this.pluginVersion = pluginVersion; }
    void setAgentResolver(ArtifactResolver agentResolver) { this.agentResolver = agentResolver; }
}
