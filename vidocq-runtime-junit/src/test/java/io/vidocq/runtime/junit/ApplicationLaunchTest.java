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
package io.vidocq.runtime.junit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleRequireInfo;
import java.lang.constant.ModuleDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What {@code @VidocqTest} runs, worked out before any process starts (Vidocq/vidocq#350). */
class ApplicationLaunchTest {

    @TempDir
    Path dir;

    private final List<String> warnings = new ArrayList<>();

    /**
     * The runtime's main boots the application from {@code vidocq.app.path}, as {@code vidocq:run} does: the
     * application's modules are on no other path (BUG-20261114-07).
     */
    @Test
    void theRuntimeMainBootsTheApplicationLayerOnALoopbackPort() {
        var layout = new ApplicationLaunch.Layout(List.of(Path.of("/m2/core.jar"), Path.of("/m2/vauban.jar")),
                List.of(Path.of("/app/classes"), Path.of("/m2/lib.jar")), List.of("vidocq.app.layer=all"),
                "ALL-MODULE-PATH,java.net.http");

        List<String> command = ApplicationLaunch.command("/jdk/bin/java", layout, "com.acme.demo", 41234,
                List.of("greeting=hi"));

        assertEquals(List.of("/jdk/bin/java",
                "-Dvidocq.chappe.listener.default.host=127.0.0.1",
                "-Dvidocq.chappe.listener.default.port=41234",
                "-Dvidocq.app.layer=all",
                "-Dgreeting=hi",
                "-Dvidocq.app.module=com.acme.demo",
                "-Dvidocq.app.path=" + path("/app/classes", "/m2/lib.jar"),
                "-p", path("/m2/core.jar", "/m2/vauban.jar"),
                "--add-modules", "ALL-MODULE-PATH,java.net.http",
                "-m", "io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq"), command);
    }

    @Test
    void aPropertyWithoutValueIsRefused() {
        List<String> properties = List.of("greeting");
        var layout = new ApplicationLaunch.Layout(List.of(Path.of("/p")), List.of(Path.of("/a")), List.of(),
                "ALL-MODULE-PATH");

        var e = assertThrows(IllegalArgumentException.class,
                () -> ApplicationLaunch.command("java", layout, "m", 1, properties));

        assertEquals("@VidocqTest(properties) takes key=value entries, not 'greeting'", e.getMessage());
    }

    /**
     * Under {@code vidocq.app.layer=all}, the default, the application module and the libraries {@code LayerSplit}
     * gives it leave the module path for the application layer, as in {@code vidocq:run} and the packaged
     * application. On the module path, the JVM resolved them into the boot layer too, and a {@code ServiceLoader}
     * of the application layer found each of their providers twice: Vaadin's two {@code InstantiatorFactory}
     * (BUG-20261114-07). The JDK modules they require join {@code --add-modules}: a child layer cannot define one.
     */
    @Test
    void theApplicationAndItsLibrariesRunInTheApplicationLayer() throws IOException {
        Path core = explicit("io.vidocq.runtime.core");
        Path app = explicit("com.acme.demo", "io.vidocq.runtime.core", "java.net.http", "static java.sql");
        Path library = automatic("acme-lib-1.0.jar");
        Path tools = explicit("acme.tools", "java.logging");

        var layout = ApplicationLaunch.layout(path(core, app, library, tools), "com.acme.demo", List.of(),
                warnings::add);

        assertEquals(List.of(core), layout.modulePath());
        assertEquals(List.of(app, library, tools), layout.appPath());
        assertEquals(List.of("vidocq.app.layer=all"), layout.properties());
        assertEquals("ALL-MODULE-PATH,java.logging,java.net.http", layout.addModules());
        assertEquals(List.of(), warnings);
    }

    @Test
    void underClassesTheApplicationLayerHoldsTheApplicationModuleAlone() throws IOException {
        Path core = explicit("io.vidocq.runtime.core");
        Path app = explicit("com.acme.demo");
        Path library = automatic("acme-lib-1.0.jar");

        var layout = ApplicationLaunch.layout(path(core, app, library), "com.acme.demo",
                List.of("vidocq.app.layer=all", "vidocq.app.layer=classes"), warnings::add);

        assertEquals(List.of(core, library), layout.modulePath());
        assertEquals(List.of(app), layout.appPath());
        assertEquals(List.of("vidocq.app.layer=classes"), layout.properties());
        assertEquals("ALL-MODULE-PATH", layout.addModules());
    }

    /** {@code vidocq.app.layer.keep} pins a library in the parent layer, and the runtime is told why. */
    @Test
    void aPinnedLibraryStaysOnTheModulePath() throws IOException {
        Path core = explicit("io.vidocq.runtime.core");
        Path app = explicit("com.acme.demo");
        Path library = automatic("acme-lib-1.0.jar");

        var layout = ApplicationLaunch.layout(path(core, app, library), "com.acme.demo",
                List.of("vidocq.app.layer.keep=acme.lib"), warnings::add);

        assertEquals(List.of(core, library), layout.modulePath());
        assertEquals(List.of(app), layout.appPath());
        assertEquals(List.of("vidocq.app.layer=all", "vidocq.app.layer.pinned=acme.lib=vidocq.app.layer.keep"),
                layout.properties());
    }

    /** As in {@code vidocq:run}: the default {@code all} that cannot be honoured falls back to {@code classes}. */
    @Test
    void theDefaultThatCannotSplitFallsBackToClassesWithAWarning() throws IOException {
        Path core = explicit("io.vidocq.runtime.core");
        Path app = explicit("com.acme.demo");
        Path helper = explicit("acme.helper", "com.acme.demo");

        var layout = ApplicationLaunch.layout(path(core, app, helper), "com.acme.demo",
                List.of("vidocq.app.layer.keep=acme.helper"), warnings::add);

        assertEquals(List.of(core, helper), layout.modulePath());
        assertEquals(List.of(app), layout.appPath());
        assertEquals(List.of("vidocq.app.layer=classes"), layout.properties());
        assertEquals(1, warnings.size(), warnings::toString);
        assertTrue(warnings.getFirst().startsWith("vidocq.app.layer=all, the default, cannot split this launch:"
                + " acme.helper is kept in the parent layer"), warnings.getFirst());
    }

    @Test
    void anAllAskedForThatCannotSplitFails() throws IOException {
        String modulePath = path(explicit("io.vidocq.runtime.core"), explicit("com.acme.demo"),
                explicit("acme.helper", "com.acme.demo"));
        List<String> properties = List.of("vidocq.app.layer=all", "vidocq.app.layer.keep=acme.helper");

        var e = assertThrows(IllegalStateException.class,
                () -> ApplicationLaunch.layout(modulePath, "com.acme.demo", properties, warnings::add));

        assertTrue(e.getMessage().startsWith("acme.helper is kept in the parent layer"), e.getMessage());
    }

    /** An entry the split cannot read stays on the module path, where the JVM reports it as before. */
    @Test
    void anEntryWithoutAModuleStaysOnTheModulePath() throws IOException {
        Path core = explicit("io.vidocq.runtime.core");
        Path app = explicit("com.acme.demo");
        Path missing = dir.resolve("missing.jar");

        var layout = ApplicationLaunch.layout(path(core, app, missing) + File.pathSeparator, "com.acme.demo",
                List.of(), warnings::add);

        assertEquals(List.of(core, missing), layout.modulePath());
        assertEquals(List.of(app), layout.appPath());
    }

    @Test
    void aModuleMissingFromTheModulePathIsNamed() throws IOException {
        String modulePath = path(explicit("io.vidocq.runtime.core"));
        List<String> properties = List.of();

        var e = assertThrows(IllegalStateException.class,
                () -> ApplicationLaunch.layout(modulePath, "com.acme.demo", properties, warnings::add));

        assertEquals("@VidocqTest starts the module com.acme.demo, which no entry of the module path of the tests"
                + " holds: " + modulePath, e.getMessage());
    }

    @Test
    void theModuleNamedOnTheAnnotationWins() {
        assertEquals("com.acme.other", ApplicationLaunch.module("com.acme.other", "com.acme.demo", "com.acme.prop"));
    }

    /** Surefire and the IDEs patch the tests into the application module: the test class names it. */
    @Test
    void elseTheModuleOfTheTestClass() {
        assertEquals("com.acme.demo", ApplicationLaunch.module("", "com.acme.demo", "com.acme.prop"));
    }

    @Test
    void elseTheMainModuleProperty() {
        assertEquals("com.acme.prop", ApplicationLaunch.module("", null, "com.acme.prop"));
    }

    @Test
    void withoutAnyItSaysWhereToNameIt() {
        var e = assertThrows(IllegalStateException.class, () -> ApplicationLaunch.module("", null, null));

        assertEquals("@VidocqTest cannot tell which module to start: the test class is not in a named module."
                + " Name it with @VidocqTest(module = \"…\") or -Dvidocq.mainModule.", e.getMessage());
    }

    @Test
    void theApplicationRunsOnTheModulePathOfTheTests() {
        assertEquals("/a:/b", ApplicationLaunch.modulePath("/a:/b", null));
        assertEquals("/a:/b", ApplicationLaunch.modulePath("/a:/b", " "));

        var e = assertThrows(IllegalStateException.class, () -> ApplicationLaunch.modulePath(null, "/c.jar"));
        assertTrue(e.getMessage().startsWith("@VidocqTest starts the application from the module path of the tests,"
                + " and they run on the class path"), e.getMessage());
    }

    /**
     * A runtime dependency no module requires, a JDBC driver for one, is on the class path of the tests, where
     * Surefire leaves it: the application gets it on its module path, as {@code vidocq:dev} and the packaged
     * application do (BUG-20261030-06).
     */
    @Test
    void theRuntimeDependenciesTheTestsLeaveOnTheClassPathJoinTheModulePath() {
        String sep = java.io.File.pathSeparator;

        assertEquals(String.join(sep, "/app/classes", "/m2/core.jar", "/m2/postgresql.jar"),
                ApplicationLaunch.modulePath(String.join(sep, "/app/classes", "/m2/core.jar"),
                        String.join(sep, "/m2/core.jar", "/m2/postgresql.jar")));
    }

    /**
     * A dependency {@code vidocq:generate} enriched runs from its copy under {@code target/vidocq-enriched}, as in
     * {@code vidocq dev} and the packaged application: the copy, which {@code vidocq:test-path} names, takes the
     * place of the jar Surefire put on the module path, same file name.
     */
    @Test
    void anEnrichedCopyTakesThePlaceOfItsJar() {
        String sep = java.io.File.pathSeparator;

        assertEquals(String.join(sep, "/app/classes", "/app/target/vidocq-enriched/books-1.0.jar", "/m2/core.jar"),
                ApplicationLaunch.modulePath(String.join(sep, "/app/classes", "/m2/books-1.0.jar", "/m2/core.jar"),
                        String.join(sep, "/app/target/vidocq-enriched/books-1.0.jar", "/m2/core.jar")));
    }

    /**
     * The dev services the test run started, through {@code vidocq test}, {@code vidocq dev} or the JUnit listener,
     * reach the application: each key the session injected, which its {@code vidocq.dev.provided.<key>} marker names,
     * the marker itself and the state file, and nothing else of the test JVM (BUG-20261030-20).
     */
    @Test
    void theApplicationGetsTheKeysTheDevServicesInjected() {
        Properties system = new Properties();
        system.setProperty("vidocq.pool.url", "jdbc:postgresql://localhost:33003/app");
        system.setProperty("vidocq.dev.provided.vidocq.pool.url", "postgres");
        system.setProperty("mp.jwt.verify.issuer", "http://localhost:33004/realms/app");
        system.setProperty("vidocq.dev.provided.mp.jwt.verify.issuer", "keycloak");
        system.setProperty("vidocq.devservices.state", "/app/target/vidocq-dev-services.json");
        system.setProperty("vidocq.pool.username", "set-by-hand");
        system.setProperty("user.dir", "/app");

        assertEquals(List.of(
                "mp.jwt.verify.issuer=http://localhost:33004/realms/app",
                "vidocq.dev.provided.mp.jwt.verify.issuer=keycloak",
                "vidocq.pool.url=jdbc:postgresql://localhost:33003/app",
                "vidocq.dev.provided.vidocq.pool.url=postgres",
                "vidocq.devservices.state=/app/target/vidocq-dev-services.json"),
                ApplicationLaunch.devServiceProperties(system));
    }

    @Test
    void withoutDevServicesTheApplicationGetsNoneOfTheTestJvmProperties() {
        Properties system = new Properties();
        system.setProperty("vidocq.pool.url", "jdbc:h2:mem:app");

        assertEquals(List.of(), ApplicationLaunch.devServiceProperties(system));
    }

    // ---------------------------------------------------------------- fixtures

    private static String path(Object... entries) {
        return Stream.of(entries).map(String::valueOf).collect(Collectors.joining(File.pathSeparator));
    }

    /**
     * An exploded named module {@code name}, its descriptor alone, that requires each of {@code requires}; a
     * {@code static } prefix makes the dependency compile-time only.
     */
    private Path explicit(String name, String... requires) throws IOException {
        Path module = Files.createDirectories(dir.resolve(name));
        Files.write(module.resolve("module-info.class"), ClassFile.of().buildModule(
                ModuleAttribute.of(ModuleDesc.of(name), builder -> {
                    builder.requires(ModuleRequireInfo.of(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null));
                    for (String required : requires) {
                        boolean compileTime = required.startsWith("static ");
                        builder.requires(ModuleRequireInfo.of(
                                ModuleDesc.of(compileTime ? required.substring("static ".length()) : required),
                                compileTime ? ClassFile.ACC_STATIC_PHASE : 0, null));
                    }
                })));
        return module;
    }

    /** An empty jar, an automatic module named after {@code fileName}. */
    private Path automatic(String fileName) throws IOException {
        Path jar = dir.resolve(fileName);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (OutputStream out = Files.newOutputStream(jar); var _ = new JarOutputStream(out, manifest)) {
            return jar;
        }
    }

    /** {@code @VidocqTest(properties)} comes after the dev services' keys: the last {@code -D} wins. */
    @Test
    void thePropertiesOfTheAnnotationOverrideTheDevServices() {
        assertEquals(List.of("vidocq.pool.url=jdbc:postgresql://dev", "vidocq.pool.url=jdbc:h2:mem:t"),
                ApplicationLaunch.properties(List.of("vidocq.pool.url=jdbc:postgresql://dev"),
                        new String[] {"vidocq.pool.url=jdbc:h2:mem:t"}));
    }
}
