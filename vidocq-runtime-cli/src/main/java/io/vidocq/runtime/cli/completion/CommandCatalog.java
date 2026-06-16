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
package io.vidocq.runtime.cli.completion;

import java.util.List;

/**
 * The set of top-level command tokens the CLI understands, used to drive shell
 * completion (and any other catalogue-style feature). Kept here as the single
 * source of truth so completion stays in step with the parser.
 */
public final class CommandCatalog {

    /** Built-in top-level commands offered for completion. */
    public static final List<String> COMMANDS = List.of(
            "version",
            "info",
            "start",
            "dev",
            "doctor",
            "create",
            "build",
            "clean",
            "extension",
            "config",
            "completion",
            "help");

    private CommandCatalog() {}
}
