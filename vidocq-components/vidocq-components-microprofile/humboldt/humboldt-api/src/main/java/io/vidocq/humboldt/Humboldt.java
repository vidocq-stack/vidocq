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
package io.vidocq.humboldt;

import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Properties;

/**
 * Public facade of Humboldt, the entry point for the MicroProfile Telemetry 2.2 runtime.
 *
 * <p>M0 stub — the functional surface (Tracer/Meter/Logger provided by the SDKs)
 * arrives in M1.</p>
 */
public final class Humboldt {

    private static final Logger LOG = System.getLogger(Humboldt.class.getName());

    // Filtered by the Maven build into a same-module resource — published artifacts
    // used to report a stale hardcoded snapshot version (BUG-20260712-01).
    private static final String VERSION = loadVersion();

    private static String loadVersion() {
        try (InputStream in = Humboldt.class.getResourceAsStream("version.properties")) {
            if (in == null) {
                return "unknown";
            }
            Properties props = new Properties();
            props.load(in);
            return props.getProperty("version", "unknown");
        } catch (IOException e) {
            return "unknown";
        }
    }

    private Humboldt() {
        // static facade
    }

    /**
     * @return the embedded Humboldt runtime version.
     */
    public static String version() {
        return VERSION;
    }

    /**
     * Initializes the Humboldt runtime with the default configuration resolved from
     * the {@code OTEL_*} and {@code MP_TELEMETRY_*} environment variables.
     *
     * <p>M0 stub: only emits an init log. The effective implementation arrives in M1.</p>
     */
    public static void start() {
        LOG.log(Level.INFO, "Humboldt {0} — init stub (M0)", VERSION);
    }
}
