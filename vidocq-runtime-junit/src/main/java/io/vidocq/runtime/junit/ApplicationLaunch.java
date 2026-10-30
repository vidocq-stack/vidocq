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

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * The command that starts the application under test, and where its parts come from. The runtime's own main boots
 * the application module from the module path of the tests, as {@code vidocq:run} does: under Surefire it holds
 * {@code target/classes} and the main dependencies the application module requires, while JUnit and the other test
 * dependencies stay on the class path, out of the application's sight. Surefire also leaves there the runtime
 * dependencies no module requires, a JDBC driver for one: {@code vidocq:test-path} names them in
 * {@value #RUNTIME_PATH_PROPERTY}, and they join the application's module path.
 */
final class ApplicationLaunch {

    /** The runtime's own main, which boots {@code vidocq.app.module}. */
    static final String RUNTIME_MAIN = "io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq";

    /**
     * The runtime dependencies of the application, as {@code vidocq:test-path} hands them to the tests through
     * Surefire: every jar the packaged application would have on its module path.
     */
    static final String RUNTIME_PATH_PROPERTY = "vidocq.test.runtimePath";

    /**
     * Copy of {@code io.vidocq.runtime.devservices.host.DevServicesSession.PROVIDED_PREFIX}: the dev services session
     * marks each key it injects with {@code vidocq.dev.provided.<key>=<provider id>}. A copy, not an import: this
     * module does not depend on the dev services.
     */
    static final String PROVIDED_PREFIX = "vidocq.dev.provided.";

    /** Copy of {@code io.vidocq.runtime.devservices.host.StateFile.PROPERTY}, for the same reason. */
    static final String STATE_PROPERTY = "vidocq.devservices.state";

    private ApplicationLaunch() {}

    /**
     * The dev services of the test run, as {@code key=value}: each key a session injected into {@code system}, with
     * its marker, then the state file. {@code vidocq test}, {@code vidocq dev} and the JUnit listener put them in the
     * test JVM, and the application, in its own JVM, would not see them otherwise (BUG-20261030-20). A key set by
     * hand carries no marker and stays in the test JVM.
     */
    static List<String> devServiceProperties(Properties system) {
        List<String> properties = new ArrayList<>();
        new TreeSet<>(system.stringPropertyNames()).forEach(name -> {
            if (name.startsWith(PROVIDED_PREFIX)) {
                String key = name.substring(PROVIDED_PREFIX.length());
                String value = system.getProperty(key);
                if (value != null) {
                    properties.add(key + "=" + value);
                    properties.add(name + "=" + system.getProperty(name));
                }
            }
        });
        String state = system.getProperty(STATE_PROPERTY);
        if (state != null && !properties.isEmpty()) {
            properties.add(STATE_PROPERTY + "=" + state);
        }
        return properties;
    }

    /** {@code devServices}, then {@code @VidocqTest(properties)}, which win since the last {@code -D} does. */
    static List<String> properties(List<String> devServices, String[] declared) {
        List<String> properties = new ArrayList<>(devServices);
        properties.addAll(List.of(declared));
        return properties;
    }

    /**
     * {@code java} with the {@code default} Chappe listener on {@code 127.0.0.1:port}, then {@code properties}
     * ({@code key=value}, as {@code -D}), then the application module on {@code modulePath}. Every module of the
     * path is resolved, as in {@code vidocq:dev} and the packaged application, so that a driver or a provider no
     * module requires is still found through {@link java.util.ServiceLoader}.
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
                "--add-modules", "ALL-MODULE-PATH",
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

    /**
     * The module path of the tests ({@code jdk.module.path}), which the application runs on, followed by the
     * entries of {@code runtimePath} it does not hold yet.
     */
    static String modulePath(String jdkModulePath, String runtimePath) {
        if (jdkModulePath == null || jdkModulePath.isBlank()) {
            throw new IllegalStateException("@VidocqTest starts the application from the module path of the tests,"
                    + " and they run on the class path: give the application a module-info.java, and leave"
                    + " Surefire's useModulePath on.");
        }
        if (runtimePath == null || runtimePath.isBlank()) {
            return jdkModulePath;
        }
        Set<String> entries = new LinkedHashSet<>(List.of(jdkModulePath.split(File.pathSeparator)));
        for (String entry : runtimePath.split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                entries.add(entry);
            }
        }
        return String.join(File.pathSeparator, entries);
    }
}
