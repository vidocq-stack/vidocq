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
import io.vidocq.runtime.cli.ext.ExtensionCache;
import io.vidocq.runtime.cli.ext.ProjectExtensions;
import io.vidocq.runtime.cli.ext.TempFiles;
import io.vidocq.runtime.cli.spi.CliPlugins;
import io.vidocq.runtime.cli.spi.VidocqCliPlugin;
import io.vidocq.runtime.cli.update.CliInstaller;
import io.vidocq.runtime.cli.update.HttpDownloads;
import io.vidocq.runtime.cli.update.UpdateNotice;
import io.vidocq.runtime.cli.update.UpdatePlan;
import io.vidocq.runtime.tooling.ConfigFile;
import io.vidocq.runtime.tooling.ConfigKeys;
import io.vidocq.runtime.tooling.Diagnostic;
import io.vidocq.runtime.tooling.Diagnostics;
import io.vidocq.runtime.tooling.DoctorFacts;
import io.vidocq.runtime.tooling.ExtensionEditor;
import io.vidocq.runtime.tooling.KnownExtensions;
import io.vidocq.runtime.tooling.ProjectDoctor;
import io.vidocq.runtime.tooling.PropertiesText;
import io.vidocq.runtime.tooling.ProjectScaffolder;
import io.vidocq.runtime.tooling.ProjectSummary;
import io.vidocq.runtime.tooling.RegistryEntry;
import io.vidocq.runtime.tooling.Reporter;

import java.io.Console;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
/**
 * Dispatches a parsed {@link Command} to its implementation.
 * Uses pattern-matching switch so the compiler enforces exhaustiveness
 * as the sealed hierarchy grows.
 */
public final class CommandRunner {

    private static final String POM_XML = "pom.xml";
    /** The Unix launcher of an installed CLI, under its {@code bin} directory. */
    private static final String LAUNCHER = "vidocq";

    private CommandRunner() {}

