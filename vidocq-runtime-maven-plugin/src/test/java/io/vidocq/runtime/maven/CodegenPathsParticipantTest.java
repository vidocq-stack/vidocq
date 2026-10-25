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

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.PoolEntry;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How Maven finds the participant, and what it does to the projects of a session (Vidocq/vidocq#339). */
class CodegenPathsParticipantTest {

    /**
     * A Plexus descriptor, not a Sisu index: Maven 3.9.9 reads an index with an ASM that does not know Java 25 class
     * files, and the participant never ran there.
     */
    @Test
    void thePluginDeclaresTheParticipantInAPlexusDescriptor() throws Exception {
        // This module's own output: Maven's jars on the test class path carry Sisu indexes of their own.
        assertFalse(Files.exists(Path.of("target/classes/META-INF/sisu/javax.inject.Named")),
                "a Sisu index is skipped by Maven 3.9.9 for Java 25 classes");
        Element component;
        try (InputStream in = getClass().getResourceAsStream("/META-INF/plexus/components.xml")) {
            assertNotNull(in, "the plugin declares no Plexus component");
            component = (Element) DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in)
                    .getElementsByTagName("component").item(0);
        }
        assertEquals(AbstractMavenLifecycleParticipant.class.getName(), text(component, "role"));
        Class<?> implementation = Class.forName(text(component, "implementation"));
        assertEquals(CodegenPathsParticipant.class, implementation);
        assertTrue(AbstractMavenLifecycleParticipant.class.isAssignableFrom(implementation));
    }

    /**
     * VS Code's Java extension runs m2e on the Java 21 runtime it ships, and so may Eclipse: a participant compiled for
     * Java 25 failed the whole Maven import there ({@code TypeNotPresentException: Type CodegenPathsParticipant not
     * present}). Its classes are Java 17 class files, and name nothing of {@code vidocq-runtime-tooling} (Java 25).
     */
    @Test
    void theParticipantLoadsOnTheJava17RuntimeOfAnIde() throws Exception {
        List<Path> classes;
        try (var files = Files.list(Path.of("target/classes/io/vidocq/runtime/maven"))) {
            classes = files.filter(f -> f.getFileName().toString().startsWith("CodegenPaths")).toList();
        }
        assertEquals(2, classes.size(), classes.toString());
        for (Path file : classes) {
            ClassModel model = ClassFile.of().parse(Files.readAllBytes(file));
            assertTrue(model.majorVersion() <= ClassFile.JAVA_17_VERSION,
                    file.getFileName() + " is a Java " + (model.majorVersion() - 44) + " class file");
            for (PoolEntry entry : model.constantPool()) {
                if (entry instanceof ClassEntry type) {
                    assertFalse(type.asInternalName().startsWith("io/vidocq/runtime/tooling/"),
                            file.getFileName() + " names " + type.asInternalName());
                }
            }
        }
    }

    @Test
    void afterProjectsReadWiresEveryProjectOfTheSession() {
        Model model = new Model();
        model.setBuild(new org.apache.maven.model.Build());
        org.apache.maven.model.Plugin vidocq = new org.apache.maven.model.Plugin();
        vidocq.setGroupId("io.vidocq.runtime");
        vidocq.setArtifactId("vidocq-runtime-maven-plugin");
        model.getBuild().addPlugin(vidocq);
        org.apache.maven.model.Plugin compiler = new org.apache.maven.model.Plugin();
        compiler.setArtifactId("maven-compiler-plugin");
        model.getBuild().addPlugin(compiler);
        Dependency core = new Dependency();
        core.setGroupId("io.vidocq.runtime");
        core.setArtifactId("vidocq-runtime-core");
        core.setVersion("0.4.0");
        model.addDependency(core);
        MavenSession session = session(List.of(new MavenProject(model)));

        new CodegenPathsParticipant().afterProjectsRead(session);

        assertNotNull(compiler.getConfiguration(), "the indexer was not wired");
    }

    /** A session of {@code projects}, which is all the participant reads. */
    @SuppressWarnings("deprecation")
    private static MavenSession session(List<MavenProject> projects) {
        MavenSession session = new MavenSession(null, null, new DefaultMavenExecutionRequest(),
                new DefaultMavenExecutionResult());
        session.setProjects(projects);
        return session;
    }

    private static String text(Element parent, String name) {
        return parent.getElementsByTagName(name).item(0).getTextContent().strip();
    }
}
