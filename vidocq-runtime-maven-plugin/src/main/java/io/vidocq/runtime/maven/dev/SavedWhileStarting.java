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

import io.vidocq.runtime.maven.CompilerOutput;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.function.Consumer;

/**
 * The saves made while {@code vidocq:dev} started, which nobody watched (BUG-20261117-05).
 *
 * <p>The watcher is registered last: after the build, the dev services and the rest of the start. A file saved in
 * between starts no recompile, and the build may have read a source before its save. The application would start
 * without the save, and stay so until another one. A watched file saved since Maven started is such a save:
 * {@link #compile} compiles them before the application starts.
 */
final class SavedWhileStarting {

    /** What became of the saves. */
    enum Outcome {
        /** Nothing was saved, or nothing could be compiled: the output is the one the build left. */
        NOTHING_COMPILED,
        /** The saves are compiled: the output changed since the build. */
        COMPILED,
        /** The watch ended while the saves did not compile: there is no application to start. */
        WATCH_ENDED
    }

    private final List<Path> watch;
    private final FileTime started;
    private final Consumer<String> info;
    private final Consumer<String> warn;

    /**
     * @param watch   the directories the session watches for the application
     * @param started when Maven started
     * @param info    gets the files that are compiled, by name, three at most
     * @param warn    gets what could not be told, or does not compile
     */
    SavedWhileStarting(List<Path> watch, FileTime started, Consumer<String> info, Consumer<String> warn) {
        this.watch = List.copyOf(watch);
        this.started = started;
        this.info = info;
        this.warn = warn;
    }

    /**
     * To call once the watcher is there, before the application starts: a later save is the watcher's. Runs one
     * recompile when a watched file was saved since Maven started, after telling {@code saved} when the build
     * started: a source the build may have read before its save loses its class. While that recompile fails, waits
     * for the next change and compiles again, as a first build that fails does: there is no application to keep up.
     *
     * @param saved     the saves the recompiles of the session miss
     * @param recompile the recompile of the session
     * @param change    blocks until the sources change; {@code false} once the watch ended
     */
    Outcome compile(SavedWhileCompiling saved, RecompileRunner recompile, CompilerOutput.AwaitChange change)
            throws InterruptedException {
        List<Path> files;
        try {
            files = SourceWatcher.savedSince(watch, started);
        } catch (IOException e) {
            warn.accept("Cannot tell whether a file was saved while vidocq:dev started (" + e
                    + "): if a save does not show, save the file again.");
            return Outcome.NOTHING_COMPILED;
        }
        if (files.isEmpty()) {
            return Outcome.NOTHING_COMPILED;
        }
        info.accept(files.size() == 1
                ? files.getFirst().getFileName()
                        + " was saved while vidocq:dev started: compiling it before the application starts."
                : files.size() + " files were saved while vidocq:dev started (" + SavedWhileCompiling.names(files)
                        + "): compiling them before the application starts.");
        saved.anEarlierCompileStarted("the build", started);
        try {
            int exit = VidocqDevMojo.recompile(recompile, saved);
            while (exit != 0) {
                warn.accept("What was saved while vidocq:dev started does not compile (exit " + exit
                        + "): fix the sources, vidocq dev compiles again on the next change.");
                if (!change.await()) {
                    return Outcome.WATCH_ENDED;
                }
                exit = VidocqDevMojo.recompile(recompile, saved);
            }
            return Outcome.COMPILED;
        } catch (IOException e) {
            warn.accept("Cannot compile what was saved while vidocq:dev started (" + e.getMessage()
                    + "): the application starts without it. Save the file again once it runs.");
            return Outcome.NOTHING_COMPILED;
        }
    }
}
