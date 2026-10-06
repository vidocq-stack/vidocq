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
package io.vidocq.runtime.cli;

import io.vidocq.runtime.cli.build.MavenInvocation;
import io.vidocq.runtime.cli.build.MavenLauncher;
import io.vidocq.runtime.cli.completion.CommandCatalog;
import io.vidocq.runtime.cli.completion.CompletionScripts;
import io.vidocq.runtime.cli.completion.Shell;
import io.vidocq.runtime.cli.completion.ShellSetup;
import io.vidocq.runtime.cli.config.ConfigFile;
import io.vidocq.runtime.cli.config.PropertiesText;
import io.vidocq.runtime.cli.dev.BootSpinner;
import io.vidocq.runtime.cli.dev.DebugOptions;
import io.vidocq.runtime.cli.dev.Profiles;
import io.vidocq.runtime.cli.dev.SourceWatcher;
import io.vidocq.runtime.cli.doctor.Diagnostic;
import io.vidocq.runtime.cli.doctor.Diagnostics;
import io.vidocq.runtime.cli.doctor.DoctorContext;
import io.vidocq.runtime.cli.ext.ExtensionCache;
import io.vidocq.runtime.cli.ext.ExtensionCoordinate;
import io.vidocq.runtime.cli.ext.ExtensionRegistry;
import io.vidocq.runtime.cli.ext.HttpRegistryFetcher;
import io.vidocq.runtime.cli.ext.KnownExtensions;
import io.vidocq.runtime.cli.ext.PomEditor;
import io.vidocq.runtime.cli.ext.ProjectExtensions;
import io.vidocq.runtime.cli.ext.RegistryEntry;
import io.vidocq.runtime.cli.scaffold.ProjectScaffolder;
import io.vidocq.runtime.cli.spi.CliPlugins;
import io.vidocq.runtime.cli.spi.VidocqCliPlugin;
import io.vidocq.runtime.core.VidocqBootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
/**
 * Dispatches a parsed {@link Command} to its implementation.
 * Uses pattern-matching switch so the compiler enforces exhaustiveness
 * as the sealed hierarchy grows.
 */
public final class CommandRunner {

    private CommandRunner() {}

    public static int run(Command command) {
        return switch (command) {
            case Command.Version v             -> runVersion();
            case Command.Info i                -> runInfo();
            case Command.Help h                -> runHelp(h);
            case Command.Start s               -> runStart(s);
            case Command.Dev d                 -> runDev(d);
            case Command.Doctor doc            -> runDoctor(doc);
            case Command.Create c              -> runCreate(c);
            case Command.Build b               -> runBuild(b);
            case Command.Clean cl              -> runClean(cl);
            case Command.Config cfg            -> switch (cfg) {
                case Command.Config.Get g       -> runConfigGet(g);
                case Command.Config.Set s       -> runConfigSet(s);
                case Command.Config.Listing l   -> runConfigList(l);
            };
            case Command.Completion comp       -> runCompletion(comp);
            case Command.CompletionSetup setup -> runCompletionSetup(setup);
            case Command.Plugin p              -> runPlugin(p);
            case Command.Extension e           -> switch (e) {
                case Command.Extension.Listing l -> runExtensionList(l);
                case Command.Extension.Add a     -> runExtensionAdd(a);
                case Command.Extension.Remove r  -> runExtensionRemove(r);
            };
        };
    }

    // -------------------------------------------------------------------------
    // Command implementations
    // -------------------------------------------------------------------------

    private static int runVersion() {
        System.out.println(CliOutput.bold("Vidocq CLI ") + Version.cliDisplay());
        return 0;
    }

