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
package io.vidocq.runtime.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.SequencedSet;

/**
 * What {@code vidocq:dev} asks of the child on a reload: the content of the reload file it writes, and that the
 * child reads. The first line is the {@link System#nanoTime()} of the request, which nothing reads; the stamp the
 * child compares is the file's modification time. The second line is the mode:
 *
 * <pre>
 * &lt;nanoTime&gt;
 * redefine
 * io.vidocq.tools.vaadin.MainView &lt;init&gt; lambda$new$0
 * </pre>
 *
 * one line per class, its binary name then the methods whose code changed; or {@code swap <reason>}, followed by
 * {@code sessions keep} when the plugin vouches that the HTTP sessions can move to the new layer, or
 * {@code sessions drop <reason>} when it found why not. A file without a mode line, as written before this format,
 * asks for a swap; a swap without a {@code sessions} line drops the sessions and says nothing. An older child reads
 * only the modification time, and swaps.
 */
public sealed interface ReloadRequest permits ReloadRequest.Redefine, ReloadRequest.Swap {

    /** The content of the reload file for this request, each line ending in {@code \n}. */
    String format(long nanoTime);

    /** Reads a reload file's content. Never fails: anything it does not understand asks for a swap. */
    static ReloadRequest parse(String content) {
        // -1 keeps a trailing empty line: an empty sessions line drops the sessions silently, as a missing one does
        String[] lines = content.split("\\R", -1);
        if (lines.length < 2) {
            return new Swap("");
        }
        String mode = lines[1].strip();
        if (mode.equals(Swap.MODE) || mode.startsWith(Swap.MODE + " ")) {
            return new Swap(mode.substring(Swap.MODE.length()).strip(),
                    lines.length > 2 ? Swap.sessions(lines[2].strip()) : Swap.SILENT_DROP);
        }
        if (!mode.equals(Redefine.MODE)) {
            return new Swap("");
        }
        var methodsByClass = new LinkedHashMap<String, SequencedSet<String>>();
        for (int i = 2; i < lines.length; i++) {
            String[] words = lines[i].strip().split("\\s+");
            if (!words[0].isEmpty()) {
                methodsByClass.computeIfAbsent(words[0], _ -> new LinkedHashSet<>())
                        .addAll(Arrays.asList(words).subList(1, words.length));
            }
        }
        return methodsByClass.isEmpty() ? new Swap("") : new Redefine(methodsByClass);
    }

    /**
     * Redefine these classes in place.
     *
     * @param methodsByClass each class's binary name, mapped to the names of its methods whose code changed
     */
    record Redefine(SequencedMap<String, SequencedSet<String>> methodsByClass) implements ReloadRequest {

        static final String MODE = "redefine";

        public Redefine {
            if (methodsByClass.isEmpty()) {
                throw new IllegalArgumentException("A redefinition needs at least one class");
            }
            var copy = new LinkedHashMap<String, SequencedSet<String>>();
            methodsByClass.forEach((name, methods) ->
                    copy.put(name, Collections.unmodifiableSequencedSet(new LinkedHashSet<>(methods))));
            methodsByClass = Collections.unmodifiableSequencedMap(copy);
        }

        @Override
        public String format(long nanoTime) {
            var out = new StringBuilder().append(nanoTime).append('\n').append(MODE).append('\n');
            methodsByClass.forEach((name, methods) -> {
                out.append(name);
                methods.forEach(method -> out.append(' ').append(method));
                out.append('\n');
            });
            return out.toString();
        }
    }

    /**
     * Restart on a fresh application layer.
     *
     * @param reason       why, for the log; empty when the request gave none
     * @param dropSessions empty when the HTTP sessions move to the new layer; otherwise why they do not, for the log,
     *                     empty when nothing is to be said
     */
    record Swap(String reason, Optional<String> dropSessions) implements ReloadRequest {

        static final String MODE = "swap";
        private static final String KEEP = "sessions keep";
        private static final String DROP = "sessions drop";
        private static final Optional<String> SILENT_DROP = Optional.of("");

        /** A swap no check vouched for: the sessions do not move, and nothing is said about them. */
        public Swap(String reason) {
            this(reason, SILENT_DROP);
        }

        @Override
        public String format(long nanoTime) {
            String swap = nanoTime + "\n" + (reason.isBlank() ? MODE : MODE + " " + reason.strip()) + "\n";
            if (dropSessions.isEmpty()) {
                return swap + KEEP + "\n";
            }
            String why = dropSessions.get().strip();
            return why.isEmpty() ? swap : swap + DROP + " " + why + "\n";
        }

        private static Optional<String> sessions(String line) {
            if (line.equals(KEEP)) {
                return Optional.empty();
            }
            if (line.startsWith(DROP + " ")) {
                return Optional.of(line.substring(DROP.length() + 1).strip());
            }
            return SILENT_DROP;
        }
    }
}
