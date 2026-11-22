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
package io.vidocq.runtime.maven;

import org.apache.maven.model.ActivationProperty;
import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The profile of {@code vidocq-parent} moves the modules of the monorepo when an IDE reads them, and the participant
 * moves an application of one module ({@link IdeOutput}): the same property of m2e, the same folder, and the
 * annotation processing {@link ApplicationBuild} turns on in an IDE. The parent's pom, in this reactor, is read as it
 * is written: this test fails when the two diverge.
 */
class IdeOutputParentAlignmentTest {

    private static final Path PARENT_POM = Path.of("..", "..", "vidocq-parent", "pom.xml");

    @Test
    void theProfileOfTheParentIsTheParticipantsRule() throws Exception {
        List<Profile> profiles = parent().getProfiles().stream()
                .filter(profile -> IdeOutput.PROFILE.equals(profile.getId())).toList();
        assertEquals(1, profiles.size(), "vidocq-parent has one profile " + IdeOutput.PROFILE);
        Profile profile = profiles.get(0);

        assertNotNull(profile.getActivation(), "the profile has an activation");
        ActivationProperty property = profile.getActivation().getProperty();
        assertNotNull(property, "a property activates the profile");
        assertEquals(IdeOutput.M2E_VERSION, property.getName());
        assertNull(property.getValue(), "the property activates the profile whatever its value");
        assertNotNull(profile.getBuild(), "the profile sets the build directory");
        assertEquals("${project.basedir}/" + IdeOutput.DIRECTORY, profile.getBuild().getDirectory());
        assertEquals(ApplicationBuild.M2E_APT_MODE, profile.getProperties().getProperty(ApplicationBuild.M2E_APT));
    }

    private static Model parent() throws Exception {
        try (Reader reader = Files.newBufferedReader(PARENT_POM)) {
            return new MavenXpp3Reader().read(reader);
        }
    }
}
