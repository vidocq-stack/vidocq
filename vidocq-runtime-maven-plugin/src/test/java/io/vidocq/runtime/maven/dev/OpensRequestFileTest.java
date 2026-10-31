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
package io.vidocq.runtime.maven.dev;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plugin's side of the runtime's module-access diagnostic (BUG-20261031-15): the application, under
 * {@code vidocq dev}, writes the packages a library could not reach by reflection to a request file; the plugin opens
 * them in {@code module-info.java}, whose change recompiles and reloads the application.
 */
class OpensRequestFileTest {

    @TempDir
    Path dir;

    @Test
    void opensTheRequestedPackagesInModuleInfoOnce() throws Exception {
        Path moduleInfo = Files.writeString(dir.resolve("module-info.java"),
                "module shop {\n    requires jakarta.ws.rs;\n}\n");
        Path request = Files.writeString(dir.resolve(OpensRequestFile.FILE_NAME),
                "shop.report\nshop.report\nnot a package; drop\n");
        List<String> info = new ArrayList<>();
        var opens = new OpensRequestFile(request, moduleInfo, info::add, info::add);

        opens.apply();

        String source = Files.readString(moduleInfo);
        assertTrue(source.contains("opens shop.report; // vidocq:reflection"), source);
        assertEquals(1, source.split("opens shop.report;", -1).length - 1, source);
        assertFalse(Files.exists(request), "the request is consumed");
        assertTrue(info.stream().anyMatch(l -> l.startsWith("✔ module-info.java: opens shop.report;")), info.toString());
    }

    @Test
    void anAlreadyOpenedPackageChangesNothing() throws Exception {
        String declared = "module shop {\n    opens shop.report;\n}\n";
        Path moduleInfo = Files.writeString(dir.resolve("module-info.java"), declared);
        Path request = Files.writeString(dir.resolve(OpensRequestFile.FILE_NAME), "shop.report\n");
        var opens = new OpensRequestFile(request, moduleInfo, _ -> { }, _ -> { });

        opens.apply();

        assertEquals(declared, Files.readString(moduleInfo));
    }

    @Test
    void withoutAModuleInfoSourceItWarnsWithTheLine() throws Exception {
        Path request = Files.writeString(dir.resolve(OpensRequestFile.FILE_NAME), "shop.report\n");
        List<String> warnings = new ArrayList<>();
        var opens = new OpensRequestFile(request, null, _ -> { }, warnings::add);

        opens.apply();

        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("opens shop.report;"), warnings.getFirst());
    }

    @Test
    void pollingOpensARequestWrittenWhileItRuns() throws Exception {
        Path moduleInfo = Files.writeString(dir.resolve("module-info.java"), "module shop {\n}\n");
        Path request = dir.resolve(OpensRequestFile.FILE_NAME);
        Files.writeString(request, "left.by.an.earlier.session\n");
        try (var opens = OpensRequestFile.poll(request, moduleInfo, _ -> { }, _ -> { })) {
            assertFalse(Files.exists(request), "an earlier session's request is dropped");
            Files.writeString(request, "shop.report\n");
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (!Files.readString(moduleInfo).contains("opens shop.report;") && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            opens.close();
        }
        String source = Files.readString(moduleInfo);
        assertTrue(source.contains("opens shop.report; // vidocq:reflection"), source);
        assertFalse(source.contains("left.by.an.earlier.session"), source);
    }

    @Test
    void onlyPackageNamesAreRead() {
        assertTrue(OpensRequestFile.isPackageName("com.example.report"));
        assertTrue(OpensRequestFile.isPackageName("single"));
        for (String bad : List.of("", "a..b", ".a", "a.", "1a", "a b", "a;drop", "a.b-c")) {
            assertFalse(OpensRequestFile.isPackageName(bad), bad);
        }
    }
}
