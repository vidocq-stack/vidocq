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
package acme.app;

import java.io.PrintWriter;
import java.util.spi.ToolProvider;

/**
 * A service the application provides: {@link Probe} counts the providers of this type that the context loader
 * finds. One copy of the application module finds one; a launch that leaves the module in the boot layer and loads
 * it again in the application layer finds two (BUG-20261114-10).
 *
 * <p>Only a {@code public static provider()} method creates it, no public constructor: a form valid in a
 * {@code provides} and not in a {@code META-INF/services} file. Whatever a launch does with the descriptor of the
 * module, {@link Probe} must still create it through that method (BUG-20261114-11).
 */
public final class AppTool implements ToolProvider {

    /** The name {@link Probe} reports. */
    static final String TYPE = AppTool.class.getName();

    private AppTool() {
    }

    /** The provider method {@link java.util.ServiceLoader} calls. */
    public static AppTool provider() {
        return new AppTool();
    }

    @Override
    public String name() {
        return "acme-app-tool";
    }

    @Override
    public int run(PrintWriter out, PrintWriter err, String... args) {
        return 0;
    }
}
