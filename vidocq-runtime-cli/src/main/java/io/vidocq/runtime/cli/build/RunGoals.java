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
package io.vidocq.runtime.cli.build;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The Maven goals {@code vidocq dev} and {@code vidocq start} delegate to. Both run the
 * project the way its build does — compiled sources, its dependencies and extensions on the
 * module path — which only the Vidocq Maven plugin can assemble; the CLI's own JVM holds none
 * of them.
 *
 * <p>Pure: the options become {@code -D} arguments of the plugin's goals.</p>
 */
public final class RunGoals {

    /**
     * The canonical key of the {@code default} Chappe listener, not the {@code vidocq.http.port}
     * alias: the alias is read only when this key is absent, so an explicit {@code --port}
     * outranks whatever the project configured.
     */
    private static final String PORT_KEY = "vidocq.chappe.listener.default.port";

    private RunGoals() {}

    /**
     * {@code vidocq:dev} after {@code process-classes}: the goal starts from
     * {@code target/classes} and only recompiles on a change.
     */
    public static List<String> dev(String profile, boolean portExplicit, int port, boolean debug) {
        List<String> args = new ArrayList<>(List.of("process-classes", "vidocq:dev"));
        args.add("-Dvidocq.profile=" + profile);
        if (portExplicit) {
            args.add("-Dvidocq.dev.systemProperties=" + PORT_KEY + "=" + port);
        }
        if (debug) {
            args.add("-Dvidocq.dev.debug=true");
        }
        return List.copyOf(args);
    }

    /** {@code vidocq:run}, which forks the lifecycle up to {@code process-classes} itself. */
    public static List<String> start(boolean portExplicit, int port, Path configFile, boolean debug) {
        List<String> args = new ArrayList<>(List.of("vidocq:run"));
        List<String> properties = new ArrayList<>();
        if (portExplicit) {
            properties.add(PORT_KEY + "=" + port);
        }
        if (configFile != null) {
            properties.add("vidocq.config.file=" + configFile);
        }
        if (!properties.isEmpty()) {
            args.add("-Dvidocq.run.systemProperties=" + String.join(",", properties));
        }
        if (debug) {
            args.add("-Dvidocq.run.debug=true");
        }
        return List.copyOf(args);
    }
}
