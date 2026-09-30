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

import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertTrue(VidocqCheckPomMojo.isVidocqRuntimeGroup("io.vidocq.runtime.extensions.module.repackaged"));

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

    @Test
    void aDevOnlyDependencyOutsideTestScopeIsReported(@TempDir Path dir) throws Exception {
        Path marked = DevOnlyJarsTest.jar(dir, "console.jar", "true");
        Dependency compile = dependency("vidocq-runtime-devconsole-extension", null);
        Dependency test = dependency("vidocq-runtime-devconsole-extension", "test");
        Dependency plain = dependency("vidocq-runtime-core", "compile");

        List<String> issues = VidocqCheckPomMojo.findDevOnlyDeclarations(
                List.of(compile, test, plain),
                d -> d.getArtifactId().contains("devconsole") ? Optional.of(marked)
                        : Optional.of(jarRethrowsUnchecked(dir, "core.jar", null)));

        assertEquals(List.of(DevOnlyJars.droppedWarning("vidocq-runtime-devconsole-extension")),
                issues);
    }

    /**
     * The dev-only warning does not depend on the codegen checks: a module with no extension/codegen coupling still
     * learns that its declared dev-only dependency is never packaged.
     */
    @Test
    void aDevOnlyDependencyIsReportedEvenWithNothingElseToCheck(@TempDir Path dir) throws Exception {
        Path marked = DevOnlyJarsTest.jar(dir, "knock-dev.jar", "true");
        Model model = new Model();
        model.setPackaging("jar");
        model.addDependency(dependency("vidocq-runtime-knock-health-extension-dev", "compile"));
        List<String> warnings = new ArrayList<>();

        checkpom(new MavenProject(model), marked, warnings).execute();

        assertEquals(List.of(DevOnlyJars.droppedWarning("vidocq-runtime-knock-health-extension-dev")), warnings);
    }

    /** A dev-only module (a {@code -dev} companion, the console) depends on dev tools by design: no warning. */
    @Test
    void aDevOnlyModuleIsNotWarnedAboutItsDevOnlyDependencies(@TempDir Path dir) throws Exception {
        Path marked = DevOnlyJarsTest.jar(dir, "console.jar", "true");
        Model model = new Model();
        model.setPackaging("jar");
        model.addDependency(dependency("vidocq-runtime-knock-health-extension-dev", "compile"));
        Plugin jar = new Plugin();
        jar.setGroupId("org.apache.maven.plugins");
        jar.setArtifactId("maven-jar-plugin");
        jar.setConfiguration(Xpp3DomBuilder.build(new StringReader("<configuration><archive><manifestEntries>"
                + "<Vidocq-Dev-Only>true</Vidocq-Dev-Only></manifestEntries></archive></configuration>")));
        Build build = new Build();
        build.addPlugin(jar);
        model.setBuild(build);
        MavenProject project = new MavenProject(model);
        List<String> warnings = new ArrayList<>();

        assertTrue(VidocqCheckPomMojo.isDevOnlyProject(project));
        assertFalse(VidocqCheckPomMojo.isDevOnlyProject(new MavenProject(new Model())));
        checkpom(project, marked, warnings).execute();

        assertEquals(List.of(), warnings);
    }

    /**
     * #147: a dev-only module depends on its runtime extension for its live API only and generates nothing: its
     * missing codegen bundle is not an issue. Any other module still fails on it.
     */
    @Test
    void aDevOnlyModuleNeedsNoCodegenBundleForTheExtensionItReads(@TempDir Path dir) throws Exception {
        Path plain = DevOnlyJarsTest.jar(dir, "knock.jar", null);
        Model devModel = new Model();
        devModel.setPackaging("jar");
        devModel.addDependency(dependency("vidocq-runtime-knock-health-extension", "compile"));
        Plugin jar = new Plugin();
        jar.setGroupId("org.apache.maven.plugins");
        jar.setArtifactId("maven-jar-plugin");
        jar.setConfiguration(Xpp3DomBuilder.build(new StringReader("<configuration><archive><manifestEntries>"
                + "<Vidocq-Dev-Only>true</Vidocq-Dev-Only></manifestEntries></archive></configuration>")));
        Build build = new Build();
        build.addPlugin(jar);
        devModel.setBuild(build);
        Model plainModel = new Model();
        plainModel.setPackaging("jar");
        plainModel.addDependency(dependency("vidocq-runtime-knock-health-extension", "compile"));

        assertDoesNotThrow(() -> strictCheckpom(new MavenProject(devModel), plain).execute());
        MojoFailureException failure = assertThrows(MojoFailureException.class,
                () -> strictCheckpom(new MavenProject(plainModel), plain).execute());
        assertTrue(failure.getMessage().contains("1 issue(s)"), failure.getMessage());
    }

    /** A checkpom mojo failing on its issues, every codegen bundle published, every dependency resolving to jar. */
    private static VidocqCheckPomMojo strictCheckpom(MavenProject project, Path jarFile) throws Exception {
        VidocqCheckPomMojo mojo = new VidocqCheckPomMojo() {
            @Override
            Optional<Path> resolvedJar(Dependency d) {
                return Optional.of(jarFile);
            }

            @Override
            boolean codegenArtifactExists(String artifactId, String groupId, String version) {
                return true;
            }
        };
        setProject(mojo, project);
        Field failOnMissing = VidocqCheckPomMojo.class.getDeclaredField("failOnMissing");
        failOnMissing.setAccessible(true);
        failOnMissing.set(mojo, true);
        mojo.setLog(new SystemStreamLog() {
            @Override
            public void info(CharSequence content) {
            }

            @Override
            public void error(CharSequence content) {
            }
        });
        return mojo;
    }

    /** A checkpom mojo on {@code project} whose every dependency resolves to {@code jar}, warnings captured. */
    private static VidocqCheckPomMojo checkpom(MavenProject project, Path jar, List<String> warnings)
            throws Exception {
        VidocqCheckPomMojo mojo = new VidocqCheckPomMojo() {
            @Override
            Optional<Path> resolvedJar(Dependency d) {
                return Optional.of(jar);
            }
        };
        setProject(mojo, project);
        mojo.setLog(new SystemStreamLog() {
            @Override
            public void warn(CharSequence content) {
                warnings.add(content.toString());
            }

            @Override
            public void info(CharSequence content) {
            }
        });
        return mojo;
    }

    private static Path jarRethrowsUnchecked(Path dir, String name, String devOnly) {
        try {
            return DevOnlyJarsTest.jar(dir, name, devOnly);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Dependency dependency(String artifactId, String scope) {
        Dependency d = new Dependency();
        d.setGroupId("io.vidocq.runtime.extensions.essentials");
        d.setArtifactId(artifactId);
        d.setVersion("0.2.0-SNAPSHOT");
        d.setScope(scope);
        return d;
    }

    private static void setProject(VidocqCheckPomMojo mojo, MavenProject project) throws Exception {
        Field f = VidocqCheckPomMojo.class.getDeclaredField("project");
        f.setAccessible(true);
        f.set(mojo, project);
    }
}
