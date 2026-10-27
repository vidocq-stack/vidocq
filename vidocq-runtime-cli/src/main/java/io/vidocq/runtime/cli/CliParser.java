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

import io.vidocq.runtime.cli.build.BuildType;
import io.vidocq.runtime.cli.completion.Shell;
import io.vidocq.runtime.cli.spi.CliPlugins;
import io.vidocq.runtime.cli.spi.VidocqCliPlugin;
import io.vidocq.runtime.tooling.ProjectSpec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Converts a raw {@code String[]} argv into a typed {@link Command}.
 * No external dependency — hand-rolled to stay zero-dep.
 */
public final class CliParser {

    private static final String START = "start";
    private static final String DOCTOR = "doctor";
    private static final String DEBUG = "--debug";
    private static final String CHECK = "--check";
    private static final String VSCODE = "vscode";
    private static final String ECLIPSE = "eclipse";
    private static final String OPTIONS = "Options:";
    private static final String CREATE = "create";
    private static final String BUILD = "build";
    private static final String CLEAN = "clean";
    private static final String UPDATE = "update";
    private static final String PORT = "--port";
    private static final String DRY_RUN = "--dry-run";

    private CliParser() {}

    public static Command parse(String[] args) {
        return switch (args[0]) {
            case "version", "--version", "-V" -> new Command.Version();
            case "info"                       -> new Command.Info();
            case "help", "--help", "-h"       -> new Command.Help(args.length > 1 ? args[1] : null);
            case START                      -> parseStart(args, 1);
            case "dev"                        -> parseDev(args, 1);
            case DOCTOR                     -> parseDoctor(args, 1);
            case "test"                       -> parseTest(args, 1);
            case "idea"                       -> parseIdea(args, 1);
            case VSCODE                       -> parseVscode(args, 1);
            case ECLIPSE                      -> parseEclipse(args, 1);
            case CREATE                     -> parseCreate(args, 1);
            case BUILD                      -> parseBuild(args, 1);
            case CLEAN                      -> parseClean(args, 1);
            case "extension", "ext"           -> parseExtension(args, 1);
            case "config"                     -> parseConfig(args, 1);
            case "completion"                 -> parseCompletion(args, 1);
            case UPDATE                     -> parseUpdate(args, 1);
            default -> new Command.Plugin(args[0], rest(args, 1));
        };
    }

    // -------------------------------------------------------------------------
    // Sub-parsers
    // -------------------------------------------------------------------------

    private static Command.Start parseStart(String[] args, int from) {
        int port = 8080;
        boolean portExplicit = false;
        Path config = null;
        boolean debug = false;
        for (int i = from; i < args.length; i++) {
            switch (args[i]) {
                case PORT, "-p"   -> { port = parseInt(args, ++i, PORT); portExplicit = true; }
                case "--config", "-c" -> config = Path.of(value(args, ++i, "--config"));
                case DEBUG        -> debug  = true;
                default               -> unknownOpt(args[i], START);
            }
        }
        return new Command.Start(port, portExplicit, config, debug);
    }

    private static Command.Dev parseDev(String[] args, int from) {
        int port = 8080;
        boolean portExplicit = false;
        String profile = "dev";
        boolean debug = false;
        for (int i = from; i < args.length; i++) {
            switch (args[i]) {
                case PORT, "-p"    -> { port = parseInt(args, ++i, PORT); portExplicit = true; }
                case "--profile", "-P" -> profile = value(args, ++i, "--profile");
                case DEBUG         -> debug   = true;
                default                -> unknownOpt(args[i], "dev");
            }
        }
        return new Command.Dev(port, portExplicit, profile, debug);
    }

    private static Command.ContinuousTest parseTest(String[] args, int from) {
        if (args.length > from) {
            unknownOpt(args[from], "test");
        }
        return new Command.ContinuousTest();
    }

    private static Command.Vscode parseVscode(String[] args, int from) {
        boolean check = false;
        for (String arg : Arrays.asList(args).subList(from, args.length)) {
            if (CHECK.equals(arg)) {
                check = true;
            } else {
                unknownOpt(arg, VSCODE);
            }
        }
        return new Command.Vscode(check);
    }

    private static Command.Eclipse parseEclipse(String[] args, int from) {
        boolean check = false;
        for (String arg : Arrays.asList(args).subList(from, args.length)) {
            if (CHECK.equals(arg)) {
                check = true;
            } else {
                unknownOpt(arg, ECLIPSE);
            }
        }
        return new Command.Eclipse(check);
    }

