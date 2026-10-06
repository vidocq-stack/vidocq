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
import io.vidocq.runtime.cli.build.RunGoals;
import io.vidocq.runtime.cli.completion.CommandCatalog;
import io.vidocq.runtime.cli.completion.CompletionScripts;
import io.vidocq.runtime.cli.completion.Shell;
import io.vidocq.runtime.cli.completion.ShellSetup;
import io.vidocq.runtime.cli.config.ConfigFile;
import io.vidocq.runtime.cli.config.PropertiesText;
import io.vidocq.runtime.cli.doctor.Diagnostic;
import io.vidocq.runtime.cli.doctor.Diagnostics;
import io.vidocq.runtime.cli.doctor.DoctorContext;
import io.vidocq.runtime.cli.ext.ExtensionCache;
import io.vidocq.runtime.cli.ext.ExtensionCoordinate;
import io.vidocq.runtime.cli.ext.ExtensionRegistry;
import io.vidocq.runtime.cli.ext.HttpRegistryFetcher;
import io.vidocq.runtime.cli.ext.KnownExtensions;
import io.vidocq.runtime.cli.ext.ModuleInfoEditor;
import io.vidocq.runtime.cli.ext.PomDependencies;
import io.vidocq.runtime.cli.ext.PomEditor;
import io.vidocq.runtime.cli.ext.ProjectExtensions;
import io.vidocq.runtime.cli.ext.RegistryEntry;
import io.vidocq.runtime.cli.scaffold.ProjectScaffolder;
import io.vidocq.runtime.cli.spi.CliPlugins;
import io.vidocq.runtime.cli.update.CliInstaller;
import io.vidocq.runtime.cli.update.HttpDownloads;
import io.vidocq.runtime.cli.update.UpdatePlan;
import io.vidocq.runtime.cli.spi.VidocqCliPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
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
            case Command.Update u              -> runUpdate(u);
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

    private static int runStart(Command.Start start) {
        Path config = start.configFile() == null ? null : start.configFile().toAbsolutePath();
        return runMaven(RunGoals.start(start.portExplicit(), start.port(), config, start.debug()),
                MavenInvocation.Options.none(), false,
                "Starting the application" + (start.portExplicit() ? " on port " + start.port() : "") + "…");
    }

    private static int runDev(Command.Dev dev) {
        return runMaven(RunGoals.dev(dev.profile(), dev.portExplicit(), dev.port(), dev.debug()),
                MavenInvocation.Options.none(), false,
                "Starting " + CliOutput.bold("dev mode") + " (profile: " + dev.profile() + ")…");
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

    private static final String CLI_PATH = "/io/vidocq/runtime/vidocq-runtime-cli";

    private static int runUpdate(Command.Update update) {
        Path installed = installedCliDirectory();
        if (installed == null) {
            CliOutput.error("This CLI was not installed by install.sh or 'vidocq update' (no bin/vidocq"
                    + " next to its modules) — nothing to update.");
            return 1;
        }
        String central = env("VIDOCQ_CENTRAL", "https://repo1.maven.org/maven2") + CLI_PATH;
        String snapshots = env("VIDOCQ_SNAPSHOTS",
                "https://central.sonatype.com/repository/maven-snapshots") + CLI_PATH;
        CliOutput.info("Current: " + Version.cliDisplay()
                + CliInstaller.installedBuild(installed).map(b -> " — build " + b).orElse(""));
        try (HttpDownloads http = new HttpDownloads()) {
            var decision = UpdatePlan.plan(Version.cli(), CliInstaller.installedBuild(installed),
                    Version.buildTimestamp(), http::fetch, central, snapshots);
            switch (decision) {
                case UpdatePlan.UpToDate u -> {
                    CliOutput.success("Already up to date: " + u.current());
                    return 0;
                }
                case UpdatePlan.Unavailable u -> {
                    CliOutput.error("Could not check for updates: " + u.reason());
                    return 1;
                }
                case UpdatePlan.Update u -> {
                    String label = u.version() + u.build().map(b -> " (build " + b + ")").orElse("");
                    if (update.checkOnly()) {
                        CliOutput.info("Update available: " + label + " — run 'vidocq update'.");
                        return 0;
                    }
                    CliOutput.info("Downloading " + u.zipUrl());
                    Path zip = Files.createTempFile("vidocq-cli", ".zip");
                    Path target;
                    try {
                        http.download(u.zipUrl(), zip);
                        target = CliInstaller.install(zip, installed.getParent(), u.version(), u.build());
                    } finally {
                        Files.deleteIfExists(zip);
                    }
                    CliOutput.success("Installed " + label + " in " + target);
                    if (!target.equals(installed)) {
                        retargetLauncher(target);
                    }
                    refreshCompletion(target);
                    return 0;
                }
            }
        } catch (IOException e) {
            CliOutput.error("Update failed: " + e.getMessage());
            return 1;
        }
    }

    /** {@code <root>/<version>} holding this CLI's {@code modules/} and {@code bin/vidocq}, or {@code null}. */
    private static Path installedCliDirectory() {
        try {
            Path jar = Path.of(CommandRunner.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path modules = jar.getParent();
            Path dir = modules == null ? null : modules.getParent();
            return dir != null && "modules".equals(String.valueOf(modules.getFileName()))
                    && Files.isRegularFile(dir.resolve("bin").resolve("vidocq")) ? dir : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void retargetLauncher(Path target) throws IOException {
        Path bin = Path.of(env("VIDOCQ_BIN", Path.of(System.getProperty("user.home", "."))
                .resolve(".local").resolve("bin").toString()));
        Path launcher = bin.resolve("vidocq");
        var retargeted = Files.isRegularFile(launcher)
                ? CliInstaller.retargetLauncher(Files.readString(launcher), target)
                : Optional.<String>empty();
        if (retargeted.isPresent()) {
            Files.writeString(launcher, retargeted.get());
            CliOutput.success("Launcher " + launcher + " now runs " + target.getFileName());
        } else {
            CliOutput.warning("Could not update a launcher at " + launcher + " — run "
                    + target.resolve("bin").resolve("vidocq") + " or point your PATH at it.");
        }
    }

    private static void refreshCompletion(Path target) {
        Path home = Path.of(System.getProperty("user.home", "."));
        for (Shell shell : Shell.values()) {
            if (!Files.isRegularFile(ShellSetup.scriptFile(shell, home))) {
                continue;
            }
            try {
                Process p = new ProcessBuilder(target.resolve("bin").resolve("vidocq").toString(),
                        "completion", "install", shell.token())
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
                if (p.waitFor() == 0) {
                    CliOutput.success("Completion script refreshed for " + shell.token());
                }
            } catch (IOException e) {
                CliOutput.warning("Could not refresh the " + shell.token() + " completion: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Identifies this CLI build in the completion scripts it writes. */
    static String completionStamp() {
        return Version.cli() + " " + Version.buildTimestamp();
    }

    /**
     * Rewrites the installed completion scripts another CLI build wrote: a CLI replaced
     * without 'vidocq update' (a manual install, a local build) still completes its own
     * commands. Cheap: at most one small file read per shell.
     */
    static void refreshStaleCompletion() {
        ShellSetup.refreshStale(Path.of(System.getProperty("user.home", ".")), completionStamp(),
                shell -> CompletionScripts.script(shell, CommandCatalog.COMMANDS, completionStamp()));
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
                Files.writeString(script, CompletionScripts.script(shell, CommandCatalog.COMMANDS,
                        completionStamp()));
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
        System.out.print(CompletionScripts.script(completion.shell(), CommandCatalog.COMMANDS,
                completionStamp()));
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

    /**
     * Adds (or removes) the {@code module-info.java} directives of {@code ids}, when the
     * project has one — the module path needs them as much as the pom needs the dependency.
     *
     * @return whether the file changed
     */
    private static boolean updateModuleInfo(Path projectDir, List<String> ids, boolean add) {
        Path file = projectDir.resolve("src").resolve("main").resolve("java").resolve("module-info.java");
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            String source = Files.readString(file);
            String pkg = applicationPackage(source, file.getParent());
            String updated = source;
            for (String id : ids) {
                updated = (add
                        ? ModuleInfoEditor.add(updated, id, KnownExtensions.moduleDirectives(id, pkg))
                        : ModuleInfoEditor.remove(updated, id, KnownExtensions.moduleName(id).orElse(null)))
                        .source();
            }
            if (updated.equals(source)) {
                return false;
            }
            Files.writeString(file, updated);
            CliOutput.success("module-info.java updated"
                    + CliOutput.dim("  (" + (add ? "requires/opens for " : "dropped ") + String.join(", ", ids) + ")"));
            return true;
        } catch (IOException | IllegalArgumentException e) {
            CliOutput.warning("Could not update module-info.java: " + e.getMessage());
            return false;
        }
    }

    /** The module's name when it is also a package of the sources (the scaffold's layout), else null. */
    private static String applicationPackage(String moduleInfo, Path javaRoot) {
        var m = Pattern.compile("\\bmodule\\s+([A-Za-z0-9_.]+)\\s*\\{").matcher(moduleInfo);
        if (!m.find()) {
            return null;
        }
        String name = m.group(1);
        return Files.isDirectory(javaRoot.resolve(name.replace('.', '/'))) ? name : null;
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

        // Pin the runtime version: the parent manages Vidocq artifacts at ${project.version},
        // which is the application's own version as soon as it declares one.
        String runtimeVersion;
        try {
            runtimeVersion = PomDependencies.runtimeVersion(pom).orElse(Version.runtime());
        } catch (IllegalArgumentException e) {
            CliOutput.error(e.getMessage());
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
            PomEditor.Result result = add
                    ? PomEditor.add(pom, coord, runtimeVersion)
                    : PomEditor.remove(pom, coord);
            pom = result.pom();
            // Extensions shipping an APT codegen bundle need it on the compiler's
            // annotationProcessorPaths, or vidocq:checkpom fails the next build; removing
            // the extension removes it too.
            var codegen = KnownExtensions.codegenBundle(id);
            if (codegen.isPresent()) {
                PomEditor.Result apt = add
                        ? PomEditor.addAnnotationProcessorPath(pom, codegen.get(), runtimeVersion)
                        : PomEditor.removeAnnotationProcessorPath(pom, codegen.get());
                pom = apt.pom();
                if (apt.changed()) {
                    anyChange = true;
                    CliOutput.success((add ? "Wired " : "Unwired ") + codegen.get().artifactId()
                            + CliOutput.dim("  (annotationProcessorPaths)"));
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

        boolean moduleInfoChanged = updateModuleInfo(pomPath.getParent(), ids, add);
        if (!anyChange) {
            CliOutput.println();
            CliOutput.info(moduleInfoChanged ? "pom.xml left untouched." : "No changes — pom.xml left untouched.");
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
