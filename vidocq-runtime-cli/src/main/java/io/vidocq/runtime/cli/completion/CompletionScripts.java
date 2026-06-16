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
 * Pure generators for shell completion scripts. Each produces a self-contained
 * script that completes the first argument against the known command list and
 * otherwise falls back to the shell's default (file) completion.
 */
public final class CompletionScripts {

    private CompletionScripts() {}

    /** Emit the completion script for {@code shell} over {@code commands}. */
    public static String script(Shell shell, List<String> commands) {
        return switch (shell) {
            case BASH -> bash(commands);
            case ZSH -> zsh(commands);
        };
    }

    static String bash(List<String> commands) {
        String joined = String.join(" ", commands);
        return """
                # vidocq bash completion
                # Install: vidocq completion bash > /etc/bash_completion.d/vidocq
                #      or: source <(vidocq completion bash)
                _vidocq() {
                    local cur cmds
                    cur="${COMP_WORDS[COMP_CWORD]}"
                    cmds="%s"
                    if [ "$COMP_CWORD" -eq 1 ]; then
                        COMPREPLY=( $(compgen -W "$cmds" -- "$cur") )
                        return 0
                    fi
                    return 0
                }
                complete -F _vidocq vidocq
                """.formatted(joined);
    }

    static String zsh(List<String> commands) {
        String joined = String.join(" ", commands);
        return """
                #compdef vidocq
                # vidocq zsh completion
                # Install: vidocq completion zsh > "${fpath[1]}/_vidocq"
                #      or: source <(vidocq completion zsh)
                _vidocq() {
                    local -a cmds
                    cmds=(%s)
                    if (( CURRENT == 2 )); then
                        compadd -- $cmds
                        return 0
                    fi
                    return 0
                }
                compdef _vidocq vidocq
                """.formatted(joined);
    }
}
