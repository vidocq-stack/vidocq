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
package io.vidocq.runtime.tooling;


import java.util.List;

/**
 * Pure diagnostics engine for {@code vidocq doctor}.
 *
 * <p>Given a {@link DoctorContext} of already-gathered facts, it produces an
 * ordered list of {@link Diagnostic}s. It performs no I/O, reads no system
 * state, and has no side effects, so it can be exhaustively unit-tested by
 * feeding synthetic contexts.
 */
public final class Diagnostics {

    /** Minimum Java feature version the Vidocq runtime targets. */
    public static final int MINIMUM_JAVA_VERSION = 25;

    private static final String MAVEN_WRAPPER = "Maven wrapper";
    private static final String JAVA_HOME = "JAVA_HOME";
    private static final String VIDOCQ_PROJECT = "Vidocq project";
    private static final String CONFIG = "Config";
    private static final String EXTENSIONS = "Extensions";

    private Diagnostics() {}

    /** Runs every check against {@code ctx}, in display order. */
    public static List<Diagnostic> run(DoctorContext ctx) {
        return List.of(
                checkJava(ctx),
                checkJavaHome(ctx),
                checkMavenWrapper(ctx),
                checkProject(ctx),
                checkConfig(ctx),
                checkExtensions(ctx));
    }

    /**
     * Aggregate exit code: {@code 1} if any check failed, otherwise {@code 0}.
     * Warnings do not fail the command.
     */
    public static int exitCode(List<Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(d -> d.status() == Diagnostic.Status.FAIL) ? 1 : 0;
    }

    /** Per-status tally of a diagnostics run. */
    public record Summary(int ok, int warn, int fail) {
        public int total() {
            return ok + warn + fail;
        }
    }

    /** Counts diagnostics by status. */
    public static Summary summarize(List<Diagnostic> diagnostics) {
        int ok = 0, warn = 0, fail = 0;
        for (Diagnostic d : diagnostics) {
            switch (d.status()) {
                case OK   -> ok++;
                case WARN -> warn++;
                case FAIL -> fail++;
            }
        }
        return new Summary(ok, warn, fail);
    }

    // -------------------------------------------------------------------------
    // Individual checks
    // -------------------------------------------------------------------------

    private static Diagnostic checkJava(DoctorContext ctx) {
        String detail = "Java " + ctx.javaVersionString();
        if (ctx.javaFeatureVersion() >= ctx.minimumJavaVersion()) {
            return Diagnostic.ok("Java version", detail);
        }
        return Diagnostic.fail(
                "Java version",
                detail + " (Vidocq requires Java " + ctx.minimumJavaVersion() + "+)",
                "Install a JDK " + ctx.minimumJavaVersion()
                        + "+ (e.g. 'sdk install java " + ctx.minimumJavaVersion() + "-tem').");
    }

    private static Diagnostic checkJavaHome(DoctorContext ctx) {
        if (!ctx.javaHomeSet()) {
            return Diagnostic.warn(
                    JAVA_HOME,
                    "not set",
                    "Set JAVA_HOME to your JDK so builds (mvnw, jlink) pick the right toolchain.");
        }
        if (!ctx.javaHomeIsDirectory()) {
            return Diagnostic.warn(
                    JAVA_HOME,
                    ctx.javaHome() + " (not a directory)",
                    "Point JAVA_HOME at an existing JDK installation directory.");
        }
        return Diagnostic.ok(JAVA_HOME, ctx.javaHome());
    }

    private static Diagnostic checkMavenWrapper(DoctorContext ctx) {
        if (ctx.mavenWrapperPresent()) {
            return Diagnostic.ok(MAVEN_WRAPPER, "mvnw found");
        }
        if (ctx.pomPresent()) {
            // A project to write it into: the one vidocq create writes, pinned to the Maven Vidocq is built with.
            return Diagnostic.warn(
                    MAVEN_WRAPPER,
                    "the project has no mvnw",
                    "Without it, builds run on whatever Maven the machine has.").asFixable();
        }
        return Diagnostic.warn(
                MAVEN_WRAPPER,
                "no mvnw on this path",
                "Run from a project that ships ./mvnw, or install Maven, to build and package.");
    }

    private static Diagnostic checkProject(DoctorContext ctx) {
        if (!ctx.pomPresent()) {
            return Diagnostic.warn(
                    VIDOCQ_PROJECT,
                    "no pom.xml in the current directory",
                    "Run 'vidocq create --name <app>' to scaffold a project, or cd into one.");
        }
        if (!ctx.vidocqProject()) {
            return Diagnostic.warn(
                    VIDOCQ_PROJECT,
                    "pom.xml present but no Vidocq runtime dependency detected",
                    "Add a 'io.vidocq.runtime' dependency, or check you are in the right module.");
        }
        return Diagnostic.ok(VIDOCQ_PROJECT, "pom.xml references the Vidocq runtime");
    }

    private static Diagnostic checkConfig(DoctorContext ctx) {
        if (!ctx.configPresent()) {
            return Diagnostic.ok(CONFIG, "no vidocq.properties (built-in defaults apply)");
        }
        List<String> unknown = ConfigKeys.unknownKeys(ctx.configKeys());
        int total = ctx.configKeys().size();
        if (unknown.isEmpty()) {
            return Diagnostic.ok(
                    CONFIG,
                    "vidocq.properties — " + total + " key" + (total == 1 ? "" : "s")
                            + ", all recognized");
        }
        return Diagnostic.warn(
                CONFIG,
                "vidocq.properties — " + unknown.size() + " unrecognized key"
                        + (unknown.size() == 1 ? "" : "s") + ": " + String.join(", ", unknown),
                "Check for typos; Vidocq keys live under known namespaces "
                        + "(vidocq.http, vidocq.dev, vidocq.pool, …).");
    }

    private static Diagnostic checkExtensions(DoctorContext ctx) {
        if (ctx.extensionsError() != null) {
            return Diagnostic.warn(
                    EXTENSIONS,
                    "could not resolve the project's dependencies — " + ctx.extensionsError(),
                    "Run 'mvn dependency:tree' in the project to see what Maven cannot resolve.");
        }
        int count = ctx.extensionCount();
        if (count > 0) {
            return Diagnostic.ok(
                    EXTENSIONS,
                    count + " extension" + (count == 1 ? "" : "s") + " in the runtime dependencies");
        }
        // Not a warning: an application of CDI beans and a main class needs none, and vidocq create writes one
        // (Vidocq/vidocq#339). The hint shows with --verbose.
        return new Diagnostic(
                EXTENSIONS,
                Diagnostic.Status.OK,
                "no extension: the application runs on CDI alone",
                "Add one to enable features (e.g. 'vidocq extension add cassini-rest').");
    }
}
