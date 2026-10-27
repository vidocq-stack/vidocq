package io.vidocq.runtime.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which modules stay in the parent layer and which move to the application layer (spec §1, §2; test 6). */
@DisplayName("LayerSplit — the closure of the runtime stays, the rest is the application's")
class LayerSplitTest {

    private static final String EXTENSION = "io.vidocq.runtime.spi.VidocqExtension";

    @Test
    @DisplayName("an automatic module goes to the application layer; a library a brick reads stays")
    void automaticMovesBrickDependencyStays() {
        var core = explicit("io.vidocq.runtime.core", "acme.codec");
        var codec = explicit("acme.codec");
        var vaadin = automatic("flow.server");
        var result = LayerSplit.split(List.of(core, codec, vaadin), Set.of("acme.app"), List.of(), Map.of());

        assertEquals(List.of("io.vidocq.runtime.core", "acme.codec"), names(result.parent()));
        assertEquals(List.of("flow.server"), names(result.application()));
        assertEquals(Map.of(), result.pinned(), "name rules and closures are not pins");
    }

    @Test
    @DisplayName("a module providing a Vidocq extension is a runtime root, whatever its name")
    void aThirdPartyExtensionIsARoot() {
        var ext = new LayerSplit.Candidate(ModuleDescriptor.newModule("org.acme.vidocq.ext")
                .requires("acme.client").packages(Set.of("org.acme.vidocq.ext"))
                .provides(EXTENSION, List.of("org.acme.vidocq.ext.Ext")).build(),
                Path.of("/libs/org.acme.vidocq.ext.jar"), null, List.of());
        var client = explicit("acme.client");
        var result = LayerSplit.split(List.of(ext, client), Set.of(), List.of(), Map.of());

        assertEquals(List.of("org.acme.vidocq.ext", "acme.client"), names(result.parent()));
    }

    @Test
    @DisplayName("an automatic module read by an extension stays, and its own reads are not followed")
    void anAutomaticModuleReadByAnExtensionStays() {
        var ext = explicit("io.vidocq.runtime.extensions.essentials.migration.liquibase", "liquibase.core");
        var liquibase = automatic("liquibase.core");
        var other = automatic("acme.other");
        var result = LayerSplit.split(List.of(ext, liquibase, other), Set.of(), List.of(), Map.of());

        assertEquals(List.of(ext.descriptor().name(), "liquibase.core"), names(result.parent()));
        assertEquals(List.of("acme.other"), names(result.application()),
                "an automatic module reads every module: following it would keep everything");
    }

    @Test
    @DisplayName("a module with a package the Vauban loader refuses stays")
    void aModuleWithAPackageTheLoaderRefusesStays() {
        var jsr305 = new LayerSplit.Candidate(ModuleDescriptor.newAutomaticModule("jsr305")
                .packages(Set.of("javax.annotation")).build(), Path.of("/libs/jsr305.jar"), null, List.of());
        var result = LayerSplit.split(List.of(jsr305), Set.of(), List.of(), Map.of());

        assertEquals(List.of("jsr305"), names(result.parent()),
                "the loader refuses javax.*: in the application layer it would be an empty shell");
    }

    @Test
    @DisplayName("an application module under a runtime prefix moves, and so does what it reads")
    void anApplicationModuleUnderARuntimePrefixMoves() {
        var app = explicit("io.vidocq.runtime.examples.servlet", "acme.pricing");
        var pricing = explicit("acme.pricing");
        var result = LayerSplit.split(List.of(app, pricing), Set.of("io.vidocq.runtime.examples.servlet"),
                List.of(), Map.of());

        assertEquals(List.of("io.vidocq.runtime.examples.servlet", "acme.pricing"), names(result.application()));
    }

    @Test
    @DisplayName("a pinned module drags its closure and is listed with its reason")
    void aPinnedModuleDragsItsClosure() {
        var jni = automaticWith("acme.jni", null, List.of());
        var vaadin = explicitWith("flow.server", "com.vaadin:flow-server", List.of(), "flow.commons");
        var commons = explicit("flow.commons");
        var brick = explicitWith("io.vidocq.foy.core", null, List.of("flow.server")); // its Vidocq-Parent-Layer
        var result = LayerSplit.split(List.of(jni, vaadin, commons, brick), Set.of(), List.of("acme.j*"),
                Map.of());

        assertEquals(List.of("acme.jni", "flow.server", "flow.commons", "io.vidocq.foy.core"),
                names(result.parent()));
        assertEquals(new LinkedHashMap<>(Map.of(
                        "acme.jni", "vidocq.app.layer.keep",
                        "flow.server", "Vidocq-Parent-Layer of io.vidocq.foy.core")).entrySet(),
                result.pinned().entrySet());
    }

