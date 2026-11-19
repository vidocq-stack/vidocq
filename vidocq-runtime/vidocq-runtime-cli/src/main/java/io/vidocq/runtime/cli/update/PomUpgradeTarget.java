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
package io.vidocq.runtime.cli.update;

import java.util.Optional;
import java.util.function.Function;

/**
 * The version {@code vidocq pom upgrade} moves a pom to (Vidocq/vidocq#510), read from the catalogue {@code vidocq
 * update} reads: the latest release of Maven Central, or the highest SNAPSHOT line of the snapshot repository.
 *
 * <p>With no option ({@link UpdatePlan.Channel#INSTALLED}) a release pom follows the releases and a SNAPSHOT pom the
 * SNAPSHOTs; {@code --release} and {@code --snapshot} switch channel. On its own channel a pom is never moved back:
 * the target is its own version when the catalogue has nothing newer.</p>
 */
public final class PomUpgradeTarget {

    private PomUpgradeTarget() {}

    /**
     * @param current   the Vidocq version of the pom
     * @param channel   the channel asked for
     * @param fetch     reads a URL, empty when it cannot
     * @param central   base URL of the CLI artifact on Maven Central
     * @param snapshots base URL of the CLI artifact in the snapshot repository
     * @return the target version, empty when the catalogue does not answer
     */
    public static Optional<String> resolve(String current, UpdatePlan.Channel channel,
                                           Function<String, Optional<String>> fetch, String central,
                                           String snapshots) {
        boolean snapshot = current.endsWith("-SNAPSHOT");
        boolean releases = channel == UpdatePlan.Channel.RELEASE
                || channel == UpdatePlan.Channel.INSTALLED && !snapshot;
        Optional<String> latest = releases
                ? UpdatePlan.latestRelease(fetch, central)
                : UpdatePlan.highestSnapshot(fetch, snapshots);
        boolean switching = releases == snapshot;
        return latest.map(version -> switching || UpdatePlan.compare(version, current) > 0 ? version : current);
    }
}
