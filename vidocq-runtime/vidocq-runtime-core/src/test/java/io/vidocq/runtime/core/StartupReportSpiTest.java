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

import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportContributor;
import io.vidocq.runtime.spi.report.StartupReportSection;
import io.vidocq.runtime.spi.report.Verbosity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The startup report SPI, {@code io.vidocq.runtime.spi.report}: the SPI module has no tests of its own. */
class StartupReportSpiTest {

    @Test
    void theLevelsAndTheModesComeInTheirDocumentedOrder() {
        assertEquals(List.of(Verbosity.OFF, Verbosity.SUMMARY, Verbosity.DETAILED), List.of(Verbosity.values()));
        assertEquals(List.of(LaunchMode.DEV, LaunchMode.TEST, LaunchMode.PROD), List.of(LaunchMode.values()));
    }

    @Test
    void aModeIsWrittenInLowerCase() {
        assertEquals(List.of("dev", "test", "prod"),
                List.of(LaunchMode.DEV.label(), LaunchMode.TEST.label(), LaunchMode.PROD.label()));
    }

    @ParameterizedTest
    @CsvSource({"dev, DEV", "' Dev ', DEV", "TEST, TEST", "prod, PROD", "'\tProd\n', PROD"})
    void aModeIsReadIgnoringCaseAndSurroundingBlanks(String value, LaunchMode mode) {
        assertEquals(Optional.of(mode), LaunchMode.parse(value));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "staging", "auto", "de v", "development"})
    void aValueThatNamesNoModeReadsAsNone(String value) {
        assertEquals(Optional.empty(), LaunchMode.parse(value));
    }

    @Test
    void aContributorIsTitledByItsIdAndOrderedAtOneThousandByDefault() {
        StartupReportContributor mcp = new StartupReportContributor() {
            @Override
            public String id() {
                return "mcp";
            }

            @Override
            public void contribute(StartupReportContext context, StartupReportSection section) {
                // nothing to report
            }
        };

        assertEquals("mcp", mcp.title());
        assertEquals(1000, mcp.order());
    }

    @Test
    void theSpiExportsTheReportPackageToEveryoneAndTheCoreUsesTheContributors() {
        ModuleDescriptor spi = descriptor(StartupReportContributor.class);
        ModuleDescriptor core = descriptor(VidocqBootstrap.class);

        assertEquals("io.vidocq.runtime.spi", spi.name());
        assertTrue(spi.exports().stream().anyMatch(export ->
                export.source().equals("io.vidocq.runtime.spi.report") && !export.isQualified()), spi.exports()
                .toString());
        assertEquals("io.vidocq.runtime.core", core.name());
        assertTrue(core.uses().contains(StartupReportContributor.class.getName()), core.uses().toString());
    }

    /** The descriptor of {@code type}'s module, read from its archive when the tests run on the class path. */
    private static ModuleDescriptor descriptor(Class<?> type) {
        Module module = type.getModule();
        if (module.isNamed()) {
            return module.getDescriptor();
        }
        try {
            Path archive = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            return ModuleFinder.of(archive).findAll().iterator().next().descriptor();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
