package io.vidocq.runtime.core;

import io.vidocq.vauban.classloader.VaubanClassLoader;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.FindException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Which modules of a launch stay in the parent layer and which form the application layer, when the application
 * layer holds the application's dependencies ({@value #MODE_PROPERTY}{@code =all}, Vidocq/vidocq#247).
 *
 * <p>The parent layer is the closure of the runtime: the platform, Jakarta and MicroProfile APIs and the Vidocq
 * modules by name, every module that provides a Vidocq extension, every module with a package the Vauban loader
 * refuses to define, every pinned module, and, transitively, what they {@code requires}. Everything else is the
 * application's, automatic modules included: a framework that looks things up through its own class loader then
 * sees the application's classes and resources, as in a servlet container's {@code WEB-INF/lib}.
 *
 * <p>One rule for every launch shape: the Maven plugin applies it at build time, {@link VidocqAppLayer} applies it
 * to the boot layer of an IDE launch.
 */
public final class LayerSplit {

    /** {@code classes} (the project's classes only) or {@code all} (this rule). */
    public static final String MODE_PROPERTY = "vidocq.app.layer";
    /** Comma-separated patterns of modules the application keeps in the parent layer. */
    public static final String KEEP_PROPERTY = "vidocq.app.layer.keep";
    /** The pinned modules and their reasons, as the build computed them, for the startup report. */
    public static final String PINNED_PROPERTY = "vidocq.app.layer.pinned";
    /** Manifest attribute by which a jar pins modules to the parent layer: comma-separated patterns. */
    public static final String PARENT_LAYER_ATTRIBUTE = "Vidocq-Parent-Layer";

    private static final String EXTENSION_SERVICE = "io.vidocq.runtime.spi.VidocqExtension";
    private static final List<String> RUNTIME_PREFIXES = List.of(
            "java.", "jdk.", "jakarta.", "org.eclipse.microprofile.", "io.vidocq.");

    /** The value of {@value #MODE_PROPERTY}. */
    public enum Mode {
        CLASSES, ALL;

        /** {@code classes} when {@code value} is {@code null} or blank. */
        public static Mode of(String value) {
            String v = value == null ? "" : value.strip();
            return switch (v) {
                case "", "classes" -> CLASSES;
                case "all" -> ALL;
                default -> throw new IllegalArgumentException(
                        MODE_PROPERTY + " must be classes or all, not '" + value + "'");
            };
        }
    }

    /**
     * One module of the launch.
     *
     * @param descriptor          its descriptor, derived for an automatic module
     * @param location            the jar or directory it comes from
     * @param coordinates         {@code groupId:artifactId}, or {@code null} when unknown (an IDE launch)
     * @param parentLayerPatterns the patterns of its {@value #PARENT_LAYER_ATTRIBUTE} manifest attribute
     */
    public record Candidate(ModuleDescriptor descriptor, Path location, String coordinates,
                            List<String> parentLayerPatterns) {
        public Candidate {
            parentLayerPatterns = List.copyOf(parentLayerPatterns);
        }
    }

    /**
     * The split.
     *
     * @param parent      the modules of the parent layer, in the candidates' order
     * @param application the modules of the application layer, in the candidates' order
     * @param pinned      each pinned or forced module and why, in the candidates' order
     */
    public record Result(List<Candidate> parent, List<Candidate> application, Map<String, String> pinned) {
        public Result {
            parent = List.copyOf(parent);
            application = List.copyOf(application);
            pinned = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(pinned));
        }

        public List<Path> parentPaths() {
            return parent.stream().map(Candidate::location).toList();
        }

        public List<Path> applicationPaths() {
            return application.stream().map(Candidate::location).toList();
        }