    public static int run(Command command) {
        return switch (command) {
            case Command.Version _             -> runVersion();
            case Command.Info _                -> runInfo(Path.of("").toAbsolutePath());
            case Command.Help h                -> runHelp(h);
            case Command.Start s               -> runStart(s);
            case Command.Dev d                 -> runDev(d);
            case Command.Doctor doc            -> runDoctor(doc);
            case Command.ContinuousTest _      -> runMaven(RunGoals.test(), MavenInvocation.Options.none(), false,
                    "Starting " + CliOutput.bold("continuous testing") + "…");
            case Command.Idea(String check, String kind) -> runMaven(RunGoals.idea(check, kind),
                    MavenInvocation.Options.none(), false,
                    progress(check != null, "the IntelliJ run configurations"));
            case Command.Vscode(boolean check) -> runMaven(RunGoals.vscode(check), MavenInvocation.Options.none(),
                    false, progress(check, "the VS Code files in .vscode/"));
            case Command.Eclipse(boolean check) -> runMaven(RunGoals.eclipse(check), MavenInvocation.Options.none(),
                    false, progress(check, "the Eclipse configurations in .eclipse/"));
            case Command.Create c              -> runCreate(c);
            case Command.CreateInteractively c -> runCreateInteractively(c);
            case Command.Build b               -> runBuild(b);
            case Command.Clean cl              -> runClean(cl);
            case Command.Config cfg            -> switch (cfg) {
                case Command.Config.Get g       -> runConfigGet(g);
                case Command.Config.Set s       -> runConfigSet(s, Path.of("").toAbsolutePath());
                case Command.Config.Listing _   -> runConfigList();
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

    /**
     * The environment, then a summary of the project in {@code cwd} read from its files: no Maven run, the detailed
     * list of extensions being {@code vidocq extension list}'s (Vidocq/vidocq#353).
     */
    static int runInfo(Path cwd) {
        CliOutput.println(CliOutput.bold("Environment"));
        infoLine("CLI version:", Version.cliDisplay());
        infoLine("Runtime for new projects:", Version.runtime());
        infoLine("Java version:", System.getProperty("java.version"));
        infoLine("JVM:", System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
        infoLine("OS:", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        CliOutput.println();
        CliOutput.println(CliOutput.bold("Project"));
        if (!Files.isRegularFile(cwd.resolve(POM_XML))) {
            CliOutput.println(CliOutput.dim("  (no pom.xml in this directory)"));
            return 0;
        }
        ProjectSummary summary;
        try {
            summary = ProjectSummary.read(cwd, System::getenv);
        } catch (IOException e) {
            CliOutput.error("Cannot read the project: " + e.getMessage());
            return 1;
        }
        infoLine("Main module:", orUnknown(summary.mainModule()));
        infoLine("Runtime version:", orUnknown(summary.runtimeVersion()));
        infoLine("Extensions:", summary.extensions().size() + " declared"
                + CliOutput.dim("  (vidocq extension list for the details, transitive ones included)"));
        infoLine("Profile:", summary.profile() == null
                ? "none" + CliOutput.dim("  (vidocq dev runs with dev)")
                : summary.profile() + CliOutput.dim("  (" + summary.profileSource() + ")"));
        return 0;
    }

    private static void infoLine(String label, String value) {
        System.out.printf("  %-26s %s%n", label, value);
    }

    private static String orUnknown(String value) {
        return value == null ? CliOutput.dim("unknown") : value;
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
        if (!Files.isRegularFile(cwd.resolve(POM_XML))) {
            CliOutput.println(CliOutput.dim("  (no pom.xml in the current directory)"));
            return 1;
        }
        var resolution = resolveProjectExtensions(cwd, refresh);
        return switch (resolution) {
            case ProjectExtensions.Resolution.Failed(var reason, var mavenOutput) -> {
                CliOutput.error("Could not resolve the project's dependencies: " + reason);
                if (!mavenOutput.isBlank()) {
                    System.err.println(mavenOutput);
                }
                yield 1;
            }
            case ProjectExtensions.Resolution.Resolved(var extensions, var cached) -> {
                if (extensions.isEmpty()) {
                    CliOutput.println(CliOutput.dim(
                            "  (none — add one with 'vidocq extension add <id>')"));
                }
                extensions.forEach(ext ->
                        System.out.printf("  %s %-26s %s%s%n",
                                CliOutput.green("✔"),
                                ext.id(),
                                CliOutput.dim(ext.artifact().toString()),
                                ext.direct() ? "" : CliOutput.dim("  (transitive)")));
                if (cached) {
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
        Path cwd = Path.of("").toAbsolutePath();
        return runDoctor(doctor, cwd, () -> switch (resolveProjectExtensions(cwd, false)) {
            case ProjectExtensions.Resolution.Resolved r -> DoctorFacts.Extensions.found(r.extensions().size());
            case ProjectExtensions.Resolution.Failed f   -> DoctorFacts.Extensions.failed(f.reason());
        });
    }

    /** {@code vidocq doctor} on the project in {@code dir}, whose extensions {@code extensions} counts. */
    static int runDoctor(Command.Doctor doctor, Path dir, Supplier<DoctorFacts.Extensions> extensions) {
        CliOutput.println(CliOutput.bold("Vidocq doctor — environment & project checks"));
        CliOutput.println();
        if (doctor.fix()) {
            fixProject(dir);
        }

        List<Diagnostic> diagnostics = new ArrayList<>(Diagnostics.run(
                DoctorFacts.gather(dir, System.getenv("JAVA_HOME"), extensions)));
        diagnostics.addAll(ProjectDoctor.check(ProjectDoctor.read(dir, DoctorFacts::portFree)));
        diagnostics.forEach(d -> printDiagnostic(d, doctor.verbose()));

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

    /** {@code vidocq doctor --fix}: the repairs, before the report. */
    private static void fixProject(Path dir) {
        try {
            if (!ProjectDoctor.fix(dir, Version.runtime(), CLI_REPORTER)) {
                CliOutput.info("Nothing to fix.");
            }
        } catch (IOException e) {
            CliOutput.error("Could not fix the project: " + e.getMessage());
        }
        CliOutput.println();
    }

    private static void printDiagnostic(Diagnostic d, boolean verbose) {
        CliOutput.println(String.format("  %s %-17s %s", symbol(d.status()), d.name(), d.detail()));
        if (d.hint() != null && (verbose || d.status() != Diagnostic.Status.OK)) {
            CliOutput.println("       " + CliOutput.dim("↳ " + d.hint()));
        }
        if (d.fixable() && d.status() != Diagnostic.Status.OK) {
            CliOutput.println("       " + CliOutput.dim("↳ Fix: vidocq doctor --fix"));
        }
    }

    private static String symbol(Diagnostic.Status status) {
        return switch (status) {
            case OK   -> CliOutput.green("✔");
            case WARN -> CliOutput.yellow("⚠");
            case FAIL -> CliOutput.red("✘");
        };
    }

    private static int runCreate(Command.Create create) {
        return runCreate(create, Path.of(""));
    }

    /** {@code vidocq create} without {@code --name}: the questions on a console, else the missing option. */
    private static int runCreateInteractively(Command.CreateInteractively create) {
        CreatePrompts.Prompter console = CreatePrompts.console();
        if (console == null) {
            throw new CliException("'create' requires --name <app-name>.");
        }
        return runCreate(new Command.Create(CreatePrompts.ask(create, console)));
    }

    /** {@code vidocq create} in {@code baseDir}. */
    static int runCreate(Command.Create create, Path baseDir) {
        CliOutput.info("Scaffolding project '" + CliOutput.bold(create.spec().name()) + "'…");
        try {
            ProjectScaffolder.scaffold(create.spec(), baseDir, Version.runtime(), CLI_REPORTER);
        } catch (Exception e) {
            CliOutput.error("Failed to create project: " + e.getMessage());
            return 1;
        }
        CliOutput.success("Project '" + create.spec().name() + "' created.");
        CliOutput.println();
        CliOutput.println("  Navigate:  " + CliOutput.cyan("cd " + create.spec().name()));
        CliOutput.println("  Dev mode:  " + CliOutput.cyan("vidocq dev"));
        CliOutput.println("  Test:      " + CliOutput.cyan("vidocq test"));
        CliOutput.println("  Build:     " + CliOutput.cyan("vidocq build"));
        CliOutput.println("  Run:       " + CliOutput.cyan("vidocq start"));
        if (!create.spec().main()) {
            CliOutput.println();
            CliOutput.println("  " + CliOutput.dim("No main class: the runtime boots the app. Add " + CliOutput.cyan("--main")
                    + CliOutput.dim(" for an @VidocqMain trampoline (IDE Run, VidocqApp).")));
        }
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

    /** {@code vidocq config set} in the project in {@code cwd}; warns on a key no Vidocq module reads (#353). */
    static int runConfigSet(Command.Config.Set set, Path cwd) {
        Path file = ConfigFile.locate(cwd, Files::isRegularFile)
                .orElseGet(() -> ConfigFile.resolveTarget(cwd, Files::isDirectory));
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
        if (!ConfigKeys.isKnown(set.key())) {
            CliOutput.warning("No Vidocq module reads '" + set.key() + "'."
                    + ConfigKeys.suggestion(set.key()).map(k -> " Did you mean '" + k + "'?").orElse(""));
        }
        return 0;
    }

    private static int runConfigList() {
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
        CliOutput.info("Current: " + Version.cliDisplay()
                + CliInstaller.installedBuild(installed).map(b -> " — build " + b).orElse(""));
        try (HttpDownloads http = new HttpDownloads()) {
            var decision = updatePlan(installed, http);
            switch (decision) {
                case UpdatePlan.UpToDate(var current) -> {
                    CliOutput.success("Already up to date: " + current);
                    return 0;
                }
                case UpdatePlan.Unavailable(var reason) -> {
                    CliOutput.error("Could not check for updates: " + reason);
                    return 1;
                }
                case UpdatePlan.Update u -> {
                    String label = u.version() + u.build().map(b -> " (build " + b + ")").orElse("");
                    if (update.checkOnly()) {
                        CliOutput.info("Update available: " + label + " — run 'vidocq update'.");
                        return 0;
                    }
                    CliOutput.info("Downloading " + u.zipUrl());
                    Path zip = TempFiles.createFile("vidocq-cli", ".zip");
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

    /** What {@code vidocq update} would install in place of the CLI installed in {@code installed}. */
    private static UpdatePlan.Decision updatePlan(Path installed, HttpDownloads http) {
        String central = env("VIDOCQ_CENTRAL", "https://repo1.maven.org/maven2") + CLI_PATH;
        String snapshots = env("VIDOCQ_SNAPSHOTS",
                "https://central.sonatype.com/repository/maven-snapshots") + CLI_PATH;
        return UpdatePlan.plan(Version.cli(), CliInstaller.installedBuild(installed), Version.buildTimestamp(),
                http::fetch, central, snapshots);
    }

    /**
     * The notice of a newer build to give after {@code command} (#353). None after a command whose output a script
     * reads, after the update itself, off a terminal, for a CLI that install.sh or 'vidocq update' did not install,
     * or within a day of the last check.
     */
    static UpdateNotice updateNotice(Command command) {
        if (command instanceof Command.Version || command instanceof Command.Completion
                || command instanceof Command.CompletionSetup || command instanceof Command.Update
                || command instanceof Command.Config.Get) {
            return UpdateNotice.NONE;
        }
        Path installed = installedCliDirectory();
        Console console = System.console();
        if (installed == null || !UpdateNotice.due(System::getenv, console != null && console.isTerminal(),
                updateStamp(), Instant.now())) {
            return UpdateNotice.NONE;
        }
        return UpdateNotice.start(() -> {
            try (HttpDownloads http = new HttpDownloads()) {
                return updatePlan(installed, http);
            }
        });
    }

    /** The user's home directory, where the CLI keeps its caches and settings. */
    private static Path userHome() {
        return Path.of(System.getProperty("user.home", "."));
    }

    /** When the update notice last checked. */
    static Path updateStamp() {
        return userHome().resolve(".vidocq").resolve("cache")
                .resolve("update-check");
    }

    /** {@code <root>/<version>} holding this CLI's {@code modules/} and {@code bin/vidocq}, or {@code null}. */
    private static Path installedCliDirectory() {
        try {
            Path jar = Path.of(CommandRunner.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path modules = jar.getParent();
            Path dir = modules == null ? null : modules.getParent();
            return dir != null && "modules".equals(String.valueOf(modules.getFileName()))
                    && Files.isRegularFile(dir.resolve("bin").resolve(LAUNCHER)) ? dir : null;
        } catch (Exception _) {
            return null;
        }
    }

    private static void retargetLauncher(Path target) throws IOException {
        Path bin = Path.of(env("VIDOCQ_BIN", userHome()
                .resolve(".local").resolve("bin").toString()));
        Path launcher = bin.resolve(LAUNCHER);
        var retargeted = Files.isRegularFile(launcher)
                ? CliInstaller.retargetLauncher(Files.readString(launcher), target)
                : Optional.<String>empty();
        if (retargeted.isPresent()) {
            Files.writeString(launcher, retargeted.get());
            CliOutput.success("Launcher " + launcher + " now runs " + target.getFileName());
        } else {
            CliOutput.warning("Could not update a launcher at " + launcher + " — run "
                    + target.resolve("bin").resolve(LAUNCHER) + " or point your PATH at it.");
        }
    }

    private static void refreshCompletion(Path target) {
        Path home = userHome();
        for (Shell shell : Shell.values()) {
            if (!Files.isRegularFile(ShellSetup.scriptFile(shell, home))) {
                continue;
            }
            try {
                Process p = new ProcessBuilder(target.resolve("bin").resolve(LAUNCHER).toString(),
                        "completion", "install", shell.token())
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
                if (p.waitFor() == 0) {
                    CliOutput.success("Completion script refreshed for " + shell.token());
                }
            } catch (IOException e) {
                CliOutput.warning("Could not refresh the " + shell.token() + " completion: " + e.getMessage());
            } catch (InterruptedException _) {
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
        ShellSetup.refreshStale(userHome(), completionStamp(),
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
        Path home = userHome();
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
            // The catalog built into this CLI, by family (#353): a remote catalog belongs with #17.
            KnownExtensions.catalogByFamily().forEach((family, entries) -> {
                if (!entries.isEmpty()) {
                    CliOutput.println();
                    CliOutput.println("  " + CliOutput.bold(family.title()));
                    for (RegistryEntry e : entries) {
                        System.out.printf("    %s %-28s %s%n", CliOutput.cyan("•"), e.id(),
                                CliOutput.dim(e.description()));
                    }
                }
            });
            CliOutput.println();
            CliOutput.println(CliOutput.dim("  Add one with 'vidocq extension add <id>'."));
        }
        return exit;
    }

    /** Answers from the on-disk cache when it holds, otherwise runs (and announces) Maven. */
    private static ProjectExtensions.Resolution resolveProjectExtensions(Path cwd, boolean refresh) {
        Path cacheFile = ExtensionCache.fileFor(userHome()
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

    private static int runExtensionAdd(Command.Extension.Add add) {
        return editPom(Path.of("").toAbsolutePath(), add.ids(), true);
    }

    private static int runExtensionRemove(Command.Extension.Remove remove) {
        return editPom(Path.of("").toAbsolutePath(), remove.ids(), false);
    }

    /** How the shared project editing reports on the terminal. */
    private static final Reporter CLI_REPORTER = new Reporter() {
        @Override
        public void success(String message, String detail) {
            CliOutput.success(detail.isEmpty() ? message : message + CliOutput.dim("  (" + detail + ")"));
        }

        @Override
        public void note(String message) {
            CliOutput.println("  " + CliOutput.dim(message));
        }

        @Override
        public void warning(String message) {
            CliOutput.warning(message);
        }

        @Override
        public void error(String message) {
            CliOutput.error(message);
        }
    };

    /**
     * Adds (or removes) the {@code module-info.java} directives of {@code ids}, when the
     * project has one.
     *
     * @return whether the file changed
     */
    static boolean updateModuleInfo(Path projectDir, List<String> ids, boolean add) {
        return ExtensionEditor.updateModuleInfo(projectDir, ids, add, CLI_REPORTER);
    }

    /**
     * {@code vidocq extension add} ({@code add}) or {@code remove} on the project in {@code projectDir}: the
     * edit {@code vidocq:add-extension} and {@code vidocq:remove-extension} make too (#314).
     */
    static int editPom(Path projectDir, List<String> ids, boolean add) {
        ExtensionEditor.Outcome outcome = ExtensionEditor.edit(projectDir, ids, add, Version.runtime(), CLI_REPORTER);
        if (!outcome.succeeded()) {
            return 1;
        }
        CliOutput.println();
        if (!outcome.pomChanged()) {
            CliOutput.info(outcome.moduleInfoChanged() ? "pom.xml left untouched." : "No changes — pom.xml left untouched.");
            return 0;
        }
        CliOutput.success("pom.xml updated.");
        CliOutput.println(CliOutput.dim("  Run 'mvn package' (or './mvnw package' if present) to fetch the new dependencies."));
        return 0;
    }

    /** What a goal that writes, or with {@code check} checks, IDE files says while Maven runs. */
    static String progress(boolean check, String files) {
        return (check ? "Checking " : "Writing ") + files + "…";
    }
}
