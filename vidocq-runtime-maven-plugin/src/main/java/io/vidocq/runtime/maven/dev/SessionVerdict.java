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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Whether the HTTP sessions of the application can move to the new layer of a swap, or why not (session handover
 * design §1). Only the classes present in both outputs whose bytes differ are looked at: a new class, or one deleted,
 * changes no instance a session holds. A session holds only what Java serialization writes: the shape counts for a
 * serializable class alone, and the numbering for serializable lambdas and serializable anonymous classes alone. A
 * class whose supertypes cannot all be read counts as serializable, the safe side.
 */
final class SessionVerdict {

    private static final String SERIALIZABLE = "java.io.Serializable";
    private static final String RUNS_ANOTHER = " moved, and a restored listener could run another one's code";
    private static final Pattern ANONYMOUS = Pattern.compile("(.+)\\$\\d+");

    private record Change(ClassFingerprint old, ClassFingerprint now) {

        String simpleName() {
            return SessionVerdict.simpleName(now.binaryName());
        }
    }

    private SessionVerdict() {
    }

    /**
     * The first reason the sessions cannot move from {@code base} to {@code next}, in the order of the design: the
     * parameter, a changed serialized form, moved lambdas, moved anonymous classes.
     *
     * @param supertypes the supertypes of the classes of {@code next}
     * @return empty when the sessions move
     */
    static Optional<String> dropReason(ClassesSnapshot base, ClassesSnapshot next, SupertypeIndex supertypes,
            boolean keepSessions) {
        if (!keepSessions) {
            return Optional.of("vidocq.dev.keepSessions is false");
        }
        List<Change> changes = changes(base, next);
        for (Change change : changes) {
            if (!serializable(change.now().binaryName(), supertypes)) {
                continue;
            }
            var difference = change.now().instanceShapeDifference(change.old());
            if (difference.isPresent()) {
                return Optional.of(change.simpleName() + " " + difference.get()
                        + ", so a restored instance would not match the new class");
            }
        }
        for (Change change : changes) {
            if (change.now().lambdasMoved(change.old())) {
                return Optional.of(change.simpleName() + "'s lambdas" + RUNS_ANOTHER);
            }
        }
        // javac numbers the anonymous classes of a class in source order: one inserted before the others gives an old
        // name to other code. They moved when the set of them changed while one kept changed.
        for (Change change : changes) {
            var anonymous = ANONYMOUS.matcher(change.now().binaryName());
            if (anonymous.matches() && serializable(change.now().binaryName(), supertypes)
                    && !anonymous(base, anonymous.group(1)).equals(anonymous(next, anonymous.group(1)))) {
                return Optional.of(simpleName(anonymous.group(1)) + "'s anonymous classes" + RUNS_ANOTHER);
            }
        }
        return Optional.empty();
    }

    /** Whether an instance of the class can be in a session: it is serializable, or a supertype could not be read. */
    private static boolean serializable(String binaryName, SupertypeIndex supertypes) {
        return supertypes.isA(binaryName, SERIALIZABLE) || supertypes.unresolved(binaryName);
    }

    /** The anonymous classes of {@code outer}: {@code Outer$1}, {@code Outer$2}, ... */
    private static Set<String> anonymous(ClassesSnapshot snapshot, String outer) {
        return snapshot.byRoot().values().stream().flatMap(files -> files.values().stream())
                .flatMap(entry -> entry.fingerprint().stream()).map(ClassFingerprint::binaryName)
                .filter(name -> {
                    var anonymous = ANONYMOUS.matcher(name);
                    return anonymous.matches() && anonymous.group(1).equals(outer);
                })
                .collect(Collectors.toSet());
    }

    private static String simpleName(String binaryName) {
        return binaryName.substring(binaryName.lastIndexOf('.') + 1);
    }

    private static List<Change> changes(ClassesSnapshot base, ClassesSnapshot next) {
        List<Change> changes = new ArrayList<>();
        next.byRoot().forEach((root, files) -> {
            var before = base.byRoot().get(root);
            if (before == null) {
                return;
            }
            files.forEach((path, entry) -> {
                var old = before.get(path);
                if (old != null && !old.sha256().equals(entry.sha256()) && old.fingerprint().isPresent()
                        && entry.fingerprint().isPresent()) {
                    changes.add(new Change(old.fingerprint().get(), entry.fingerprint().get()));
                }
            });
        });
        return changes;
    }
}
