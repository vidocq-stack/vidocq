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

import io.vidocq.runtime.maven.ApplicationMainClass;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.Profile;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Finds the Vidocq applications of a reactor and what their IntelliJ run configurations contain.
 *
 * <p>A project is an application when:
 * <ol>
 *   <li>its packaging is not {@code pom};</li>
 *   <li>{@value #PLUGIN_KEY} is in its effective {@code <build><plugins>} (inherited declarations and
 *       active profiles count, {@code <pluginManagement>} alone does not): the before-launch step runs
 *       {@code vidocq:generate} on its pom, which must resolve the plugin and its configuration there;</li>
 *   <li>its pom does not set {@code vidocq.idea.skip} to {@code true};</li>
 *   <li>it declares an application main class ({@link ApplicationMainClass#declared(MavenProject)}).</li>
 * </ol>
 * Every value is read from the project's own model, never from the command line: on an aggregator a
 * {@code -D} would apply to every module at once, and the committed files would depend on how Maven was
 * invoked.
 *
 * <p>The result is pure data (applications, diagnostics to log, configuration errors), so that the goal
 * can report every problem before it writes anything.
 */
final class IdeaApplications {

    static final String PLUGIN_KEY = ApplicationMainClass.PLUGIN_KEY;

    private static final String COMPILER_KEY = "org.apache.maven.plugins:maven-compiler-plugin";
    private static final String PREFIX = "Vidocq idea: ";
    private static final Pattern JAVA_NAME = Pattern.compile(
            "([\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*\\.)*"
                    + "[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*");
    /** Application settings that a command-line {@code -D} must not change. */
    private static final List<String> POM_ONLY_KEYS = List.of(ApplicationMainClass.PROPERTY,
            ApplicationMainClass.MODULE_PROPERTY, "vidocq.idea.configurationName", "vidocq.idea.moduleName");

    enum Level { DEBUG, INFO, WARN }

    record Diagnostic(Level level, String message) {
    }

    record Discovery(List<IdeaApplication> applications, List<Diagnostic> diagnostics, List<String> errors) {
    }

    private IdeaApplications() {
    }

    /**
     * Discovers the applications among {@code projects}, in reactor order.
     *
     * @param projectDirectory the directory IntelliJ opens; every application pom must be inside it
     * @param userProperties   the command-line properties, only to report the ones that are ignored
     */
    static Discovery discover(List<MavenProject> projects, Path projectDirectory, Properties userProperties) {
        List<IdeaApplication> applications = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Path directory = realPath(projectDirectory);

        for (MavenProject project : projects) {
            String coordinates = project.getGroupId() + ":" + project.getArtifactId();
            if ("pom".equals(project.getPackaging())) {
                diagnostics.add(new Diagnostic(Level.DEBUG, notAnApplication(coordinates, "packaging pom")));
                continue;
            }
            Plugin plugin = project.getPlugin(PLUGIN_KEY);
            if (plugin == null) {
                diagnostics.add(new Diagnostic(Level.DEBUG, notAnApplication(coordinates,
                        "vidocq-runtime-maven-plugin is not in its <build><plugins>")));
                continue;
            }
            if (Boolean.parseBoolean(project.getProperties().getProperty("vidocq.idea.skip"))) {
                diagnostics.add(new Diagnostic(Level.INFO, PREFIX + coordinates
                        + " skipped (vidocq.idea.skip=true in its pom)"));
                continue;
            }
            String declared = ApplicationMainClass.declared(project);
            String mainClass = ApplicationMainClass.normalize(declared);
            if (mainClass == null) {
                reportNoMainClass(project, plugin, coordinates, diagnostics);
                continue;
            }

            int errorsBefore = errors.size();
            if (!JAVA_NAME.matcher(mainClass).matches()) {
                errors.add(PREFIX + coordinates + ": the main class \"" + mainClass + "\" is not a Java class name.");
            }
            String configurationName = pomValue(project, "vidocq.idea.configurationName",
                    simpleName(mainClass), coordinates, errors);
            String moduleName = pomValue(project, "vidocq.idea.moduleName", project.getArtifactId(),
                    coordinates, errors);
            String pomPath = pomPath(project.getFile(), directory, coordinates, errors);
            if (errors.size() > errorsBefore) {
                continue;
            }

            applications.add(new IdeaApplication(coordinates, mainClass, configurationName, moduleName, pomPath,
                    generateGoal(plugin, coordinates, diagnostics)));

            if (project.getProperties().getProperty("vidocq.idea.moduleName") == null) {
                String reason = splitReason(project);
                if (reason != null) {
                    String artifactId = project.getArtifactId();
                    diagnostics.add(new Diagnostic(Level.WARN, PREFIX + "IntelliJ may import " + coordinates
                            + " as the modules '" + artifactId + ".main' and '" + artifactId + ".test' (" + reason
                            + "). If Run on its main class does not reuse the generated configuration, set"
                            + " <vidocq.idea.moduleName>" + artifactId + ".main</vidocq.idea.moduleName> in its pom."));
                }
            }
            List<String> profiles = vidocqProfiles(project);
            if (!profiles.isEmpty()) {
                diagnostics.add(new Diagnostic(Level.INFO, PREFIX + coordinates + " takes Vidocq settings from"
                        + " active profile(s) " + String.join(", ", profiles)
                        + "; run vidocq:idea and its check with the same profiles."));
            }
        }

        for (String key : POM_ONLY_KEYS) {
            if (userProperties.getProperty(key) != null) {
                diagnostics.add(new Diagnostic(Level.WARN, PREFIX + "ignoring -D" + key + " from the command line:"
                        + " application settings are read from each module's pom, so that the files in .run/ do"
                        + " not depend on how Maven was invoked."));
            }
        }
        return new Discovery(List.copyOf(applications), List.copyOf(diagnostics), List.copyOf(errors));
    }

    private static String notAnApplication(String coordinates, String reason) {
        return PREFIX + coordinates + " is not a Vidocq application: " + reason;
    }

    private static void reportNoMainClass(MavenProject project, Plugin plugin, String coordinates,
                                          List<Diagnostic> diagnostics) {
        List<String> executions = new ArrayList<>();
        for (PluginExecution execution : plugin.getExecutions()) {
            Xpp3Dom configuration = dom(execution.getConfiguration());
            Xpp3Dom mainClass = configuration == null ? null : configuration.getChild("mainClass");
            if (mainClass != null && ApplicationMainClass.normalize(mainClass.getValue()) != null) {
                executions.add(execution.getId());
            }
        }
        if (!executions.isEmpty()) {
            diagnostics.add(new Diagnostic(Level.WARN, PREFIX + coordinates + " sets mainClass only in the"
                    + " configuration of execution(s) " + String.join(", ", executions) + ", which vidocq:idea"
                    + " does not read. Declare <vidocq.mainClass> (and <vidocq.mainModule>) in its <properties>"
                    + " to get a run configuration."));
        } else if (ApplicationMainClass.declaredMainModule(project) != null) {
            diagnostics.add(new Diagnostic(Level.INFO, PREFIX + coordinates + " sets vidocq.mainModule but no"
                    + " application main class; an IntelliJ Application configuration needs vidocq.mainClass."));
        } else {
            diagnostics.add(new Diagnostic(Level.DEBUG, notAnApplication(coordinates,
                    "no application main class (vidocq.mainClass)")));
        }
    }

    /** A per-application setting from the pom, or its default; blank or control characters are errors. */
    private static String pomValue(MavenProject project, String key, String defaultValue, String coordinates,
                                   List<String> errors) {
        String value = project.getProperties().getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        if (value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
            errors.add(PREFIX + coordinates + ": " + key + " must not be blank or contain control characters.");
        }
        return value;
    }

    /** The text after the last dot of the qualified name ({@code $} of a nested class read as a dot). */
    private static String simpleName(String mainClass) {
        String qualified = mainClass.replace('$', '.');
        return qualified.substring(qualified.lastIndexOf('.') + 1);
    }

    private static String pomPath(File pom, Path directory, String coordinates, List<String> errors) {
        Path file = realPath(pom.toPath());
        if (!file.startsWith(directory)) {
            errors.add(PREFIX + coordinates + ": its pom " + file + " is outside " + directory + ". Run vidocq:idea"
                    + " from a directory that contains every application, or set -Dvidocq.idea.projectDirectory.");
            return null;
        }
        return directory.relativize(file).toString().replace(File.separatorChar, '/');
    }

    /**
     * The before-launch goal. A goal run on its own uses the {@code default-cli} execution, which sees the
     * plugin-level configuration only; {@code goal@execution} uses that execution's configuration instead.
     * Maven copies the plugin-level configuration into every execution of the effective model, so an
     * execution configures {@code generate} itself only when its configuration differs from the
     * plugin-level one.
     */
    private static String generateGoal(Plugin plugin, String coordinates, List<Diagnostic> diagnostics) {
        Xpp3Dom pluginConfiguration = dom(plugin.getConfiguration());
        List<String> configured = new ArrayList<>();
        for (PluginExecution execution : plugin.getExecutions()) {
            if ("default-cli".equals(execution.getId()) || !execution.getGoals().contains("generate")) {
                continue;
            }
            Xpp3Dom configuration = dom(execution.getConfiguration());
            if (configuration != null && configuration.getChildCount() > 0
                    && !configuration.equals(pluginConfiguration)) {
                configured.add(execution.getId());
            }
        }
        if (configured.size() == 1 && configured.get(0).chars().noneMatch(Character::isWhitespace)) {
            return "vidocq:generate@" + configured.get(0);
        }
        if (!configured.isEmpty()) {
            String why = configured.size() == 1
                    ? " execution '" + configured.get(0) + "', whose id contains a space that the before-launch"
                    + " step would split;"
                    : " several executions (" + String.join(", ", configured) + "). The before-launch step runs one"
                    + " Maven invocation;";
            diagnostics.add(new Diagnostic(Level.WARN, PREFIX + coordinates + " configures the generate goal on"
                    + why + " it runs \"vidocq:generate\", which sees the plugin-level configuration only. Move the"
                    + " shared settings, such as <scanDependencies>, to the plugin-level <configuration>."));
        }
        return "vidocq:generate";
    }

    /**
     * A reason why IntelliJ may split the module into {@code <name>.main} and {@code <name>.test}, among the
     * triggers of its Maven import that the model shows ({@code needCreateCompoundModule} in IntelliJ 2026.2),
     * or {@code null}. A heuristic: it only feeds a warning.
     */
    static String splitReason(MavenProject project) {
        Plugin compiler = project.getPlugin(COMPILER_KEY);
        Properties properties = project.getProperties();
        for (String level : List.of("release", "source", "target")) {
            String testLevel = "test" + Character.toUpperCase(level.charAt(0)) + level.substring(1);
            String test = compilerValue(compiler, testLevel, properties.getProperty("maven.compiler." + testLevel));
            if (test == null) {
                continue;
            }
            String main = compilerValue(compiler, level, properties.getProperty("maven.compiler." + level));
            if (!test.equals(main)) {
                return testLevel + " " + test + " differs from " + level + " " + (main == null ? "(unset)" : main);
            }
        }
        if (compiler == null) {
            return null;
        }
        for (String arguments : List.of("testCompilerArgument", "testCompilerArguments")) {
            if (compilerConfigurations(compiler).stream().anyMatch(c -> c.getChild(arguments) != null)) {
                return arguments + " is set";
            }
        }
        List<PluginExecution> executions = compiler.getExecutions();
        if (!executions.isEmpty()) {
            PluginExecution compile = executions.stream()
                    .filter(e -> isExecutionOf(e, "compile", "compile", "default-compile")).findFirst().orElse(null);
            PluginExecution testCompile = executions.stream()
                    .filter(e -> isExecutionOf(e, "test-compile", "testCompile", "default-testCompile"))
                    .findFirst().orElse(null);
            Xpp3Dom compileArgs = child(compile, "compilerArgs");
            Xpp3Dom testCompileArgs = child(testCompile, "compilerArgs");
            if (compileArgs == null ? testCompileArgs != null : !compileArgs.equals(testCompileArgs)) {
                return "compile and testCompile use different compilerArgs";
            }
            Xpp3Dom compileToolchain = executions.stream()
                    .filter(e -> isExecutionOf(e, "compile", "compile", "default-compile"))
                    .map(e -> child(e, "jdkToolchain")).filter(Objects::nonNull).findFirst().orElse(null);
            Xpp3Dom testToolchain = executions.stream()
                    .filter(e -> isExecutionOf(e, "test-compile", "testCompile", "default-testCompile"))
                    .map(e -> child(e, "jdkToolchain")).filter(Objects::nonNull).findFirst().orElse(null);
            if (!Objects.equals(compileToolchain, testToolchain)) {
                return "compile and testCompile use different jdkToolchain";
            }
            for (PluginExecution execution : executions) {
                Xpp3Dom roots = child(execution, "compileSourceRoots");
                if (roots != null && roots.getChildCount() > 0
                        && (execution.getPhase() == null || "compile".equals(execution.getPhase()))
                        && !"default-compile".equals(execution.getId())
                        && !"default-testCompile".equals(execution.getId())) {
                    return "execution '" + execution.getId() + "' adds compileSourceRoots";
                }
            }
        }
        return null;
    }

    private static String compilerValue(Plugin compiler, String name, String property) {
        if (compiler != null) {
            for (Xpp3Dom configuration : compilerConfigurations(compiler)) {
                Xpp3Dom child = configuration.getChild(name);
                if (child != null && child.getValue() != null && !child.getValue().isBlank()) {
                    return child.getValue().strip();
                }
            }
        }
        return property == null || property.isBlank() ? null : property.strip();
    }

    private static List<Xpp3Dom> compilerConfigurations(Plugin compiler) {
        List<Xpp3Dom> configurations = new ArrayList<>();
        Xpp3Dom pluginLevel = dom(compiler.getConfiguration());
        if (pluginLevel != null) {
            configurations.add(pluginLevel);
        }
        for (PluginExecution execution : compiler.getExecutions()) {
            Xpp3Dom configuration = dom(execution.getConfiguration());
            if (configuration != null) {
                configurations.add(configuration);
            }
        }
        return configurations;
    }

    private static boolean isExecutionOf(PluginExecution execution, String phase, String goal, String defaultId) {
        return !"none".equals(execution.getPhase())
                && (phase.equals(execution.getPhase()) || execution.getGoals().contains(goal)
                || defaultId.equals(execution.getId()));
    }

    private static Xpp3Dom child(PluginExecution execution, String name) {
        Xpp3Dom configuration = execution == null ? null : dom(execution.getConfiguration());
        return configuration == null ? null : configuration.getChild(name);
    }

    /** Active profiles of the project's own pom that declare the plugin or a {@code vidocq.*} property. */
    private static List<String> vidocqProfiles(MavenProject project) {
        List<String> ids = new ArrayList<>();
        for (Profile profile : project.getActiveProfiles()) {
            boolean plugin = profile.getBuild() != null
                    && profile.getBuild().getPlugins().stream().anyMatch(p -> PLUGIN_KEY.equals(p.getKey()));
            boolean properties = profile.getProperties().stringPropertyNames().stream()
                    .anyMatch(key -> key.startsWith("vidocq."));
            if (plugin || properties) {
                ids.add(profile.getId());
            }
        }
        return ids;
    }

    private static Xpp3Dom dom(Object configuration) {
        return configuration instanceof Xpp3Dom dom ? dom : null;
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }
}