    private static int runInfo() {
        CliOutput.println(CliOutput.bold("Vidocq Runtime — System Information"));
        CliOutput.println();
        System.out.printf("  %-24s %s%n", "CLI version:",  Version.cliDisplay());
        System.out.printf("  %-24s %s%n", "Runtime version:", Version.runtime());
        System.out.printf("  %-24s %s%n", "Java version:", System.getProperty("java.version"));
        System.out.printf("  %-24s %s%n", "JVM:",
                System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
        System.out.printf("  %-24s %s%n", "OS:",
                System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        CliOutput.println();
        printProjectExtensions("Project extensions:", false);
        return 0;
    }

    /**
     * Prints the extensions on the runtime classpath of the project in the working
     * directory, transitive ones included. Resolving it runs Maven, hence slow.
     *
     * @return {@code 0}, or {@code 1} when there is no project or Maven failed
     */
    private static int printProjectExtensions(String title, boolean refresh) {
        CliOutput.println(CliOutput.bold(title));
        Path cwd = Path.of("").toAbsolutePath();
        if (!Files.isRegularFile(cwd.resolve("pom.xml"))) {
            CliOutput.println(CliOutput.dim("  (no pom.xml in the current directory)"));
            return 1;
        }
        var resolution = resolveProjectExtensions(cwd, refresh);
        return switch (resolution) {
            case ProjectExtensions.Resolution.Failed f -> {
                CliOutput.error("Could not resolve the project's dependencies: " + f.reason());
                if (!f.mavenOutput().isBlank()) {
                    System.err.println(f.mavenOutput());
                }
                yield 1;
            }
            case ProjectExtensions.Resolution.Resolved r -> {
                if (r.extensions().isEmpty()) {
                    CliOutput.println(CliOutput.dim(
                            "  (none — add one with 'vidocq extension add <id>')"));
                }
                r.extensions().forEach(ext ->
                        System.out.printf("  %s %-26s %s%s%n",
                                CliOutput.green("✔"),
                                ext.id(),
                                CliOutput.dim(ext.artifact().toString()),
                                ext.direct() ? "" : CliOutput.dim("  (transitive)")));
                if (r.cached()) {
                    CliOutput.println(CliOutput.dim(
                            "  (cached — 'vidocq extension list --refresh' resolves again)"));
                }
                yield 0;
            }
        };
    }

    private static int runHelp(Command.Help h) {
        CliParser.printHelp(h.topic());
        return 0;
    }

    /**
     * The canonical key of the {@code default} Chappe listener. The CLI publishes this one rather
     * than the {@code vidocq.http.port} alias so an explicit {@code --port} outranks any value the
     * project set for itself — the alias is resolved only when the listener key is absent.
     */
    private static final String DEFAULT_LISTENER_PORT_KEY = "vidocq.chappe.listener.default.port";

    private static int runStart(Command.Start start) {
        if (start.portExplicit()) {
            System.setProperty(DEFAULT_LISTENER_PORT_KEY, String.valueOf(start.port()));
        }
        if (start.configFile() != null) {
            System.setProperty("vidocq.config.file", start.configFile().toAbsolutePath().toString());
        }
        if (start.debug()) {
            CliOutput.warning("Debug mode active — attach your debugger to port 5005 before the server binds.");
        }
        CliOutput.info("Starting Vidocq on port " + start.port() + "…");
        VidocqBootstrap.create().configure().start().awaitShutdown();
        return 0;
    }

    private static int runDev(Command.Dev dev) {
        Path projectDir = Path.of("").toAbsolutePath();
        CliOutput.info("Starting Vidocq in " + CliOutput.bold("dev mode")
                + " on port " + dev.port() + " (profile: " + dev.profile() + ")…");
        if (dev.portExplicit()) {
            System.setProperty(DEFAULT_LISTENER_PORT_KEY, String.valueOf(dev.port()));
        }
        applyProfile(dev.profile(), projectDir);

        if (dev.debug()) {
            DebugOptions debug = DebugOptions.defaults();
            CliOutput.warning(debug.hint());
            CliOutput.println(CliOutput.dim("  " + debug.agentArgument()));
        }

        var current = new AtomicReference<VidocqBootstrap>();
        bootRuntime(current);

        var reloadLock = new ReentrantLock();
        SourceWatcher watcher = new SourceWatcher(
                watchRoots(projectDir),
                () -> reload(current, dev, projectDir, reloadLock));
        watcher.start();
        if (watcher.watchableRoots().isEmpty()) {
            CliOutput.warning("No source directories to watch — running without live reload.");
        } else {
            CliOutput.info("Watching " + watcher.watchableRoots().size()
                    + " path(s) for changes. Press Ctrl+C to stop.");
        }

        var done = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            watcher.close();
            done.countDown();
        }, "vidocq-dev-stop"));
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    private static void bootRuntime(AtomicReference<VidocqBootstrap> ref) {
        // configure() prints the startup banner: the spinner starts after it, or the banner's
        // first line would stick to a spinner frame.
        VidocqBootstrap bootstrap = VidocqBootstrap.create().configure();
        BootSpinner spinner = new BootSpinner("Booting Vidocq…");
        spinner.start();
        try {
            ref.set(bootstrap.start());
        } finally {
            spinner.stop();
        }
    }

