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
package io.vidocq.runtime.maven.idea;

import io.vidocq.runtime.maven.JdwpAgent;

/**
 * A Vidocq application of the reactor, as one IntelliJ IDEA run configuration describes it.
 *
 * @param coordinates       {@code groupId:artifactId} of its Maven project, for messages
 * @param mainClass         the application main class as the pom declares it (a binary name: a nested
 *                          class uses {@code $}), or {@code null} when the runtime's own main boots the
 *                          application: only the Application kind needs one
 * @param configurationName the run configuration name, which also gives the file name
 * @param moduleName        the IntelliJ module of the application, which holds its main class
 * @param pomPath           its pom, relative to the project directory, with {@code /} separators
 * @param generateGoal      the Maven goal of the before-launch step: {@code vidocq:generate}, or
 *                          {@code vidocq:generate@<execution>} when one execution carries the
 *                          configuration of that goal
 * @param debugHost         the host the {@code (debug)} configuration attaches to; blank or {@code null}
 *                          becomes {@link JdwpAgent#DEFAULT_HOST} (Vidocq/vidocq#143)
 * @param debugPort         the port the {@code (debug)} configuration attaches to; not positive becomes
 *                          {@code 5005}, the default of {@code vidocq:dev}'s own debug agent
 * @param runJvmArgs        the application's {@code vidocq.run.jvmArgs}, its runs of tokens separated by single
 *                          spaces as {@code vidocq:run} splits them; {@code null} becomes empty
 */
record IdeaApplication(String coordinates, String mainClass, String configurationName, String moduleName,
                       String pomPath, String generateGoal, String debugHost, int debugPort, String runJvmArgs) {

    /**
     * What the Application configuration passes to the JVM before {@code vidocq.run.jvmArgs}. IntelliJ runs a
     * modular main class with {@code -m}, which resolves only what the application module requires; a module
     * its dependencies need without a {@code requires} (an SLF4J provider, a service) would be missing, as
     * {@code vidocq:run} avoids with the same option (BUG-20261116-01).
     */
    static final String ADD_MODULES = "--add-modules ALL-MODULE-PATH";

    IdeaApplication {
        debugHost = debugHost == null || debugHost.isBlank() ? JdwpAgent.DEFAULT_HOST : debugHost;
        debugPort = debugPort <= 0 ? 5005 : debugPort;
        runJvmArgs = runJvmArgs == null ? "" : String.join(" ", runJvmArgs.strip().split("\\s+"));
    }

    /** No {@code vidocq.run.jvmArgs}. */
    IdeaApplication(String coordinates, String mainClass, String configurationName, String moduleName,
                    String pomPath, String generateGoal, String debugHost, int debugPort) {
        this(coordinates, mainClass, configurationName, moduleName, pomPath, generateGoal, debugHost, debugPort, null);
    }

    /** The debug host and port default to those of {@code vidocq:dev}'s own debug agent. */
    IdeaApplication(String coordinates, String mainClass, String configurationName, String moduleName,
                    String pomPath, String generateGoal) {
        this(coordinates, mainClass, configurationName, moduleName, pomPath, generateGoal, null, 0);
    }

    /** The VM options of the Application configuration: {@link #ADD_MODULES}, then {@code vidocq.run.jvmArgs}. */
    String vmParameters() {
        return runJvmArgs.isEmpty() ? ADD_MODULES : ADD_MODULES + " " + runJvmArgs;
    }
}
