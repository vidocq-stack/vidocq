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
package io.vidocq.runtime.ext.chappe;

import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.config.VidocqConfig;

import java.util.Optional;

/** Minimal {@link ExtensionContext} stub for integration testing. */
final class FakeExtensionContext implements ExtensionContext {

    private final VidocqConfig config;

    FakeExtensionContext(VidocqConfig config) {
        this.config = config;
    }

    @Override
    public VaubanContainer container() {
        return null;
    }

    @Override
    public VidocqConfiguration configuration() {
        return new VidocqConfiguration() {
            @Override public Optional<String> property(String key) { return config.getValue(key); }
        };
    }

    @Override
    public VidocqConfig config() {
        return config;
    }
}
