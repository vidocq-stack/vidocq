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
package io.vidocq.runtime.cli.build;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The kinds of image {@code vidocq build docker} builds: the values of {@code vidocq.docker.kind}. */
public enum DockerKind {
    /** The jlink image in a distroless base: {@code vidocq:jlink} runs first. */
    JLINK(List.of("package", "vidocq:jlink", "vidocq:docker")),
    /** The distribution in a JDK base: no jlink image, so an application with an encrypted jar can build it. */
    JDK(List.of("package", "vidocq:docker"));

    // goals is an unmodifiable List.copyOf: Error Prone only knows Guava's immutable collections as immutable.
    @SuppressWarnings("ImmutableEnumChecker")
    private final List<String> goals;

    DockerKind(List<String> goals) {
        this.goals = List.copyOf(goals);
    }

    /** The Maven phases and goals that build this kind, in order. */
    public List<String> goals() {
        return goals;
    }

    /** The value of {@code vidocq.docker.kind}. */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The kind named {@code value}, case-insensitively, or empty when there is none. */
    public static Optional<DockerKind> of(String value) {
        for (DockerKind kind : values()) {
            if (kind.value().equalsIgnoreCase(value)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
