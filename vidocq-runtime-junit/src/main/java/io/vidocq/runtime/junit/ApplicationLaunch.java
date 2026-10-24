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

import java.util.ArrayList;
import java.util.List;

/**
 * The command that starts the application under test, and where its parts come from. The runtime's own main boots
 * the application module from the module path of the tests, as {@code vidocq:run} does: under Surefire it holds
 * {@code target/classes} and the main dependencies, while JUnit and the other test dependencies stay on the class
 * path, out of the application's sight.
 */
final class ApplicationLaunch {

    /** The runtime's own main, which boots {@code vidocq.app.module}. */
    static final String RUNTIME_MAIN = "io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq";

    private ApplicationLaunch() {}

    /**
     * {@code java} with the {@code default} Chappe listener on {@code 127.0.0.1:port}, then {@code properties}
     * ({@code key=value}, as {@code -D}), then the application module on {@code modulePath}.
     */
    static List<String> command(String java, String modulePath, String module, int port, List<String> properties) {
        List<String> command = new ArrayList<>(List.of(java,
                "-Dvidocq.chappe.listener.default.host=127.0.0.1",
                "-Dvidocq.chappe.listener.default.port=" + port));
        for (String property : properties) {
            if (property.indexOf('=') <= 0) {
                throw new IllegalArgumentException(
                        "@VidocqTest(properties) takes key=value entries, not '" + property + "'");
            }
            command.add("-D" + property);
        }
        command.addAll(List.of("-Dvidocq.app.module=" + module,
                "-p", modulePath,
                "--add-modules", module,
                "-m", RUNTIME_MAIN));
        return command;
    }

    /**
     * The module to start: the one {@code @VidocqTest(module)} names, else the module of the test class, which
     * Surefire and the IDEs patch into the application module, else {@code -Dvidocq.mainModule}.
     */
    static String module(String declared, String testClassModule, String mainModuleProperty) {
        if (declared != null && !declared.isBlank()) {
            return declared;
        }
        if (testClassModule != null) {
            return testClassModule;
        }
        if (mainModuleProperty != null && !mainModuleProperty.isBlank()) {
            return mainModuleProperty;
        }
        throw new IllegalStateException("@VidocqTest cannot tell which module to start: the test class is not in a"
                + " named module. Name it with @VidocqTest(module = \"…\") or -Dvidocq.mainModule.");
    }

    /** The module path of the tests ({@code jdk.module.path}), which the application runs on. */
    static String modulePath(String jdkModulePath) {
        if (jdkModulePath == null || jdkModulePath.isBlank()) {
            throw new IllegalStateException("@VidocqTest starts the application from the module path of the tests,"
                    + " and they run on the class path: give the application a module-info.java, and leave"
                    + " Surefire's useModulePath on.");
        }
        return jdkModulePath;
    }
}
