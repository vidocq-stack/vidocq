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
package io.vidocq.runtime.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The stamp {@code vidocq:generate} writes into the application's classes, {@value #PATH}, recording the bean
 * index ({@value #INDEX}) it leaves there. That index holds the beans of the dependencies compiled without
 * Vauban's processor, which nothing else indexes. An application whose module lacks the stamp was built without
 * the goal, as an IDE builds it; one whose index no longer matches the stamp had it rewritten since, as an IDE's
 * incremental build rewrites it through the annotation processor. In both cases the boot scans the unindexed bean
 * archives itself (Vidocq/vidocq#241).
 */
public final class GenerateStamp {

    /** Where the stamp lives in the application's classes. */
    public static final String PATH = "META-INF/vidocq/generate.stamp";

    /** The bean index the stamp records. */
    public static final String INDEX = "META-INF/vauban-beans.list";

    private GenerateStamp() {
    }

    /**
     * The stamp for {@code index}, the bytes of {@value #INDEX} as {@code vidocq:generate} leaves it, or
     * {@code null} when it leaves none. It depends on them only, so that the build stays reproducible.
     */
    public static String content(byte[] index) {
        return "vidocq:generate\nindex=" + (index == null ? "none" : "sha256:" + sha256(index)) + "\n";
    }

    /**
     * Whether {@code application}, a named module, lacks the stamp, or holds an index the stamp does not record.
     * {@code null} or an unnamed module tells nothing, and answers {@code false}: the boot then changes nothing.
     */
    static boolean staleIn(Module application) {
        if (application == null || !application.isNamed()) {
            return false;
        }
        try {
            byte[] stamp = read(application, PATH);
            return stamp == null || !content(read(application, INDEX)).equals(new String(stamp, StandardCharsets.UTF_8));
        } catch (IOException _) {
            return false;
        }
    }

    /**
     * Whether {@code application}, a named module, has bean classes and none of the files Vauban's annotation
     * processor leaves (its index, its generated provider, its BCE marker): it was compiled without the processor, as
     * by an IDE whose annotation processing did not run, and its beans are lost, since a module without
     * {@code beans.xml} is in no scan. The processor leaves nothing for a module without beans, so those are told
     * from it by their annotations. Read only when the files are missing: a normal boot reads three resources.
     */
    static boolean beansWithoutIndex(Module application) {
        if (application == null || !application.isNamed() || application.getLayer() == null) {
            return false;
        }
        try {
            for (String marker : PROCESSOR_FILES) {
                try (InputStream in = application.getResourceAsStream(marker)) {
                    if (in != null) {
                        return false;
                    }
                }
            }
            var reference = application.getLayer().configuration().findModule(application.getName())
                    .map(java.lang.module.ResolvedModule::reference).orElse(null);
            if (reference == null) {
                return false;
            }
            try (var reader = reference.open(); var entries = reader.list()) {
                for (String entry : (Iterable<String>) entries::iterator) {
                    if (entry.endsWith(".class") && !entry.endsWith("module-info.class")) {
                        byte[] bytes = read(application, entry);
                        if (bytes != null && definesABean(bytes)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        } catch (IOException | RuntimeException _) {
            return false;
        }
    }

    /**
     * The modules to check with {@link #beansWithoutIndex}, each once: {@code application} when the launch names it,
     * and every exploded module (a directory of classes, as a build leaves {@code target/classes}) of
     * {@code applicationLayer}. An application started by the runtime's own main has no application module to name,
     * since none is guessed among its archives, but its own classes are the layer's directories.
     */
    static java.util.List<Module> beanModulesWithoutIndex(Module application, ModuleLayer applicationLayer) {
        var candidates = new java.util.LinkedHashSet<Module>();
        if (application != null) {
            candidates.add(application);
        }
        if (applicationLayer != null) {
            for (var resolved : applicationLayer.configuration().modules()) {
                boolean exploded = resolved.reference().location()
                        .filter(uri -> "file".equals(uri.getScheme()))
                        .map(uri -> java.nio.file.Files.isDirectory(java.nio.file.Path.of(uri)))
                        .orElse(false);
                if (exploded) {
                    applicationLayer.findModule(resolved.name()).ifPresent(candidates::add);
                }
            }
        }
        return candidates.stream().filter(GenerateStamp::beansWithoutIndex).toList();
    }

    /** The files Vauban's annotation processor leaves in a module it indexed. */
    static final java.util.List<String> PROCESSOR_FILES = java.util.List.of(INDEX, "META-INF/vauban-bce-processed",
            "META-INF/services/io.vidocq.vauban.api.VaubanComponentProvider");

    /** Annotations that make a class a bean, or a resource Cassini registers as one. */
    private static final java.util.List<String> BEAN_ANNOTATIONS = java.util.List.of("Ljakarta/enterprise/context/",
            "Ljakarta/inject/Singleton;", "Ljakarta/ws/rs/Path;", "Ljakarta/interceptor/Interceptor;",
            "Ljakarta/decorator/Decorator;");

    private static boolean definesABean(byte[] classFile) {
        var model = java.lang.classfile.ClassFile.of().parse(classFile);
        return model.findAttribute(java.lang.classfile.Attributes.runtimeVisibleAnnotations())
                .map(attribute -> attribute.annotations().stream().anyMatch(annotation -> {
                    String type = annotation.className().stringValue();
                    return BEAN_ANNOTATIONS.stream().anyMatch(type::startsWith);
                }))
                .orElse(false);
    }

    private static byte[] read(Module module, String path) throws IOException {
        try (InputStream in = module.getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a mandatory JDK algorithm", e);
        }
    }
}
