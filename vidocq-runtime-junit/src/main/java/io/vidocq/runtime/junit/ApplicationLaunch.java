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
package io.vidocq.runtime.junit;

import io.vidocq.runtime.core.LayerSplit;
import io.vidocq.runtime.core.VidocqAppLayer;

import java.io.File;
import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The command that starts the application under test, and where its parts come from. The runtime's own main boots
 * the application from the module path of the tests, as {@code vidocq:run} does: under Surefire it holds
 * {@code target/classes} and the main dependencies the application module requires, while JUnit and the other test
 * dependencies stay on the class path, out of the application's sight. Surefire also leaves there the runtime
 * dependencies no module requires, a JDBC driver for one: {@code vidocq:test-path} names them in
 * {@value #RUNTIME_PATH_PROPERTY}, and they join the application's path.
 *
 * <p>That path is then split as {@code vidocq:run} splits it ({@link #layout}): the application module and the
 * libraries {@link LayerSplit} gives it go to the application layer through {@code -Dvidocq.app.path}, the runtime and
 * the rest stay on the module path. A module must not be on both: the JVM would resolve it into the boot layer, and a
 * {@link java.util.ServiceLoader} of the application layer would find each of its providers twice (BUG-20261114-07).
 */
final class ApplicationLaunch {

    /** The runtime's own main, which boots the application of {@code vidocq.app.path}. */
    static final String RUNTIME_MAIN = "io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq";

    /** What {@code --add-modules} always holds: every module of the module path is resolved. */
    private static final String ALL_MODULE_PATH = "ALL-MODULE-PATH";

    /**
     * Where the modules of the launch go.
     *
     * @param modulePath the module path: the runtime, the bricks and what they read
     * @param appPath    {@code vidocq.app.path}: the application module and its libraries
     * @param properties the {@code key=value} system properties that tell the runtime how the layer was split
     * @param addModules the value of {@code --add-modules}
     */
    record Layout(List<Path> modulePath, List<Path> appPath, List<String> properties, String addModules) {
        Layout {
            modulePath = List.copyOf(modulePath);
            appPath = List.copyOf(appPath);
            properties = List.copyOf(properties);
        }
    }

    /**
     * The runtime dependencies of the application, as {@code vidocq:test-path} hands them to the tests through
     * Surefire: every jar the packaged application would have on its module path.
     */
    static final String RUNTIME_PATH_PROPERTY = "vidocq.test.runtimePath";

    /**
     * Copy of {@code io.vidocq.runtime.devservices.host.DevServicesSession.PROVIDED_PREFIX}: the dev services session
     * marks each key it injects with {@code vidocq.dev.provided.<key>=<provider id>}. A copy, not an import: this
     * module does not depend on the dev services.
     */
    static final String PROVIDED_PREFIX = "vidocq.dev.provided.";

    /** Copy of {@code io.vidocq.runtime.devservices.host.StateFile.PROPERTY}, for the same reason. */
    static final String STATE_PROPERTY = "vidocq.devservices.state";

    /** Copy of {@code io.vidocq.runtime.maven.EnrichedJars.DIR_NAME}: where {@code vidocq:generate} writes its copies. */
    static final String ENRICHED_DIR = "vidocq-enriched";

    private ApplicationLaunch() {}

    /**
     * The dev services of the test run, as {@code key=value}: each key a session injected into {@code system}, with
     * its marker, then the state file. {@code vidocq test}, {@code vidocq dev} and the JUnit listener put them in the
     * test JVM, and the application, in its own JVM, would not see them otherwise (BUG-20261030-20). A key set by
     * hand carries no marker and stays in the test JVM.
     */
    static List<String> devServiceProperties(Properties system) {
        List<String> properties = new ArrayList<>();
        new TreeSet<>(system.stringPropertyNames()).forEach(name -> {
            if (name.startsWith(PROVIDED_PREFIX)) {
                String key = name.substring(PROVIDED_PREFIX.length());
                String value = system.getProperty(key);
                if (value != null) {
                    properties.add(key + "=" + value);
                    properties.add(name + "=" + system.getProperty(name));
                }
            }
        });
        String state = system.getProperty(STATE_PROPERTY);
        if (state != null && !properties.isEmpty()) {
            properties.add(STATE_PROPERTY + "=" + state);
        }
        return properties;
    }

    /** {@code devServices}, then {@code @VidocqTest(properties)}, which win since the last {@code -D} does. */
    static List<String> properties(List<String> devServices, String[] declared) {
        List<String> properties = new ArrayList<>(devServices);
        properties.addAll(List.of(declared));
        return properties;
    }

    /**
     * {@code java} with the {@code default} Chappe listener on {@code 127.0.0.1:port}, then the properties of the
     * layout and {@code properties} ({@code key=value}, as {@code -D}), then the application {@code module} on the
     * paths of {@code layout}. Every module of the module path is resolved, as in {@code vidocq:dev} and the packaged
     * application, so that a driver or a provider no module requires is still found through
     * {@link java.util.ServiceLoader}.
     */
    static List<String> command(String java, Layout layout, String module, int port, List<String> properties) {
        List<String> command = new ArrayList<>(List.of(java,
                "-Dvidocq.chappe.listener.default.host=127.0.0.1",
                "-Dvidocq.chappe.listener.default.port=" + port));
        layout.properties().forEach(property -> command.add("-D" + property));
        for (String property : properties) {
            if (property.indexOf('=') <= 0) {
                throw new IllegalArgumentException(
                        "@VidocqTest(properties) takes key=value entries, not '" + property + "'");
            }
            command.add("-D" + property);
        }
        command.addAll(List.of("-D" + VidocqAppLayer.APP_MODULE_PROPERTY + "=" + module,
                "-D" + VidocqAppLayer.APP_PATH_PROPERTY + "=" + join(layout.appPath()),
                "-p", join(layout.modulePath()),
                "--add-modules", layout.addModules(),
                "-m", RUNTIME_MAIN));
        return command;
    }

    /**
     * Splits {@code modulePath} as {@code vidocq:run} splits the application's dependencies, under the
     * {@code vidocq.app.layer} and {@code vidocq.app.layer.keep} of {@code properties} (the last one wins, as on the
     * command line). Under {@code all}, the default, {@link LayerSplit} gives the application layer the application
     * {@code module} and its libraries; under {@code classes}, the module alone. A default {@code all} that cannot be
     * honoured falls back to {@code classes} and says why through {@code warn}; an {@code all} asked for fails. An
     * entry that holds no module the split can read stays on the module path, where the JVM reports it.
     *
     * @throws IllegalStateException when no entry of {@code modulePath} holds {@code module}, or when an {@code all}
     *                               asked for cannot be honoured
     */
    static Layout layout(String modulePath, String module, List<String> properties, Consumer<String> warn) {
        List<LayerSplit.Candidate> candidates = new ArrayList<>();
        List<Path> unreadable = new ArrayList<>();
        for (String entry : modulePath.split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                try {
                    candidates.add(LayerSplit.candidate(Path.of(entry), null));
                } catch (IOException _) {
                    unreadable.add(Path.of(entry));
                }
            }
        }
        if (candidates.stream().noneMatch(candidate -> isModule(candidate, module))) {
            throw new IllegalStateException("@VidocqTest starts the module " + module
                    + ", which no entry of the module path of the tests holds: " + modulePath);
        }
        Optional<LayerSplit.Result> all = all(candidates, module, properties, warn);
        LayerSplit.Result split = all.orElseGet(() -> new LayerSplit.Result(
                candidates.stream().filter(c -> !isModule(c, module)).toList(),
                candidates.stream().filter(c -> isModule(c, module)).toList(), Map.of()));
        List<String> layerProperties = new ArrayList<>();
        layerProperties.add(LayerSplit.MODE_PROPERTY + "=" + (all.isPresent() ? "all" : "classes"));
        if (!split.pinned().isEmpty()) {
            layerProperties.add(LayerSplit.PINNED_PROPERTY + "=" + LayerSplit.encodePinned(split.pinned()));
        }
        List<Path> parent = new ArrayList<>(split.parentPaths());
        parent.addAll(unreadable);
        return new Layout(parent, split.applicationPaths(), layerProperties, addModules(split.application()));
    }

    /**
     * The split of {@code all}; empty under {@code classes}, and when a default {@code all} cannot be honoured, which
     * {@code warn} is told.
     */
    private static Optional<LayerSplit.Result> all(List<LayerSplit.Candidate> candidates, String module,
                                                   List<String> properties, Consumer<String> warn) {
        String mode = lastValue(properties, LayerSplit.MODE_PROPERTY);
        if (LayerSplit.Mode.of(mode) != LayerSplit.Mode.ALL) {
            return Optional.empty();
        }
        try {
            return Optional.of(LayerSplit.split(candidates, Set.of(module),
                    LayerSplit.patterns(lastValue(properties, LayerSplit.KEEP_PROPERTY)), Map.of()));
        } catch (IllegalStateException e) {
            if (LayerSplit.requested(mode)) {
                throw e;
            }
            warn.accept("vidocq.app.layer=all, the default, cannot split this launch: " + e.getMessage()
                    + " Falling back to vidocq.app.layer=classes. Fix the cause, or set vidocq.app.layer=classes"
                    + " in @VidocqTest(properties) to silence this.");
            return Optional.empty();
        }
    }

    private static boolean isModule(LayerSplit.Candidate candidate, String module) {
        return candidate.descriptor().name().equals(module);
    }

    /** The value of the last {@code key=value} entry of {@code properties} for {@code key}, or {@code null}. */
    private static String lastValue(List<String> properties, String key) {
        String value = null;
        for (String property : properties) {
            if (property.startsWith(key + "=")) {
                value = property.substring(key.length() + 1);
            }
        }
        return value;
    }

    /**
     * {@value #ALL_MODULE_PATH}, then the JDK modules the explicit modules of the application layer require, sorted:
     * the application layer is a child of the boot layer, and a JDK module can be defined in the boot layer only. A
     * {@code requires static} resolves nothing and is left out.
     */
    private static String addModules(List<LayerSplit.Candidate> application) {
        ModuleFinder system = ModuleFinder.ofSystem();
        Set<String> jdk = application.stream()
                .flatMap(candidate -> candidate.descriptor().requires().stream())
                .filter(requires -> !requires.modifiers().contains(ModuleDescriptor.Requires.Modifier.STATIC))
                .map(ModuleDescriptor.Requires::name)
                .filter(name -> !"java.base".equals(name) && system.find(name).isPresent())
                .collect(Collectors.toCollection(TreeSet::new));
        return jdk.isEmpty() ? ALL_MODULE_PATH : ALL_MODULE_PATH + "," + String.join(",", jdk);
    }

    private static String join(List<Path> entries) {
        return entries.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
    }

    /**
     * The module to start: the one {@code @VidocqTest(module)} names, else the module of the test class, which
     * Surefire and the IDEs patch into the application module, else {@code -Dvidocq.mainModule}.
     */
    static String module(String declared, String testClassModule, String mainModuleProperty) {
        if (declared != null && !declared.isBlank()) {
            return declared;
        }
        if (testClassModule != null) {
            return testClassModule;
        }
        if (mainModuleProperty != null && !mainModuleProperty.isBlank()) {
            return mainModuleProperty;
        }
        throw new IllegalStateException("@VidocqTest cannot tell which module to start: the test class is not in a"
                + " named module. Name it with @VidocqTest(module = \"…\") or -Dvidocq.mainModule.");
    }

    /**
     * The module path of the tests ({@code jdk.module.path}), which the application runs on, followed by the
     * entries of {@code runtimePath} it does not hold yet. An entry of {@code runtimePath} under
     * {@code vidocq-enriched/}, the copy {@code vidocq:generate} wrote of a dependency, replaces the jar of the same
     * file name.
     */
    static String modulePath(String jdkModulePath, String runtimePath) {
        if (jdkModulePath == null || jdkModulePath.isBlank()) {
            throw new IllegalStateException("@VidocqTest starts the application from the module path of the tests,"
                    + " and they run on the class path: give the application a module-info.java, and leave"
                    + " Surefire's useModulePath on.");
        }
        if (runtimePath == null || runtimePath.isBlank()) {
            return jdkModulePath;
        }
        List<String> runtime = List.of(runtimePath.split(File.pathSeparator)).stream()
                .filter(entry -> !entry.isBlank()).toList();
        Set<String> entries = new LinkedHashSet<>();
        for (String entry : jdkModulePath.split(File.pathSeparator)) {
            // An enriched copy, same file name, runs in place of the jar Surefire put there.
            entries.add(runtime.stream().filter(r -> isCopyOf(r, entry)).findFirst().orElse(entry));
        }
        entries.addAll(runtime);
        return String.join(File.pathSeparator, entries);
    }

    private static boolean isCopyOf(String runtimeEntry, String entry) {
        Path copy = Path.of(runtimeEntry);
        Path parent = copy.getParent();
        return parent != null && ENRICHED_DIR.equals(String.valueOf(parent.getFileName()))
                && !runtimeEntry.equals(entry) && copy.getFileName().equals(Path.of(entry).getFileName());
    }
}