    private static Command.Idea parseIdea(String[] args, int from) {
        String check = null;
        String kind = null;
        Iterator<String> it = Arrays.asList(args).subList(from, args.length).iterator();
        while (it.hasNext()) {
            String arg = it.next();
            switch (arg) {
                case CHECK      -> check = "true";
                case "--strict" -> check = "strict";
                case "--kind"   -> kind  = next(it, "--kind");
                default         -> unknownOpt(arg, "idea");
            }
        }
        return new Command.Idea(check, kind);
    }

    /** The value after an option, read from {@code it}. */
    private static String next(Iterator<String> it, String opt) {
        if (!it.hasNext()) throw new CliException("Option " + opt + " requires a value.");
        return it.next();
    }

    private static Command.Doctor parseDoctor(String[] args, int from) {
        boolean verbose = false;
        boolean fix = false;
        for (int i = from; i < args.length; i++) {
            switch (args[i]) {
                case "--verbose", "-v" -> verbose = true;
                case "--fix"           -> fix = true;
                default                -> unknownOpt(args[i], DOCTOR);
            }
        }
        return new Command.Doctor(verbose, fix);
    }

    private static Command parseCreate(String[] args, int from) {
        String name = null;
        String groupId = null;
        String pkg = null;
        String parentVersion = null;
        boolean main = false;
        Set<String> extensions = new LinkedHashSet<>();
        for (int i = from; i < args.length; i++) {
            switch (args[i]) {
                case "--name", "-n"      -> name    = value(args, ++i, "--name");
                case "--group-id", "-g"  -> groupId = value(args, ++i, "--group-id");
                case "--package"         -> pkg     = value(args, ++i, "--package");
                case "--extension", "-x" -> extensions.add(value(args, ++i, "--extension"));
                case "--parent-version"  -> parentVersion = value(args, ++i, "--parent-version");
                case "--main"            -> main = true;
                default                  -> unknownOpt(args[i], CREATE);
            }
        }
        if (name == null) {
            return new Command.CreateInteractively(groupId, pkg, extensions, parentVersion, main);
        }
        try {
            return new Command.Create(ProjectSpec.of(name, groupId, pkg, extensions, parentVersion, main,
                    "--package <pkg>"));
        } catch (IllegalArgumentException e) {
            throw new CliException(e.getMessage());
        }
    }

    /** Java keywords and literals, which no package segment may be (JLS §3.9, §3.10). */

    private static Command.Build parseBuild(String[] args, int from) {
        BuildType type = BuildType.PACKAGE;
        boolean offline = false;
        boolean skipTests = false;
        boolean dryRun = false;
        List<String> passthrough = new ArrayList<>();
        int i = from;
        // An optional leading non-flag token selects the build flavour.
        if (i < args.length && !args[i].startsWith("-")) {
            try {
                type = BuildType.fromToken(args[i++]);
            } catch (IllegalArgumentException e) {
                throw new CliException(e.getMessage());
            }
        }
        for (; i < args.length; i++) {
            switch (args[i]) {
                case "--offline", "-o" -> offline   = true;
                case "--skip-tests"    -> skipTests = true;
                case DRY_RUN       -> dryRun    = true;
                case "--"              -> { passthrough.addAll(rest(args, i + 1)); i = args.length; }
                default                -> unknownOpt(args[i], BUILD);
            }
        }
        return new Command.Build(type, offline, skipTests, dryRun, List.copyOf(passthrough));
    }

    private static Command.Clean parseClean(String[] args, int from) {
        boolean offline = false;
        boolean dryRun = false;
        List<String> passthrough = new ArrayList<>();
        for (int i = from; i < args.length; i++) {
            switch (args[i]) {
                case "--offline", "-o" -> offline = true;
                case DRY_RUN       -> dryRun  = true;
                case "--"              -> { passthrough.addAll(rest(args, i + 1)); i = args.length; }
                default                -> unknownOpt(args[i], CLEAN);
            }
        }
        return new Command.Clean(offline, dryRun, List.copyOf(passthrough));
    }

    private static List<String> rest(String[] args, int from) {
        List<String> out = new ArrayList<>();
        for (int i = from; i < args.length; i++) out.add(args[i]);
        return out;
    }

