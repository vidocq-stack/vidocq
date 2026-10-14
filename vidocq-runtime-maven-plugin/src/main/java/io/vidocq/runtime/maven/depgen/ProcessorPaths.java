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
package io.vidocq.runtime.maven.depgen;

import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/** Reads the annotationProcessorPaths of the project's maven-compiler-plugin. */
public final class ProcessorPaths {

    private static final String MAVEN_COMPILER_KEY = "org.apache.maven.plugins:maven-compiler-plugin";

    private ProcessorPaths() {
    }

    /**
     * Every path of maven-compiler-plugin's configuration and executions, in order, de-duplicated by
     * {@code groupId:artifactId:type:classifier} (the first one wins). An entry without an artifactId is skipped.
     */
    public static List<ProcessorPath> of(MavenProject project) {
        List<ProcessorPath> result = new ArrayList<>();
        Plugin compiler = project.getPlugin(MAVEN_COMPILER_KEY);
        if (compiler == null) return result;
        Set<String> seen = new LinkedHashSet<>();
        Stream.concat(
                Stream.ofNullable(compiler.getConfiguration()),
                compiler.getExecutions().stream().map(PluginExecution::getConfiguration)
        ).filter(Objects::nonNull).forEach(cfg -> {
            if (!(cfg instanceof Xpp3Dom dom)) return;
            Xpp3Dom paths = dom.getChild("annotationProcessorPaths");
            if (paths == null) return;
            for (Xpp3Dom path : paths.getChildren("path")) {
                String artifactId = text(path, "artifactId");
                if (artifactId == null) continue;
                String type = text(path, "type");
                ProcessorPath p = new ProcessorPath(text(path, "groupId"), artifactId, text(path, "version"),
                        type == null ? "jar" : type, text(path, "classifier"));
                if (seen.add(p.groupId() + ":" + p.artifactId() + ":" + p.type() + ":" + p.classifier())) {
                    result.add(p);
                }
            }
        });
        return result;
    }

    private static String text(Xpp3Dom node, String name) {
        Xpp3Dom c = node.getChild(name);
        if (c == null || c.getValue() == null) return null;
        String v = c.getValue().strip();
        return v.isEmpty() ? null : v;
    }
}
