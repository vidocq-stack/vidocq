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

import io.vidocq.runtime.maven.dev.TestResults.Trigger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The plugin's side of {@code target/vidocq-dev-tests.request} (spec §3.3): the dev console writes one word there,
 * {@code run-all} or {@code rerun-failed}; the plugin reads it, deletes it, and queues the run. The console never
 * sends a test name: the plugin computes a rerun itself. Anything else is ignored, logged once, and never quoted.
 */
final class TestRequestFile implements AutoCloseable {

    /** The request file's name, next to the results file. */
    static final String FILE_NAME = "vidocq-dev-tests.request";
    /** How often the file is looked for. */
    static final long POLL_MILLIS = 250;

    private final Path file;
    private final Consumer<String> warn;
    private boolean warnedUnknown;
    private volatile Thread poller;

    TestRequestFile(Path file, Consumer<String> warn) {
        this.file = file;
        this.warn = warn;
    }

    /** Reads and deletes a pending request; empty when there is none, or when it is unknown. */
    synchronized Optional<Trigger> take() {
        String word;
        try {
            word = Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (NoSuchFileException none) {
            return Optional.empty();
        } catch (IOException unreadable) {
            return Optional.empty();
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // read again on the next poll, and ignored again if unknown
        }
        Optional<Trigger> trigger = Trigger.ofRequest(word);
        if (trigger.isEmpty() && !warnedUnknown) {
            warnedUnknown = true;
            warn.accept("Tests: ignoring an unknown request in " + file.getFileName()
                    + "; only run-all and rerun-failed are known");
        }
        return trigger;
    }

    /**
     * Deletes a request an earlier session left, then looks for one every {@value #POLL_MILLIS} ms on the daemon
     * thread {@code vidocq-tests-requests}, and hands each to {@code onRequest}.
     */
    static TestRequestFile poll(Path file, Consumer<Trigger> onRequest, Consumer<String> warn) throws IOException {
        Files.deleteIfExists(file);
        TestRequestFile requests = new TestRequestFile(file, warn);
        requests.poller = Thread.ofPlatform().name("vidocq-tests-requests").daemon(true).start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    requests.take().ifPresent(onRequest);
                } catch (RuntimeException failed) {
                    warn.accept("Tests: a request failed: " + failed.getClass().getName());
                }
                try {
                    Thread.sleep(POLL_MILLIS);
                } catch (InterruptedException stopping) {
                    return;
                }
            }
        });
        return requests;
    }

    /** Stops polling; idempotent. */
    @Override
    public void close() {
        Thread running = poller;
        poller = null;
        if (running != null) {
            running.interrupt();
            try {
                running.join(1_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
