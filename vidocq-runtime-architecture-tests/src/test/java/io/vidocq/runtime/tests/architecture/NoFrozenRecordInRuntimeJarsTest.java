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
package io.vidocq.runtime.tests.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * No module of the runtime carries a record of frozen build compatible extensions: a record belongs to an
 * application. A jar that carries one gives two records to each application that depends on it, and a start that
 * finds two uses none and runs its extensions (BUG-20261118-04).
 */
class NoFrozenRecordInRuntimeJarsTest {

    private static final String RECORD = "META-INF/vauban-frozen-extensions.properties";

    private static final String BEAN_LIST = "META-INF/vauban-beans.list";

    /** The module of this reactor that runs {@code vidocq:generate} as a library, to write its bean list. */
    private static final String GENERATING_LIBRARY = "vidocq-runtime-langchain4j-cdi-mcp-extension/";

    /** Where the test class path holds {@code resource}, one location per module that carries it. */
    private static List<String> locationsOf(String resource) throws IOException {
        return Collections.list(NoFrozenRecordInRuntimeJarsTest.class.getClassLoader().getResources(resource))
                .stream()
                .map(URL::toString)
                .toList();
    }

    @Test
    void theLibraryThatRunsGenerateIsRead() throws IOException {
        // Guards the rule below: a class path without the module that runs vidocq:generate would make it pass.
        List<String> beanLists = locationsOf(BEAN_LIST);

        assertTrue(beanLists.stream().anyMatch(location -> location.contains(GENERATING_LIBRARY)),
                beanLists::toString);
    }

    @Test
    void noRuntimeModuleCarriesARecordOfFrozenExtensions() throws IOException {
        assertEquals(List.of(), locationsOf(RECORD),
                "a library module that runs vidocq:generate sets <freezeExtensions>false</freezeExtensions>");
    }
}
