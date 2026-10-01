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

import org.apache.maven.artifact.Artifact;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Which dependency jars {@code vidocq:generate} scans for CDI beans to generate code for, and why each other is left
 * out. Three sources select a jar: an extension that ships it, naming it in its manifest
 * ({@value #MANIFEST_ATTRIBUTE}); the application, in {@code <scanDependencies>}; and, unless turned off, any CDI
 * bean archive — a {@code META-INF/beans.xml} whose discovery mode is not {@code none}. A selected jar is left out
 * when it already carries generated code, when {@code <scanExcludes>} names it, or when it is signed and the
 * application did not name it: enriching it would break its signature.
 */
public final class ScanSelection {

    /** Manifest attribute by which a jar asks for others to be scanned: comma-separated patterns. */
    public static final String MANIFEST_ATTRIBUTE = "Vidocq-Scan-Dependencies";

    private static final Pattern DISCOVERY_MODE = Pattern.compile("bean-discovery-mode\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final List<String> PROCESSED_MARKERS = List.of("META-INF/vauban-bce-processed",
            "META-INF/vauban-beans.list", "META-INF/services/io.vidocq.vauban.api.VaubanComponentProvider");

    /** What selected a jar. */
    public enum Source {
        EXTENSION("declared by an extension"),
        APPLICATION("named in scanDependencies"),
        AUTOMATIC("a CDI bean archive");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** A dependency of the project: a jar, or a reactor module's classes directory. */
    public record Dependency(String groupId, String artifactId, String version, Path jar) {
        public String coordinates() {
            return groupId + ":" + artifactId + ":" + version;
        }
    }

    /**
     * What a jar's content says.
     *
     * @param discoveryMode the {@code bean-discovery-mode} of its {@code beans.xml}, {@code annotated} when absent
     * @param processed     it already carries Vauban-generated code (APT marker, bean list or provider service)
     * @param automatic     it has no module descriptor at its root
     * @param scanPatterns  the patterns its manifest asks to scan
     */
    public record JarFacts(boolean beansXml, String discoveryMode, boolean processed, boolean signed,
                           boolean automatic, List<String> scanPatterns) {}

    /**
     * What the selection decided for one dependency.
     *
     * @param source          what selected it, or {@code null} when nothing did
     * @param excludedBecause why it is left out although selected, or {@code null}
     */
    public record Decision(Dependency dependency, JarFacts facts, Source source, String excludedBecause) {
        public boolean selected() {
            return source != null && excludedBecause == null;
        }
    }

    private ScanSelection() {}

    /** The project's dependencies that are a jar or a reactor classes directory: a pom or a zip holds no bean. */
    public static List<Dependency> dependenciesOf(Collection<Artifact> artifacts) {
        List<Dependency> deps = new ArrayList<>();
        for (Artifact a : artifacts) {
            if (a.getFile() != null && isJarOrDirectory(a.getFile().toPath())) {
                deps.add(new Dependency(a.getGroupId(), a.getArtifactId(), a.getVersion(), a.getFile().toPath()));
            }
        }
        return deps;
    }

    /** Whether {@code file} is a directory or a {@code .jar}, the only dependency files a module path takes. */
    static boolean isJarOrDirectory(Path file) {
        return Files.isDirectory(file) || file.getFileName().toString().endsWith(".jar");
    }

    /** One decision per dependency, in their order. */
    public static List<Decision> decide(List<Dependency> dependencies, List<String> scanDependencies,
                                        List<String> scanExcludes, boolean autoScan) throws IOException {
        List<JarFacts> facts = new ArrayList<>();
        Set<String> extensionPatterns = new LinkedHashSet<>();
        for (Dependency dep : dependencies) {
            JarFacts f = inspect(dep.jar());
            facts.add(f);
            extensionPatterns.addAll(f.scanPatterns());
        }
        List<Decision> decisions = new ArrayList<>();
        for (int i = 0; i < dependencies.size(); i++) {
            Dependency dep = dependencies.get(i);
            JarFacts f = facts.get(i);
            Source source = anyMatch(scanDependencies, dep) ? Source.APPLICATION
                    : anyMatch(extensionPatterns, dep) ? Source.EXTENSION
                    : autoScan && f.beansXml() && !"none".equals(f.discoveryMode()) ? Source.AUTOMATIC
                    : null;
            String excluded = null;
            if (source != null) {
                if (f.processed()) {
                    excluded = "already carries generated code (Vauban APT or plugin)";
                } else if (anyMatch(scanExcludes, dep)) {
                    excluded = "listed in scanExcludes";
                } else if (f.signed() && source != Source.APPLICATION) {
                    excluded = "signed: enriching it would break its signature — name it in scanDependencies to"
                            + " enrich it anyway";
                }
            }
            decisions.add(new Decision(dep, f, source, excluded));
        }
        return decisions;
    }

    /** What the jar, or reactor classes directory, says about itself. */
    public static JarFacts inspect(Path jarOrDir) throws IOException {
        if (Files.isDirectory(jarOrDir)) {
            String beansXml = read(jarOrDir.resolve("META-INF/beans.xml"));
            boolean processed = PROCESSED_MARKERS.stream().anyMatch(m -> Files.exists(jarOrDir.resolve(m)));
            Path manifest = jarOrDir.resolve(JarFile.MANIFEST_NAME);
            List<String> patterns = List.of();
            if (Files.isRegularFile(manifest)) {
                try (var in = Files.newInputStream(manifest)) {
                    patterns = patterns(new Manifest(in));
                }
            }
            return new JarFacts(beansXml != null, discoveryMode(beansXml), processed, false,
                    !Files.isRegularFile(jarOrDir.resolve(EnrichedJars.MODULE_INFO)), patterns);
        }
        try (JarFile jar = new JarFile(jarOrDir.toFile(), false)) {
            JarEntry beans = jar.getJarEntry("META-INF/beans.xml");
            String beansXml = beans == null ? null
                    : new String(jar.getInputStream(beans).readAllBytes(), StandardCharsets.UTF_8);
            boolean processed = PROCESSED_MARKERS.stream().anyMatch(m -> jar.getEntry(m) != null);
            boolean signed = Collections.list(jar.entries()).stream()
                    .anyMatch(e -> EnrichedJars.isSignature(e.getName()) && !e.getName().toUpperCase(Locale.ROOT)
                            .startsWith("META-INF/SIG-"));
            return new JarFacts(beansXml != null, discoveryMode(beansXml), processed, signed,
                    jar.getEntry(EnrichedJars.MODULE_INFO) == null,
                    jar.getManifest() == null ? List.of() : patterns(jar.getManifest()));
        }
    }

    /** {@code groupId:artifactId}, either side optional or ending in {@code *}. */
    public static boolean matches(String pattern, String groupId, String artifactId) {
        int colon = pattern.indexOf(':');
        String g = colon >= 0 ? pattern.substring(0, colon) : pattern;
        String a = colon >= 0 ? pattern.substring(colon + 1) : "*";
        return token(g.strip(), groupId) && token(a.isBlank() ? "*" : a.strip(), artifactId);
    }

    private static boolean anyMatch(Collection<String> patterns, Dependency dep) {
        return patterns.stream().anyMatch(p -> matches(p, dep.groupId(), dep.artifactId()));
    }

    private static boolean token(String pattern, String value) {
        if ("*".equals(pattern)) {
            return true;
        }
        return pattern.endsWith("*") ? value.startsWith(pattern.substring(0, pattern.length() - 1))
                : pattern.equals(value);
    }

    private static String discoveryMode(String beansXml) {
        if (beansXml == null) {
            return null;
        }
        Matcher m = DISCOVERY_MODE.matcher(beansXml);
        return m.find() ? m.group(1).strip() : "annotated";
    }

    private static List<String> patterns(Manifest manifest) {
        String value = manifest.getMainAttributes().getValue(MANIFEST_ATTRIBUTE);
        return value == null ? List.of() : Stream.of(value.split(",")).map(String::strip)
                .filter(s -> !s.isEmpty()).toList();
    }

    private static String read(Path file) throws IOException {
        return Files.isRegularFile(file) ? Files.readString(file) : null;
    }
}
