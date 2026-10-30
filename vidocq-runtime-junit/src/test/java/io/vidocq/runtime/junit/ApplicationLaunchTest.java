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
package io.vidocq.runtime.junit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What {@code @VidocqTest} runs, worked out before any process starts (Vidocq/vidocq#350). */
class ApplicationLaunchTest {

    @Test
    void theRuntimeMainBootsTheApplicationModuleOnALoopbackPort() {
        List<String> command = ApplicationLaunch.command("/jdk/bin/java", "/app/classes:/m2/core.jar",
                "com.acme.demo", 41234, List.of("greeting=hi"));

        assertEquals(List.of("/jdk/bin/java",
                "-Dvidocq.chappe.listener.default.host=127.0.0.1",
                "-Dvidocq.chappe.listener.default.port=41234",
                "-Dgreeting=hi",
                "-Dvidocq.app.module=com.acme.demo",
                "-p", "/app/classes:/m2/core.jar",
                "--add-modules", "ALL-MODULE-PATH",
                "-m", "io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq"), command);
    }

    @Test
    void aPropertyWithoutValueIsRefused() {
        List<String> properties = List.of("greeting");

        var e = assertThrows(IllegalArgumentException.class,
                () -> ApplicationLaunch.command("java", "/p", "m", 1, properties));

        assertEquals("@VidocqTest(properties) takes key=value entries, not 'greeting'", e.getMessage());
    }

    @Test
    void theModuleNamedOnTheAnnotationWins() {
        assertEquals("com.acme.other", ApplicationLaunch.module("com.acme.other", "com.acme.demo", "com.acme.prop"));
    }

    /** Surefire and the IDEs patch the tests into the application module: the test class names it. */
    @Test
    void elseTheModuleOfTheTestClass() {
        assertEquals("com.acme.demo", ApplicationLaunch.module("", "com.acme.demo", "com.acme.prop"));
    }

    @Test
    void elseTheMainModuleProperty() {
        assertEquals("com.acme.prop", ApplicationLaunch.module("", null, "com.acme.prop"));
    }

    @Test
    void withoutAnyItSaysWhereToNameIt() {
        var e = assertThrows(IllegalStateException.class, () -> ApplicationLaunch.module("", null, null));

        assertEquals("@VidocqTest cannot tell which module to start: the test class is not in a named module."
                + " Name it with @VidocqTest(module = \"…\") or -Dvidocq.mainModule.", e.getMessage());
    }

    @Test
    void theApplicationRunsOnTheModulePathOfTheTests() {
        assertEquals("/a:/b", ApplicationLaunch.modulePath("/a:/b", null));
        assertEquals("/a:/b", ApplicationLaunch.modulePath("/a:/b", " "));

        var e = assertThrows(IllegalStateException.class, () -> ApplicationLaunch.modulePath(null, "/c.jar"));
        assertTrue(e.getMessage().startsWith("@VidocqTest starts the application from the module path of the tests,"
                + " and they run on the class path"), e.getMessage());
    }

    /**
     * A runtime dependency no module requires, a JDBC driver for one, is on the class path of the tests, where
     * Surefire leaves it: the application gets it on its module path, as {@code vidocq:dev} and the packaged
     * application do (BUG-20261030-06).
     */
    @Test
    void theRuntimeDependenciesTheTestsLeaveOnTheClassPathJoinTheModulePath() {
        String sep = java.io.File.pathSeparator;

        assertEquals(String.join(sep, "/app/classes", "/m2/core.jar", "/m2/postgresql.jar"),
                ApplicationLaunch.modulePath(String.join(sep, "/app/classes", "/m2/core.jar"),
                        String.join(sep, "/m2/core.jar", "/m2/postgresql.jar")));
    }

    /**
     * The dev services the test run started, through {@code vidocq test}, {@code vidocq dev} or the JUnit listener,
     * reach the application: each key the session injected, which its {@code vidocq.dev.provided.<key>} marker names,
     * the marker itself and the state file, and nothing else of the test JVM (BUG-20261030-20).
     */
    @Test
    void theApplicationGetsTheKeysTheDevServicesInjected() {
        Properties system = new Properties();
        system.setProperty("vidocq.pool.url", "jdbc:postgresql://localhost:33003/app");
        system.setProperty("vidocq.dev.provided.vidocq.pool.url", "postgres");
        system.setProperty("mp.jwt.verify.issuer", "http://localhost:33004/realms/app");
        system.setProperty("vidocq.dev.provided.mp.jwt.verify.issuer", "keycloak");
        system.setProperty("vidocq.devservices.state", "/app/target/vidocq-dev-services.json");
        system.setProperty("vidocq.pool.username", "set-by-hand");
        system.setProperty("user.dir", "/app");

        assertEquals(List.of(
                "mp.jwt.verify.issuer=http://localhost:33004/realms/app",
                "vidocq.dev.provided.mp.jwt.verify.issuer=keycloak",
                "vidocq.pool.url=jdbc:postgresql://localhost:33003/app",
                "vidocq.dev.provided.vidocq.pool.url=postgres",
                "vidocq.devservices.state=/app/target/vidocq-dev-services.json"),
                ApplicationLaunch.devServiceProperties(system));
    }

    @Test
    void withoutDevServicesTheApplicationGetsNoneOfTheTestJvmProperties() {
        Properties system = new Properties();
        system.setProperty("vidocq.pool.url", "jdbc:h2:mem:app");

        assertEquals(List.of(), ApplicationLaunch.devServiceProperties(system));
    }

    /** {@code @VidocqTest(properties)} comes after the dev services' keys: the last {@code -D} wins. */
    @Test
    void thePropertiesOfTheAnnotationOverrideTheDevServices() {
        assertEquals(List.of("vidocq.pool.url=jdbc:postgresql://dev", "vidocq.pool.url=jdbc:h2:mem:t"),
                ApplicationLaunch.properties(List.of("vidocq.pool.url=jdbc:postgresql://dev"),
                        new String[] {"vidocq.pool.url=jdbc:h2:mem:t"}));
    }
}
