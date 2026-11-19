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

import io.vidocq.runtime.cli.update.UpdateNotice;

import java.time.Duration;
import java.time.Instant;

/**
 * Entry point for the Vidocq command-line interface.
 *
 * <pre>{@code
 * java -m io.vidocq.runtime.cli/io.vidocq.runtime.cli.VidocqCli <command> [options]
 * }</pre>
 */
public final class VidocqCli {

    /** How long the end of a command waits for an update check still running (#353). */
    private static final Duration NOTICE_WAIT = Duration.ofSeconds(1);

    private VidocqCli() {}

    public static void main(String[] args) {
        CommandRunner.refreshStaleCompletion();
        if (args.length == 0) {
            CliParser.printHelp(null);
            System.exit(0);
        }
        try {
            Command cmd = CliParser.parse(args);
            UpdateNotice notice = CommandRunner.updateNotice(cmd);
            int exit = CommandRunner.run(cmd);
            notice.print(System.err, CommandRunner.updateStamp(), Instant.now(), NOTICE_WAIT);
            System.exit(exit);
        } catch (CliException e) {
            CliOutput.error(e.getMessage());
            System.exit(1);
        }
    }
}
