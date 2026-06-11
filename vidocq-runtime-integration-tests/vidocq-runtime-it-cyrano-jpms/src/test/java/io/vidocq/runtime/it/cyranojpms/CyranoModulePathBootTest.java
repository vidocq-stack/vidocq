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
package io.vidocq.runtime.it.cyranojpms;

import io.vidocq.runtime.core.VidocqBootstrap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VID-001: booting the Vidocq runtime with the Cyrano wrapper on the module
 * path must process the wrapper's {@code CyranoBuildCompatibleExtension}
 * (instantiated reflectively by vauban-core's {@code BceProcessor}) without an
 * {@code IllegalAccessException}. Before the wrapper gained its
 * {@code opens ... to io.vidocq.vauban.core}, this boot failed with
 * "BCE processing failed ... does not export ... to module io.vidocq.vauban.core"
 * — exactly like the historical cervantes case, and invisible on the class path.
 */
class CyranoModulePathBootTest {

    /**
     * Sentinel: this test is only meaningful on the module path. If surefire
     * ever silently degrades to the class path (e.g. the main module-info
     * disappears), fail loudly instead of green-washing.
     */
    @Test
    void runsAsNamedModule() {
        Module self = CyranoJpmsFixture.class.getModule();
        assertTrue(self.isNamed(),
                "this IT must run on the module path, not the class path");
        Module wrapper = io.vidocq.runtime.core.VidocqBootstrap.class.getModule();
        assertTrue(wrapper.isNamed(), "vidocq-runtime-core must be a named module here");
    }

    @Test
    void bootsWithTheCyranoWrapperOnTheModulePath() {
        VidocqBootstrap bootstrap = VidocqBootstrap.create().configure().start();
        try {
            // The boot itself is the assertion: BceProcessor instantiated the
            // wrapper's BCE reflectively across module boundaries. Belt and
            // braces: the wrapper module must still be strictly encapsulated
            // apart from the single qualified opens to vauban-core.
            Module wrapper = ModuleLayer.boot()
                    .findModule("io.vidocq.runtime.extensions.microprofile.cyrano")
                    .orElseThrow();
            var descriptor = wrapper.getDescriptor();
            assertFalse(descriptor.opens().isEmpty(),
                    "the wrapper must carry the VID-001 qualified opens");
            assertTrue(descriptor.opens().stream().allMatch(o ->
                            o.isQualified() && o.targets().contains("io.vidocq.vauban.core")),
                    "every opens must be qualified to io.vidocq.vauban.core only: " + descriptor.opens());
        } finally {
            bootstrap.shutdown();
        }
    }
}
