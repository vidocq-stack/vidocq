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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What an extension with a dev service needs besides its own dependency, so that the application gets its database,
 * Keycloak or collector without a step of its own (BUG-20261031-05, -06, -07):
 * <ul>
 *   <li>the provider among {@code vidocq-runtime-maven-plugin}'s dependencies, where {@code vidocq dev} and
 *       {@code vidocq test} discover it;</li>
 *   <li>the provider and {@code vidocq-runtime-devservices-junit} in test scope, which start it under a plain
 *       {@code mvn test}: Surefire never sees the plugin's dependencies. Not for a service no test needs, which
 *       says so with {@code devservice.in-tests = false} (Telemetry's collector);</li>
 *   <li>for the pool, the JDBC driver the PostgreSQL dev service starts on, unless the project declares a driver of
 *       its own, being then on another database.</li>
 * </ul>
 * {@code vidocq extension add}, {@code vidocq create} and {@code vidocq doctor --fix} write them; {@code vidocq doctor}
 * and {@code vidocq:checkpom} say which one is missing.
 */
public final class DevServiceWiring {

    private static final String RUNTIME_GROUP = "io.vidocq.runtime";

    /** The plugin whose dependencies {@code vidocq dev} and {@code vidocq test} discover providers in. */
    public static final ExtensionCoordinate PLUGIN = new ExtensionCoordinate(RUNTIME_GROUP, "vidocq-runtime-maven-plugin");

    /** The JUnit host, which starts the providers of the test class path under {@code mvn test}. */
    public static final ExtensionCoordinate JUNIT_HOST =
            new ExtensionCoordinate(RUNTIME_GROUP, "vidocq-runtime-devservices-junit");

    private static final String TEST = "test";
    private static final String RUNTIME = "runtime";
    private static final String PARENT_VERSION = "${project.parent.version}";

    /** The JDBC drivers a project may declare: one of them other than the dev service's says another database. */
    private static final List<ExtensionCoordinate> JDBC_DRIVERS = List.of(
            new ExtensionCoordinate("org.postgresql", "postgresql"),
            new ExtensionCoordinate("com.h2database", "h2"),
            new ExtensionCoordinate("org.hsqldb", "hsqldb"),
            new ExtensionCoordinate("org.mariadb.jdbc", "mariadb-java-client"),
            new ExtensionCoordinate("com.mysql", "mysql-connector-j"),
            new ExtensionCoordinate("mysql", "mysql-connector-java"),
            new ExtensionCoordinate("com.oracle.database.jdbc", "ojdbc11"),
            new ExtensionCoordinate("com.oracle.database.jdbc", "ojdbc17"),
            new ExtensionCoordinate("com.microsoft.sqlserver", "mssql-jdbc"),
            new ExtensionCoordinate("org.apache.derby", "derby"));

    private static final Pattern BOM_VERSION = Pattern.compile(
            "<artifactId>vidocq-runtime-bom</artifactId>\\s*<version>([^<]+)</version>");

    private DevServiceWiring() {}

    /**
     * What a project declares that the checks read.
     *
     * @param plugin the dependencies of {@code vidocq-runtime-maven-plugin} in the project build
     * @param scopes the scope of each project dependency, {@code compile} when it names none
     */
    public record Declared(List<ExtensionCoordinate> plugin, Map<ExtensionCoordinate, String> scopes) {

        public Declared {
            plugin = List.copyOf(plugin);
            scopes = Map.copyOf(scopes);
        }

        /** What {@code pom} declares itself, without what a parent adds. */
        public static Declared of(String pom) {
            return new Declared(PomDependencies.pluginDependencies(pom, PLUGIN), PomDependencies.scopes(pom));
        }
    }

    /** The JDBC driver of a dev service: its coordinate and the version written with it. */
    private record Driver(ExtensionCoordinate coordinate, String version) {

        static Optional<Driver> of(String id) {
            return KnownExtensions.devServiceDriver(id).map(gav -> {
                String[] parts = gav.split(":");
                if (parts.length != 3) {
                    throw new IllegalStateException(id + ": devservice.driver is not groupId:artifactId:version");
                }
                return new Driver(new ExtensionCoordinate(parts[0], parts[1]), parts[2]);
            });
        }
    }

    /** The dev service provider of extension {@code id}, from its descriptor; empty for most extensions. */
    public static Optional<ExtensionCoordinate> provider(String id) {
        return KnownExtensions.devService(id);
    }

    /**
     * What each of {@code ids} with a dev service lacks in {@code declared}, one line per piece, in the order of
     * {@code ids}: the provider on the plugin, the test-scope pair, the driver.
     */
    public static List<String> missing(Collection<String> ids, Declared declared) {
        List<String> lines = new ArrayList<>();
        for (String id : ids) {
            Optional<ExtensionCoordinate> provider = provider(id);
            if (provider.isEmpty() || onAnotherDatabase(id, declared)) {
                continue;
            }
            String artifact = provider.get().artifactId();
            if (!declared.plugin().contains(provider.get())) {
                lines.add(id + ": " + artifact + " is not in the <dependencies> of " + PLUGIN.artifactId()
                        + ", so vidocq dev and vidocq test start no dev service for it");
            }
            if (KnownExtensions.devServiceInTests(id) && (!TEST.equals(declared.scopes().get(provider.get()))
                    || !TEST.equals(declared.scopes().get(JUNIT_HOST)))) {
                lines.add(id + ": " + JUNIT_HOST.artifactId() + " and " + artifact + " are not both in test scope,"
                        + " so a plain mvn test starts no dev service for it");
            }
            Driver.of(id).filter(driver -> !declared.scopes().containsKey(driver.coordinate()))
                    .ifPresent(driver -> lines.add(id + ": no JDBC driver, and the dev service needs "
                            + driver.coordinate() + " (runtime scope)"));
        }
        return lines;
    }

    /**
     * Writes what {@link #missing} reports for {@code ids}. {@code runtimeVersion} versions the test dependencies,
     * none when {@code null} (the BOM does); the plugin's dependencies always carry one, the parent's version when
     * the parent is {@code vidocq-runtime-parent}, as Maven versions no plugin dependency from a BOM.
     */
    public static PomEditor.Result wire(String pom, Collection<String> ids, String runtimeVersion, Reporter reporter) {
        String edited = pom;
        for (String id : ids) {
            Optional<ExtensionCoordinate> provider = provider(id);
            if (provider.isEmpty() || onAnotherDatabase(id, Declared.of(edited))) {
                continue;
            }
            List<String> wrote = new ArrayList<>();
            PomEditor.Result onPlugin = PomEditor.addPluginDependency(edited, PLUGIN, provider.get(),
                    pluginDependencyVersion(edited, runtimeVersion), pluginVersion(edited, runtimeVersion));
            if (onPlugin.changed()) {
                wrote.add("on " + PLUGIN.artifactId());
            }
            edited = onPlugin.pom();
            if (KnownExtensions.devServiceInTests(id)) {
                PomEditor.Result host = PomEditor.add(edited, JUNIT_HOST, runtimeVersion, TEST);
                PomEditor.Result inTests = PomEditor.add(host.pom(), provider.get(), runtimeVersion, TEST);
                if (host.changed() || inTests.changed()) {
                    wrote.add("in test scope with " + JUNIT_HOST.artifactId());
                }
                edited = inTests.pom();
            }
            Optional<Driver> driver = Driver.of(id);
            if (driver.isPresent()) {
                PomEditor.Result withDriver = PomEditor.add(edited, driver.get().coordinate(), driver.get().version(),
                        RUNTIME);
                if (withDriver.changed()) {
                    reporter.success("Added " + driver.get().coordinate(), "the JDBC driver of the "
                            + provider.get().artifactId() + " dev service");
                }
                edited = withDriver.pom();
            }
            if (!wrote.isEmpty()) {
                reporter.success("Wired " + provider.get().artifactId(), String.join(", ", wrote));
            }
        }
        return new PomEditor.Result(edited, !edited.equals(pom));
    }

    /**
     * Takes away the provider of {@code removedId} unless one of {@code remainingIds} has the same, and the JUnit
     * host when none of them has a dev service that starts under {@code mvn test}. The driver stays: the application may query the database itself.
     */
    public static PomEditor.Result unwire(String pom, String removedId, Collection<String> remainingIds,
                                          Reporter reporter) {
        Optional<ExtensionCoordinate> provider = provider(removedId);
        if (provider.isEmpty()) {
            return new PomEditor.Result(pom, false);
        }
        String edited = pom;
        if (remainingIds.stream().noneMatch(id -> provider(id).equals(provider))) {
            edited = PomEditor.removePluginDependency(edited, PLUGIN, provider.get()).pom();
            edited = PomEditor.remove(edited, provider.get()).pom();
        }
        if (remainingIds.stream().noneMatch(id -> provider(id).isPresent() && KnownExtensions.devServiceInTests(id))) {
            edited = PomEditor.remove(edited, JUNIT_HOST).pom();
        }
        if (!edited.equals(pom)) {
            reporter.success("Unwired " + provider.get().artifactId(), "its extension is gone");
            Driver.of(removedId).filter(d -> PomDependencies.contains(pom, d.coordinate()))
                    .ifPresent(d -> reporter.note("↳ " + d.coordinate() + " stays: remove it if nothing else"
                            + " connects to the database."));
        }
        return new PomEditor.Result(edited, !edited.equals(pom));
    }

    /** Whether the project declares a JDBC driver other than the one the dev service of {@code id} starts on. */
    private static boolean onAnotherDatabase(String id, Declared declared) {
        return Driver.of(id).map(own -> JDBC_DRIVERS.stream()
                        .filter(driver -> !driver.equals(own.coordinate()))
                        .anyMatch(declared.scopes()::containsKey))
                .orElse(false);
    }

    /** The version of a plugin dependency: the parent's under {@code vidocq-runtime-parent}, else the runtime's. */
    private static String pluginDependencyVersion(String pom, String runtimeVersion) {
        if (PomDependencies.runtimeVersion(pom).isPresent()) {
            return PARENT_VERSION;
        }
        if (runtimeVersion != null) {
            return runtimeVersion;
        }
        Matcher bom = BOM_VERSION.matcher(pom);
        return bom.find() ? bom.group(1).strip() : null;
    }

    /** The version of a plugin the pom declares for the first time: none when the runtime parent manages it. */
    private static String pluginVersion(String pom, String runtimeVersion) {
        return PomDependencies.runtimeVersion(pom).isPresent() ? null : pluginDependencyVersion(pom, runtimeVersion);
    }
}
