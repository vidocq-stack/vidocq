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
package io.vidocq.runtime.maven.dev;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The keys of {@code vidocq:test} (spec §4.2), read a line at a time: the first character of a line decides —
 * {@code r} runs every test, {@code f} the failed ones, {@code q} quits — and Enter alone runs every test. Reading
 * stops at {@code q} or at the end of the input.
 */
final class TerminalControls implements Runnable {

    enum Command { RUN_ALL, RERUN_FAILED, QUIT }

    private final InputStream in;
    private final Consumer<Command> commands;

    TerminalControls(InputStream in, Consumer<Command> commands) {
        this.in = in;
        this.commands = commands;
    }

    /**
     * Whether a person can type keys: a console that is a terminal. Since JDK 22 {@link System#console()} returns
     * a {@code Console} even when standard input is a pipe (CI, an IDE's run window), so its {@code isTerminal()}
     * decides.
     */
    static boolean attached() {
        Console console = System.console();
        return console != null && console.isTerminal();
    }

    static Optional<Command> parse(String line) {
        String key = line.strip();
        if (key.isEmpty()) {
            return Optional.of(Command.RUN_ALL);
        }
        return switch (Character.toLowerCase(key.charAt(0))) {
            case 'r' -> Optional.of(Command.RUN_ALL);
            case 'f' -> Optional.of(Command.RERUN_FAILED);
            case 'q' -> Optional.of(Command.QUIT);
            default -> Optional.empty();
        };
    }

    @Override
    public void run() {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                Optional<Command> command = parse(line);
                if (command.isEmpty()) {
                    continue;
                }
                commands.accept(command.get());
                if (command.get() == Command.QUIT) {
                    return;
                }
            }
        } catch (IOException closed) {
            // the goal is stopping
        }
    }
}