    private static Command parseConfig(String[] args, int from) {
        if (from >= args.length) {
            throw new CliException("'config' requires a sub-command: get, set, or list.");
        }
        return switch (args[from]) {
            case "get" -> {
                if (from + 1 >= args.length) throw new CliException("'config get' requires a <key>.");
                yield new Command.Config.Get(args[from + 1]);
            }
            case "set" -> {
                if (from + 2 >= args.length) {
                    throw new CliException("'config set' requires a <key> and a <value>.");
                }
                yield new Command.Config.Set(args[from + 1], args[from + 2]);
            }
            case "list", "ls" -> new Command.Config.Listing();
            default -> throw new CliException(
                    "Unknown config sub-command '" + args[from] + "'. Available: get, set, list.");
        };
    }

    private static Command.Update parseUpdate(String[] args, int from) {
        boolean check = false;
        for (int i = from; i < args.length; i++) {
            switch (args[i]) {
                case CHECK     -> check = true;
                default        -> unknownOpt(args[i], UPDATE);
            }
        }
        return new Command.Update(check);
    }

    private static Command parseCompletion(String[] args, int from) {
        if (from >= args.length) {
            throw new CliException("'completion' requires a shell: "
                    + String.join(", ", Shell.tokens()) + ", or install / uninstall.");
        }
        try {
            if (args[from].equals("install") || args[from].equals("uninstall")) {
                if (from + 2 < args.length) {
                    unknownOpt(args[from + 2], "completion " + args[from]);
                }
                Shell shell = from + 1 < args.length ? Shell.fromToken(args[from + 1]) : null;
                return new Command.CompletionSetup(shell, args[from].equals("install"));
            }
            return new Command.Completion(Shell.fromToken(args[from]));
        } catch (IllegalArgumentException e) {
            throw new CliException(e.getMessage());
        }
    }

    private static Command parseExtension(String[] args, int from) {
        if (from >= args.length) return Command.Extension.Listing.defaults();
        return switch (args[from]) {
            case "list", "ls"   -> parseExtensionList(args, from + 1);
            case "add"          -> parseExtensionIds(args, from + 1, true);
            case "remove", "rm" -> parseExtensionIds(args, from + 1, false);
            default -> throw new CliException(
                    "Unknown extension sub-command '" + args[from] + "'. Available: list, add, remove.");
        };
    }

    private static Command.Extension.Listing parseExtensionList(String[] args, int from) {
        boolean installed = true;
        boolean available = false;
        boolean refresh = false;
        for (int i = from; i < args.length; i++) {
            switch (args[i]) {
                case "--installed" -> installed = true;
                case "--refresh"   -> refresh = true;
                case "--available" -> { available = true; installed = false; }
                case "--all"       -> { installed = true; available = true; }
                default            -> unknownOpt(args[i], "extension list");
            }
        }
        return new Command.Extension.Listing(installed, available, refresh);
    }

    private static Command parseExtensionIds(String[] args, int from, boolean add) {
        List<String> ids = new ArrayList<>();
        for (int i = from; i < args.length; i++) {
            if (args[i].startsWith("-")) unknownOpt(args[i], add ? "extension add" : "extension remove");
            ids.add(args[i]);
        }
        if (ids.isEmpty()) throw new CliException(
                (add ? "'extension add'" : "'extension remove'") + " requires at least one extension id.");
        return add ? new Command.Extension.Add(List.copyOf(ids))
                   : new Command.Extension.Remove(List.copyOf(ids));
    }

    // -------------------------------------------------------------------------
    // Help text
    // -------------------------------------------------------------------------

