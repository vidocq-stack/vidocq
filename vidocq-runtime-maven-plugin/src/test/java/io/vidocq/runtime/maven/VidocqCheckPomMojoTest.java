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

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the group-recognition logic after the domain-based reorganization of the runtime
 * extensions: the leaf artifacts moved out of the flat {@code io.vidocq.runtime} group into
 * per-domain {@code io.vidocq.runtime.extensions.*} sub-groups. checkpom must keep recognising
 * them as extensions (otherwise it silently stops pairing extensions with their codegen bundles)
 * and must remember each extension's real group so it resolves the matching codegen there.
 */
class VidocqCheckPomMojoTest {

    @Test
    void isVidocqRuntimeGroup_recognizesLegacyAndReorganizedSubgroups() {
        assertTrue(VidocqCheckPomMojo.isVidocqRuntimeGroup("io.vidocq.runtime"));
        assertTrue(VidocqCheckPomMojo.isVidocqRuntimeGroup("io.vidocq.runtime.extensions.essentials"));
        assertTrue(VidocqCheckPomMojo.isVidocqRuntimeGroup("io.vidocq.runtime.extensions.jakartaee.core"));
        assertTrue(VidocqCheckPomMojo.isVidocqRuntimeGroup("io.vidocq.runtime.extensions.jakartaee.web"));
        assertTrue(VidocqCheckPomMojo.isVidocqRuntimeGroup("io.vidocq.runtime.extensions.microprofile"));
        assertTrue(VidocqCheckPomMojo.isVidocqRuntimeGroup("io.vidocq.runtime.extensions.jpms.repackaged"));

        assertFalse(VidocqCheckPomMojo.isVidocqRuntimeGroup("io.vidocq.cassini"));
        // Prefix match must be dot-anchored: a group that merely *starts with* the string is not a member.
        assertFalse(VidocqCheckPomMojo.isVidocqRuntimeGroup("io.vidocq.runtimexyz"));
        assertFalse(VidocqCheckPomMojo.isVidocqRuntimeGroup(null));
    }

    @Test
    void collectExtensions_recordsTheGroupOfEachExtensionDependency() throws Exception {
        Model model = new Model();
        model.addDependency(dep("io.vidocq.runtime.extensions.jakartaee.core",
                "vidocq-runtime-cassini-rest-extension", "0.2.0-SNAPSHOT"));
        // The codegen bundle shares the artifactId prefix but must be excluded — it is an APT
        // bundle, not a runtime extension.
        model.addDependency(dep("io.vidocq.runtime.extensions.jakartaee.core",
                "vidocq-runtime-cassini-rest-extension-codegen", "0.2.0-SNAPSHOT"));
        // A non-extension runtime module must be ignored.
        model.addDependency(dep("io.vidocq.runtime", "vidocq-runtime-spi", "0.2.0-SNAPSHOT"));
        // A foreign group must be ignored.
        model.addDependency(dep("io.vidocq.cassini", "cassini-api", "0.1.0-SNAPSHOT"));

        VidocqCheckPomMojo mojo = new VidocqCheckPomMojo();
        setProject(mojo, new MavenProject(model));

        Map<String, String> groups = new LinkedHashMap<>();
        Map<String, String> extensions = mojo.collectExtensions(groups);

        assertEquals(1, extensions.size(), "only the runtime extension should be collected");
        assertTrue(extensions.containsKey("vidocq-runtime-cassini-rest-extension"));
        assertEquals("io.vidocq.runtime.extensions.jakartaee.core",
                groups.get("vidocq-runtime-cassini-rest-extension"),
                "the extension's actual sub-group must be recorded so the codegen resolves there");
    }

    private static Dependency dep(String groupId, String artifactId, String version) {
        Dependency d = new Dependency();
        d.setGroupId(groupId);
        d.setArtifactId(artifactId);
        d.setVersion(version);
        return d;
    }

    private static void setProject(VidocqCheckPomMojo mojo, MavenProject project) throws Exception {
        Field f = VidocqCheckPomMojo.class.getDeclaredField("project");
        f.setAccessible(true);
        f.set(mojo, project);
    }
}
