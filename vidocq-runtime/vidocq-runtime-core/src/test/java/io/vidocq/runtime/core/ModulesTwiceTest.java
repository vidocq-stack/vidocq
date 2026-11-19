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

import io.vidocq.runtime.core.report.Anomaly;
import io.vidocq.runtime.core.report.StartupAnomalies;
import io.vidocq.runtime.core.report.StartupRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleRequireInfo;
import java.lang.constant.ClassDesc;
import java.lang.constant.ModuleDesc;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** VIDOCQ-LAY-001: which modules a trampoline from the module path holds twice, and what the start says. */
@DisplayName("BUG-20261114-10 — the modules loaded twice")
class ModulesTwiceTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("a trampoline whose module a layer already holds records it twice, and the start warns")
    void aTrampolineFromTheModulePathIsReported() throws Exception {
        Class<?> caller = resolvedProbe();
        try {
            assertTrue(VidocqAppLayer.installFromBootLayer(caller));
            assertEquals(List.of("acme.app"), VidocqAppLayer.installation().twice());

            StartupRecorder recorder = new StartupRecorder();
            VidocqBootstrap.reportModulesTwice(recorder);
            Anomaly anomaly = recorder.anomalies().getFirst();
            assertEquals(StartupAnomalies.MODULES_TWICE, anomaly.code());
            assertTrue(anomaly.message().startsWith("1 application module is in the boot layer and in the application"
                    + " layer: acme.app."), anomaly.message());
        } finally {
            VidocqAppLayer.resetForReload();
        }
    }

    @Test
    @DisplayName("vidocq.app.layer.off leaves the application in the layer the JVM resolved it in")
    void theOffPropertyInstallsNoLayer() throws Exception {
        Class<?> caller = resolvedProbe();
        String previous = System.setProperty(LayerSplit.OFF_PROPERTY, "true");
        try {
            Vidocq.ensureLayer(caller);
            assertNull(VidocqAppLayer.installedLayer());
        } finally {
            if (previous == null) {
                System.clearProperty(LayerSplit.OFF_PROPERTY);
            } else {
                System.setProperty(LayerSplit.OFF_PROPERTY, previous);
            }
            VidocqAppLayer.resetForReload();
        }
    }

    @Test
    @DisplayName("a start without an application layer reports nothing twice")
    void noLayerNoReport() {
        StartupRecorder recorder = new StartupRecorder();
        VidocqBootstrap.reportModulesTwice(recorder);
        assertEquals(List.of(), recorder.anomalies());
    }

    /**
     * {@code acme.app.Probe}, from an exploded named module {@code acme.app} that a layer of this test resolved, as
     * the JVM resolves an application an IDE puts on the module path. Emitted rather than compiled.
     */
    private Class<?> resolvedProbe() throws Exception {
        Path classes = Files.createDirectories(dir.resolve("acme.app"));
        Files.write(classes.resolve("module-info.class"), ClassFile.of().buildModule(
                ModuleAttribute.of(ModuleDesc.of("acme.app"), module -> module.requires(
                        ModuleRequireInfo.of(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null)))));
        Path probe = Files.createDirectories(classes.resolve("acme/app")).resolve("Probe.class");
        Files.write(probe, ClassFile.of().build(ClassDesc.of("acme.app.Probe"),
                type -> type.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)));
        ModuleLayer boot = ModuleLayer.boot();
        var configuration = boot.configuration().resolve(ModuleFinder.of(dir), ModuleFinder.of(), Set.of("acme.app"));
        ModuleLayer layer = boot.defineModulesWithOneLoader(configuration, ClassLoader.getSystemClassLoader());
        return layer.findLoader("acme.app").loadClass("acme.app.Probe");
    }

    @Test
    @DisplayName("the modules of the layer that the other layer holds too, sorted")
    void theModulesTheBootLayerHoldsToo() {
        assertEquals(List.of("java.base", "java.logging"),
                VidocqAppLayer.twice(Set.of("java.logging", "acme.absent", "java.base"), ModuleLayer.boot()));
    }

    @Test
    @DisplayName("an installation records none unless told")
    void anInstallationRecordsNoneUnlessTold() {
        assertEquals(List.of(), new VidocqAppLayer.Installation("vidocq.app.path", List.of(), true, Map.of()).twice());
    }

    @Test
    @DisplayName("the message counts and names the modules")
    void theMessageNamesTheModules() {
        assertTrue(StartupAnomalies.modulesTwice(List.of("acme.app"))
                .startsWith("1 application module is in the boot layer and in the application layer: acme.app."));
        assertTrue(StartupAnomalies.modulesTwice(List.of("acme.app", "acme.lib"))
                .startsWith("2 application modules are in the boot layer and in the application layer: acme.app,"
                        + " acme.lib."));
        assertTrue(StartupAnomalies.MODULES_TWICE_HINT.contains("vidocq:dev or vidocq:run"));
    }
}
