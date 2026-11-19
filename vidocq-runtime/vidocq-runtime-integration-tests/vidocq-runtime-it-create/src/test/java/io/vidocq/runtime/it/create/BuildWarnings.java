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
package io.vidocq.runtime.it.create;

import java.util.List;

/**
 * The warnings of a build of a created project that are the project's own (BUG-20261115-09). The fixture mirrors every
 * repository to the build machine's local repository, as a {@code file:} URL. What a producer's downstream job
 * installed there has no checksum file, and Maven's default {@code warn} policy prints "Could not validate integrity of
 * download from" for each such file: a warning about the machine, not about the project. The same warning from any
 * other repository is kept.
 */
final class BuildWarnings {

    private static final String INTEGRITY = "[WARNING] Could not validate integrity of download from ";

    private BuildWarnings() {
    }

    /**
     * @param output             what the build printed
     * @param outerRepositoryUrl the URL of the build machine's local repository, with or without its trailing slash
     * @return the {@code [WARNING]} lines that do not come from a download from that repository
     */
    static List<String> warningsOf(String output, String outerRepositoryUrl) {
        String root = outerRepositoryUrl.endsWith("/") ? outerRepositoryUrl : outerRepositoryUrl + "/";
        String fromOuter = INTEGRITY + root;
        return output.lines()
                .filter(l -> l.startsWith("[WARNING]") && !l.startsWith(fromOuter))
                .toList();
    }
}
