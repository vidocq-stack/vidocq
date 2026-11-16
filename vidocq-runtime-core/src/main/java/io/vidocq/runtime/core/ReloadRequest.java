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
 * one line per class, its binary name then the methods whose code changed; or {@code swap <reason>}. A file without
 * a mode line, as written before this format, asks for a swap. An older child reads only the modification time, and
 * swaps.
 */
public sealed interface ReloadRequest permits ReloadRequest.Redefine, ReloadRequest.Swap {

    /** The content of the reload file for this request, each line ending in {@code \n}. */
    String format(long nanoTime);

    /** Reads a reload file's content. Never fails: anything it does not understand asks for a swap. */
    static ReloadRequest parse(String content) {
        String[] lines = content.split("\\R");
        if (lines.length < 2) {
            return new Swap("");
        }
        String mode = lines[1].strip();
        if (mode.equals(Swap.MODE)) {
            return new Swap("");
        }
        if (mode.startsWith(Swap.MODE + " ")) {
            return new Swap(mode.substring(Swap.MODE.length() + 1).strip());
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
     * @param reason why, for the log; empty when the request gave none
     */
    record Swap(String reason) implements ReloadRequest {

        static final String MODE = "swap";

        @Override
        public String format(long nanoTime) {
            return nanoTime + "\n" + (reason.isBlank() ? MODE : MODE + " " + reason.strip()) + "\n";
        }
    }
}