        public Set<String> parentNames() {
            Set<String> names = new LinkedHashSet<>();
            parent.forEach(c -> names.add(c.descriptor().name()));
            return names;
        }
    }

    private LayerSplit() {}

    /**
     * Splits {@code candidates}, with no declared dependencies for the automatic modules.
     *
     * @see #split(List, Set, List, Map, Map)
     */
    public static Result split(List<Candidate> candidates, Set<String> applicationModules,
                               List<String> keepPatterns, Map<String, String> forced) {
        return split(candidates, applicationModules, keepPatterns, forced, Map.of());
    }

    /**
     * Splits {@code candidates}.
     *
     * @param applicationModules the application's own modules: never kept by a rule
     * @param keepPatterns       the patterns of {@value #KEEP_PROPERTY}
     * @param forced             modules the caller must keep, with the reason
     * @param declaredReads      for a module name, the names of the modules it depends on according to the build
     *                           tool (Maven). An automatic module's descriptor names no dependency, so without
     *                           this a kept automatic module would lose the libraries it needs; every kept module's
     *                           declared reads are followed, in addition to the {@code requires} of an explicit one.
     *                           Names that are not candidates are ignored.
     * @throws IllegalStateException when a kept module reads a module of {@code applicationModules}
     */
    public static Result split(List<Candidate> candidates, Set<String> applicationModules,
                               List<String> keepPatterns, Map<String, String> forced,
                               Map<String, Set<String>> declaredReads) {
        Map<String, Candidate> byName = new LinkedHashMap<>();
        candidates.forEach(c -> byName.put(c.descriptor().name(), c));
        Map<String, String> pinned = new LinkedHashMap<>();
        Map<String, String> seedReason = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            String name = c.descriptor().name();
            if (applicationModules.contains(name)) {
                continue;
            }
            String pin = pinReason(c, candidates, keepPatterns, forced);
            if (pin != null) {
                pinned.put(name, pin);
                seedReason.put(name, pin);
            } else if (isRuntime(c.descriptor())) {
                seedReason.put(name, "a runtime module");
            }
        }
        Set<String> kept = new LinkedHashSet<>(seedReason.keySet());
        record Visit(String module, String seed) {}
        var queue = new ArrayDeque<Visit>();
        seedReason.keySet().forEach(n -> queue.add(new Visit(n, n)));
        while (!queue.isEmpty()) {
            Visit visit = queue.poll();
            ModuleDescriptor d = byName.get(visit.module()).descriptor();
            Set<String> reads = new LinkedHashSet<>();
            if (!d.isAutomatic()) {
                d.requires().forEach(r -> reads.add(r.name()));
            }
            reads.addAll(declaredReads.getOrDefault(visit.module(), Set.of()));
            for (String read : reads) {
                if (applicationModules.contains(read)) {
                    String reason = seedReason.get(visit.seed());
                    if (!visit.module().equals(visit.seed())) {
                        reason += ", through " + visit.seed();
                    }
                    throw new IllegalStateException(visit.module() + " is kept in the parent layer (" + reason
                            + ") but reads " + read
                            + ", a module of the application: a parent-layer module cannot read the application"
                            + " layer. Remove the pin, or move the code it needs out of " + read + ".");
                }
                if (byName.containsKey(read) && kept.add(read)) {
                    queue.add(new Visit(read, visit.seed()));
                }
            }
        }
        List<Candidate> parent = new ArrayList<>();
        List<Candidate> application = new ArrayList<>();
        for (Candidate c : candidates) {
            (kept.contains(c.descriptor().name()) ? parent : application).add(c);
        }
        return new Result(parent, application, pinned);
    }

    /** The module of {@code jarOrDir}, with its {@value #PARENT_LAYER_ATTRIBUTE} patterns. */
    public static Candidate candidate(Path jarOrDir, String coordinates) throws IOException {
        ModuleDescriptor descriptor;
        try {
            descriptor = ModuleFinder.of(jarOrDir).findAll().stream().findFirst()
                    .orElseThrow(() -> new IOException("No Java module in " + jarOrDir))
                    .descriptor();
        } catch (FindException e) {
            throw new IOException("Cannot read the module of " + jarOrDir.getFileName() + ": " + e.getMessage(), e);
        }
        return new Candidate(descriptor, jarOrDir, coordinates, patterns(manifestValue(jarOrDir)));
    }

    /** The non-blank entries of a comma-separated list; empty for {@code null}. */
    public static List<String> patterns(String commaSeparated) {
        if (commaSeparated == null) {
            return List.of();
        }
        return Stream.of(commaSeparated.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    /** {@code module=reason;module=reason}: neither a module name nor a reason holds {@code ;} or {@code =}. */
    public static String encodePinned(Map<String, String> pinned) {
        StringBuilder out = new StringBuilder();
        pinned.forEach((module, reason) -> {
            if (!out.isEmpty()) {
                out.append(';');
            }
            out.append(module).append('=').append(reason);
        });
        return out.toString();
    }

    /** The inverse of {@link #encodePinned}; empty for {@code null} or blank. */
    public static Map<String, String> decodePinned(String encoded) {
        Map<String, String> pinned = new LinkedHashMap<>();
        if (encoded == null || encoded.isBlank()) {
            return pinned;
        }
        for (String entry : encoded.split(";")) {
            int eq = entry.indexOf('=');
            if (eq > 0) {
                pinned.put(entry.substring(0, eq).strip(), entry.substring(eq + 1).strip());
            }
        }
        return pinned;
    }

    private static boolean isRuntime(ModuleDescriptor d) {
        for (String prefix : RUNTIME_PREFIXES) {
            if (d.name().startsWith(prefix)) {
                return true;
            }
        }
        for (ModuleDescriptor.Provides p : d.provides()) {
            if (p.service().equals(EXTENSION_SERVICE)) {
                return true;
            }
        }
        for (String pkg : d.packages()) {
            if (VaubanClassLoader.excludesPackage(pkg)) {
                return true;
            }
        }
        return false;
    }

    private static String pinReason(Candidate c, Collection<Candidate> all, List<String> keepPatterns,
                                    Map<String, String> forced) {
        String name = c.descriptor().name();
        if (forced.containsKey(name)) {
            return forced.get(name);
        }
        for (Candidate declaring : all) {
            for (String pattern : declaring.parentLayerPatterns()) {
                if (matches(pattern, c)) {
                    return PARENT_LAYER_ATTRIBUTE + " of " + declaring.descriptor().name();
                }
            }
        }
        for (String pattern : keepPatterns) {
            if (matches(pattern, c)) {
                return KEEP_PROPERTY;
            }
        }
        return null;
    }

    static boolean matches(String pattern, Candidate c) {
        int colon = pattern.indexOf(':');
        if (colon < 0) {
            return token(pattern.strip(), c.descriptor().name());
        }
        if (c.coordinates() == null) {
            return false;
        }
        int own = c.coordinates().indexOf(':');
        if (own < 0) {
            return false;
        }
        String group = c.coordinates().substring(0, own);
        String artifact = c.coordinates().substring(own + 1);
        String a = pattern.substring(colon + 1).strip();
        return token(pattern.substring(0, colon).strip(), group) && token(a.isEmpty() ? "*" : a, artifact);
    }

    private static boolean token(String pattern, String value) {
        if ("*".equals(pattern)) {
            return true;
        }
        return pattern.endsWith("*") ? value.startsWith(pattern.substring(0, pattern.length() - 1))
                : pattern.equals(value);
    }

    private static String manifestValue(Path jarOrDir) throws IOException {
        if (Files.isDirectory(jarOrDir)) {
            Path file = jarOrDir.resolve(JarFile.MANIFEST_NAME);
            if (!Files.isRegularFile(file)) {
                return null;
            }
            try (InputStream in = Files.newInputStream(file)) {
                return new Manifest(in).getMainAttributes().getValue(PARENT_LAYER_ATTRIBUTE);
            }
        }
        try (JarFile jar = new JarFile(jarOrDir.toFile(), false)) {
            Manifest manifest = jar.getManifest();
            return manifest == null ? null : manifest.getMainAttributes().getValue(PARENT_LAYER_ATTRIBUTE);
        }
    }
}
