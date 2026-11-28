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
package io.vidocq.runtime.tooling;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * The catalog of first-party Vidocq extensions built into the CLI and the plugin, and the resolver
 * that turns a user-supplied id into a Maven {@link ExtensionCoordinate}.
 *
 * <p>This catalog is what {@code extension list --available} lists, by family, and the lookup table that lets
 * {@code extension add <short-id>} inject the correct (category-specific) groupId. A remote catalog, for
 * extensions released apart from the runtime, belongs with Vidocq/vidocq#17.</p>
 *
 * <p>The extensions describe themselves: each ships {@value #DESCRIPTOR}, and the
 * {@value #CATALOG_RESOURCE} this class reads is built from those descriptors and
 * checked against them by {@code ExtensionCatalogTest} (vidocq#314).</p>
 */
public final class KnownExtensions {

    private KnownExtensions() {}

    /** The descriptor each first-party extension ships: its short id, description, family, codegen bundle, directives. */
    static final String DESCRIPTOR = "META-INF/vidocq/extension.properties";

    /** The catalog built from those descriptors (see {@code ExtensionCatalogTest}). */
    static final String CATALOG_RESOURCE = "META-INF/vidocq/extension-catalog.properties";

    private static final String ARTIFACT_ID = ".artifactId";
    private static final String OPENS = "opens ";
    private static final String FORMER_IDS = "former-ids";

    /** Every catalog key, prefixed by the extension's short id, e.g. {@code health-knock.groupId}. */
    private static final Properties DESCRIPTORS = load();

    private static final List<RegistryEntry> CATALOG = DESCRIPTORS.stringPropertyNames().stream()
            .filter(key -> key.endsWith(ARTIFACT_ID))
            .map(key -> key.substring(0, key.length() - ARTIFACT_ID.length()))
            .sorted()
            .map(id -> new RegistryEntry(id, property(id, "groupId"), property(id, "artifactId"),
                    property(id, "description")))
            .toList();

    private static Properties load() {
        Properties props = new Properties();
        try (InputStream in = KnownExtensions.class.getResourceAsStream("/" + CATALOG_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(CATALOG_RESOURCE + " is missing from the Vidocq CLI");
            }
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + CATALOG_RESOURCE, e);
        }
        return props;
    }

    private static String property(String id, String key) {
        return DESCRIPTORS.getProperty(id + "." + key, "").strip();
    }

    private static List<String> csv(String value) {
        List<String> out = new ArrayList<>();
        for (String token : value.split(",", -1)) {
            if (!token.isBlank()) {
                out.add(token.strip());
            }
        }
        return out;
    }

    /**
     * The ids of a comma-separated list, stripped, blanks dropped: how {@code -Dextensions=a,b},
     * {@code vidocq create --extension a,b} and {@code vidocq extension add a,b} read one (Vidocq/vidocq#779).
     * Empty for {@code null}.
     */
    public static List<String> splitIds(String commaSeparated) {
        if (commaSeparated == null) {
            return List.of();
        }
        return Arrays.stream(commaSeparated.split(",")).map(String::strip).filter(id -> !id.isEmpty()).toList();
    }

    /** The immutable built-in catalog, sorted by id. */
    public static List<RegistryEntry> catalog() {
        return CATALOG;
    }

    /**
     * The catalog by family, in the order of {@link ExtensionFamily}, each family sorted by id (Vidocq/vidocq#353).
     * Every family is a key, even one without an extension.
     */
    public static Map<ExtensionFamily, List<RegistryEntry>> catalogByFamily() {
        Map<ExtensionFamily, List<RegistryEntry>> byFamily = new EnumMap<>(ExtensionFamily.class);
        for (ExtensionFamily family : ExtensionFamily.values()) {
            byFamily.put(family, new ArrayList<>());
        }
        for (RegistryEntry e : CATALOG) {
            byFamily.get(ExtensionFamily.of(property(e.id(), "family"))).add(e);
        }
        byFamily.replaceAll((family, entries) -> List.copyOf(entries));
        return Collections.unmodifiableMap(byFamily);
    }

    /**
     * Looks up a catalog entry by its short id, or by an id it had before (case-insensitive): {@code knock-health}
     * still names {@code health-knock} (Vidocq/vidocq#809).
     */
    public static Optional<RegistryEntry> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String needle = id.trim();
        return CATALOG.stream()
                .filter(e -> e.id().equalsIgnoreCase(needle))
                .findFirst()
                .or(() -> CATALOG.stream()
                        .filter(e -> csv(property(e.id(), FORMER_IDS)).stream().anyMatch(needle::equalsIgnoreCase))
                        .findFirst());
    }

    /**
     * The catalog id {@code id} names: the extension's own id for one of its ids, former ones included, and for a
     * function only one catalog id starts with ({@code health} names {@code health-knock}); otherwise {@code id}
     * itself, trimmed, as an id outside the catalog or a {@code groupId:artifactId} is written (Vidocq/vidocq#809).
     */
    public static String canonicalId(String id) {
        if (id == null) {
            return null;
        }
        String trimmed = id.trim();
        String prefix = trimmed.toLowerCase(Locale.ROOT) + "-";
        Optional<RegistryEntry> known = byId(trimmed);
        if (known.isPresent()) {
            return known.get().id();
        }
        List<String> starting = CATALOG.stream().map(RegistryEntry::id).filter(c -> c.startsWith(prefix)).toList();
        return starting.size() == 1 ? starting.getFirst() : trimmed;
    }

    /** {@link #canonicalId(String)} of each of {@code ids}, in order, each once. */
    public static List<String> canonicalIds(Collection<String> ids) {
        return List.copyOf(new LinkedHashSet<>(ids.stream().map(KnownExtensions::canonicalId).toList()));
    }

    /**
     * The ids the extension {@code id} names had before its own (Vidocq/vidocq#809): its
     * {@code module-info.java} lines may still be marked with one. Empty outside the catalog.
     */
    public static List<String> formerIds(String id) {
        return byId(id).map(e -> csv(property(e.id(), FORMER_IDS))).orElse(List.of());
    }

    /**
     * Resolves a user-supplied id to a coordinate:
     * <ol>
     *   <li>a known catalog short id, a former one, or a function only one id starts with
     *       ({@link #canonicalId(String)}) → its (category-specific) coordinate;</li>
     *   <li>an explicit {@code groupId:artifactId} → parsed verbatim.</li>
     * </ol>
     * Any other id is refused, naming the closest catalog ids: every Vidocq extension is in the catalog, under a
     * groupId of its category, so a coordinate guessed from the id could only be wrong (BUG-20261024-04).
     *
     * @throws IllegalArgumentException for a blank id, or a short id outside the catalog
     */
    public static ExtensionCoordinate resolve(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("extension id must not be blank");
        }
        String trimmed = id.trim();
        if (trimmed.contains(":")) {
            return ExtensionCoordinate.parse(trimmed);
        }
        return byId(canonicalId(trimmed)).map(RegistryEntry::coordinate).orElseThrow(() -> unknown(trimmed));
    }

    private static IllegalArgumentException unknown(String id) {
        List<String> close = closest(id);
        return new IllegalArgumentException("not in the Vidocq catalog."
                + (close.isEmpty() ? "" : " Did you mean: " + String.join(", ", close) + "?")
                + " An extension outside the catalog is added as groupId:artifactId.");
    }

    /** The catalog ids sharing a word with {@code id}, or within two edits of it or of a former id. */
    private static List<String> closest(String id) {
        String needle = id.toLowerCase(Locale.ROOT);
        Set<String> words = Set.of(needle.split("-"));
        return CATALOG.stream().map(RegistryEntry::id)
                .filter(candidate -> Arrays.stream(candidate.split("-")).anyMatch(words::contains)
                        || distance(needle, candidate) <= 2
                        || formerIds(candidate).stream().anyMatch(former -> distance(needle, former) <= 2))
                .toList();
    }

    /** The Levenshtein distance between {@code a} and {@code b}. */
    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j], current[j - 1]) + 1);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    /** The catalog id of the extension at {@code coordinate}, else its artifactId. */
    public static String idOf(ExtensionCoordinate coordinate) {
        return CATALOG.stream()
                .filter(e -> e.coordinate().equals(coordinate))
                .map(RegistryEntry::id)
                .findFirst()
                .orElse(coordinate.artifactId());
    }

    /** Whether {@code id} maps to a known first-party extension. */
    public static boolean isKnown(String id) {
        return byId(id).isPresent();
    }

    /**
     * The APT codegen bundle that MUST sit on the compiler's
     * {@code annotationProcessorPaths} when the matching extension is on the
     * dependencies (enforced by {@code vidocq:checkpom}). It shares the extension's
     * groupId. Empty for extensions that do not ship one.
     */
    public static Optional<ExtensionCoordinate> codegenBundle(String id) {
        return byId(id).flatMap(e -> Optional.of(property(e.id(), "codegen"))
                .filter(bundle -> !bundle.isEmpty())
                .map(bundle -> new ExtensionCoordinate(e.groupId(), bundle)));
    }

    /**
     * The catalog extensions that go with {@code declared}: each one whose descriptor's {@code companion-of} lists
     * extensions all declared, and that is not declared itself, sorted. The JDBC spans glue goes with Telemetry and
     * the pool (BUG-20261030-25).
     */
    public static List<String> companions(Collection<String> declared) {
        List<String> ids = canonicalIds(declared);
        return CATALOG.stream()
                .map(RegistryEntry::id)
                .filter(id -> !ids.contains(id))
                .filter(id -> {
                    List<String> of = companionOf(id);
                    return !of.isEmpty() && ids.containsAll(of);
                })
                .toList();
    }

    /**
     * The extensions {@code id} cannot work without, from its descriptor's {@code requires-extensions}: Mansart Data
     * reads the pool's DataSource. Empty for most.
     */
    public static List<String> requiredExtensions(String id) {
        return byId(id).map(e -> csv(property(e.id(), "requires-extensions"))).orElse(List.of());
    }

    /** {@code ids} with the extensions they require, transitively, each required one before the first that needs it. */
    public static List<String> withRequired(Collection<String> ids) {
        Set<String> ordered = new LinkedHashSet<>();
        for (String id : canonicalIds(ids)) {
            addWithRequired(id, ordered);
        }
        return List.copyOf(ordered);
    }

    private static void addWithRequired(String id, Set<String> ordered) {
        if (ordered.contains(id)) {
            return;
        }
        for (String required : requiredExtensions(id)) {
            addWithRequired(required, ordered);
        }
        ordered.add(id);
    }

    /** The extensions {@code id} goes with, from its descriptor's {@code companion-of}; empty for most. */
    public static List<String> companionOf(String id) {
        return byId(id).map(e -> csv(property(e.id(), "companion-of"))).orElse(List.of());
    }

    /**
     * The dev service that provides what {@code id} connects to under {@code vidocq dev}, {@code vidocq test} and
     * {@code mvn test}, from its descriptor's {@code devservice}: an {@code io.vidocq.runtime} artifact
     * (BUG-20261031-05). Empty for most extensions.
     */
    public static Optional<ExtensionCoordinate> devService(String id) {
        return byId(id).map(e -> property(e.id(), "devservice")).filter(artifact -> !artifact.isEmpty())
                .map(artifact -> new ExtensionCoordinate("io.vidocq.runtime", artifact));
    }

    /**
     * Whether the dev service of {@code id} also starts under {@code mvn test}, from the descriptor's
     * {@code devservice.in-tests}: {@code true} unless it says {@code false}, as Telemetry's collector does.
     */
    public static boolean devServiceInTests(String id) {
        return byId(id).map(e -> !"false".equalsIgnoreCase(property(e.id(), "devservice.in-tests"))).orElse(false);
    }

    /**
     * The JDBC driver that dev service starts on, from the descriptor's {@code devservice.driver}, as
     * {@code groupId:artifactId:version} (BUG-20261031-06); empty for an extension without one.
     */
    public static Optional<String> devServiceDriver(String id) {
        return byId(id).map(e -> property(e.id(), "devservice.driver")).filter(driver -> !driver.isEmpty());
    }

    /**
     * The lines {@code id} adds to the application's {@code vidocq.properties}, from its descriptor's
     * {@code config.<NN>} keys in their order; {@code {app}} stands for the project's artifactId
     * (BUG-20261031-09). Empty for most extensions.
     */
    public static List<String> configLines(String id) {
        return byId(id).map(e -> DESCRIPTORS.stringPropertyNames().stream()
                        .filter(key -> key.startsWith(e.id() + ".config."))
                        .sorted()
                        .map(key -> DESCRIPTORS.getProperty(key).strip())
                        .toList())
                .orElse(List.of());
    }

    /** The module a catalog extension ships, which an application {@code requires}; empty outside the catalog. */
    public static Optional<String> moduleName(String id) {
        return byId(id).map(e -> property(e.id(), "module")).filter(module -> !module.isEmpty());
    }

    /**
     * The packages of the application's resources that {@code id} reads and that its module must open once they
     * exist, {@code db.migration} for Flyway and {@code db.changelog} for Liquibase (BUG-20261030-11); empty for an
     * id outside the catalog.
     */
    public static List<String> resourcePackagesToOpen(String id) {
        return byId(id).map(e -> csv(property(e.id(), "module-info.opens-resource-package"))).orElse(List.of());
    }

    /**
     * The modules an application must {@code requires} <em>besides</em> the extension's own module, because
     * {@code id} does not re-export them -- Cassini does not re-export Jakarta REST. Empty for an id outside
     * the catalog.
     *
     * <p>The {@code module-info.requires.static} entries are not part of this list: they are a separate
     * key with a weaker directive, served by {@link #moduleRequiresStatic(String)}.</p>
     */
    public static List<String> moduleRequires(String id) {
        return byId(id).map(e -> csv(property(e.id(), "module-info.requires"))).orElse(List.of());
    }

    /**
     * The modules {@code id} needs its consumer to declare as {@code requires static}: needed to
     * compile against the extension's generated sources, absent at run time. Empty for an id outside
     * the catalog. Two ids use the key today ({@code rest-cassini} and {@code data-mansart}, both
     * {@code java.compiler}, for the {@code @Generated} annotation).
     */
    public static List<String> moduleRequiresStatic(String id) {
        return byId(id).map(e -> csv(property(e.id(), "module-info.requires.static"))).orElse(List.of());
    }

    /**
     * The {@code module-info.java} directives (without the trailing {@code ;}) an application
     * needs to use {@code id}: its module, which re-exports most extension APIs transitively,
     * plus what the extension's descriptor says it leaves to the application — Cassini does not
     * re-export Jakarta REST, and its and Mansart Data's generated sources import {@code @Generated}.
     * {@code opens} is added only for an existing {@code applicationPackage}, when the descriptor
     * asks for it (JAX-RS, JSON-B and Jakarta Persistence reflect on it), and for each of the
     * {@link #resourcePackagesToOpen resource packages} the extension reads, whose folder the tooling
     * creates with the directive (BUG-20261031-01, -03). Empty for an id outside the catalog.
     */
    public static List<String> moduleDirectives(String id, String applicationPackage) {
        Optional<String> module = moduleName(id);
        if (module.isEmpty()) {
            return List.of();
        }
        String key = byId(id).orElseThrow().id();
        List<String> directives = new ArrayList<>();
        csv(property(key, "module-info.requires.static")).forEach(m -> directives.add("requires static " + m));
        directives.add("requires " + module.get());
        csv(property(key, "module-info.requires")).forEach(m -> directives.add("requires " + m));
        if (Boolean.parseBoolean(property(key, "module-info.opens-application-package"))
                && applicationPackage != null && !applicationPackage.isBlank()) {
            directives.add(OPENS + applicationPackage);
        }
        resourcePackagesToOpen(id).forEach(p -> directives.add(OPENS + p));
        return List.copyOf(directives);
    }

    /**
     * {@link #moduleDirectives(String, String)}, plus the {@code opens} of each folder below a resource package of
     * {@code id} that holds resources under {@code resources} ({@link ResourcePackages}): the build check asks for
     * them, so doctor and its fix do too (BUG-20261115-10).
     *
     * @param resources the project's {@code src/main/resources}, or {@code null}
     */
    public static List<String> moduleDirectives(String id, String applicationPackage, java.nio.file.Path resources) {
        List<String> directives = new ArrayList<>(moduleDirectives(id, applicationPackage));
        if (!directives.isEmpty()) {
            resourcePackagesToOpen(id).forEach(p -> ResourcePackages.below(resources, p)
                    .forEach(sub -> directives.add(OPENS + sub)));
        }
        return List.copyOf(directives);
    }
}
