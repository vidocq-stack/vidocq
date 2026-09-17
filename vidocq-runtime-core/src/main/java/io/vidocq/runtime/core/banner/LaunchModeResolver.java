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
package io.vidocq.runtime.core.banner;

import io.vidocq.runtime.core.console.ConsoleSupport;

import java.io.File;
import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Which {@link LaunchMode} this JVM runs in, and the signal that says so.
 *
 * <p>The decision is a pure function of {@link Inputs}: first match wins, in this order.
 * <ol>
 *   <li>{@value #MODE_KEY} set to {@code dev}, {@code test} or {@code prod} (another value logs
 *       one warning and falls through to the detection);</li>
 *   <li>{@value #PROFILE_KEY} that <em>is</em> {@code dev}, {@code test} or {@code prod} — both
 *       dev tools set it; another profile ({@code staging}) is not a mode and falls through;</li>
 *   <li>{@value #RELOAD_FILE_PROPERTY}, the {@code vidocq:dev} reload loop: {@code dev};</li>
 *   <li>a test frame on the booting thread, or a JUnit launcher on the class path: {@code test};</li>
 *   <li>an application archive that is a directory of a build tree, or IntelliJ's Run console:
 *       {@code dev};</li>
 *   <li>otherwise {@code prod}, which no signal proves: the reason says so.</li>
 * </ol>
 *
 * <p>Reading the archives is bounded: at most {@value #MAX_ARCHIVES} of them are looked at, their
 * shape is a string comparison, and only the first directory has its parents probed for a build
 * file — a handful of {@code stat} calls, once per JVM. Nothing here throws: a failing probe is
 * a signal that was not found.
 */
public final class LaunchModeResolver {

    /** Configuration key of the launch mode: {@code dev}, {@code test} or {@code prod}. */
    public static final String MODE_KEY = "vidocq.launch.mode";
    /** Configuration key of the active profile, a launch signal when it is a mode name. */
    public static final String PROFILE_KEY = "vidocq.profile";
    /** Set by {@code vidocq:dev} for its reload loop. */
    public static final String RELOAD_FILE_PROPERTY = "vidocq.dev.reload.file";

    /** The reason of a {@code prod} nothing proved. */
    static final String NO_SIGNAL = "no dev or test signal";
    /** Frame prefixes of the test frameworks, and the name each one is reported under. */
    static final Map<String, String> TEST_FRAMES = testFrames();
    /** Build files that mean a source tree, looked for above a directory of classes. */
    static final List<String> BUILD_FILES =
            List.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts");
    /** How many parent levels are probed for a {@link #BUILD_FILES build file}. */
    static final int BUILD_FILE_LEVELS = 3;
    /** How many application archives are looked at. */
    static final int MAX_ARCHIVES = 4;
    /** How many frames of the booting thread are read. */
    static final int MAX_FRAMES = 128;

    private static final System.Logger LOG = System.getLogger(LaunchModeResolver.class.getName());

    /**
     * What the decision is made of, so that it can be made without a JVM in that state.
     *
     * @param configuredMode  {@value #MODE_KEY}, or {@code null}
     * @param profile         {@value #PROFILE_KEY}, or {@code null}
     * @param reloadFile      {@value #RELOAD_FILE_PROPERTY}, or {@code null}
     * @param testRuntime     whether a JUnit Platform launcher is on the class path
     * @param stackFrames     the class names on the booting thread, outermost frames included
     * @param appArchives     the archives the application was loaded from, the application's own first
     * @param intellijConsole whether the JVM was started by IntelliJ's Run console
     */
    public record Inputs(String configuredMode, String profile, String reloadFile, boolean testRuntime,
                         List<String> stackFrames, List<Path> appArchives, boolean intellijConsole) {

        public Inputs {
            stackFrames = stackFrames == null ? List.of() : List.copyOf(stackFrames);
            appArchives = appArchives == null ? List.of() : List.copyOf(appArchives);
        }

        /** The inputs of this JVM, read on the thread that boots it. */
        public static Inputs current(Function<String, Optional<String>> config, Module appModule,
                                     ConsoleSupport console) {
            return new Inputs(setting(config, MODE_KEY), setting(config, PROFILE_KEY),
                    System.getProperty(RELOAD_FILE_PROPERTY), StartupBanner.testRuntime(),
                    LaunchModeResolver.stackFrames(), LaunchModeResolver.appArchives(appModule),
                    console != null && console.intellijConsole());
        }
    }

    /**
     * The mode and the signal it was read from.
     *
     * @param mode      the launch mode
     * @param reason    the signal, short enough for the context line ({@code profile dev},
     *                  {@code IntelliJ agent}, {@value #NO_SIGNAL})
     * @param signalled whether something was found: {@code false} only for a {@code prod} that
     *                  rests on the absence of any signal, which is never claimed without its reason
     */
    public record Resolution(LaunchMode mode, String reason, boolean signalled) {

        /** {@code dev (IntelliJ agent)}, {@code prod (no dev or test signal)}. */
        public String text() {
            return reason == null ? mode.label() : mode.label() + " (" + reason + ")";
        }

        /**
         * The segment without its reason, for a context line that must shrink: {@code null} when
         * the mode was not signalled, since {@code prod} alone would claim more than is known.
         */
        public String shortText() {
            return signalled ? mode.label() : null;
        }
    }

    private LaunchModeResolver() {}

    /** The mode of {@code inputs}, and why. */
    public static Resolution resolve(Inputs inputs) {
        Resolution configured = fromConfiguration(inputs.configuredMode());
        if (configured != null) {
            return configured;
        }
        Resolution profile = fromProfile(inputs.profile());
        if (profile != null) {
            return profile;
        }
        if (inputs.reloadFile() != null && !inputs.reloadFile().isBlank()) {
            return new Resolution(LaunchMode.DEV, "dev reload loop", true);
        }
        Resolution test = fromTestRun(inputs);
        if (test != null) {
            return test;
        }
        Resolution dev = fromBuildTree(inputs);
        if (dev != null) {
            return dev;
        }
        return new Resolution(LaunchMode.PROD, NO_SIGNAL, false);
    }

    /** (a) {@value #MODE_KEY}; an unknown value logs one warning and lets the detection decide. */
    static Resolution fromConfiguration(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Optional<LaunchMode> mode = LaunchMode.parse(value);
        if (mode.isPresent()) {
            return new Resolution(mode.get(), MODE_KEY, true);
        }
        LOG.log(System.Logger.Level.WARNING, "Configuration key ''{0}'' has an unknown value ''{1}''"
                + " (expected dev, test or prod); detecting the launch mode", MODE_KEY, value);
        return null;
    }

    /** (b) {@value #PROFILE_KEY} when it names a mode; another profile is not one. */
    static Resolution fromProfile(String profile) {
        return LaunchMode.parse(profile)
                .map(mode -> new Resolution(mode, "profile " + mode.label(), true))
                .orElse(null);
    }

    /** (d) a test frame on the booting thread, or the test runtime the banner already probes. */
    static Resolution fromTestRun(Inputs inputs) {
        for (String frame : inputs.stackFrames()) {
            if (frame == null) {
                continue;
            }
            for (Map.Entry<String, String> framework : TEST_FRAMES.entrySet()) {
                if (frame.startsWith(framework.getKey())) {
                    return new Resolution(LaunchMode.TEST, framework.getValue() + " on the stack", true);
                }
            }
        }
        return inputs.testRuntime() ? new Resolution(LaunchMode.TEST, "JUnit on the class path", true) : null;
    }

    /** (e) an application archive inside a build tree, or IntelliJ's Run console. */
    static Resolution fromBuildTree(Inputs inputs) {
        Resolution archives = fromArchives(inputs.appArchives());
        if (archives != null) {
            return archives;
        }
        return inputs.intellijConsole() ? new Resolution(LaunchMode.DEV, "IntelliJ agent", true) : null;
    }

    /**
     * A directory of classes of a build tool, or any directory of classes with a build file above
     * it. At most one directory has its parents probed: the one whose name says it is a build
     * output, otherwise the first one.
     */
    static Resolution fromArchives(List<Path> archives) {
        Path candidate = null;
        String shape = null;
        for (int i = 0; i < archives.size() && i < MAX_ARCHIVES; i++) {
            Path archive = archives.get(i);
            if (archive == null || !isDirectory(archive)) {
                continue;
            }
            String named = classesDirectory(archive);
            if (named != null) {
                candidate = archive;
                shape = named;
                break;
            }
            if (candidate == null) {
                candidate = archive;
            }
        }
        if (candidate == null) {
            return null;
        }
        String buildFile = buildFileAbove(candidate);
        if (shape != null && buildFile != null) {
            return new Resolution(LaunchMode.DEV, shape + " with a " + buildFile + " above", true);
        }
        if (shape != null) {
            return new Resolution(LaunchMode.DEV, shape, true);
        }
        if (buildFile != null) {
            return new Resolution(LaunchMode.DEV, "a " + buildFile + " above the classes", true);
        }
        return null;
    }

    /**
     * The shape of a directory of classes written by a build tool, as it is worth naming:
     * {@code target/classes}, {@code build/classes/java/main}, {@code out/production/<name>}.
     *
     * @return the shape, or {@code null} when the directory is none of them
     */
    static String classesDirectory(Path directory) {
        List<String> names = new ArrayList<>();
        for (Path name : directory) {
            names.add(name.toString());
        }
        int n = names.size();
        if (n >= 2 && names.get(n - 2).equals("target") && names.get(n - 1).equals("classes")) {
            return "target/classes";
        }
        if (n >= 4 && names.get(n - 4).equals("build") && names.get(n - 3).equals("classes")
                && names.get(n - 1).equals("main")) {
            return "build/classes/" + names.get(n - 2) + "/main";
        }
        if (n >= 3 && names.get(n - 3).equals("out") && names.get(n - 2).equals("production")) {
            return "out/production/" + names.get(n - 1);
        }
        return null;
    }

    /**
     * The name of the first {@link #BUILD_FILES build file} within {@value #BUILD_FILE_LEVELS}
     * parent levels of {@code directory}, or {@code null} when there is none.
     */
    static String buildFileAbove(Path directory) {
        Path parent = directory;
        for (int level = 0; level < BUILD_FILE_LEVELS; level++) {
            parent = parent.getParent();
            if (parent == null) {
                return null;
            }
            for (String file : BUILD_FILES) {
                if (isRegularFile(parent.resolve(file))) {
                    return file;
                }
            }
        }
        return null;
    }

    /** The class names on the calling thread, outermost frames included, bounded. */
    static List<String> stackFrames() {
        try {
            return StackWalker.getInstance().walk(frames -> frames.limit(MAX_FRAMES)
                    .map(StackWalker.StackFrame::getClassName)
                    .toList());
        } catch (RuntimeException | LinkageError unavailable) {
            return List.of();
        }
    }

    /**
     * Where the application was loaded from: the archive of its module, else the directories of
     * the class path (a jar is never a build tree, so jar entries are skipped).
     */
    static List<Path> appArchives(Module appModule) {
        Path module = moduleArchive(appModule);
        List<Path> archives = new ArrayList<>(MAX_ARCHIVES);
        if (module != null) {
            archives.add(module);
        }
        for (String entry : classPath()) {
            if (archives.size() >= MAX_ARCHIVES) {
                break;
            }
            if (!entry.isBlank() && !entry.endsWith(".jar")) {
                Path path = path(entry);
                if (path != null && !archives.contains(path)) {
                    archives.add(path);
                }
            }
        }
        return archives;
    }

    /** The {@code file:} archive a named module was resolved from, without reading inside it. */
    static Path moduleArchive(Module module) {
        if (module == null || !module.isNamed() || module.getLayer() == null) {
            return null;
        }
        try {
            Optional<URI> location = module.getLayer().configuration()
                    .findModule(module.getName())
                    .map(ResolvedModule::reference)
                    .flatMap(ModuleReference::location);
            if (location.isEmpty() || !"file".equalsIgnoreCase(location.get().getScheme())) {
                return null;
            }
            return Path.of(location.get());
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static List<String> classPath() {
        String classPath = System.getProperty("java.class.path");
        return classPath == null || classPath.isEmpty() ? List.of() : List.of(classPath.split(File.pathSeparator, -1));
    }

    private static Path path(String value) {
        try {
            return Path.of(value);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static boolean isDirectory(Path path) {
        try {
            return Files.isDirectory(path);
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    private static boolean isRegularFile(Path path) {
        try {
            return Files.isRegularFile(path);
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    private static String setting(Function<String, Optional<String>> config, String key) {
        try {
            return config.apply(key).map(String::strip).filter(value -> !value.isEmpty()).orElse(null);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static Map<String, String> testFrames() {
        Map<String, String> frames = new LinkedHashMap<>();
        frames.put("org.junit.", "JUnit");
        frames.put("org.testng.", "TestNG");
        frames.put("org.apache.maven.surefire.", "Surefire");
        frames.put("org.jboss.arquillian.", "Arquillian");
        return Collections.unmodifiableMap(frames);
    }
}
