package io.vidocq.runtime.cli;

import io.vidocq.runtime.cli.doctor.Diagnostic;
import io.vidocq.runtime.cli.doctor.Diagnostics;
import io.vidocq.runtime.cli.doctor.DoctorContext;
import io.vidocq.runtime.cli.scaffold.ProjectScaffolder;
import io.vidocq.runtime.core.VidocqBootstrap;
import io.vidocq.runtime.spi.VidocqExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
            case Command.Doctor doc            -> runDoctor(doc);
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
        return new DoctorContext(
                Runtime.version().feature(),
                System.getProperty("java.version"),
                Diagnostics.MINIMUM_JAVA_VERSION,
                javaHome,
                javaHomeDir,
                hasMavenWrapper(cwd),
                pomPresent,
                pomPresent && pomReferencesVidocq(pom),
                extensionCount());
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

    private static int extensionCount() {
        return (int) ServiceLoader.load(VidocqExtension.class).stream().count();
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
