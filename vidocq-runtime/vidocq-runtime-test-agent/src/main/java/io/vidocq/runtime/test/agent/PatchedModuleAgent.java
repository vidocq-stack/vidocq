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
package io.vidocq.runtime.test.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Completes the module access of a test launch, from inside the launched JVM.
 *
 * <p>To run the tests of a module that has a descriptor, a launcher patches the test classes into the module
 * ({@code --patch-module}), makes it read the class path ({@code --add-reads X=ALL-UNNAMED}) and opens the test
 * packages to the class path ({@code --add-opens X/p=ALL-UNNAMED}). That is enough while the test libraries are on
 * the class path, where Maven puts them. A launcher that puts them on the module path as named modules and stops
 * there leaves the tests unreachable: the module neither reads the test libraries nor opens its test packages to
 * them.
 *
 * <p>This agent gives the named test libraries what the class path has. For each module that was made to read the
 * class path, the test libraries are the modules of the launch that the module does not reach through its own
 * {@code requires}, the JDK's aside: what Maven would have left on the class path. The agent makes the module
 * read them, and opens to them the packages that are open to the class path. It works on the modules the JVM
 * resolved, so it names none. It leaves the module's own dependencies as they are, and so changes nothing in a
 * launch whose test libraries are on the class path or already have that access.
 *
 * <p>Such a launcher may also split the test libraries, and leave on the class path one that a named one counts
 * among its friends: a module it names in an {@code exports … to} or an {@code opens … to}. On the class path the
 * friend has lost its name, and with it that access. The agent gives the class path the packages a module of the
 * launch exports or opens to a friend that is on it: what that friend had under Maven, where both were on the
 * class path. A friend that is nowhere in the launch changes nothing.
 *
 * <p>Loaded with {@code -javaagent:<jar>}. The option {@code verbose} ({@code -javaagent:<jar>=verbose}) prints
 * what was changed on the standard error stream.
 */
public final class PatchedModuleAgent {

    private PatchedModuleAgent() {
    }

    /**
     * Called by the JVM before the main class.
     *
     * @param options {@code verbose}, or nothing
     * @param instrumentation the JVM's instrumentation service
     */
    public static void premain(String options, Instrumentation instrumentation) {
        try {
            boolean verbose = "verbose".equals(options);
            Module classPath = ClassLoader.getSystemClassLoader().getUnnamedModule();
            Map<String, Module> launched = launchedModules();
            Set<String> friends = friendsOnTheClassPath(launched);
            for (Module module : launched.values()) {
                if (!module.getDescriptor().isAutomatic() && module.canRead(classPath)) {
                    complete(module, classPath, launched, instrumentation, verbose);
                }
                if (!friends.isEmpty()) {
                    admit(module, classPath, friends, instrumentation, verbose);
                }
            }
        } catch (Throwable e) { // NOSONAR a launch must never fail because of this agent
            report("test launch agent: stopped, " + e);
        }
    }

    /** The modules of the launch, by name, without those of the JDK. */
    private static Map<String, Module> launchedModules() {
        Set<String> system = ModuleFinder.ofSystem().findAll().stream()
                .map(ModuleReference::descriptor)
                .map(ModuleDescriptor::name)
                .collect(Collectors.toSet());
        Map<String, Module> launched = new TreeMap<>();
        for (Module module : ModuleLayer.boot().modules()) {
            if (!system.contains(module.getName())) {
                launched.put(module.getName(), module);
            }
        }
        return launched;
    }

    private static void complete(Module module, Module classPath, Map<String, Module> launched,
            Instrumentation instrumentation, boolean verbose) {
        try {
            Set<String> own = ownDependencies(module, launched);
            List<Module> testLibraries = launched.values().stream()
                    .filter(other -> other != module && !own.contains(other.getName()))
                    .collect(Collectors.toList());

            Set<Module> reads = testLibraries.stream()
                    .filter(library -> !module.canRead(library))
                    .collect(Collectors.toSet());
            Map<String, Set<Module>> opens = new TreeMap<>();
            for (String packageName : module.getPackages()) {
                if (module.isOpen(packageName, classPath)) {
                    Set<Module> closedTo = testLibraries.stream()
                            .filter(library -> !module.isOpen(packageName, library))
                            .collect(Collectors.toSet());
                    if (!closedTo.isEmpty()) {
                        opens.put(packageName, closedTo);
                    }
                }
            }
            if (reads.isEmpty() && opens.isEmpty()) {
                return;
            }
            instrumentation.redefineModule(module, reads, Map.of(), opens, Set.of(), Map.of());
            if (verbose) {
                List<String> changes = new ArrayList<>();
                if (!reads.isEmpty()) {
                    changes.add("reads " + names(reads));
                }
                opens.forEach((packageName, closedTo) -> changes.add("opens " + packageName + " to " + names(closedTo)));
                report(module.getName() + ": " + String.join("; ", changes));
            }
        } catch (Throwable e) { // NOSONAR a launch must never fail because of this agent
            report("test launch agent: " + module.getName() + " left as it is, " + e);
        }
    }

