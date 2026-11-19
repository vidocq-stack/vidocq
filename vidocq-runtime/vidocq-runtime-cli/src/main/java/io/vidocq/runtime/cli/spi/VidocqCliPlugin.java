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
package io.vidocq.runtime.cli.spi;

/**
 * Service interface that lets third-party modules contribute extra top-level
 * commands to the Vidocq CLI without forking it. Implementations are discovered
 * via {@link java.util.ServiceLoader}; a provider module declares:
 *
 * <pre>{@code
 * provides io.vidocq.runtime.cli.spi.VidocqCliPlugin with com.acme.MyPlugin;
 * }</pre>
 *
 * <p>When the CLI sees a command token it does not recognise, it looks for a
 * plugin whose {@link #command()} matches and delegates to {@link #run(String[])}.
 * Built-in commands always take precedence, so a plugin cannot shadow them.
 */
public interface VidocqCliPlugin {

    /** The top-level command token this plugin handles, e.g. {@code deploy}. */
    String command();

    /** One-line description shown in {@code vidocq help}. */
    String description();

    /**
     * Execute the command.
     *
     * @param args the arguments following the command token (never {@code null})
     * @return the process exit code ({@code 0} for success)
     */
    int run(String[] args);
}
