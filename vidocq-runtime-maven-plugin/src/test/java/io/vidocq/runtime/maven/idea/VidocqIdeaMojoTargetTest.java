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
package io.vidocq.runtime.maven.idea;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VidocqIdeaMojoTargetTest {

    private static final IdeaApplication APP = new IdeaApplication("com.example:app", "com.example.App", "App",
            "app", "pom.xml", "generate", null, 0);
    private static final UnaryOperator<String> SAME_JRE = UnaryOperator.identity();

    private static VidocqIdeaMojo.Target target(String shownPath, byte[] onDisk) {
        return new VidocqIdeaMojo.Target(APP, shownPath, Path.of(shownPath), "<body/>", onDisk,
                RunConfigurationFiles.State.OUTDATED, SAME_JRE);
    }

    @Test
    void aTargetComparesWhatIsOnDiskByContent() {
        VidocqIdeaMojo.Target one = target(".run/App.run.xml", new byte[] {1, 2, 3});
        VidocqIdeaMojo.Target same = target(".run/App.run.xml", new byte[] {1, 2, 3});

        assertEquals(one, same);
        assertEquals(one.hashCode(), same.hashCode());
        assertNotEquals(one, target(".run/App.run.xml", new byte[] {1, 2, 4}));
        assertNotEquals(one, target(".run/Other.run.xml", new byte[] {1, 2, 3}));
    }

    @Test
    void aTargetShowsItsPathAndStateNotItsBytes() {
        String shown = target(".run/App.run.xml", new byte[] {1, 2, 3}).toString();

        assertTrue(shown.contains(".run/App.run.xml") && shown.contains("OUTDATED"), shown);
        assertFalse(shown.contains("[B@"), shown);
    }
}