    /**
     * Prints one line of the agent on the standard error stream, where the IDE shows the launch's own output. No
     * logger: the agent runs before the application configures one, and a line of the JDK's default format would
     * take two lines and a time stamp in each test run of the IDE.
     */
    @SuppressWarnings("java:S106")
    private static void report(String line) {
        System.err.println("[vidocq] " + line);
    }

    /**
     * The modules that a module of the launch names as a friend, in an {@code exports … to} or an
     * {@code opens … to}, and that are on the class path instead of being modules of the launch.
     *
     * <p>The class path is read only when a friend is missing from the launch, so a launch that is whole costs
     * nothing. It is the one of {@code java.class.path}: a launch whose class path is shortened into the manifest
     * of a jar, or into a file, names no friend here.
     */
    private static Set<String> friendsOnTheClassPath(Map<String, Module> launched) {
        Set<String> missing = new TreeSet<>();
        for (Module module : launched.values()) {
            ModuleDescriptor descriptor = module.getDescriptor();
            descriptor.exports().forEach(exports -> missing.addAll(exports.targets()));
            descriptor.opens().forEach(opens -> missing.addAll(opens.targets()));
        }
        missing.removeIf(friend -> ModuleLayer.boot().findModule(friend).isPresent());
        if (missing.isEmpty()) {
            return missing;
        }
        Set<String> onTheClassPath = new HashSet<>();
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator, -1)) {
            onTheClassPath.addAll(moduleNames(entry));
        }
        missing.retainAll(onTheClassPath);
        return missing;
    }

    /**
     * The name a class path entry would have as a module: the one of its descriptor, or the one the module system
     * derives for a jar that has none. A folder of classes with no descriptor, where an IDE compiles the tests,
     * has none.
     */
    private static Set<String> moduleNames(String entry) {
        try {
            Path path = Paths.get(entry);
            if (Files.isRegularFile(path) || Files.isRegularFile(path.resolve("module-info.class"))) {
                return ModuleFinder.of(path).findAll().stream()
                        .map(ModuleReference::descriptor)
                        .map(ModuleDescriptor::name)
                        .collect(Collectors.toSet());
            }
        } catch (RuntimeException e) { // NOSONAR an entry the module system cannot read names no friend
            return Set.of();
        }
        return Set.of();
    }

    /** Gives the class path the packages a module exports or opens to a friend that is on the class path. */
    private static void admit(Module module, Module classPath, Set<String> friends,
            Instrumentation instrumentation, boolean verbose) {
        try {
            Map<String, Set<Module>> exports = new TreeMap<>();
            Map<String, Set<Module>> opens = new TreeMap<>();
            List<String> changes = new ArrayList<>();
            for (ModuleDescriptor.Exports export : module.getDescriptor().exports()) {
                String present = present(export.targets(), friends);
                if (!present.isEmpty() && !module.isExported(export.source(), classPath)) {
                    exports.put(export.source(), Set.of(classPath));
                    changes.add("exports " + export.source() + " to the class path for " + present);
                }
            }
            for (ModuleDescriptor.Opens open : module.getDescriptor().opens()) {
                String present = present(open.targets(), friends);
                if (!present.isEmpty() && !module.isOpen(open.source(), classPath)) {
                    opens.put(open.source(), Set.of(classPath));
                    changes.add("opens " + open.source() + " to the class path for " + present);
                }
            }
            if (changes.isEmpty()) {
                return;
            }
            instrumentation.redefineModule(module, Set.of(), exports, opens, Set.of(), Map.of());
            if (verbose) {
                Collections.sort(changes);
                report(module.getName() + ": " + String.join("; ", changes));
            }
        } catch (Throwable e) { // NOSONAR a launch must never fail because of this agent
            report("test launch agent: " + module.getName() + " left as it is, " + e);
        }
    }

    /** Those of the targets that are friends on the class path, in order, or nothing. */
    private static String present(Set<String> targets, Set<String> friends) {
        return targets.stream().filter(friends::contains).sorted().collect(Collectors.joining(", "));
    }

    /**
     * The modules a module reaches through {@code requires}, its own and those of the modules it requires.
     *
     * <p>A {@code requires static} is not followed: a module required that way is not a dependency the launch
     * gives access to, and a launcher's own options leave it out.
     */
    private static Set<String> ownDependencies(Module module, Map<String, Module> launched) {
        Set<String> reached = new HashSet<>();
        Deque<Module> toVisit = new ArrayDeque<>();
        toVisit.add(module);
        while (!toVisit.isEmpty()) {
            for (ModuleDescriptor.Requires requires : toVisit.remove().getDescriptor().requires()) {
                Module required = launched.get(requires.name());
                if (required != null && !requires.modifiers().contains(ModuleDescriptor.Requires.Modifier.STATIC)
                        && reached.add(requires.name())) {
                    toVisit.add(required);
                }
            }
        }
        return reached;
    }

    private static String names(Collection<Module> modules) {
        return modules.stream().map(Module::getName).sorted().collect(Collectors.joining(", "));
    }
}
