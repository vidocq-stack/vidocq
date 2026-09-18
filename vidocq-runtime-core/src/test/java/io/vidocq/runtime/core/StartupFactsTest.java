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

import io.vidocq.runtime.core.config.VidocqConfigImpl;
import io.vidocq.runtime.core.report.DisplayPaths;
import io.vidocq.runtime.core.report.Section;
import io.vidocq.runtime.core.report.Section.Cells;
import io.vidocq.runtime.core.report.Section.Items;
import io.vidocq.runtime.core.report.Section.Row;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.config.ConfigSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleRequireInfo;
import java.lang.constant.ModuleDesc;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the core reads for its report sections, from a real application layer, configuration and extensions. */
class StartupFactsTest {

    @TempDir
    Path dir;

    @Test
    void theLayerSectionListsTheModulesInTheOrderOfTheirArchives() throws Exception {
        Path app = module("zeta.app");
        Path lib = module("alpha.lib");
        String previous = System.getProperty(VidocqAppLayer.APP_PATH_PROPERTY);
        System.setProperty(VidocqAppLayer.APP_PATH_PROPERTY, app + File.pathSeparator + lib);
        try {
            assertTrue(VidocqAppLayer.installIfConfigured());
            DisplayPaths paths = new DisplayPaths(dir, null);

            Section layer = StartupFacts.layer("none", paths);

            assertEquals("2 modules (vidocq.app.path=..." + File.separator + "zeta.app" + File.pathSeparator + "..."
                    + File.separator + "alpha.lib)", layer.summary());
            assertEquals("2 modules (vidocq.app.path=zeta.app" + File.pathSeparator + "alpha.lib)", layer.headline());
            assertEquals(List.of(new Cells(List.of("zeta.app", "zeta.app", "directory")),
                    new Cells(List.of("alpha.lib", "alpha.lib", "directory")),
                    new Row("weaving", "none")), layer.lines());
        } finally {
            VidocqAppLayer.resetForReload();
            if (previous == null) {
                System.clearProperty(VidocqAppLayer.APP_PATH_PROPERTY);
            } else {
                System.setProperty(VidocqAppLayer.APP_PATH_PROPERTY, previous);
            }
        }
        assertNull(VidocqAppLayer.installation(), "a reload forgets the layer it tears down");
        assertEquals("no application layer", StartupFacts.layer("none", new DisplayPaths(dir, null)).summary());
    }

    @Test
    void theConfigurationSectionNamesTheSourcesInLookupOrderAndNeverAValue() {
        VidocqConfigImpl config = new VidocqConfigImpl(List.of(
                source("low", 100, Map.of("vidocq.startup.report", "detailed")),
                source("ExternalFile(" + dir.resolve("conf/vidocq.properties") + ")", 250, Map.of())));

        Section configuration = StartupFacts.configuration(config, List.of(), new DisplayPaths(dir, null));

        assertEquals(new Items("sources", List.of("ExternalFile(conf" + File.separator + "vidocq.properties) 250",
                "low 100")), configuration.lines().getFirst());
        Items audited = (Items) configuration.lines().getLast();
        assertTrue(audited.items().containsAll(List.of("vidocq.launch.*", "vidocq.startup.*")), audited.toString());
        assertTrue(configuration.lines().stream().noneMatch(line -> line.toString().contains("detailed")),
                configuration.toString());
    }

    @Test
    void theExtensionsSectionSaysWhichStartedAndWhereTheyComeFrom() {
        VidocqExtension first = extension("first", Set.of("vidocq.first.port"));
        VidocqExtension second = extension("second", Set.of());

        VidocqExtension third = extension("third", Set.of());

        Section extensions = StartupFacts.extensions(List.of(first, second, third), List.of(2_000_000L),
                "onStart second");

        String module = StartupFacts.where(first.getClass().getModule());
        assertEquals(List.of(new Cells(List.of("1000", "first", "onStart 2 ms", module, "vidocq.first.*")),
                new Cells(List.of("1000", "second", "onStart failed", module, "")),
                new Cells(List.of("1000", "third", "not started", module, ""))), extensions.lines());
    }

    @Test
    void aModuleIsPlacedInItsLayer() {
        assertEquals("java.base (boot layer)", StartupFacts.where(String.class.getModule()));
        assertEquals("class path", StartupFacts.where(new ClassLoader() {}.getUnnamedModule()));
    }

    @Test
    void anArchiveIsADirectoryOrAFileOfSomeKind() {
        assertEquals("directory", StartupFacts.kind(URI.create("file:/x/target/classes/"), Path.of("/x/target/classes")));
        assertEquals("jar", StartupFacts.kind(URI.create("file:/x/lib.JAR"), Path.of("/x/lib.JAR")));
        assertEquals("sjar", StartupFacts.kind(URI.create("file:/x/lib.sjar"), Path.of("/x/lib.sjar")));
    }

    /** A directory holding only the {@code module-info.class} of {@code name}. */
    private Path module(String name) throws Exception {
        Path classes = Files.createDirectories(dir.resolve(name));
        Files.write(classes.resolve("module-info.class"), ClassFile.of().buildModule(
                ModuleAttribute.of(ModuleDesc.of(name), module -> module.requires(
                        ModuleRequireInfo.of(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null)))));
        return classes;
    }

    private static ConfigSource source(String name, int ordinal, Map<String, String> values) {
        return new ConfigSource() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public int getOrdinal() {
                return ordinal;
            }

            @Override
            public String getValue(String key) {
                return values.get(key);
            }

            @Override
            public Set<String> getPropertyNames() {
                return values.keySet();
            }
        };
    }

    private static VidocqExtension extension(String name, Set<String> keys) {
        return new VidocqExtension() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Set<String> configKeys() {
                return keys;
            }
        };
    }
}