    @Test
    @DisplayName("an artifact pattern matches the coordinates; a candidate without coordinates never matches it")
    void artifactPatterns() {
        var vaadin = explicitWith("flow.server", "com.vaadin:flow-server", List.of());
        var noCoordinates = explicitWith("flow.client", null, List.of());
        var result = LayerSplit.split(List.of(vaadin, noCoordinates), Set.of(), List.of("com.vaadin:*"),
                Map.of());

        assertEquals(List.of("flow.server"), names(result.parent()));
        assertEquals(List.of("flow.client"), names(result.application()));
    }

    @Test
    @DisplayName("a forced module stays with the given reason")
    void forced() {
        var patched = explicit("acme.patched");
        var result = LayerSplit.split(List.of(patched), Set.of(), List.of(),
                Map.of("acme.patched", "patched with --patch-module"));

        assertEquals(Map.of("acme.patched", "patched with --patch-module"), result.pinned());
    }

    @Test
    @DisplayName("a pinned module that reads an application module fails, naming both")
    void aPinnedModuleThatReadsTheApplicationFails() {
        var helper = explicit("acme.helper", "acme.app");
        var app = explicit("acme.app");
        List<LayerSplit.Candidate> candidates = List.of(helper, app);
        Set<String> application = Set.of("acme.app");
        List<String> keep = List.of("acme.helper");
        Map<String, String> forced = Map.of();
        var e = assertThrows(IllegalStateException.class,
                () -> LayerSplit.split(candidates, application, keep, forced));

        assertTrue(e.getMessage().startsWith("acme.helper is kept in the parent layer (vidocq.app.layer.keep) "
                + "but reads acme.app, a module of the application"), e.getMessage());
    }

    @Test
    @DisplayName("a kept automatic module keeps the dependencies the build declares for it")
    void aKeptAutomaticModuleKeepsItsDeclaredDependencies() {
        var ext = explicit("io.vidocq.runtime.extensions.essentials.migration.liquibase", "liquibase.core");
        var liquibase = automatic("liquibase.core");
        var snakeyaml = automatic("snakeyaml");
        var opencsv = automatic("opencsv");
        var lang3 = automatic("commons.lang3");
        var other = automatic("acme.other");
        var result = LayerSplit.split(List.of(ext, liquibase, snakeyaml, opencsv, lang3, other), Set.of(),
                List.of(), Map.of(), Map.of(
                        "liquibase.core", Set.of("snakeyaml", "opencsv", "absent.module"),
                        "opencsv", Set.of("commons.lang3")));

        assertEquals(List.of(ext.descriptor().name(), "liquibase.core", "snakeyaml", "opencsv", "commons.lang3"),
                names(result.parent()));
        assertEquals(List.of("acme.other"), names(result.application()));
    }

    @Test
    @DisplayName("a declared read of an application module fails, naming the reader")
    void aDeclaredReadOfTheApplicationFails() {
        var ext = explicit("io.vidocq.runtime.extensions.x", "lib.a");
        var lib = automatic("lib.a");
        var app = explicit("acme.app");
        List<LayerSplit.Candidate> candidates = List.of(ext, lib, app);
        Set<String> application = Set.of("acme.app");
        List<String> keep = List.of();
        Map<String, String> forced = Map.of();
        Map<String, Set<String>> reads = Map.of("lib.a", Set.of("acme.app"));
        var e = assertThrows(IllegalStateException.class,
                () -> LayerSplit.split(candidates, application, keep, forced, reads));

        assertTrue(e.getMessage().startsWith("lib.a is kept in the parent layer (a runtime module, through "
                + "io.vidocq.runtime.extensions.x) but reads acme.app"), e.getMessage());
    }

    @Test
    @DisplayName("an indirect read of the application names the module that reads it")
    void anIndirectReadNamesTheReader() {
        var helper = explicit("acme.helper", "acme.util");
        var util = explicit("acme.util", "acme.app");
        var app = explicit("acme.app");
        List<LayerSplit.Candidate> candidates = List.of(helper, util, app);
        Set<String> application = Set.of("acme.app");
        List<String> keep = List.of("acme.helper");
        Map<String, String> forced = Map.of();
        var e = assertThrows(IllegalStateException.class,
                () -> LayerSplit.split(candidates, application, keep, forced));

        assertTrue(e.getMessage().startsWith("acme.util is kept in the parent layer "
                + "(vidocq.app.layer.keep, through acme.helper) but reads acme.app"), e.getMessage());
    }

    @Test
    @DisplayName("coordinates without a colon never match an artifact pattern")
    void malformedCoordinatesNeverMatch() {
        assertTrue(!LayerSplit.matches("a:b", new LayerSplit.Candidate(
                ModuleDescriptor.newModule("m").build(), Path.of("/m.jar"), "nocolon", List.of())));
    }

    @Test
    @DisplayName("the mode accepts classes and all, nothing else")
    void mode() {
        assertEquals(LayerSplit.Mode.CLASSES, LayerSplit.Mode.of(null));
        assertEquals(LayerSplit.Mode.CLASSES, LayerSplit.Mode.of(" classes "));
        assertEquals(LayerSplit.Mode.ALL, LayerSplit.Mode.of("all"));
        var e = assertThrows(IllegalArgumentException.class, () -> LayerSplit.Mode.of("libs"));
        assertEquals("vidocq.app.layer must be classes or all, not 'libs'", e.getMessage());
    }

