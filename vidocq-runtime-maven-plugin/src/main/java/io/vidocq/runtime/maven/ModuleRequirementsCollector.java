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
import io.vidocq.runtime.tooling.ExtensionCoordinate;
import io.vidocq.runtime.tooling.KnownExtensions;
import io.vidocq.runtime.tooling.ReflectedPackages;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.logging.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.FindException;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Unions the module requirements of a set of resolved dependency artifacts, from two sources:
 * the {@link ModuleRequirementsDescriptor#RESOURCE} descriptor an extension may ship, and the
 * {@code module-info.requires} of the extension catalog. Shared by {@code check-module-info} and
 * {@code complete-module-info} so both goals agree on the required Java Modules directives. Reads a
 * descriptor from a JAR or from a reactor {@code target/classes} directory alike.
 */
final class ModuleRequirementsCollector {

    private ModuleRequirementsCollector() {
    }

    static Set<Requirement> collect(Set<Artifact> artifacts, Log log) {
        Set<Requirement> all = new LinkedHashSet<>();
        for (Artifact artifact : artifacts) {
            all.addAll(ModuleRequirementsDescriptor.parse(readDescriptor(artifact.getFile(), log)));
            all.addAll(fromCatalog(artifact));
        }
        return all;
    }

    /**
     * The {@code requires} the extension catalog states an application needs besides the extension's own
     * module. Only two of the first-party extensions ship a
     * {@link ModuleRequirementsDescriptor#RESOURCE} descriptor, while the catalog carries the same
     * information for the rest under {@code module-info.requires} -- so without this the check could not
     * see, for instance, that an application using {@code cassini-rest} has to require
     * {@code jakarta.ws.rs} itself.
     *
     * <p>A {@code requires static} is not reported: it is a separate catalog key and
     * {@link Requirement} cannot express it.</p>
     */
    private static Set<Requirement> fromCatalog(Artifact artifact) {
        if (artifact.getGroupId() == null || artifact.getGroupId().isBlank()
                || artifact.getArtifactId() == null || artifact.getArtifactId().isBlank()) {
            return Set.of();
        }
        String id = KnownExtensions.idOf(new ExtensionCoordinate(artifact.getGroupId(), artifact.getArtifactId()));
        Set<Requirement> out = new LinkedHashSet<>();
        for (String module : KnownExtensions.moduleRequires(id)) {
            out.add(Requirement.requires(module, id + " does not re-export it"));
        }
        for (String module : KnownExtensions.moduleRequiresStatic(id)) {
            out.add(Requirement.requiresStatic(module, id + "'s generated sources need it to compile"));
        }
        return out;
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
        return applicableTo(required, outputDirectory, null);
    }

    /**
     * Keeps only the requirements that actually APPLY to the module under build. An {@code opens <pkg>}
     * applies only to a module that itself contains {@code <pkg>}. A {@code requires <module>} applies only
     * to a module whose compiled classes refer to a package {@code <module>} exports: the catalog states
     * what an application USING an extension's API must declare, and a module that depends on the extension
     * only to extend it uses none of it (BUG-20261109-01 -- the first version of this check demanded five
     * directives of Vidocq's own cassini-rest dev panel, which imports nothing from Jakarta REST).
     *
     * @param modulesInUse the modules the compiled classes refer to, or {@code null} when that could not be
     *                     determined (no classes yet, or an unreadable module path) -- then no
     *                     {@code requires} is filtered out, so the check stays conservative
     */
    static Set<Requirement> applicableTo(Set<Requirement> required, File outputDirectory, Set<String> modulesInUse) {
        Set<Requirement> out = new LinkedHashSet<>();
        for (Requirement r : required) {
            boolean keep = switch (r.kind()) {
                case OPENS -> containsPackage(outputDirectory, r.name());
                case REQUIRES, REQUIRES_STATIC -> modulesInUse == null || modulesInUse.contains(r.name());
            };
            if (keep) {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * The modules whose exported packages the compiled classes under {@code outputDirectory} refer to, or
     * an empty set when the module has no compiled main code at all (a test-only module), and {@code null}
     * only when the scan itself failed -- an unreadable output directory or a module path that
     * {@link ModuleFinder} rejects -- so that a real error keeps the check conservative instead of silently
     * passing. The system modules are included, so a {@code requires static java.compiler} is kept exactly
     * for a module that really uses {@code javax.annotation.processing}.
     */
    static Set<String> modulesInUse(File outputDirectory, Set<Artifact> artifacts, Log log) {
        if (outputDirectory == null || !outputDirectory.isDirectory()) {
            // No compiled output: nothing in this module can use an extension's API.
            return Set.of();
        }
        Set<String> referenced;
        try {
            referenced = ReflectedPackages.referencedPackages(outputDirectory.toPath());
        } catch (IOException e) {
            log.debug("Vidocq: cannot scan " + outputDirectory + " for referenced packages: " + e);
            return null;
        }
        if (referenced.isEmpty()) {
            // An output directory without a single class file -- a test-only module, for instance.
            // Any class at all would at least reference java.lang, so this is "no main code", not a
            // failed scan: the module needs no directive (BUG-20261109-01).
            return Set.of();
        }
        List<Path> path = new ArrayList<>();
        for (Artifact artifact : artifacts) {
            File file = artifact.getFile();
            if (file != null && file.exists()) {
                path.add(file.toPath());
            }
        }
        Set<String> inUse = new TreeSet<>();
        try {
            ModuleFinder finder = ModuleFinder.compose(
                    ModuleFinder.of(path.toArray(new Path[0])), ModuleFinder.ofSystem());
            for (ModuleReference reference : finder.findAll()) {
                if (exports(reference).stream().anyMatch(referenced::contains)) {
                    inUse.add(reference.descriptor().name());
                }
            }
        } catch (FindException e) {
            log.debug("Vidocq: cannot read the module path to tell which modules are in use: " + e);
            return null;
        }
        return inUse;
    }

    /** The packages {@code reference} offers to any reader: all of them for an automatic module. */
    private static Set<String> exports(ModuleReference reference) {
        var descriptor = reference.descriptor();
        if (descriptor.isAutomatic()) {
            return descriptor.packages();
        }
        Set<String> exported = new TreeSet<>();
        descriptor.exports().stream()
                .filter(e -> !e.isQualified())
                .forEach(e -> exported.add(e.source()));
        return exported;
    }

    private static boolean containsPackage(File outputDirectory, String pkg) {
        return outputDirectory != null
                && new File(outputDirectory, pkg.replace('.', '/')).isDirectory();
    }

    /** The descriptor of {@code artifactFile}: empty when it has none, or when it cannot be read. */
    private static Properties readDescriptor(File artifactFile, Log log) {
        if (artifactFile == null || !artifactFile.exists()) {
            return new Properties();
        }
        try {
            if (artifactFile.isDirectory()) {
                // Reactor dependency resolved to target/classes (built in the same reactor).
                File resource = new File(artifactFile, ModuleRequirementsDescriptor.RESOURCE);
                if (!resource.isFile()) {
                    return new Properties();
                }
                try (InputStream in = Files.newInputStream(resource.toPath())) {
                    return load(in);
                }
            }
            try (JarFile jar = new JarFile(artifactFile)) {
                ZipEntry entry = jar.getEntry(ModuleRequirementsDescriptor.RESOURCE);
                if (entry == null) {
                    return new Properties();
                }
                try (InputStream in = jar.getInputStream(entry)) {
                    return load(in);
                }
            }
        } catch (IOException e) {
            log.debug("Vidocq: cannot read module-requirements descriptor from " + artifactFile + ": " + e);
            return new Properties();
        }
    }

    private static Properties load(InputStream in) throws IOException {
        Properties props = new Properties();
        props.load(in);
        return props;
    }
}