    static void printHelp(String topic) {
        if (topic != null) { printTopicHelp(topic); return; }

        CliOutput.println(CliOutput.bold("Vidocq CLI " + Version.cliDisplay()));
        CliOutput.println();
        CliOutput.println("Usage: " + CliOutput.cyan("vidocq") + " <command> [options]");
        CliOutput.println();
        CliOutput.println(CliOutput.bold("Commands:"));
        cmd("version",          "Print the CLI and runtime version.");
        cmd("info",             "Display runtime, JVM, and extension information.");
        cmd(START,            "Build and run the project's application (mvn vidocq:run).");
        cmd("dev",              "Run the project in dev mode with live reload (mvn vidocq:dev).");
        cmd("test",             "Run the tests on every change, without the application (mvn vidocq:test).");
        cmd("idea",             "Write the IntelliJ run configurations into .run/ (mvn vidocq:idea).");
        cmd(VSCODE,             "Write the VS Code settings, tasks and debug into .vscode/ (mvn vidocq:vscode).");
        cmd(ECLIPSE,            "Write the Eclipse run and debug configurations into .eclipse/ (mvn vidocq:eclipse).");
        cmd(DOCTOR,           "Run environment & project health checks.");
        cmd(CREATE,           "Scaffold a new Vidocq Maven application.");
        cmd("build [type]",     "Build/package the project (package, jlink, jpackage, docker).");
        cmd(CLEAN,            "Remove build output (mvn clean).");
        cmd("extension list",   "List installed (and optionally available) extensions.");
        cmd("extension add",    "Add extensions to the current project's pom.xml.");
        cmd("extension remove", "Remove extensions from the current project's pom.xml.");
        cmd("config get|set|list", "Read or write keys in vidocq.properties.");
        cmd("update [--check]",    "Update the CLI: latest release, or latest SNAPSHOT build.");
        cmd("completion bash|zsh", "Print a shell completion script.");
        cmd("completion install",  "Enable completion in your shell's rc file (uninstall to undo).");
        cmd("help [command]",   "Show detailed help for a specific command.");
        printPluginCommands();
        CliOutput.println();
        CliOutput.println(CliOutput.dim("Run 'vidocq help <command>' for per-command options."));
    }

