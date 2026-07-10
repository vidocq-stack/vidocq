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
package io.vidocq.runtime.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * Build-time version information, filtered by Maven into {@code version.properties}.
 *
 * <p>{@link #cli()} is the version of the CLI itself ({@code project.version}), while
 * {@link #runtime()} is the version of the released {@code vidocq-runtime-parent} the CLI
 * inherits from ({@code project.parent.version}) — the version scaffolded projects must
 * reference so their parent resolves from Maven Central.</p>
 */
public final class Version {

    private static final String RESOURCE = "version.properties";

    private static final class Holder {
        static final Properties PROPS = load();

        private static Properties load() {
            Properties props = new Properties();
            try (InputStream in = Version.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException(
                            "Missing " + RESOURCE + " on the module path — broken CLI packaging.");
                }
                props.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + RESOURCE, e);
            }
            return props;
        }
    }

    private Version() {}

    /** Version of the CLI itself. */
    public static String cli() {
        return Holder.PROPS.getProperty("cliVersion", "unknown");
    }

    /** Version of the released runtime parent that scaffolded projects inherit from. */
    public static String runtime() {
        return Holder.PROPS.getProperty("runtimeVersion", "unknown");
    }
}
