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

import io.vidocq.ibarra.compiler.GenerationOptions;
import io.vidocq.ibarra.compiler.JspConfigurationParser;
import io.vidocq.ibarra.compiler.JspPropertyGroup;
import io.vidocq.ibarra.compiler.PagePrecompiler;

import java.io.IOException;
import java.lang.module.FindException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Compiles the application's JSP pages with Ibarra during {@code vidocq:generate} (vidocq#232).
 *
 * <p>A runtime application keeps its pages where Foy serves its web resources, under
 * {@code META-INF/resources/}, the pages only a forward reaches under {@code WEB-INF/}, and its descriptor at
 * {@code WEB-INF/web.xml} (else {@code META-INF/web.xml}).
 * They are staged as a web root under {@code target/ibarra-web} and compiled into the application module,
 * whose descriptor Ibarra completes with {@code provides io.vidocq.ibarra.spi.PageIndex}. It runs before
 * {@link PeanoAccessorRegistration}, which keeps that service when it rewrites the descriptor.
 */
final class IbarraPagePrecompilation {

    static final String API = "io.vidocq.ibarra.api";
    private static final String RESOURCES = "META-INF/resources";
    private static final String WEB_INF = "WEB-INF";

    private IbarraPagePrecompilation() {}

    /**
     * Compiles the pages of {@code classes} against {@code modulePath}, the application's dependencies.
     *
     * @param warn told when pages exist but the application module does not require {@value #API}
     * @return the number of pages compiled; 0 when there is none, or when they are not compiled
     * @throws IOException when a page does not translate or compile
     */
    static int apply(Path classes, List<Path> modulePath, Consumer<String> warn) throws IOException {
        Path resources = classes.resolve(RESOURCES);
        var pages = new ArrayList<>(pages(resources, ""));
        pages.addAll(pages(classes.resolve(WEB_INF), "/WEB-INF"));
        if (pages.isEmpty()) return 0;
        Path descriptorFile = classes.resolve("module-info.class");
        if (!Files.isRegularFile(descriptorFile)) {
            warn.accept(pages.size() + " JSP page(s) are not compiled: the application has no "
                    + "module-info; Ibarra compiles pages into a named module that requires " + API);
            return 0;
        }
        ModuleDescriptor descriptor;
        try (var in = Files.newInputStream(descriptorFile)) {
            descriptor = ModuleDescriptor.read(in);
        }
        boolean readsApi = descriptor.requires().stream().anyMatch(r -> r.name().equals(API)
                && !r.modifiers().contains(ModuleDescriptor.Requires.Modifier.STATIC));
        if (!readsApi) {
            warn.accept(pages.size() + " JSP page(s) are not compiled, and Foy serves no JSP "
                    + "engine for them: add the Ibarra extension and `requires " + API + ";` to module "
                    + descriptor.name() + " (first: " + pages.getFirst() + ")");
            return 0;
        }
        Path web = stage(classes, resources);
        Path webXml = web.resolve("WEB-INF/web.xml");
        List<JspPropertyGroup> groups = Files.isRegularFile(webXml)
                ? new JspConfigurationParser().parse("/WEB-INF/web.xml", Files.readAllBytes(webXml))
                : List.of();
        try {
            var result = new PagePrecompiler().precompile(web, classes, explicitModules(modulePath), null,
                    new GenerationOptions(groups, Map.of()));
            return result.pagePaths().size();
        } catch (RuntimeException failure) {
            throw new IOException("Cannot compile the JSP pages of module " + descriptor.name() + ": "
                    + failure.getMessage(), failure);
        }
    }

    /** The JSP pages under {@code root}, as web paths under {@code prefix}. */
    private static List<String> pages(Path root, String prefix) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".jsp") || f.toString().endsWith(".jspx"))
                    .map(f -> prefix + "/" + root.relativize(f).toString().replace('\\', '/'))
                    .sorted().toList();
        }
    }

    /**
     * A web root under {@code target/ibarra-web}: the web resources, then the {@code WEB-INF} of the classes
     * root, and {@code META-INF/web.xml} as {@code WEB-INF/web.xml} when there is no other.
     */
    private static Path stage(Path classes, Path resources) throws IOException {
        Path web = classes.toAbsolutePath().getParent().resolve("ibarra-web");
        delete(web);
        Files.createDirectories(web);
        if (Files.isDirectory(resources)) copyTree(resources, web);
        Path webInf = classes.resolve(WEB_INF);
        if (Files.isDirectory(webInf)) copyTree(webInf, web.resolve(WEB_INF));
        Path metaWebXml = classes.resolve("META-INF/web.xml");
        Path stagedWebXml = web.resolve("WEB-INF/web.xml");
        if (!Files.exists(stagedWebXml) && Files.isRegularFile(metaWebXml)) {
            Files.createDirectories(stagedWebXml.getParent());
            Files.copy(metaWebXml, stagedWebXml);
        }
        return web;
    }

    /**
     * The explicit modules of {@code paths}, first of a name wins: Ibarra refuses automatic modules, and a jar
     * without a descriptor cannot be read by the application module anyway.
     */
    static List<Path> explicitModules(List<Path> paths) {
        var names = new HashSet<String>();
        var out = new ArrayList<Path>();
        for (Path path : paths) {
            try {
                for (var reference : ModuleFinder.of(path).findAll()) {
                    if (!reference.descriptor().isAutomatic() && names.add(reference.descriptor().name())) out.add(path);
                }
            } catch (FindException unreadable) {
                // Not a module Ibarra can use: leave it out.
            }
        }
        return out;
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path target = to.resolve(from.relativize(file).toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void delete(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
        }
    }
}
