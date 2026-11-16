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

/**
 * Whether the HTTP sessions of the application can move to the new layer of a swap, or why not (session handover
 * design §1). Only the classes present in both outputs whose bytes differ are looked at: a new class, or one deleted,
 * changes no instance a session holds.
 */
final class SessionVerdict {

    private record Change(ClassFingerprint old, ClassFingerprint now) {

        String simpleName() {
            return now.binaryName().substring(now.binaryName().lastIndexOf('.') + 1);
        }
    }

    private SessionVerdict() {
    }

    /**
     * The first reason the sessions cannot move from {@code base} to {@code next}, in the order of the design: the
     * parameter, a changed serialized form, moved lambdas.
     *
     * @return empty when the sessions move
     */
    static Optional<String> dropReason(ClassesSnapshot base, ClassesSnapshot next, boolean keepSessions) {
        if (!keepSessions) {
            return Optional.of("vidocq.dev.keepSessions is false");
        }
        List<Change> changes = changes(base, next);
        for (Change change : changes) {
            var difference = change.now().instanceShapeDifference(change.old());
            if (difference.isPresent()) {
                return Optional.of(change.simpleName() + " " + difference.get()
                        + ", so a restored instance would not match the new class");
            }
        }
        for (Change change : changes) {
            if (change.now().lambdasMoved(change.old())) {
                return Optional.of(change.simpleName()
                        + "'s lambdas moved, and a restored listener could run another one's code");
            }
        }
        return Optional.empty();
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
