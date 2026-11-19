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
package io.vidocq.runtime.extensions.microprofile.humboldt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Telemetry extension brings no test library to the application: {@code opentelemetry-sdk-testing} reached the
 * {@code lib/} of every packaged application through it (BUG-20261030-17, item 3; humboldt BUG-20261030-04).
 */
class NoTestLibraryTest {

    private static final String SDK_TESTING = "io.opentelemetry.sdk.testing";

    @Test
    void theExtensionDoesNotRequireTheSdkTestingModule() {
        Module extension = HumboldtExtension.class.getModule();

        assertTrue(extension.getDescriptor() == null
                        || extension.getDescriptor().requires().stream().noneMatch(r -> r.name().equals(SDK_TESTING)),
                "the extension requires " + SDK_TESTING);
    }

    @Test
    void theSdkTestingLibraryIsNotADependency() {
        assertTrue(ModuleLayer.boot().findModule(SDK_TESTING).isEmpty()
                        && HumboldtExtension.class.getClassLoader()
                        .getResource("io/opentelemetry/sdk/testing/trace/TestSpanData.class") == null,
                "opentelemetry-sdk-testing is on the extension's runtime path");
    }
}
