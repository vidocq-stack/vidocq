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

/**
 * The packaging flavours {@code vidocq build} can produce, each mapped to the
 * real {@code vidocq-runtime-maven-plugin} goals (prefix {@code vidocq}).
 *
 * <p>Every flavour runs the {@code package} lifecycle phase first so the
 * application jar and dependencies are assembled, then layers the relevant
 * goal on top. {@link #PACKAGE} is the plain distribution build.
 */
public enum BuildType {

    /** {@code mvn package} — standalone distribution ZIP. */
    PACKAGE("package", "distribution ZIP", List.of("package")),

    /** {@code mvn package vidocq:jlink} — self-contained jlink runtime image. */
    JLINK("jlink", "jlink runtime image", List.of("package", "vidocq:jlink")),

    /** {@code mvn package vidocq:jpackage} — native installer / app-image. */
    JPACKAGE("jpackage", "native installer", List.of("package", "vidocq:jpackage")),

    /** {@code mvn package vidocq:docker} — Dockerfile around the jlink image. */
    DOCKER("docker", "Docker image", List.of("package", "vidocq:docker"));

    private final String token;
    private final String label;
    private final List<String> goals;

    BuildType(String token, String label, List<String> goals) {
        this.token = token;
        this.label = label;
        this.goals = goals;
    }

    /** The sub-command token typed on the CLI (e.g. {@code jlink}). */
    public String token() {
        return token;
    }

    /** Human-friendly description used in banners. */
    public String label() {
        return label;
    }

    /** The Maven phases/goals this flavour runs, in order. */
    public List<String> goals() {
        return goals;
    }

    /**
     * Resolve a CLI token to a build type. A {@code null} or blank token means
     * the default {@link #PACKAGE} build.
     *
     * @throws IllegalArgumentException if the token is unknown
     */
    public static BuildType fromToken(String token) {
        if (token == null || token.isBlank()) {
            return PACKAGE;
        }
        for (BuildType type : values()) {
            if (type.token.equals(token)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown build type '" + token
                + "'. Available: package, jlink, jpackage, docker.");
    }
}
