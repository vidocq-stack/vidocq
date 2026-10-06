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

import java.nio.file.Path;

/**
 * Pure logic behind {@code vidocq completion install|uninstall}: which shell, which
 * files, and the marked block that sources the completion script from the shell's rc
 * file. The block is replaced in place on every install, so running it again — after
 * an upgrade, from the installer — never duplicates it.
 */
public final class ShellSetup {

    static final String BEGIN = "# >>> vidocq completion >>>";
    static final String END = "# <<< vidocq completion <<<";

    private ShellSetup() {}

    /**
     * The shell named by {@code $SHELL}.
     *
     * @throws IllegalArgumentException when unset or not a supported shell
     */
    public static Shell detect(String shellVariable) {
        if (shellVariable == null || shellVariable.isBlank()) {
            throw new IllegalArgumentException("$SHELL is not set — name the shell: "
                    + String.join(", ", Shell.tokens()) + ".");
        }
        String name = Path.of(shellVariable).getFileName().toString();
        return Shell.fromToken(name);
    }

    /** The interactive rc file of {@code shell}. */
    public static Path rcFile(Shell shell, Path home) {
        return home.resolve(switch (shell) {
            case BASH -> ".bashrc";
            case ZSH -> ".zshrc";
        });
    }

    /** Where the completion script is written. */
    public static Path scriptFile(Shell shell, Path home) {
        return home.resolve(".vidocq").resolve("completion").resolve("vidocq." + shell.token());
    }

    /** {@code rc} with the block sourcing {@code script}, replacing a previous block. */
    public static String withBlock(String rc, Path script) {
        String block = BEGIN + "\n"
                + "[ -f \"" + script + "\" ] && . \"" + script + "\"\n"
                + END + "\n";
        int begin = rc.indexOf(BEGIN);
        int end = rc.indexOf(END, Math.max(begin, 0));
        if (begin >= 0 && end >= 0) {
            return rc.substring(0, begin) + block + afterBlock(rc, end);
        }
        String prefix = rc.isEmpty() || rc.endsWith("\n") ? rc : rc + "\n";
        return prefix + block;
    }

    /** {@code rc} without the block, untouched when there is none. */
    public static String withoutBlock(String rc) {
        int begin = rc.indexOf(BEGIN);
        int end = rc.indexOf(END, Math.max(begin, 0));
        if (begin < 0 || end < 0) {
            return rc;
        }
        return rc.substring(0, begin) + afterBlock(rc, end);
    }

    private static String afterBlock(String rc, int end) {
        String rest = rc.substring(end + END.length());
        return rest.startsWith("\n") ? rest.substring(1) : rest;
    }
}
