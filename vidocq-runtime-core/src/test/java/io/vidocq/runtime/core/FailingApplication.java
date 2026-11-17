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

import io.vidocq.runtime.spi.VidocqApp;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.Dependent;

/** An application class the container knows but cannot create: its {@code @PostConstruct} throws. */
@Dependent
public class FailingApplication implements VidocqApp {

    static final String FAILURE = "the post-construct callback of FailingApplication failed";

    @PostConstruct
    void initialise() {
        throw new IllegalStateException(FAILURE);
    }

    @Override
    public int run(String... args) {
        return 4;
    }
}
