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

import io.vidocq.runtime.core.ReloadRequest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The hot swap side of one {@code vidocq:dev} session (design §2): the snapshot of the output the child runs, and
 * the classification of each successful recompile against it. The base moves to a request's snapshot only once the
 * child acknowledged it; after a request that was lost (a timeout, a dead child), the child's state is unknown and
 * the next request is a swap.
 */
final class HotSwapSession {

    static final String UNCONFIRMED = "the previous reload was not confirmed";

    private final List<Path> classRoots;
    private final List<Path> projectSourceRoots;
    private final StartTimeDeclarations dependencyDeclarations;
    private final String redefinitionOff;
    private final boolean keepSessions;
    private final Consumer<String> warn;
    private SupertypeIndex supertypes;
    private ClassesSnapshot base;
    private ClassesSnapshot latest;
    private ClassesSnapshot pending;
    private boolean lost;
    /** The outputs of the requests not acknowledged since {@link #base}: the child may run any of them. */
    private final List<ClassesSnapshot> unconfirmed = new ArrayList<>();

    private HotSwapSession(List<Path> classRoots, List<Path> projectSourceRoots,
            StartTimeDeclarations dependencyDeclarations, List<Path> dependencyJars, String redefinitionOff,
            boolean keepSessions, Consumer<String> warn) {
        this.classRoots = List.copyOf(classRoots);
        this.projectSourceRoots = List.copyOf(projectSourceRoots);
        this.dependencyDeclarations = dependencyDeclarations;
        this.redefinitionOff = redefinitionOff;
        this.keepSessions = keepSessions;
        this.warn = warn;
        this.base = ClassesSnapshot.take(this.classRoots, ClassesSnapshot.EMPTY);
        this.latest = base;
        this.supertypes = SupertypeIndex.of(base, dependencyJars, warn);
    }

    /**
     * Opens the session on the output of the first build.
     *
     * @param redefinitionOff {@code null} when the child redefines classes; otherwise why it cannot, such as
     *                        {@code class redefinition is off: vidocq.dev.hotSwap=false}: a body-only change then
     *                        swaps, saying so
     * @param keepSessions    {@code vidocq.dev.keepSessions}: {@code false} drops the HTTP sessions at every swap
     * @return empty when no jar and no class root declares anything: today's reload, byte for byte
     */
    static Optional<HotSwapSession> open(List<Path> classRoots, List<Path> projectSourceRoots,
            List<Path> dependencyJars, String redefinitionOff, boolean keepSessions, Consumer<String> info,
            Consumer<String> warn) {
        Consumer<String> once = once(warn);
        var fromJars = StartTimeDeclarations.read(dependencyJars, once);
        var fromApplication = StartTimeDeclarations.read(classRoots, once);
        if (fromJars.isEmpty() && fromApplication.isEmpty()) {
            info.accept("Hot swap off: no dependency declares its start-time code (" + StartTimeDeclarations.FILE
                    + "), every change swaps the application layer");
            return Optional.empty();
        }
        int jars = fromJars.sources().size();
        info.accept("Hot swap: start-time declarations from " + jars + (jars == 1 ? " jar" : " jars")
                + (fromApplication.isEmpty() ? "" : " and the application"));
        return Optional.of(new HotSwapSession(classRoots, projectSourceRoots, fromJars, dependencyJars,
                redefinitionOff, keepSessions, once));
    }

    /** After a successful recompile: what the child should do. Empty: nothing to reload. */
    Optional<ReloadRequest> next() {
        ClassesSnapshot snapshot = ClassesSnapshot.take(classRoots, latest);
        latest = snapshot;
        supertypes = supertypes.withApplication(snapshot);
        Optional<ReloadRequest> request;
        if (lost) {
            request = Optional.of(new ReloadRequest.Swap(UNCONFIRMED));
        } else {
            var declarations = dependencyDeclarations.merge(StartTimeDeclarations.read(classRoots, warn));
            request = new HotSwapClassifier(projectSourceRoots, declarations).classify(base, snapshot, supertypes)
                    .map(this::available);
        }
        // Every swap says whether the HTTP sessions move: judged against each output the child may run (design §1).
        request = request.map(found -> found instanceof ReloadRequest.Swap swap
                ? new ReloadRequest.Swap(swap.reason(), dropReason(snapshot))
                : found);
        pending = request.isPresent() ? snapshot : null;
        return request;
    }

    /** The child acknowledged the last request: it runs that output. */
    void acknowledged() {
        if (pending != null) {
            base = pending;
            pending = null;
        }
        unconfirmed.clear();
        lost = false;
    }

    /** The last request was not acknowledged, or the child was started again: the next request is a swap. */
    void lost() {
        if (pending != null) {
            unconfirmed.add(pending);
        }
        pending = null;
        lost = true;
    }

    /**
     * A new child was started on the output on disk: its sessions all come from that output, the one the next swap
     * is checked against. The next request is still a swap, as after a lost one.
     */
    void respawned() {
        base = ClassesSnapshot.take(classRoots, latest);
        latest = base;
        unconfirmed.clear();
        pending = null;
        lost = true;
    }

    private Optional<String> dropReason(ClassesSnapshot next) {
        return Stream.concat(Stream.of(base), unconfirmed.stream())
                .map(output -> SessionVerdict.dropReason(output, next, supertypes, keepSessions))
                .flatMap(Optional::stream).findFirst();
    }

    private ReloadRequest available(ReloadRequest request) {
        if (redefinitionOff == null || !(request instanceof ReloadRequest.Redefine redefine)) {
            return request;
        }
        int classes = redefine.methodsByClass().size();
        return new ReloadRequest.Swap(classes + (classes == 1 ? " class" : " classes")
                + " changed in method bodies only; " + redefinitionOff);
    }

    /** The same warning once per session: the application's own file is read again after each recompile. */
    private static Consumer<String> once(Consumer<String> warn) {
        Set<String> seen = new HashSet<>();
        return message -> {
            if (seen.add(message)) {
                warn.accept(message);
            }
        };
    }
}
