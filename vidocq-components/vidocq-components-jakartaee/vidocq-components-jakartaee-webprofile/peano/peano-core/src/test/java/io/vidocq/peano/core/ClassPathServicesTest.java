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
package io.vidocq.peano.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An application on the class path reads {@code META-INF/services}, never the {@code provides} of
 * {@code module-info.java}: without the file, {@code ExpressionFactory.newInstance()} does not find Peano there.
 */
class ClassPathServicesTest {

    @Test
    void theExpressionFactoryIsListedForTheClassPath() throws IOException {
        var providers = new ArrayList<String>();
        var resources = getClass().getClassLoader().getResources("META-INF/services/jakarta.el.ExpressionFactory");
        while (resources.hasMoreElements()) {
            try (var in = resources.nextElement().openStream()) {
                providers.addAll(List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")));
            }
        }

        assertTrue(providers.contains("io.vidocq.peano.core.internal.PeanoExpressionFactory"), providers::toString);
    }
}