    private static void printTopicHelp(String topic) {
        switch (topic) {
            case START -> {
                topic(START, "Build and run the application",
                        "Runs 'mvn vidocq:run' in the project (./mvnw when present): compiles it and",
                        "starts it with its dependencies and extensions on the module path.");
                options("--port, -p <n>",      "HTTP listening port, over the project's own setting",
                        "--config, -c <file>", "Path to an external vidocq.properties file",
                        DEBUG,             "Open a debug agent on port 5005");
            }
            case "dev" -> {
                topic("dev", "Development mode",
                        "Runs 'mvn process-classes vidocq:dev' in the project (./mvnw when present):",
                        "the application starts with its dependencies and extensions, the dev console",
                        "and the debug agent (port 5005), and restarts on every source change.");
                options("--port, -p <n>",       "HTTP listening port, over the project's own setting",
                        "--profile, -P <name>", "Config profile to activate (default: dev)",
                        DEBUG,              "Force the debug agent on (it is on by default in dev)");
            }
            case "test" -> topic("test", "Continuous testing",
                    "Runs 'mvn vidocq:test' in the project (./mvnw when present): every test, then",
                    "again on each change of the main or test sources, without the application.",
                    "Keys: r runs every test, f the failed ones, q quits.");
            case "idea" -> {
                topic("idea", "IntelliJ run configurations (experimental)",
                        "Runs 'mvn vidocq:idea' in the project (./mvnw when present): one dev, run and",
                        "debug configuration per application, in .run/, never .idea/.");
                options(CHECK,          "Only check that .run/ matches the poms (for CI)",
                        "--strict",     "Check, and also fail on files written or edited by hand",
                        "--kind <kind>", "maven (default) or application: an Application run of the main class");
            }
            case VSCODE -> {
                topic(VSCODE, "VS Code settings, tasks and debug",
                        "Runs 'mvn vidocq:vscode' in the project (./mvnw when present): .vscode/settings.json",
                        "runs the Java extension's language server on Java 25 and turns its automatic build",
                        "off, tasks.json runs vidocq dev, run and test, launch.json attaches the debugger to",
                        "vidocq dev. A file it did not write is left alone.");
                options(CHECK, "Only check that .vscode/ is up to date (for CI)");
            }
            case ECLIPSE -> {
                topic(ECLIPSE, "Eclipse run and debug configurations",
                        "Runs 'mvn vidocq:eclipse' in the project (./mvnw when present): .eclipse/ gets, per",
                        "application, Maven builds of vidocq dev, run and test and a debug attach to vidocq dev,",
                        "listed under Run Configurations. A file it did not write is left alone. Turn Project >",
                        "Build Automatically off while vidocq dev runs.");
                options(CHECK, "Only check that .eclipse/ is up to date (for CI)");
            }
            case DOCTOR -> {
                topic(DOCTOR, "Environment & project health checks",
                        "Checks the machine (Java version, JAVA_HOME, Maven wrapper) and the",
                        "project (codegen bundles, Vauban indexer, module-info.java directives,",
                        "Maven version, HTTP port) and prints a report. Exits non-zero if any",
                        "check fails.");
                options("--fix",         "First repair what it can: codegen paths, module-info.java, the wrapper",
                        "--verbose, -v", "Print extra details (per-status summary, minimum Java version)");
            }
            case CREATE -> {
                CliOutput.println(CliOutput.bold("vidocq create") + " — Scaffold a new Vidocq project");
                CliOutput.println();
                CliOutput.println(OPTIONS);
                opt("--name, -n <name>",         "Project / artifact name (asked on a console when left out)");
                opt("--group-id, -g <groupId>",  "Maven groupId            (default: io.example)");
                opt("--package <pkg>",            "Root Java package        (default: <groupId>.<name>)");
                opt("--extension, -x <id>",       "Extension to enable, repeatable");
                opt("--parent-version <version>", "Vidocq runtime parent version   (default: the CLI's runtime version)");
                opt("",                           "a SNAPSHOT adds the Central snapshot repository to the pom");
                opt("--main",                     "Also write an @VidocqMain trampoline (default: none, the runtime");
                opt("",                           "boots the app) — for an IDE Run or a VidocqApp");
                CliOutput.println();
                CliOutput.println("Example:");
                CliOutput.println("  " + CliOutput.cyan(
                        "vidocq create --name my-api -g com.acme -x cassini-rest -x knock-health"));
            }
            case BUILD -> {
                CliOutput.println(CliOutput.bold("vidocq build") + " — Build & package the project");
                CliOutput.println();
                CliOutput.println("Wraps the Vidocq Maven plugin. With no type it runs the package");
                CliOutput.println("lifecycle phase; a type layers the matching plugin goal on top.");
                CliOutput.println();
                CliOutput.println("Types:");
                cmd("package  (default)", "Standalone distribution ZIP (mvn package)");
                cmd("jlink",              "Self-contained jlink runtime image (vidocq:jlink)");
                cmd("jpackage",           "Native installer / app-image (vidocq:jpackage)");
                cmd("docker",             "Dockerfile around the jlink image (vidocq:docker)");
                CliOutput.println();
                CliOutput.println(OPTIONS);
                opt("--offline, -o",  "Run Maven offline (-o)");
                opt("--skip-tests",   "Skip tests (-DskipTests)");
                opt(DRY_RUN,      "Print the Maven command without running it");
                opt("-- <args...>",   "Pass everything after -- straight to Maven");
                CliOutput.println();
                CliOutput.println("Example:");
                CliOutput.println("  " + CliOutput.cyan("vidocq build jlink --skip-tests"));
            }
            case CLEAN -> {
                CliOutput.println(CliOutput.bold("vidocq clean") + " — Remove build output");
                CliOutput.println();
                CliOutput.println("Runs 'mvn clean' in the current project.");
                CliOutput.println();
                CliOutput.println(OPTIONS);
                opt("--offline, -o", "Run Maven offline (-o)");
                opt(DRY_RUN,     "Print the Maven command without running it");
                opt("-- <args...>",  "Pass everything after -- straight to Maven");
            }
            case "extension" -> {
                CliOutput.println(CliOutput.bold("vidocq extension") + " — Manage extensions");
                CliOutput.println();
                CliOutput.println("Sub-commands:");
                cmd("list [--installed|--available|--all] [--refresh]", "List extensions");
                cmd("add <id...>",                          "Add extensions to the project's pom.xml");
                cmd("remove <id...>",                       "Remove extensions from the project's pom.xml");
                CliOutput.println();
                CliOutput.println("Ids may be a short name (e.g. " + CliOutput.cyan("knock-health")
                        + "), an explicit " + CliOutput.cyan("groupId:artifactId") + ",");
                CliOutput.println("or any name (resolved as io.vidocq.runtime:vidocq-runtime-<id>-extension).");
                CliOutput.println();
                CliOutput.println("Examples:");
                CliOutput.println("  " + CliOutput.cyan("vidocq extension list --available"));
                CliOutput.println("  " + CliOutput.cyan("vidocq extension add cassini-rest knock-health"));
            }
            case "config" -> {
                CliOutput.println(CliOutput.bold("vidocq config") + " — Read & write vidocq.properties");
                CliOutput.println();
                CliOutput.println("Operates on the project's vidocq.properties (project root or");
                CliOutput.println("src/main/resources). 'set' preserves comments and key ordering.");
                CliOutput.println();
                CliOutput.println("Sub-commands:");
                cmd("get <key>",         "Print the value bound to <key>");
                cmd("set <key> <value>", "Set (or add) <key> to <value>");
                cmd("list",              "Print every key=value pair");
                CliOutput.println();
                CliOutput.println("Examples:");
                CliOutput.println("  " + CliOutput.cyan("vidocq config get vidocq.http.port"));
                CliOutput.println("  " + CliOutput.cyan("vidocq config set vidocq.http.port 9090"));
            }
            case UPDATE -> {
                CliOutput.println(CliOutput.bold("vidocq update") + " — Update the CLI itself");
                CliOutput.println();
                CliOutput.println("A release CLI moves to the latest release on Maven Central (0.3.0 → 0.4.0).");
                CliOutput.println("A SNAPSHOT CLI moves to the latest build of the highest SNAPSHOT line in the");
                CliOutput.println("Central snapshot repository. The new version is installed next to the others");
                CliOutput.println("in ~/.vidocq/cli, the 'vidocq' launcher is pointed at it, and an installed");
                CliOutput.println("completion script is regenerated.");
                CliOutput.println();
                CliOutput.println(OPTIONS);
                CliOutput.println("  --check    Only report whether an update is available");
                CliOutput.println();
                CliOutput.println("Environment: VIDOCQ_CENTRAL, VIDOCQ_SNAPSHOTS (repository mirrors),");
                CliOutput.println("             VIDOCQ_BIN (launcher directory, default ~/.local/bin)");
            }
            case "completion" -> {
                CliOutput.println(CliOutput.bold("vidocq completion") + " — Shell completion scripts");
                CliOutput.println();
                CliOutput.println("Prints a completion script to stdout for the given shell, or");
                CliOutput.println("sets it up for you: 'install' writes it to ~/.vidocq/completion/ and");
                CliOutput.println("sources it from ~/.zshrc or ~/.bashrc (shell taken from $SHELL when");
                CliOutput.println("omitted); 'uninstall' removes both. Re-run 'install' after an upgrade.");
                CliOutput.println();
                CliOutput.println("Shells: " + String.join(", ", Shell.tokens()));
                CliOutput.println();
                CliOutput.println("Examples:");
                CliOutput.println("  " + CliOutput.cyan("vidocq completion install"));
                CliOutput.println("  " + CliOutput.cyan("vidocq completion uninstall zsh"));
                CliOutput.println("  " + CliOutput.cyan("source <(vidocq completion bash)"));
                CliOutput.println("  " + CliOutput.cyan("vidocq completion zsh > \"${fpath[1]}/_vidocq\""));
            }
            default -> CliOutput.warning("No detailed help for '" + topic + "'. Run 'vidocq help'.");
        }
    }

