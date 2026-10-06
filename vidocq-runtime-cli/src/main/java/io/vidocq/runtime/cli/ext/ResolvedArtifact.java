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

import java.nio.file.Path;
import java.util.Objects;

/**
 * A dependency Maven resolved for a project, with the JAR it resolved to.
 *
 * <p>Pure value type — produced by {@link DependencyList#parse(String)}.</p>
 */
public record ResolvedArtifact(String groupId, String artifactId, String version, Path file) {

    public ResolvedArtifact {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(file, "file");
    }

    /** The {@code groupId:artifactId} pair, comparable with a POM's declared dependencies. */
    public ExtensionCoordinate coordinate() {
        return new ExtensionCoordinate(groupId, artifactId);
    }

    @Override
    public String toString() {
        return groupId + ":" + artifactId + ":" + version;
    }
}
