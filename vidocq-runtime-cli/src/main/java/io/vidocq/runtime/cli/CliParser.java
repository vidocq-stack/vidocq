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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Converts a raw {@code String[]} argv into a typed {@link Command}.
 * No external dependency — hand-rolled to stay zero-dep.
 */
public final class CliParser {

    private CliParser() {}

    public static Command parse(String[] args) {
        return switch (args[0]) {
            case "version", "--version", "-V" -> new Command.Version();
            case "info"                       -> new Command.Info();
            case "help", "--help", "-h"       -> new Command.Help(args.length > 1 ? args[1] : null);
            case "start"                      -> parseStart(args, 1);
            case "dev"                        -> parseDev(args, 1);
            case "doctor"                     -> parseDoctor(args, 1);
            case "create"                     -> parseCreate(args, 1);
            case "build"                      -> parseBuild(args, 1);
            case "clean"                      -> parseClean(args, 1);
            case "extension", "ext"           -> parseExtension(args, 1);
            case "config"                     -> parseConfig(args, 1);
            case "completion"                 -> parseCompletion(args, 1);
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
                case "--port", "-p"   -> { port = parseInt(args, ++i, "--port"); portExplicit = true; }
                case "--config", "-c" -> config = Path.of(value(args, ++i, "--config"));
                case "--debug"        -> debug  = true;
                default               -> unknownOpt(args[i], "start");
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
                case "--port", "-p"    -> { port = parseInt(args, ++i, "--port"); portExplicit = true; }
                case "--profile", "-P" -> profile = value(args, ++i, "--profile");
                case "--debug"         -> debug   = true;
                default                -> unknownOpt(args[i], "dev");
            }
        }
        return new Command.Dev(port, portExplicit, profile, debug);
    }

    private static Command.Doctor parseDoctor(String[] args, int from) {
        boolean verbose = false;
        for (int i = from; i < args.length; i++) {
            switch (args[i]) {
                case "--verbose", "-v" -> verbose = true;
                default                -> unknownOpt(args[i], "doctor");
            }
        }
        return new Command.Doctor(verbose);
    }

    private static Command.Create parseCreate(String[] args, int from) {
        String name = null, groupId = null, pkg = null, parentVersion = null;
        Set<String> extensions = new LinkedHashSet<>();
        for (int i = from; i < args.length; i++) {
            switch (args[i]) {
                case "--name", "-n"      -> name    = value(args, ++i, "--name");
                case "--group-id", "-g"  -> groupId = value(args, ++i, "--group-id");
                case "--package"         -> pkg     = value(args, ++i, "--package");
                case "--extension", "-x" -> extensions.add(value(args, ++i, "--extension"));
                case "--parent-version"  -> parentVersion = value(args, ++i, "--parent-version");
                default                  -> unknownOpt(args[i], "create");
            }
        }
        if (name == null) throw new CliException("'create' requires --name <app-name>.");
        String gid = groupId != null ? groupId : "io.example";
        String p   = pkg != null ? pkg : gid + "." + name.replace('-', '.');
        return new Command.Create(name, gid, p, Set.copyOf(extensions), parentVersion);
    }

    private static Command.Build parseBuild(String[] args, int from) {
        BuildType type = BuildType.PACKAGE;
        boolean offline = false, skipTests = false, dryRun = false;
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
                case "--dry-run"       -> dryRun    = true;
                case "--"              -> { passthrough.addAll(rest(args, i + 1)); i = args.length; }
                default                -> unknownOpt(args[i], "build");
            }
        }
        return new Command.Build(type, offline, skipTests, dryRun, List.copyOf(passthrough));
    }

    private static Command.Clean parseClean(String[] args, int from) {
        boolean offline = false, dryRun = false;
        List<String> passthrough = new ArrayList<>();
        for (int i = from; i < args.length; i++) {
            switch (args[i]) {
                case "--offline", "-o" -> offline = true;
                case "--dry-run"       -> dryRun  = true;
                case "--"              -> { passthrough.addAll(rest(args, i + 1)); i = args.length; }
                default                -> unknownOpt(args[i], "clean");
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

    private static Command.Completion parseCompletion(String[] args, int from) {
        if (from >= args.length) {
            throw new CliException("'completion' requires a shell: "
                    + String.join(", ", Shell.tokens()) + ".");
        }
        try {
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
        boolean installed = true, available = false;
        for (int i = from; i < args.length; i++) {
            switch (args[i]) {
                case "--installed" -> installed = true;
                case "--available" -> { available = true; installed = false; }
                case "--all"       -> { installed = true; available = true; }
                default            -> unknownOpt(args[i], "extension list");
            }
        }
        return new Command.Extension.Listing(installed, available);
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

        CliOutput.println(CliOutput.bold("Vidocq CLI " + Version.cli()));
        CliOutput.println();
        CliOutput.println("Usage: " + CliOutput.cyan("vidocq") + " <command> [options]");
        CliOutput.println();
        CliOutput.println(CliOutput.bold("Commands:"));
        cmd("version",          "Print the CLI and runtime version.");
        cmd("info",             "Display runtime, JVM, and extension information.");
        cmd("start",            "Start the Vidocq runtime.");
        cmd("dev",              "Start in development mode (watch sources, live config reload).");
        cmd("doctor",           "Run environment & project health checks.");
        cmd("create",           "Scaffold a new Vidocq Maven application.");
        cmd("build [type]",     "Build/package the project (package, jlink, jpackage, docker).");
        cmd("clean",            "Remove build output (mvn clean).");
        cmd("extension list",   "List installed (and optionally available) extensions.");
        cmd("extension add",    "Add extensions to the current project's pom.xml.");
        cmd("extension remove", "Remove extensions from the current project's pom.xml.");
        cmd("config get|set|list", "Read or write keys in vidocq.properties.");
        cmd("completion bash|zsh", "Print a shell completion script.");
        cmd("help [command]",   "Show detailed help for a specific command.");
        printPluginCommands();
        CliOutput.println();
        CliOutput.println(CliOutput.dim("Run 'vidocq help <command>' for per-command options."));
    }

    private static void printTopicHelp(String topic) {
        switch (topic) {
            case "start" -> {
                CliOutput.println(CliOutput.bold("vidocq start") + " — Start the Vidocq runtime");
                CliOutput.println();
                CliOutput.println("Options:");
                opt("--port, -p <n>",     "HTTP listening port (default: 8080)");
                opt("--config, -c <file>","Path to an external vidocq.properties file");
                opt("--debug",            "Print debug info; attach a remote debugger on port 5005");
            }
            case "dev" -> {
                CliOutput.println(CliOutput.bold("vidocq dev") + " — Development mode");
                CliOutput.println();
                CliOutput.println("Starts the runtime and watches your sources; a reload-worthy change");
                CliOutput.println("(.java/.class/.properties/.xml/.yml) restarts the runtime context so");
                CliOutput.println("configuration and resources are re-applied without leaving the CLI.");
                CliOutput.println();
                CliOutput.println("Options:");
                opt("--port, -p <n>",      "HTTP listening port (default: 8080)");
                opt("--profile, -P <name>","Config profile to layer (default: dev)");
                opt("--debug",             "Print JDWP connection hint for port 5005");
            }
            case "doctor" -> {
                CliOutput.println(CliOutput.bold("vidocq doctor") + " — Environment & project health checks");
                CliOutput.println();
                CliOutput.println("Runs a series of checks (Java version, JAVA_HOME, Maven wrapper,");
                CliOutput.println("project layout, extensions) and prints a report. Exits non-zero if");
                CliOutput.println("any check fails.");
                CliOutput.println();
                CliOutput.println("Options:");
                opt("--verbose, -v", "Print extra details (per-status summary, minimum Java version)");
            }
            case "create" -> {
                CliOutput.println(CliOutput.bold("vidocq create") + " — Scaffold a new Vidocq project");
                CliOutput.println();
                CliOutput.println("Options:");
                opt("--name, -n <name>",         "Project / artifact name (required)");
                opt("--group-id, -g <groupId>",  "Maven groupId            (default: io.example)");
                opt("--package <pkg>",            "Root Java package        (default: <groupId>.<name>)");
                opt("--extension, -x <id>",       "Extension to enable, repeatable");
                opt("--parent-version <version>", "Vidocq runtime parent version   (default: the CLI's runtime version)");
                opt("",                           "a SNAPSHOT adds the Central snapshot repository to the pom");
                CliOutput.println();
                CliOutput.println("Example:");
                CliOutput.println("  " + CliOutput.cyan(
                        "vidocq create --name my-api -g com.acme -x rest -x health"));
            }
            case "build" -> {
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
                CliOutput.println("Options:");
                opt("--offline, -o",  "Run Maven offline (-o)");
                opt("--skip-tests",   "Skip tests (-DskipTests)");
                opt("--dry-run",      "Print the Maven command without running it");
                opt("-- <args...>",   "Pass everything after -- straight to Maven");
                CliOutput.println();
                CliOutput.println("Example:");
                CliOutput.println("  " + CliOutput.cyan("vidocq build jlink --skip-tests"));
            }
            case "clean" -> {
                CliOutput.println(CliOutput.bold("vidocq clean") + " — Remove build output");
                CliOutput.println();
                CliOutput.println("Runs 'mvn clean' in the current project.");
                CliOutput.println();
                CliOutput.println("Options:");
                opt("--offline, -o", "Run Maven offline (-o)");
                opt("--dry-run",     "Print the Maven command without running it");
                opt("-- <args...>",  "Pass everything after -- straight to Maven");
            }
            case "extension" -> {
                CliOutput.println(CliOutput.bold("vidocq extension") + " — Manage extensions");
                CliOutput.println();
                CliOutput.println("Sub-commands:");
                cmd("list [--installed|--available|--all]", "List extensions");
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
            case "completion" -> {
                CliOutput.println(CliOutput.bold("vidocq completion") + " — Shell completion scripts");
                CliOutput.println();
                CliOutput.println("Prints a completion script to stdout for the given shell.");
                CliOutput.println();
                CliOutput.println("Shells: " + String.join(", ", Shell.tokens()));
                CliOutput.println();
                CliOutput.println("Examples:");
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