    private static void cmd(String name, String desc) {
        System.out.printf("  %-30s %s%n", CliOutput.cyan(name), desc);
    }

    private static void printPluginCommands() {
        List<VidocqCliPlugin> plugins = CliPlugins.all();
        if (plugins.isEmpty()) {
            return;
        }
        CliOutput.println();
        CliOutput.println(CliOutput.bold("Plugin commands:"));
        for (VidocqCliPlugin plugin : plugins) {
            cmd(plugin.command(), plugin.description());
        }
    }

    /** The heading of a command's help, then the lines that describe it. */
    private static void topic(String command, String summary, String... description) {
        CliOutput.println(CliOutput.bold("vidocq " + command) + " — " + summary);
        CliOutput.println();
        for (String line : description) {
            CliOutput.println(line);
        }
    }

    /** The options of a command's help, as name and description pairs. */
    private static void options(String... namesAndDescriptions) {
        CliOutput.println();
        CliOutput.println(OPTIONS);
        for (int i = 0; i < namesAndDescriptions.length; i += 2) {
            opt(namesAndDescriptions[i], namesAndDescriptions[i + 1]);
        }
    }

    private static void opt(String name, String desc) {
        System.out.printf("  %-34s %s%n", CliOutput.yellow(name), desc);
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private static int parseInt(String[] args, int idx, String opt) {
        String raw = value(args, idx, opt);
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new CliException("Option " + opt + " expects an integer, got: " + raw);
        }
    }

    private static String value(String[] args, int idx, String opt) {
        if (idx >= args.length) throw new CliException("Option " + opt + " requires a value.");
        return args[idx];
    }

    private static void unknownOpt(String flag, String cmd) {
        throw new CliException(
                "Unknown option '" + flag + "' for '" + cmd + "'. Run 'vidocq help " + cmd.split(" ")[0] + "'.");
    }
}