    @Test
    @DisplayName("the pinned list survives a system property")
    void pinnedEncoding() {
        var pinned = new LinkedHashMap<String, String>();
        pinned.put("acme.jni", "vidocq.app.layer.keep");
        pinned.put("flow.server", "Vidocq-Parent-Layer of io.vidocq.foy.core");
        assertEquals(pinned, LayerSplit.decodePinned(LayerSplit.encodePinned(pinned)));
        assertEquals(Map.of(), LayerSplit.decodePinned(""));
        assertEquals(Map.of(), LayerSplit.decodePinned(null));
    }

    @Test
    @DisplayName("a candidate is read from a jar: its descriptor and its Vidocq-Parent-Layer patterns")
    void candidateFromAJar(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("acme-lib-1.0.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", "acme.lib");
        manifest.getMainAttributes().putValue(LayerSplit.PARENT_LAYER_ATTRIBUTE, "flow.server, com.vaadin:*");
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new ZipEntry("acme/lib/Lib.class"));
            out.write(new byte[] {(byte) 0xCA, (byte) 0xFE});
            out.closeEntry();
        }
        var candidate = LayerSplit.candidate(jar, "acme:acme-lib");

        assertEquals("acme.lib", candidate.descriptor().name());
        assertTrue(candidate.descriptor().isAutomatic());
        assertEquals(List.of("flow.server", "com.vaadin:*"), candidate.parentLayerPatterns());
        assertEquals("acme:acme-lib", candidate.coordinates());
    }

    @Test
    @DisplayName("a Vidocq module marked Vidocq-Application-Layer moves, and so does the framework it reads")
    void aVidocqModuleMarkedForTheApplicationLayerMoves() {
        var core = explicit("io.vidocq.runtime.core");
        var vaadin = automatic("flow.server");
        var integration = new LayerSplit.Candidate(ModuleDescriptor.newModule(
                "io.vidocq.runtime.extensions.jakartaee.web.vaadin").requires("flow.server")
                .packages(Set.of("io.vidocq.runtime.extensions.jakartaee.web.vaadin")).build(),
                Path.of("/libs/vaadin-extension.jar"), null, List.of(), true);
        var result = LayerSplit.split(List.of(core, vaadin, integration), Set.of("acme.app"), List.of(), Map.of());

        assertEquals(List.of("io.vidocq.runtime.core"), names(result.parent()));
        assertEquals(List.of("flow.server", "io.vidocq.runtime.extensions.jakartaee.web.vaadin"),
                names(result.application()), "the integration stays with Vaadin, which needs the application layer");
    }

    @Test
    @DisplayName("Vidocq-Application-Layer is read from the jar's manifest; a jar without it is not marked")
    void applicationLayerAttributeFromAJar(@TempDir Path dir) throws Exception {
        Path marked = jar(dir.resolve("marked.jar"), "acme.marked", "true");
        Path plain = jar(dir.resolve("plain.jar"), "acme.plain", null);

        assertTrue(LayerSplit.candidate(marked, null).applicationLayer());
        assertTrue(!LayerSplit.candidate(plain, null).applicationLayer());
    }

    // ---------------------------------------------------------------- fixtures

    private static Path jar(Path jar, String moduleName, String applicationLayer) throws Exception {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", moduleName);
        if (applicationLayer != null) {
            manifest.getMainAttributes().putValue(LayerSplit.APPLICATION_LAYER_ATTRIBUTE, applicationLayer);
        }
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new ZipEntry(moduleName.replace('.', '/') + "/Lib.class"));
            out.write(new byte[] {(byte) 0xCA, (byte) 0xFE});
            out.closeEntry();
        }
        return jar;
    }

    private static List<String> names(List<LayerSplit.Candidate> candidates) {
        return candidates.stream().map(c -> c.descriptor().name()).toList();
    }

    private static LayerSplit.Candidate explicit(String name, String... requires) {
        return explicitWith(name, null, List.of(), requires);
    }

    private static LayerSplit.Candidate explicitWith(String name, String coordinates, List<String> patterns,
                                                     String... requires) {
        var builder = ModuleDescriptor.newModule(name).packages(Set.of(name));
        for (var required : requires) {
            builder.requires(required);
        }
        return new LayerSplit.Candidate(builder.build(), Path.of("/libs/" + name + ".jar"), coordinates, patterns);
    }

    private static LayerSplit.Candidate automatic(String name) {
        return automaticWith(name, null, List.of());
    }

    private static LayerSplit.Candidate automaticWith(String name, String coordinates, List<String> patterns) {
        return new LayerSplit.Candidate(ModuleDescriptor.newAutomaticModule(name).packages(Set.of(name)).build(),
                Path.of("/libs/" + name + ".jar"), coordinates, patterns);
    }
}
