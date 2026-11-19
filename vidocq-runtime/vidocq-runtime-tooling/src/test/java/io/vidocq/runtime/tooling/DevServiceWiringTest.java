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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an extension with a dev service needs besides itself (BUG-20261031-05, -06, -07): the provider on the plugin
 * for {@code vidocq dev} and {@code vidocq test}, the JUnit host and the provider in test scope for a plain
 * {@code mvn test}, and, for the pool, the JDBC driver the PostgreSQL dev service starts on.
 */
class DevServiceWiringTest {

    private static final String PLUGIN = """
                <plugin>
                  <groupId>io.vidocq.runtime</groupId>
                  <artifactId>vidocq-runtime-maven-plugin</artifactId>
                  <executions>
                    <execution>
                      <goals>
                        <goal>package</goal>
                      </goals>
                    </execution>
                  </executions>
                </plugin>
            """;

    /** A project as {@code vidocq create} writes it, under a parent from 0.4.0: the BOM versions Vidocq artifacts. */
    private static String pom(String dependencies, String plugins) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>io.vidocq.runtime</groupId>
                    <artifactId>vidocq-runtime-parent</artifactId>
                    <version>0.4.0-SNAPSHOT</version>
                  </parent>
                  <artifactId>demo</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>io.vidocq.runtime</groupId>
                      <artifactId>vidocq-runtime-core</artifactId>
                    </dependency>
                %s  </dependencies>
                  <build>
                    <plugins>
                %s    </plugins>
                  </build>
                </project>
                """.formatted(dependencies, plugins);
    }

    private static String dependency(ExtensionCoordinate coordinate) {
        return coordinate.dependencyXml("    ") + "\n";
    }

    private static final String POOL = dependency(KnownExtensions.resolve("mansart-pool"));
    private static final String JWT = dependency(KnownExtensions.resolve("cervantes-jwt"));
    private static final String H2 = """
                <dependency>
                  <groupId>com.h2database</groupId>
                  <artifactId>h2</artifactId>
                  <version>2.3.232</version>
                </dependency>
            """;

    private final RecordingReporter reporter = new RecordingReporter();

    @Test
    void theCatalogNamesTheDevServiceOfThePoolJwtAndTelemetry() {
        assertEquals("vidocq-runtime-devservice-postgres",
                DevServiceWiring.provider("mansart-pool").orElseThrow().artifactId());
        assertEquals("vidocq-runtime-devservice-keycloak",
                DevServiceWiring.provider("cervantes-jwt").orElseThrow().artifactId());
        assertEquals("vidocq-runtime-devservice-otel",
                DevServiceWiring.provider("humboldt-telemetry").orElseThrow().artifactId());
        assertTrue(DevServiceWiring.provider("cassini-rest").isEmpty());
    }

    @Test
    void wiringThePoolWritesTheProviderTheTestHostAndTheDriver() {
        String pom = DevServiceWiring.wire(pom(POOL, PLUGIN), List.of("mansart-pool"), null, reporter).pom();

        DevServiceWiring.Declared declared = DevServiceWiring.Declared.of(pom);
        ExtensionCoordinate provider = DevServiceWiring.provider("mansart-pool").orElseThrow();
        assertTrue(declared.plugin().contains(provider), pom);
        assertTrue(pom.contains("<version>${project.parent.version}</version>"),
                "the BOM does not version a plugin's dependencies: " + pom);
        assertEquals("test", declared.scopes().get(provider), pom);
        assertEquals("test", declared.scopes().get(DevServiceWiring.JUNIT_HOST), pom);
        assertEquals("runtime", declared.scopes().get(new ExtensionCoordinate("org.postgresql", "postgresql")), pom);
        assertEquals(List.of(), DevServiceWiring.missing(List.of("mansart-pool"), declared));
    }

    /** A test needs no collector: Telemetry's dev service goes on the plugin only, never into mvn test. */
    @Test
    void telemetrysDevServiceStaysOutOfTheTests() {
        String pom = DevServiceWiring.wire(pom(dependency(KnownExtensions.resolve("humboldt-telemetry")), PLUGIN),
                List.of("humboldt-telemetry"), null, reporter).pom();

        DevServiceWiring.Declared declared = DevServiceWiring.Declared.of(pom);
        ExtensionCoordinate otel = DevServiceWiring.provider("humboldt-telemetry").orElseThrow();
        assertTrue(declared.plugin().contains(otel), pom);
        assertFalse(declared.scopes().containsKey(otel), pom);
        assertFalse(declared.scopes().containsKey(DevServiceWiring.JUNIT_HOST), pom);
        assertEquals(List.of(), DevServiceWiring.missing(List.of("humboldt-telemetry"), declared));
    }

    @Test
    void wiringTwiceChangesNothing() {
        String once = DevServiceWiring.wire(pom(POOL, PLUGIN), List.of("mansart-pool"), null, reporter).pom();

        PomEditor.Result twice = DevServiceWiring.wire(once, List.of("mansart-pool"), null, reporter);

        assertFalse(twice.changed());
        assertEquals(once, twice.pom());
    }

    @Test
    void aProjectWithoutThePluginGetsItDeclaredForItsDependencies() {
        String pom = DevServiceWiring.wire(pom(JWT, ""), List.of("cervantes-jwt"), null, reporter).pom();

        assertTrue(DevServiceWiring.Declared.of(pom).plugin()
                .contains(DevServiceWiring.provider("cervantes-jwt").orElseThrow()), pom);
        assertFalse(pom.contains("<artifactId>postgresql</artifactId>"), "JWT needs no JDBC driver: " + pom);
    }

    @Test
    void whatIsMissingIsSaidPieceByPiece() {
        List<String> missing = DevServiceWiring.missing(List.of("mansart-pool"),
                DevServiceWiring.Declared.of(pom(POOL, PLUGIN)));

        assertEquals(3, missing.size(), missing.toString());
        assertTrue(missing.get(0).contains("vidocq-runtime-devservice-postgres")
                && missing.get(0).contains("vidocq-runtime-maven-plugin"), missing.toString());
        assertTrue(missing.get(1).contains("vidocq-runtime-devservices-junit") && missing.get(1).contains("mvn test"),
                missing.toString());
        assertTrue(missing.get(2).contains("org.postgresql:postgresql"), missing.toString());
    }

    /** An application on H2 chose its database: the PostgreSQL dev service and driver are nothing it lacks. */
    @Test
    void aPoolOnAnotherDatabaseNeedsNoPostgres() {
        String pom = pom(POOL + H2, PLUGIN);

        assertEquals(List.of(), DevServiceWiring.missing(List.of("mansart-pool"), DevServiceWiring.Declared.of(pom)));
        assertFalse(DevServiceWiring.wire(pom, List.of("mansart-pool"), null, reporter).changed());
    }

    @Test
    void unwiringTakesTheProvidersAwayAndKeepsTheDriver() {
        String wired = DevServiceWiring.wire(pom(POOL + JWT, PLUGIN), List.of("mansart-pool", "cervantes-jwt"), null,
                reporter).pom();

        String withoutPool = DevServiceWiring.unwire(wired, "mansart-pool", List.of("cervantes-jwt"), reporter).pom();
        DevServiceWiring.Declared afterPool = DevServiceWiring.Declared.of(withoutPool);
        ExtensionCoordinate postgres = DevServiceWiring.provider("mansart-pool").orElseThrow();
        assertFalse(afterPool.plugin().contains(postgres), withoutPool);
        assertFalse(afterPool.scopes().containsKey(postgres), withoutPool);
        assertEquals("test", afterPool.scopes().get(DevServiceWiring.JUNIT_HOST), "keycloak still needs it");
        assertTrue(afterPool.scopes().containsKey(new ExtensionCoordinate("org.postgresql", "postgresql")),
                "the application may query the database itself");

        String withoutJwt = DevServiceWiring.unwire(withoutPool, "cervantes-jwt", List.of(), reporter).pom();
        Map<ExtensionCoordinate, String> scopes = DevServiceWiring.Declared.of(withoutJwt).scopes();
        assertFalse(scopes.containsKey(DevServiceWiring.JUNIT_HOST), withoutJwt);
    }
}
