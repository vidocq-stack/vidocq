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
 * The shells {@code vidocq completion} can emit scripts for.
 */
public enum Shell {

    BASH("bash"),
    ZSH("zsh");

    private final String token;

    Shell(String token) {
        this.token = token;
    }

    /** The token typed on the CLI (e.g. {@code bash}). */
    public String token() {
        return token;
    }

    /** Tokens accepted on the CLI, for help/error messages. */
    public static List<String> tokens() {
        return List.of(BASH.token, ZSH.token);
    }

    /**
     * Resolve a CLI token to a shell.
     *
     * @throws IllegalArgumentException if the token is unknown
     */
    public static Shell fromToken(String token) {
        for (Shell shell : values()) {
            if (shell.token.equals(token)) {
                return shell;
            }
        }
        throw new IllegalArgumentException("Unsupported shell '" + token
                + "'. Available: " + String.join(", ", tokens()) + ".");
    }
}
