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
package io.vidocq.runtime.cli.ext;

/**
 * A Maven {@code groupId:artifactId} pair identifying an extension dependency.
 *
 * <p>Pure value type — no I/O. Resolution of a short id (e.g. {@code knock-health})
 * to a coordinate lives in {@link KnownExtensions#resolve(String)}.</p>
 */
public record ExtensionCoordinate(String groupId, String artifactId) {

    public ExtensionCoordinate {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("groupId must not be blank");
        }
        if (artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException("artifactId must not be blank");
        }
    }

    /**
     * Parses an explicit {@code groupId:artifactId} coordinate.
     */
    public static ExtensionCoordinate parse(String gav) {
        if (gav == null) {
            throw new IllegalArgumentException("coordinate must not be null");
        }
        String[] parts = gav.split(":");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException(
                    "Expected 'groupId:artifactId', got: " + gav);
        }
        return new ExtensionCoordinate(parts[0].trim(), parts[1].trim());
    }

    /**
     * Renders a Maven {@code <dependency>} block. {@code indent} is the leading
     * whitespace for the {@code <dependency>} line; children are indented one
     * additional four-space step. No trailing newline.
     */
    public String dependencyXml(String indent) {
        return dependencyXml(indent, null);
    }

    /** {@link #dependencyXml(String)} with a {@code <version>}, omitted when {@code null}. */
    public String dependencyXml(String indent, String version) {
        String child = indent + "    ";
        return indent + "<dependency>\n"
                + child + "<groupId>" + groupId + "</groupId>\n"
                + child + "<artifactId>" + artifactId + "</artifactId>\n"
                + (version == null ? "" : child + "<version>" + version + "</version>\n")
                + indent + "</dependency>";
    }

    @Override
    public String toString() {
        return groupId + ":" + artifactId;
    }
}
