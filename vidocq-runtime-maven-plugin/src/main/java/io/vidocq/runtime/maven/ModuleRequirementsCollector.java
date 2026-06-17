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

import io.vidocq.runtime.codegen.commons.ModuleRequirementsDescriptor;
import io.vidocq.runtime.codegen.commons.Requirement;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.logging.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Unions the {@link ModuleRequirementsDescriptor#RESOURCE} descriptors shipped by a set of resolved
 * dependency artifacts. Shared by {@code check-module-info} and {@code complete-module-info} so both
 * goals agree on the required JPMS directives. Reads a descriptor from a JAR or from a reactor
 * {@code target/classes} directory alike.
 */
final class ModuleRequirementsCollector {

    private ModuleRequirementsCollector() {
    }

    static Set<Requirement> collect(Set<Artifact> artifacts, Log log) {
        Set<Requirement> all = new LinkedHashSet<>();
        for (Artifact artifact : artifacts) {
            Properties props = readDescriptor(artifact.getFile(), log);
            if (props != null) {
                all.addAll(ModuleRequirementsDescriptor.parse(props));
            }
        }
        return all;
    }

    /**
     * Keeps only the requirements that actually APPLY to the module under build. An {@code opens
     * <pkg>} applies only to a module that itself contains {@code <pkg>} — you neither need, nor can
     * legally, open a package you do not have. This is what lets the check run reactor-wide: an
     * extension that depends on (say) the migration extension but ships no {@code db.migration} package
     * is simply a no-op, instead of being wrongly told to open a package it lacks.
     *
     * @param outputDirectory the module's compiled-classes output (holds packages as directories,
     *                        resource-only packages included once the resources plugin has run)
     */
    static Set<Requirement> applicableTo(Set<Requirement> required, File outputDirectory) {
        Set<Requirement> out = new LinkedHashSet<>();
        for (Requirement r : required) {
            if (r.kind() == Requirement.Kind.OPENS && !containsPackage(outputDirectory, r.name())) {
                continue;
            }
            out.add(r);
        }
        return out;
    }

    private static boolean containsPackage(File outputDirectory, String pkg) {
        return outputDirectory != null
                && new File(outputDirectory, pkg.replace('.', '/')).isDirectory();
    }

    private static Properties readDescriptor(File artifactFile, Log log) {
        if (artifactFile == null || !artifactFile.exists()) {
            return null;
        }
        try {
            if (artifactFile.isDirectory()) {
                // Reactor dependency resolved to target/classes (built in the same reactor).
                File resource = new File(artifactFile, ModuleRequirementsDescriptor.RESOURCE);
                if (!resource.isFile()) {
                    return null;
                }
                try (InputStream in = Files.newInputStream(resource.toPath())) {
                    return load(in);
                }
            }
            try (JarFile jar = new JarFile(artifactFile)) {
                ZipEntry entry = jar.getEntry(ModuleRequirementsDescriptor.RESOURCE);
                if (entry == null) {
                    return null;
                }
                try (InputStream in = jar.getInputStream(entry)) {
                    return load(in);
                }
            }
        } catch (IOException e) {
            log.debug("Vidocq: cannot read module-requirements descriptor from " + artifactFile + ": " + e);
            return null;
        }
    }

    private static Properties load(InputStream in) throws IOException {
        Properties props = new Properties();
        props.load(in);
        return props;
    }
}
