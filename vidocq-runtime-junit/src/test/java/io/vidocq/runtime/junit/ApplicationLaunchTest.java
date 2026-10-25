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
                "--add-modules", "com.acme.demo",
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
        assertEquals("/a:/b", ApplicationLaunch.modulePath("/a:/b"));

        var e = assertThrows(IllegalStateException.class, () -> ApplicationLaunch.modulePath(null));
        assertTrue(e.getMessage().startsWith("@VidocqTest starts the application from the module path of the tests,"
                + " and they run on the class path"), e.getMessage());
    }
}
