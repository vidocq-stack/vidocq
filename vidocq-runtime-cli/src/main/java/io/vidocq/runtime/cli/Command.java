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

    /** {@code vidocq test}: the tests on every change, without the application ({@code mvn vidocq:test}). */
    record ContinuousTest() implements Command {}

    /**
     * {@code vidocq idea}: the IntelliJ run configurations ({@code mvn vidocq:idea}).
     *
     * @param check {@code null} to write them, {@code "true"} or {@code "strict"} to only check them
     * @param kind  {@code null} for the goal's default, else {@code maven} or {@code application}
     */
    record Idea(String check, String kind) implements Command {}

    /**
     * {@code vidocq vscode}: the {@code .vscode/} settings, tasks and debug configuration ({@code mvn vidocq:vscode}).
     *
     * @param check only check the files, write nothing
     */
    record Vscode(boolean check) implements Command {}

    /**
     * {@code vidocq eclipse}: the {@code .eclipse/} run and debug configurations ({@code mvn vidocq:eclipse}).
     *
     * @param check only check the files, write nothing
     */
    record Eclipse(boolean check) implements Command {}

    /**
     * {@code vidocq doctor}: the environment and project checks.
     *
     * @param verbose also print the hints of the checks that pass
     * @param fix     first repair what the tooling knows how to (Vidocq/vidocq#339)
     */
    record Doctor(boolean verbose, boolean fix) implements Command {
        static Doctor defaults() { return new Doctor(false, false); }
    }

    /** {@code vidocq create}: the project {@link io.vidocq.runtime.tooling.ProjectScaffolder} writes. */
    record Create(io.vidocq.runtime.tooling.ProjectSpec spec) implements Command {}

    /**
     * {@code vidocq create} without {@code --name}: on a console, {@link CreatePrompts} asks for the name, and for
     * the group id and the extensions the command line left out (Vidocq/vidocq#339).
     */
    record CreateInteractively(String groupId, String pkg, java.util.Set<String> extensions, String parentVersion,
                               boolean main) implements Command {
        public CreateInteractively {
            extensions = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(extensions));
        }
    }

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
