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
package io.vidocq.runtime.extensions.essentials.chappe;

import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.report.StartupReportView;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** {@link ExtensionContext#startupReport()}: a context written before the report view existed has no report. */
class FakeExtensionContextTest {

    @Test
    void aTestDoubleWrittenBeforeTheReportViewExistedHasNoReport() {
        // FakeExtensionContext implements the three original methods only
        ExtensionContext context = new FakeExtensionContext(TestConfig.of(Map.of()));

        Supplier<Optional<StartupReportView>> report = context.startupReport();

        assertNotNull(report);
        assertEquals(Optional.empty(), report.get());
    }
}
