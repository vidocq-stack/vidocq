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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code vidocq build docker} builds the image when Docker answers, and says it did not when it does not, instead
 * of a bare "Done." over a Dockerfile nobody built (BUG-20261030-17).
 */
class DockerBuildTest {

    @Test
    void withDockerTheGoalBuildsTheImage() {
        assertEquals(List.of("-Dvidocq.docker.build=true"), DockerBuild.properties(true));
    }

    @Test
    void withoutDockerTheGoalOnlyWritesTheDockerfile() {
        assertEquals(List.of(), DockerBuild.properties(false));
    }

    @Test
    void withoutDockerTheMessageSaysTheImageIsNotBuiltAndHowToBuildIt() {
        String message = DockerBuild.unavailableMessage();

        assertTrue(message.contains("not built"), message);
        assertTrue(message.contains("docker build -f target/Dockerfile target/"), message);
    }

    @Test
    void aMissingDockerBinaryIsNotAvailable() {
        assertFalse(DockerBuild.available(List.of("definitely-not-a-real-binary-xyz", "info")));
    }

    @Test
    void aProbeThatFailsIsNotAvailable() {
        assertFalse(DockerBuild.available(List.of("false")));
    }

    @Test
    void aProbeThatSucceedsIsAvailable() {
        assertTrue(DockerBuild.available(List.of("true")));
    }
}
