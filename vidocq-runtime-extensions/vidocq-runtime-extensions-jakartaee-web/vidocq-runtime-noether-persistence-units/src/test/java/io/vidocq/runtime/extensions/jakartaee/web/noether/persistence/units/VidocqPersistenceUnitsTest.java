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
package io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.units;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.noether.jpa.core.spi.PersistenceUnitDeclaration;
import io.vidocq.noether.jpa.core.spi.PersistenceUnitSource;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleRequireInfo;
import java.lang.constant.ModuleDesc;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where the keys come from: the running application's configuration, which the extension hands over at start, and,
 * at build, where {@code vidocq:generate} finds the source among the application's dependencies, the application's
 * own files.
 */
class VidocqPersistenceUnitsTest {

    @TempDir
    Path dir;

    @AfterEach
    void stop() {
        VidocqPersistenceUnits.stop();
    }

    @Test
    void theServiceLoaderFindsTheSource() {
        assertTrue(ServiceLoader.load(PersistenceUnitSource.class).stream()
                .anyMatch(provider -> provider.type() == VidocqPersistenceUnits.class));
    }

    @Test
    void atStartTheKeysAreThoseTheExtensionHandsOver() throws IOException {
        Path classes = moduleDirectory("acme.app", "vidocq.persistence.fromfile.datasource=a");
        VidocqPersistenceUnits.start(Map.of("vidocq.persistence.ledger.datasource", "production"), new ModuleRoots() {
            @Override
            public Optional<URL> application() {
                return Optional.empty();
            }

            @Override
            public Optional<URL> of(String name) {
                return Optional.empty();
            }
        });

        try (URLClassLoader deployment = loader(classes)) {
            assertEquals(List.of("ledger"), names(new VidocqPersistenceUnits().units(deployment)));
        }
    }

    /**
     * At build, the units are read from the files of the deployment, the second winning as the runtime reads them,
     * and the application module is the first one of the deployment: its classes, ahead of its dependencies.
     */
    @Test
    void atBuildTheKeysAreThoseOfTheFilesOfTheDeployment() throws Exception {
        Path classes = moduleDirectory("acme.app", """
                vidocq.persistence.ledger.module=acme.ledger
                vidocq.persistence.ledger.transaction-type=JTA
                vidocq.persistence.audit.datasource=audit
                """);
        Files.writeString(classes.resolve("application.properties"),
                "vidocq.persistence.ledger.transaction-type=RESOURCE_LOCAL\n");
        Path ledger = moduleJar("acme.ledger");

        try (URLClassLoader deployment = loader(classes, ledger)) {
            List<PersistenceUnitDeclaration> units = new VidocqPersistenceUnits().units(deployment);

            assertEquals(List.of("audit", "ledger"), names(units));
            assertEquals(classes, Path.of(units.getFirst().defaultRoot().orElseThrow().toURI()));
            assertEquals("jar:" + ledger.toUri().toURL() + "!/", units.get(1).root().orElseThrow().toExternalForm());
            assertEquals("RESOURCE_LOCAL", units.get(1).transactionType().orElseThrow().name());
        }
    }

    @Test
    void atBuildWithoutAKeyTheDefaultUnitTakesTheFirstModuleOfTheDeployment() throws Exception {
        Path classes = moduleDirectory("acme.app", null);

        try (URLClassLoader deployment = loader(classes, moduleJar("acme.ledger"))) {
            PersistenceUnitDeclaration unit = new VidocqPersistenceUnits().units(deployment).getFirst();

            assertTrue(unit.whenAlone());
            assertEquals(classes, Path.of(unit.defaultRoot().orElseThrow().toURI()));
        }
    }

    private Path moduleDirectory(String name, String properties) throws IOException {
        Path classes = Files.createDirectories(dir.resolve(name));
        Files.write(classes.resolve("module-info.class"), moduleInfo(name));
        if (properties != null) {
            Files.writeString(classes.resolve("vidocq.properties"), properties);
        }
        return classes;
    }

    private Path moduleJar(String name) throws IOException {
        Path jar = dir.resolve(name + ".jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream entries = new JarOutputStream(out)) {
            entries.putNextEntry(new JarEntry("module-info.class"));
            entries.write(moduleInfo(name));
            entries.closeEntry();
        }
        return jar;
    }

    private static byte[] moduleInfo(String name) {
        return ClassFile.of().buildModule(ModuleAttribute.of(ModuleDesc.of(name), info -> info.requires(
                ModuleRequireInfo.of(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null))));
    }

    /** A loader of the deployment's files alone, as {@code vidocq:generate} gives the extensions. */
    private static URLClassLoader loader(Path... entries) throws IOException {
        URL[] urls = new URL[entries.length];
        for (int i = 0; i < entries.length; i++) {
            urls[i] = entries[i].toUri().toURL();
        }
        return new URLClassLoader(urls, null);
    }

    /** A descriptor that cannot be read names no module: the application is the next one. */
    @Test
    void aDescriptorThatCannotBeReadIsNoModule() throws Exception {
        Path broken = Files.createDirectories(dir.resolve("broken"));
        Files.write(broken.resolve("module-info.class"), new byte[] {(byte) 0xCA, (byte) 0xFE, 0, 0});
        Path classes = moduleDirectory("acme.app", null);

        try (URLClassLoader deployment = loader(broken, classes)) {
            PersistenceUnitDeclaration unit = new VidocqPersistenceUnits().units(deployment).getFirst();

            assertEquals(classes, Path.of(unit.defaultRoot().orElseThrow().toURI()));
        }
    }

    private static List<String> names(List<PersistenceUnitDeclaration> units) {
        return units.stream().map(PersistenceUnitDeclaration::name).toList();
    }
}