    private static void reload(AtomicReference<VidocqBootstrap> ref,
                               Command.Dev dev, Path projectDir, ReentrantLock lock) {
        lock.lock();
        try {
            CliOutput.println();
            CliOutput.info("Change detected — reloading runtime…");
            VidocqBootstrap previous = ref.getAndSet(null);
            if (previous != null) {
                try {
                    previous.shutdown();
                } catch (Exception e) {
                    CliOutput.warning("Reload: error during shutdown — " + e.getMessage());
                }
            }
            applyProfile(dev.profile(), projectDir);
            bootRuntime(ref);
            CliOutput.success("Reloaded.");
        } finally {
            lock.unlock();
        }
    }

    private static List<Path> watchRoots(Path projectDir) {
        return List.of(
                projectDir.resolve("src"),
                projectDir.resolve("target").resolve("classes"));
    }

    private static void applyProfile(String profile, Path projectDir) {
        if (profile == null || profile.isBlank()) {
            return;
        }
        System.setProperty("vidocq.profile", profile);
        var files = Profiles.sourceFiles(projectDir, profile);
        if (files.isEmpty()) {
            return;
        }
        // System properties are Vidocq's highest-precedence config source, so layering the
        // profile there makes its values win without overriding an explicit -D set by the user.
        Profiles.load(files).forEach((key, value) -> {
            if (System.getProperty(key) == null) {
                System.setProperty(key, value);
            }
        });
        CliOutput.info("Profile '" + profile + "' — layered " + files.size() + " config file(s).");
    }

    private static int runDoctor(Command.Doctor doctor) {
        CliOutput.println(CliOutput.bold("Vidocq doctor — environment & project checks"));
        CliOutput.println();

        var diagnostics = Diagnostics.run(gatherDoctorContext());
        for (Diagnostic d : diagnostics) {
            System.out.printf("  %s %-16s %s%n", symbol(d.status()), d.name(), d.detail());
            if (d.hint() != null && (doctor.verbose() || d.status() != Diagnostic.Status.OK)) {
                CliOutput.println("       " + CliOutput.dim("↳ " + d.hint()));
            }
        }

        int exit = Diagnostics.exitCode(diagnostics);
        var summary = Diagnostics.summarize(diagnostics);
        CliOutput.println();
        if (exit == 0) {
            CliOutput.success("No blocking issues found.");
        } else {
            CliOutput.error("One or more checks failed — see the hints above.");
        }
        CliOutput.println(CliOutput.dim("  " + summary.total() + " checks — "
                + summary.ok() + " ok, " + summary.warn() + " warning(s), "
                + summary.fail() + " failed"));
        if (doctor.verbose()) {
            CliOutput.println(CliOutput.dim("  Minimum Java: " + Diagnostics.MINIMUM_JAVA_VERSION));
        }
        return exit;
    }

    private static String symbol(Diagnostic.Status status) {
        return switch (status) {
            case OK   -> CliOutput.green("✔");
            case WARN -> CliOutput.yellow("⚠");
            case FAIL -> CliOutput.red("✘");
        };
    }

    private static DoctorContext gatherDoctorContext() {
        Path cwd = Path.of("").toAbsolutePath();
        String javaHome = System.getenv("JAVA_HOME");
        boolean javaHomeDir = javaHome != null && Files.isDirectory(Path.of(javaHome));
        Path pom = cwd.resolve("pom.xml");
        boolean pomPresent = Files.isRegularFile(pom);
        Path config = ConfigFile.locate(cwd, Files::isRegularFile).orElse(null);
        boolean configPresent = config != null;
        List<String> configKeys = configPresent ? readConfigKeys(config) : List.of();
        boolean vidocqProject = pomPresent && pomReferencesVidocq(pom);
        int extensionCount = 0;
        String extensionsError = null;
        if (vidocqProject) {
            switch (resolveProjectExtensions(cwd, false)) {
                case ProjectExtensions.Resolution.Resolved r -> extensionCount = r.extensions().size();
                case ProjectExtensions.Resolution.Failed f   -> extensionsError = f.reason();
            }
        }
        return new DoctorContext(
                Runtime.version().feature(),
                System.getProperty("java.version"),
                Diagnostics.MINIMUM_JAVA_VERSION,
                javaHome,
                javaHomeDir,
                hasMavenWrapper(cwd),
                pomPresent,
                vidocqProject,
                extensionCount,
                configPresent,
                configKeys,
                extensionsError);
    }

