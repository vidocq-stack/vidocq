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
package io.vidocq.knock.spi;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Static metadata for the Knock implementation.
 *
 * <p>This class remains intentionally minimal at this stage (M0).
 * The SPI will be expanded over the milestones: extension registrar,
 * observability hooks, Vidocq ecosystem integration.</p>
 */
public final class Knock {

    /** Logical name of the implementation. */
    public static final String IMPLEMENTATION_NAME = "knock";

    /**
     * Knock implementation version, filtered by the Maven build into a
     * same-module resource. Not a compile-time constant on purpose: consumers
     * always read the version of the artifact actually on their module path.
     */
    public static final String IMPLEMENTATION_VERSION = loadVersion();

    /** Implemented MicroProfile Health spec version. */
    public static final String SPEC_VERSION = "4.0";

    private Knock() {
        // utility class
    }

    private static String loadVersion() {
        try (InputStream in = Knock.class.getResourceAsStream("version.properties")) {
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
}
