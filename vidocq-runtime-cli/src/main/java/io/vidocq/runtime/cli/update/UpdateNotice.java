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
package io.vidocq.runtime.cli.update;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Tells the user, at most once a day, that a newer CLI build exists (Vidocq/vidocq#353). The check runs on a virtual
 * thread while the command runs, makes the decision {@code vidocq update --check} makes, and is printed on the error
 * stream after the command, so a pipe of the output never carries it. A check that has not answered by then, or
 * fails, is dropped silently and made again next time.
 */
public final class UpdateNotice {

    /** Set to anything but blank, it turns the notice off. */
    public static final String OPT_OUT = "VIDOCQ_NO_UPDATE_CHECK";

    /** How long a check holds. */
    static final Duration INTERVAL = Duration.ofDays(1);

    /** A notice that says nothing: no check was due. */
    public static final UpdateNotice NONE = new UpdateNotice(null);

    private final CompletableFuture<UpdatePlan.Decision> decision;

    private UpdateNotice(CompletableFuture<UpdatePlan.Decision> decision) {
        this.decision = decision;
    }

    /**
     * Whether to check now: not turned off, on a terminal, and no check recorded in {@code stamp} within the last
     * {@link #INTERVAL}.
     *
     * @param env reads an environment variable, {@code null} when it is not set
     */
    public static boolean due(UnaryOperator<String> env, boolean terminal, Path stamp, Instant now) {
        String optOut = env.apply(OPT_OUT);
        if (!terminal || optOut != null && !optOut.isBlank()) {
            return false;
        }
        try {
            return Instant.parse(Files.readString(stamp).strip()).plus(INTERVAL).isBefore(now);
        } catch (IOException | DateTimeParseException _) {
            return true;
        }
    }

    /** Starts {@code check} on a virtual thread. */
    public static UpdateNotice start(Supplier<UpdatePlan.Decision> check) {
        CompletableFuture<UpdatePlan.Decision> future = new CompletableFuture<>();
        Thread.ofVirtual().name("vidocq-update-check").start(() -> {
            try {
                future.complete(check.get());
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        });
        return new UpdateNotice(future);
    }

    /**
     * Prints the notice on {@code err} when the check found a newer build within {@code wait}, and records in
     * {@code stamp} a check that answered.
     */
    public void print(PrintStream err, Path stamp, Instant now, Duration wait) {
        if (decision == null) {
            return;
        }
        UpdatePlan.Decision answer;
        try {
            answer = decision.get(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return;
        } catch (ExecutionException | TimeoutException _) {
            return;
        }
        if (answer instanceof UpdatePlan.Unavailable) {
            return;
        }
        message(answer).ifPresent(err::println);
        try {
            Files.createDirectories(stamp.getParent());
            Files.writeString(stamp, now.toString());
        } catch (IOException _) {
            // not recorded: the next command checks again
        }
    }

    /** What to tell the user about {@code decision}: something for a newer build only. */
    static Optional<String> message(UpdatePlan.Decision decision) {
        if (decision instanceof UpdatePlan.Update u) {
            return Optional.of("A newer Vidocq CLI is available: " + u.version()
                    + u.build().map(b -> " (build " + b + ")").orElse("")
                    + ". Run 'vidocq update' to install it (" + OPT_OUT + "=1 turns this notice off).");
        }
        return Optional.empty();
    }
}
