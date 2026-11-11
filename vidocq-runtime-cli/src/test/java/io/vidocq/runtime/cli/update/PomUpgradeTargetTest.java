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

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Vidocq/vidocq#510: the version {@code vidocq pom upgrade} moves to, from the catalogue {@code update} reads. */
class PomUpgradeTargetTest {

    private static final String CENTRAL = "https://central/cli";
    private static final String SNAPSHOTS = "https://snapshots/cli";

    private final Map<String, String> remote = new HashMap<>();

    PomUpgradeTargetTest() {
        remote.put(CENTRAL + "/maven-metadata.xml", "<metadata><versioning><release>0.5.0</release></versioning></metadata>");
        remote.put(SNAPSHOTS + "/maven-metadata.xml", "<metadata><versioning><versions>"
                + "<version>0.5.0-SNAPSHOT</version><version>0.6.0-SNAPSHOT</version></versions></versioning></metadata>");
    }

    private Optional<String> resolve(String current, UpdatePlan.Channel channel) {
        return PomUpgradeTarget.resolve(current, channel, url -> Optional.ofNullable(remote.get(url)), CENTRAL,
                SNAPSHOTS);
    }

    @Test
    void aReleasePomFollowsTheLatestRelease() {
        assertEquals(Optional.of("0.5.0"), resolve("0.4.0", UpdatePlan.Channel.INSTALLED));
        assertEquals(Optional.of("0.5.0"), resolve("0.4.0", UpdatePlan.Channel.RELEASE));
    }

    @Test
    void aReleaseIsNeverMovedBackByItsOwnChannel() {
        assertEquals(Optional.of("0.9.0"), resolve("0.9.0", UpdatePlan.Channel.INSTALLED));
    }

    @Test
    void aSnapshotPomFollowsTheHighestSnapshotLine() {
        assertEquals(Optional.of("0.6.0-SNAPSHOT"), resolve("0.5.0-SNAPSHOT", UpdatePlan.Channel.INSTALLED));
        assertEquals(Optional.of("0.6.0-SNAPSHOT"), resolve("0.6.0-SNAPSHOT", UpdatePlan.Channel.SNAPSHOT));
    }

    @Test
    void theOptionsSwitchChannel() {
        assertEquals(Optional.of("0.5.0"), resolve("0.6.0-SNAPSHOT", UpdatePlan.Channel.RELEASE));
        assertEquals(Optional.of("0.6.0-SNAPSHOT"), resolve("0.4.0", UpdatePlan.Channel.SNAPSHOT));
    }

    @Test
    void aCatalogueThatDoesNotAnswerGivesNoTarget() {
        remote.clear();

        assertEquals(Optional.empty(), resolve("0.4.0", UpdatePlan.Channel.INSTALLED));
        assertEquals(Optional.empty(), resolve("0.4.0-SNAPSHOT", UpdatePlan.Channel.INSTALLED));
    }
}
