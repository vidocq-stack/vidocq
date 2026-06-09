package io.vidocq.runtime.cli;

import io.vidocq.runtime.cli.scaffold.ProjectScaffolder;
import io.vidocq.runtime.core.VidocqBootstrap;
import io.vidocq.runtime.spi.VidocqExtension;

import java.util.Comparator;
import java.util.ServiceLoader;

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
            case Command.Create c              -> runCreate(c);
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
        System.out.println(CliOutput.bold("Vidocq CLI ") + VidocqCli.VERSION);
        return 0;
    }

    private static int runInfo() {
        CliOutput.println(CliOutput.bold("Vidocq Runtime — System Information"));
        CliOutput.println();
        System.out.printf("  %-24s %s%n", "CLI version:",  VidocqCli.VERSION);
        System.out.printf("  %-24s %s%n", "Java version:", System.getProperty("java.version"));
        System.out.printf("  %-24s %s%n", "JVM:",
                System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
        System.out.printf("  %-24s %s%n", "OS:",
                System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        CliOutput.println();
        CliOutput.println(CliOutput.bold("Extensions on classpath:"));
        var extensions = ServiceLoader.load(VidocqExtension.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .sorted(Comparator.comparingInt(VidocqExtension::priority))
                .toList();
        if (extensions.isEmpty()) {
            CliOutput.println(CliOutput.dim("  (none — add extension JARs to the classpath)"));
        } else {
            extensions.forEach(ext ->
                    System.out.printf("  %s %-40s %s%n",
                            CliOutput.green("✔"),
                            ext.name(),
                            CliOutput.dim("priority=" + ext.priority())));
        }
        return 0;
    }

    private static int runHelp(Command.Help h) {
        CliParser.printHelp(h.topic());
        return 0;
    }

    private static int runStart(Command.Start start) {
        System.setProperty("vidocq.http.port", String.valueOf(start.port()));
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
        CliOutput.info("Starting Vidocq in " + CliOutput.bold("dev mode") + " on port " + dev.port() + "…");
        CliOutput.warning("Live class-reload is planned for roadmap M2 — starting normally for now.");
        System.setProperty("vidocq.http.port", String.valueOf(dev.port()));
        System.setProperty("vidocq.profile", "dev");
        if (dev.debug()) {
            CliOutput.warning("Debug mode active — attach your debugger to port 5005.");
        }
        VidocqBootstrap.create().configure().start().awaitShutdown();
        return 0;
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
        CliOutput.println("  Build:     " + CliOutput.cyan("./mvnw package"));
        CliOutput.println("  Run:       " + CliOutput.cyan("vidocq start"));
        return 0;
    }

    private static int runExtensionList(Command.Extension.Listing listing) {
        CliOutput.println(CliOutput.bold("Installed extensions:"));
        var extensions = ServiceLoader.load(VidocqExtension.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .sorted(Comparator.comparingInt(VidocqExtension::priority))
                .toList();
        if (extensions.isEmpty()) {
            CliOutput.println(CliOutput.dim("  (none found on classpath)"));
        } else {
            extensions.forEach(ext ->
                    System.out.printf("  %s %-44s %s%n",
                            CliOutput.green("✔"),
                            ext.name(),
                            CliOutput.dim("priority=" + ext.priority())));
        }
        if (listing.available()) {
            CliOutput.println();
            CliOutput.warning("Remote extension registry is planned for roadmap M3.");
        }
        return 0;
    }

    private static int runExtensionAdd(Command.Extension.Add add) {
        CliOutput.warning("'extension add' is not yet implemented (pom.xml manipulation — roadmap M3).");
        CliOutput.println(CliOutput.dim("  Requested: " + String.join(", ", add.ids())));
        return 0;
    }

    private static int runExtensionRemove(Command.Extension.Remove remove) {
        CliOutput.warning("'extension remove' is not yet implemented (pom.xml manipulation — roadmap M3).");
        CliOutput.println(CliOutput.dim("  Requested: " + String.join(", ", remove.ids())));
        return 0;
    }
}
