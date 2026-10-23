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

import io.vidocq.runtime.tooling.ExtensionEditor;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * Adds or removes extensions on the project, as {@code vidocq extension add|remove} does: both run
 * {@link ExtensionEditor}, so the dependency, the codegen bundle's processor path and the
 * {@code module-info.java} directives are the same (Vidocq/vidocq#314). Never part of a build: run it by name.
 */
abstract class AbstractExtensionEditMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
    private File basedir;

    /** The extensions, comma-separated: catalog ids ({@code cassini-rest}) or {@code groupId:artifactId}. */
    @Parameter(property = "extensions")
    private String extensions;

    /** The version an added extension pins when the pom's parent gives none: this plugin's own. */
    @Parameter(defaultValue = "${plugin.version}", readonly = true, required = true)
    private String runtimeVersion;

    /** Whether the goal adds, rather than removes. */
    abstract boolean add();

    private String goal() {
        return add() ? "add-extension" : "remove-extension";
    }

    @Override
    public void execute() throws MojoFailureException {
        List<String> ids = extensions == null ? List.of() : Arrays.stream(extensions.split(","))
                .map(String::strip).filter(id -> !id.isEmpty()).toList();
        if (ids.isEmpty()) {
            throw new MojoFailureException("Name the extensions: mvn vidocq:" + goal()
                    + " -Dextensions=cassini-rest,knock-health");
        }
        ExtensionEditor.Outcome outcome = ExtensionEditor.edit(basedir.toPath(), ids, add(), runtimeVersion,
                new LogReporter(getLog()));
        if (!outcome.succeeded()) {
            throw new MojoFailureException("vidocq:" + goal() + " failed: see the error above.");
        }
        if (outcome.pomChanged()) {
            getLog().info("pom.xml updated.");
        } else {
            getLog().info(outcome.moduleInfoChanged() ? "pom.xml left untouched." : "No changes — pom.xml left untouched.");
        }
    }
}
