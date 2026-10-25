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
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;

/**
 * Wires the codegen bundles of a project's extensions once Maven has read the projects, before any goal runs
 * (Vidocq/vidocq#339, evolution 2). Maven loads it only when the plugin is declared with
 * {@code <extensions>true</extensions>}, which {@code vidocq-runtime-parent} does from 0.4.0; IntelliJ IDEA's Maven
 * import runs it too, so the IDE's processor path is the build's.
 *
 * <p>Declared in {@code META-INF/plexus/components.xml}, not with {@code @Named}: Maven 3.9.9 reads a Sisu index
 * with an ASM too old for Java 25 class files and skips the component without a word.</p>
 */
public final class CodegenPathsParticipant extends AbstractMavenLifecycleParticipant {

    @Override
    public void afterProjectsRead(MavenSession session) {
        for (MavenProject project : session.getProjects()) {
            CodegenPaths.wire(project.getModel());
        }
    }
}
