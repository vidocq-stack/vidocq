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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Pure logic behind {@code vidocq completion install|uninstall}: which shell, which
 * files, and the marked block that sources the completion script from the shell's rc
 * file. The block is replaced in place on every install, so running it again — after
 * an upgrade, from the installer — never duplicates it.
 */
public final class ShellSetup {

    static final String BEGIN = "# >>> vidocq completion >>>";
    static final String END = "# <<< vidocq completion <<<";
    private static final String STAMP = "# vidocq-cli: ";

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

    /** The script line recording which CLI wrote it. */
    static String stampLine(String stamp) {
        return STAMP + stamp;
    }

    /** The stamp of the CLI that wrote {@code script}, empty for a script without one. */
    public static Optional<String> stampOf(String script) {
        return script.lines()
                .filter(line -> line.startsWith(STAMP))
                .map(line -> line.substring(STAMP.length()).strip())
                .findFirst();
    }

    /**
     * Rewrites, with {@code generator}, every installed completion script that another CLI
     * wrote — so the completion follows the CLI however it was replaced. A shell whose script
     * is not installed is left alone; failures are ignored, the next run tries again.
     *
     * @return the shells refreshed
     */
    public static List<Shell> refreshStale(Path home, String stamp, Function<Shell, String> generator) {
        List<Shell> refreshed = new ArrayList<>();
        for (Shell shell : Shell.values()) {
            Path script = scriptFile(shell, home);
            try {
                if (Files.isRegularFile(script)
                        && !stampOf(Files.readString(script)).equals(Optional.of(stamp))) {
                    Files.writeString(script, generator.apply(shell));
                    refreshed.add(shell);
                }
            } catch (IOException | RuntimeException e) {
                // best effort: a stale completion is not worth failing the command
            }
        }
        return refreshed;
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
