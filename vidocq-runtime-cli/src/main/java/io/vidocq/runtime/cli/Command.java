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

import io.vidocq.runtime.cli.build.BuildType;
import io.vidocq.runtime.cli.completion.Shell;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Sealed hierarchy of every command the Vidocq CLI understands.
 * CliParser produces one instance; CommandRunner dispatches on it.
 */
public sealed interface Command {

    record Version() implements Command {}

    record Info() implements Command {}

    record Help(String topic) implements Command {}

    /**
     * {@code portExplicit} records whether {@code --port} was actually typed. Only then may the
     * runner override the port the application configured for itself — publishing the default on
     * every run would make {@code vidocq start} silently outrank the project's own
     * {@code vidocq.properties}.
     */
    record Start(int port, boolean portExplicit, Path configFile, boolean debug) implements Command {
        static Start defaults() { return new Start(8080, false, null, false); }
    }

    /** See {@link Start} for the meaning of {@code portExplicit}. */
    record Dev(int port, boolean portExplicit, String profile, boolean debug) implements Command {
        static Dev defaults() { return new Dev(8080, false, "dev", false); }
    }

    record Doctor(boolean verbose) implements Command {
        static Doctor defaults() { return new Doctor(false); }
    }

    record Create(
            String name,
            String groupId,
            String pkg,
            Set<String> extensions,
            String parentVersion
    ) implements Command {}

    record Build(
            BuildType type,
            boolean offline,
            boolean skipTests,
            boolean dryRun,
            List<String> passthrough
    ) implements Command {}

    record Clean(
            boolean offline,
            boolean dryRun,
            List<String> passthrough
    ) implements Command {}

    sealed interface Extension extends Command {
        /** {@code refresh} bypasses the on-disk cache of the installed extensions. */
        record Listing(boolean installed, boolean available, boolean refresh) implements Extension {
            static Listing defaults() { return new Listing(true, false, false); }
        }
        record Add(java.util.List<String> ids) implements Extension {}
        record Remove(java.util.List<String> ids) implements Extension {}
    }

    sealed interface Config extends Command {
        record Get(String key) implements Config {}
        record Set(String key, String value) implements Config {}
        record Listing() implements Config {}
    }

    record Completion(Shell shell) implements Command {}

    /** {@code update [--check]}: install the latest CLI of the running one's channel. */
    record Update(boolean checkOnly) implements Command {}

    /** {@code completion install|uninstall [shell]}; a {@code null} shell is read from {@code $SHELL}. */
    record CompletionSetup(Shell shell, boolean install) implements Command {}

    /** A command token that is not a built-in; dispatched to a CLI plugin. */
    record Plugin(String name, List<String> args) implements Command {}
}