    private static List<String> readConfigKeys(Path config) {
        try {
            return List.copyOf(PropertiesText.entries(Files.readString(config)).keySet());
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean hasMavenWrapper(Path start) {
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("mvnw"))
                    || Files.isRegularFile(dir.resolve("mvnw.cmd"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean pomReferencesVidocq(Path pom) {
        try {
            return Files.readString(pom).contains("io.vidocq.runtime");
        } catch (IOException e) {
            return false;
        }
    }

    private static int runCreate(Command.Create create) {
        CliOutput.info("Scaffolding project '" + CliOutput.bold(create.name()) + "'…");
        try {
            ProjectScaffolder.scaffold(create);
        } catch (Exception e) {
            CliOutput.error("Failed to create project: " + e.getMessage());
            return 1;
        }
        CliOutput.success("Project '" + create.name() + "' created.");
        CliOutput.println();
        CliOutput.println("  Navigate:  " + CliOutput.cyan("cd " + create.name()));
        CliOutput.println("  Build:     " + CliOutput.cyan("mvn package"));
        CliOutput.println("  Run:       " + CliOutput.cyan("vidocq start"));
        return 0;
    }

    private static int runBuild(Command.Build build) {
        List<String> goals = build.type().goals();
        MavenInvocation.Options opts = new MavenInvocation.Options(
                build.offline(), build.skipTests(), build.passthrough());
        return runMaven(goals, opts, build.dryRun(),
                "Building " + CliOutput.bold(build.type().label()) + "…");
    }

    private static int runClean(Command.Clean clean) {
        MavenInvocation.Options opts = new MavenInvocation.Options(
                clean.offline(), false, clean.passthrough());
        return runMaven(List.of("clean"), opts, clean.dryRun(), "Cleaning build output…");
    }

    private static int runMaven(List<String> goals, MavenInvocation.Options opts,
                                boolean dryRun, String banner) {
        Path cwd = Path.of("").toAbsolutePath();
        String executable = MavenLauncher.resolveExecutable(cwd);
        List<String> command = MavenInvocation.command(executable, goals, opts);

        CliOutput.info(banner);
        CliOutput.println(CliOutput.dim("  $ " + String.join(" ", command)));
        if (dryRun) {
            CliOutput.println(CliOutput.dim("  (dry run — Maven not executed)"));
            return 0;
        }
        int code = MavenLauncher.run(command, cwd);
        if (code == 0) {
            CliOutput.success("Done.");
        } else {
            CliOutput.error("Maven exited with code " + code + ".");
        }
        return code;
    }

    private static int runConfigGet(Command.Config.Get get) {
        Path file = locateConfig();
        if (file == null) {
            CliOutput.error("No vidocq.properties found in this project.");
            return 1;
        }
        String text = readConfig(file);
        if (text == null) {
            return 1;
        }
        var value = PropertiesText.get(text, get.key());
        if (value.isEmpty()) {
            CliOutput.error("Key '" + get.key() + "' is not set.");
            return 1;
        }
        System.out.println(value.get());
        return 0;
    }

    private static int runConfigSet(Command.Config.Set set) {
        Path cwd = Path.of("").toAbsolutePath();
        Path file = locateConfig();
        if (file == null) {
            file = ConfigFile.resolveTarget(cwd, Files::isDirectory);
        }
        String text = Files.exists(file) ? readConfig(file) : "";
        if (text == null) {
            return 1;
        }
        String updated = PropertiesText.set(text, set.key(), set.value());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, updated);
        } catch (IOException e) {
            CliOutput.error("Cannot write " + file + ": " + e.getMessage());
            return 1;
        }
        CliOutput.success("Set " + CliOutput.bold(set.key()) + " = " + set.value()
                + CliOutput.dim("  (" + cwd.relativize(file) + ")"));
        return 0;
    }

    private static int runConfigList(Command.Config.Listing listing) {
        Path file = locateConfig();
        if (file == null) {
            CliOutput.error("No vidocq.properties found in this project.");
            return 1;
        }
        String text = readConfig(file);
        if (text == null) {
            return 1;
        }
        var entries = PropertiesText.entries(text);
        if (entries.isEmpty()) {
            CliOutput.println(CliOutput.dim("  (no properties set)"));
            return 0;
        }
        entries.forEach((k, v) -> System.out.println(k + "=" + v));
        return 0;
    }

    private static Path locateConfig() {
        Path cwd = Path.of("").toAbsolutePath();
        return ConfigFile.locate(cwd, Files::isRegularFile).orElse(null);
    }

    private static String readConfig(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            CliOutput.error("Cannot read " + file + ": " + e.getMessage());
            return null;
        }
    }

    private static int runCompletionSetup(Command.CompletionSetup setup) {
        Shell shell;
        try {
            shell = setup.shell() != null ? setup.shell() : ShellSetup.detect(System.getenv("SHELL"));
        } catch (IllegalArgumentException e) {
            CliOutput.error(e.getMessage());
            return 1;
        }
        Path home = Path.of(System.getProperty("user.home", "."));
        Path rc = ShellSetup.rcFile(shell, home);
        Path script = ShellSetup.scriptFile(shell, home);
        try {
            String before = Files.isRegularFile(rc) ? Files.readString(rc) : "";
            if (setup.install()) {
                Files.createDirectories(script.getParent());
                Files.writeString(script, CompletionScripts.script(shell, CommandCatalog.COMMANDS));
                String after = ShellSetup.withBlock(before, script);
                if (!after.equals(before)) {
                    Files.writeString(rc, after);
                }
                CliOutput.success("Completion script written to " + script);
                CliOutput.success((after.equals(before) ? "Already sourced from " : "Sourced from ") + rc);
                CliOutput.println(CliOutput.dim("  open a new terminal, or run: source " + rc));
            } else {
                String after = ShellSetup.withoutBlock(before);
                if (!after.equals(before)) {
                    Files.writeString(rc, after);
                    CliOutput.success("Removed the completion block from " + rc);
                } else {
                    CliOutput.info("No completion block in " + rc);
                }
                if (Files.deleteIfExists(script)) {
                    CliOutput.success("Deleted " + script);
                }
            }
            return 0;
        } catch (IOException e) {
            CliOutput.error("Could not set up completion: " + e.getMessage());
            return 1;
        }
    }

    private static int runCompletion(Command.Completion completion) {
        System.out.print(CompletionScripts.script(completion.shell(), CommandCatalog.COMMANDS));
        return 0;
    }

    private static int runPlugin(Command.Plugin plugin) {
        VidocqCliPlugin impl = CliPlugins.find(plugin.name()).orElse(null);
        if (impl == null) {
            CliOutput.error("Unknown command '" + plugin.name() + "'. Run 'vidocq help' for usage.");
            return 1;
        }
        return impl.run(plugin.args().toArray(new String[0]));
    }

    private static int runExtensionList(Command.Extension.Listing listing) {
        int exit = 0;
        if (listing.installed()) {
            exit = printProjectExtensions("Installed extensions:", listing.refresh());
        }
        if (listing.available()) {
            if (listing.installed()) {
                CliOutput.println();
            }
            CliOutput.println(CliOutput.bold("Available extensions:"));
            var registry = new ExtensionRegistry(
                    new HttpRegistryFetcher(),
                    registryCacheFile(),
                    KnownExtensions.catalog());
            var result = registry.list();
            for (RegistryEntry e : result.entries()) {
                System.out.printf("  %s %-22s %s%n",
                        CliOutput.cyan("•"),
                        e.id(),
                        CliOutput.dim(e.description()));
            }
            CliOutput.println();
            CliOutput.println(CliOutput.dim("  source: " + originLabel(result.origin())
                    + " — add with 'vidocq extension add <id>'"));
        }
        return exit;
    }

    private static String originLabel(ExtensionRegistry.Origin origin) {
        return switch (origin) {
            case REMOTE  -> "remote registry";
            case CACHE   -> "local cache";
            case CATALOG -> "built-in catalog (offline)";
        };
    }

    /** Answers from the on-disk cache when it holds, otherwise runs (and announces) Maven. */
    private static ProjectExtensions.Resolution resolveProjectExtensions(Path cwd, boolean refresh) {
        Path cacheFile = ExtensionCache.fileFor(Path.of(System.getProperty("user.home", "."))
                .resolve(".vidocq").resolve("cache").resolve("project-extensions"), cwd);
        if (!refresh) {
            var cached = ProjectExtensions.fromCache(cwd, cacheFile);
            if (cached.isPresent()) {
                return new ProjectExtensions.Resolution.Resolved(cached.get(), true);
            }
        }
        CliOutput.println(CliOutput.dim("  resolving the project's runtime dependencies with Maven…"));
        return ProjectExtensions.resolveAndCache(cwd, MavenLauncher.resolveExecutable(cwd), cacheFile);
    }

    private static Path registryCacheFile() {
        return Path.of(System.getProperty("user.home", "."))
                .resolve(".vidocq").resolve("registry-cache.json");
    }

    private static int runExtensionAdd(Command.Extension.Add add) {
        return editPom(add.ids(), true);
    }

    private static int runExtensionRemove(Command.Extension.Remove remove) {
        return editPom(remove.ids(), false);
    }

    private static int editPom(List<String> ids, boolean add) {
        Path pomPath = Path.of("").toAbsolutePath().resolve("pom.xml");
        if (!Files.isRegularFile(pomPath)) {
            CliOutput.error("No pom.xml in the current directory — run from a Vidocq project root.");
            return 1;
        }
        String pom;
        try {
            pom = Files.readString(pomPath);
        } catch (IOException e) {
            CliOutput.error("Could not read pom.xml: " + e.getMessage());
            return 1;
        }

        boolean anyChange = false;
        for (String id : ids) {
            ExtensionCoordinate coord;
            try {
                coord = KnownExtensions.resolve(id);
            } catch (IllegalArgumentException e) {
                CliOutput.error("Invalid extension id '" + id + "': " + e.getMessage());
                return 1;
            }
            PomEditor.Result result = add ? PomEditor.add(pom, coord) : PomEditor.remove(pom, coord);
            pom = result.pom();
            if (add) {
                // Extensions shipping an APT codegen bundle need it on the compiler's
                // annotationProcessorPaths, or vidocq:checkpom fails the next build.
                var codegen = KnownExtensions.codegenBundle(id);
                if (codegen.isPresent()) {
                    PomEditor.Result apt = PomEditor.addAnnotationProcessorPath(pom, codegen.get());
                    pom = apt.pom();
                    if (apt.changed()) {
                        anyChange = true;
                        CliOutput.success("Wired " + codegen.get().artifactId()
                                + CliOutput.dim("  (annotationProcessorPaths)"));
                    }
                }
            }
            if (result.changed()) {
                anyChange = true;
                CliOutput.success((add ? "Added " : "Removed ") + id
                        + CliOutput.dim("  (" + coord + ")"));
                if (add && !KnownExtensions.isKnown(id)) {
                    CliOutput.println(CliOutput.dim(
                            "       ↳ not a known extension — resolved by convention; verify the coordinate."));
                }
            } else if (add) {
                CliOutput.println("  " + CliOutput.dim("• " + id + " already present — skipped."));
            } else {
                CliOutput.warning(id + " not found in pom.xml — skipped.");
            }
        }

        if (!anyChange) {
            CliOutput.println();
            CliOutput.info("No changes — pom.xml left untouched.");
            return 0;
        }
        try {
            Files.writeString(pomPath, pom);
        } catch (IOException e) {
            CliOutput.error("Could not write pom.xml: " + e.getMessage());
            return 1;
        }
        CliOutput.println();
        CliOutput.success("pom.xml updated.");
        CliOutput.println(CliOutput.dim("  Run 'mvn package' (or './mvnw package' if present) to fetch the new dependencies."));
        return 0;
    }
}
