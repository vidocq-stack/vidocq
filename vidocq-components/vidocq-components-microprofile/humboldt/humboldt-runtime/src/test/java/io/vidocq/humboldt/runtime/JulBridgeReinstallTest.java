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
package io.vidocq.humboldt.runtime;

import io.vidocq.humboldt.sdk.log.SdkLoggerProvider;
import io.vidocq.humboldt.sdk.log.SimpleLogRecordProcessor;
import io.vidocq.humboldt.sdk.log.bridge.HumboldtJulHandler;
import io.vidocq.humboldt.sdk.log.export.InMemoryLogRecordExporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.logging.Handler;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The JUL bridge follows the latest configuration: a second deployment in the same JVM, or a hot reload, got its JUL
 * records sent to the first deployment's logger provider, already shut down, because the bridge was installed once
 * and kept (BUG-20261030-03).
 */
class JulBridgeReinstallTest {

    private static final Logger ROOT = Logger.getLogger("");

    @AfterEach
    void removeBridges() {
        for (Handler handler : ROOT.getHandlers()) {
            if (handler instanceof HumboldtJulHandler) {
                ROOT.removeHandler(handler);
            }
        }
    }

    private static SdkLoggerProvider providerExportingTo(InMemoryLogRecordExporter exporter) {
        return SdkLoggerProvider.builder().addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter)).build();
    }

    @Test
    void theLatestConfigurationReceivesTheJulRecords() {
        InMemoryLogRecordExporter first = InMemoryLogRecordExporter.create();
        InMemoryLogRecordExporter second = InMemoryLogRecordExporter.create();

        HumboldtAutoConfigure.installJulBridge(providerExportingTo(first));
        HumboldtAutoConfigure.installJulBridge(providerExportingTo(second));
        Logger.getLogger("jul-bridge-test").warning("after the second deployment");

        assertEquals(1, Arrays.stream(ROOT.getHandlers()).filter(HumboldtJulHandler.class::isInstance).count());
        assertEquals(0, first.getCollected().size());
        assertEquals(1, second.getCollected().size());
    }
}
