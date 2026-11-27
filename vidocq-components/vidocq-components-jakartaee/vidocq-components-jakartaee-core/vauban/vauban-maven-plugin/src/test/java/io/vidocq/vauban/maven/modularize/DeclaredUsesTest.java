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
package io.vidocq.vauban.maven.modularize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code uses} declared by hand (Vidocq/vidocq#697). A library that loads a service type held in a variable -- Liquibase's
 * {@code StandardServiceLocator.findInstances(Class)}, here a {@code Class.forName} -- leaves no class literal a scan can
 * see, so its descriptor gets no {@code uses}. Its lookups still run (the call is redirected to {@code Module.addUses},
 * vidocq#598), but the module system binds no provider for a service nobody declares: a provider in another module is
 * left out of the configuration, and the lookup finds nothing.
 */
@DisplayName("modularize: uses declared by hand")
class DeclaredUsesTest {

    @TempDir
    Path tmp;

    private static final String PLUGIN = "com.acme.registry.Plugin";
    private static final String CODEC = "com.acme.registry.Codec";
    private static final String EXTRA = "com.acme.plugin.Extra";

    private static final Map<String, String> REGISTRY = Map.of(
            PLUGIN, "package com.acme.registry; public interface Plugin {}",
            CODEC, "package com.acme.registry; public interface Codec {}",
            "com.acme.registry.Registry", """
                    package com.acme.registry;

                    import java.util.ServiceLoader;
                    import java.util.stream.Collectors;

                    public final class Registry {
                        private Registry() {}

                        /** Like Liquibase's service locator: the service type comes from elsewhere, not from a literal. */
                        public static String names(String service) throws ClassNotFoundException {
                            Class<?> type = Class.forName(service);
                            return ServiceLoader.load(type).stream().map(p -> p.type().getName())
                                    .sorted().collect(Collectors.joining(","));
                        }

                        /** A lookup the scan sees. */
                        public static long codecs() {
                            return ServiceLoader.load(Codec.class).stream().count();
                        }
                    }
                    """);

    private static final Map<String, String> PLUGINS = Map.of(
            "com.acme.plugin.Hello", "package com.acme.plugin; public final class Hello implements com.acme.registry.Plugin {}",
            EXTRA, "package com.acme.plugin; public interface Extra {}",
            "com.acme.plugin.ExtraImpl", "package com.acme.plugin; public final class ExtraImpl implements Extra {}");

    private Path registry;
    private Path plugin;

    private List<Path> closure() throws Exception {
        Path registryClasses = tmp.resolve("registry-classes");
        TestJars.compileClasses(registryClasses, REGISTRY);
        registry = TestJars.jar(tmp.resolve("m2/acme-registry-1.0.jar"), registryClasses, Map.of(), Map.of());
        Path pluginClasses = tmp.resolve("plugin-classes");
        TestJars.compileClasses(pluginClasses, PLUGINS, registryClasses);
        plugin = TestJars.jar(tmp.resolve("m2/acme-plugin-1.0.jar"), pluginClasses, Map.of(), Map.of(
                "META-INF/services/" + PLUGIN, "com.acme.plugin.Hello\n",
                "META-INF/services/" + EXTRA, "com.acme.plugin.ExtraImpl\n"));
        return List.of(registry, plugin);
    }

    private Modularizer.Result run(Map<String, Set<String>> declared, List<String> log) throws Exception {
        List<Path> closure = closure();
        return Modularizer.run(closure, Map.of(registry, "acme-registry", plugin, "acme-plugin"),
                tmp.resolve("target"), new Modularizer.Options(Modularizer.Mode.DERIVED, Set.of(), Set.of(),
                        Map.of(), true, false, declared), log::add);
    }

    private Path patched(String fileName) {
        return ModularizedJars.root(tmp.resolve("target")).resolve(fileName);
    }

    private Set<String> usesOf(String fileName) {
        return ModuleFinder.of(patched(fileName)).findAll().iterator().next().descriptor().uses();
    }

    /** The provider names the registry's lookup finds, in a layer resolved with service binding like the boot layer. */
    private String lookup() throws Exception {
        ModuleFinder finder = ModuleFinder.of(patched("acme-registry-1.0.jar"), patched("acme-plugin-1.0.jar"));
        Configuration configuration = ModuleLayer.boot().configuration()
                .resolveAndBind(finder, ModuleFinder.of(), Set.of("acme.registry"));
        ModuleLayer layer = ModuleLayer.boot()
                .defineModulesWithOneLoader(configuration, ClassLoader.getSystemClassLoader());
        ClassLoader loader = layer.findLoader("acme.registry");
        Class<?> registryClass = loader.loadClass("com.acme.registry.Registry");
        // ServiceLoader.load(Class) looks in the layers the context class loader reaches.
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return (String) registryClass.getMethod("names", String.class).invoke(null, PLUGIN);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** The failure of a run that declares {@code type} for the registry. */
    private IllegalStateException failure(String type) {
        Map<String, Set<String>> declared = Map.of("acme-registry", Set.of(type));
        List<String> log = new ArrayList<>();
        return assertThrows(IllegalStateException.class, () -> run(declared, log));
    }

    @Test
    @DisplayName("without the parameter, the variable-typed lookup has no uses and binds no provider")
    void withoutTheParameterTheProviderIsNotBound() throws Exception {
        run(Map.of(), new ArrayList<>());

        assertEquals(Set.of(CODEC), usesOf("acme-registry-1.0.jar"));
        assertEquals("", lookup());
    }

    @Test
    @DisplayName("a declared uses is written, merged with the scanned ones, and binds the provider")
    void aDeclaredUsesBindsTheProvider() throws Exception {
        // CODEC is also scanned: declaring it again does not duplicate it.
        Modularizer.Result result = run(Map.of("acme-registry", Set.of(PLUGIN, CODEC)), new ArrayList<>());

        assertEquals(Set.of(PLUGIN, CODEC), usesOf("acme-registry-1.0.jar"));
        assertEquals("com.acme.plugin.Hello", lookup());
        assertTrue(result.report().contains("uses declared by hand: " + CODEC + ", " + PLUGIN), result.report());
    }

    @Test
    @DisplayName("* declares every service the closure provides that the module may declare")
    void theWildcardDeclaresTheReadableServicesTheClosureProvides() throws Exception {
        List<String> log = new ArrayList<>();
        Modularizer.Result result = run(Map.of("acme-registry", Set.of("*")), log);

        // EXTRA is provided too, but in acme.plugin, which requires acme.registry: no legal uses, silently left out.
        assertEquals(Set.of(PLUGIN, CODEC), usesOf("acme-registry-1.0.jar"));
        assertEquals("com.acme.plugin.Hello", lookup());
        assertTrue(result.report().contains("uses declared by hand (*): " + PLUGIN), result.report());
        assertTrue(log.stream().noneMatch(l -> l.contains(EXTRA)), String.join("\n", log));
    }

    @Test
    @DisplayName("a declared type in no jar of the closure fails the build")
    void aTypeOutsideTheClosureFails() {
        IllegalStateException e = failure("org.nowhere.Service");

        assertTrue(e.getMessage().contains("acme-registry: uses org.nowhere.Service"), e.getMessage());
        assertTrue(e.getMessage().contains("not in closure"), e.getMessage());
    }

    @Test
    @DisplayName("a declared type missing from a package of the closure fails the build")
    void aMisspelledTypeFails() {
        IllegalStateException e = failure("com.acme.registry.Plugn");

        assertTrue(e.getMessage().contains("acme-registry: uses com.acme.registry.Plugn"), e.getMessage());
        assertTrue(e.getMessage().contains("no such type in acme.registry"), e.getMessage());
    }

    @Test
    @DisplayName("a declared type the module cannot read fails the build instead of being dropped")
    void anUnreadableTypeFails() {
        IllegalStateException e = failure(EXTRA);

        assertTrue(e.getMessage().contains("acme-registry: uses " + EXTRA), e.getMessage());
        assertTrue(e.getMessage().contains("cannot read acme.plugin"), e.getMessage());
    }

    @Test
    @DisplayName("a JDK service type may be declared, a nested one by its binary name")
    void aSystemTypeIsAccepted() throws Exception {
        run(Map.of("acme-registry", Set.of("java.lang.System$LoggerFinder")), new ArrayList<>());

        assertTrue(usesOf("acme-registry-1.0.jar").contains("java.lang.System$LoggerFinder"));
    }

    @Test
    @DisplayName("uses declared for an artifact this run does not patch is reported, not silently lost")
    void usesForAnArtifactNotPatchedWarns() throws Exception {
        List<String> log = new ArrayList<>();
        run(Map.of("acme-unknown", Set.of(PLUGIN)), log);

        assertTrue(log.contains("WARN uses declared for acme-unknown, which this run does not patch"),
                String.join("\n", log));
        assertTrue(Files.isRegularFile(patched("acme-registry-1.0.jar")));
    }

    @Test
    @DisplayName("the six-argument options declare nothing")
    void theFormerOptionsDeclareNothing() {
        var options = new Modularizer.Options(Modularizer.Mode.DERIVED, Set.of(), Set.of(), Map.of(), true, false);

        assertEquals(Map.of(), options.declaredUses());
    }
}
