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
package io.vidocq.runtime.extensions.essentials.devconsole;

import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The answers the page offers to download, in its pure half: which types, and the name of the saved file. */
class DownloadCoreTest {

    private static PageCore core;

    @BeforeAll
    static void loadTheModule() {
        core = new PageCore();
    }

    @AfterAll
    static void closeTheEngine() {
        core.close();
    }

    private static String download(String type) {
        return core.json(core.run("downloadOf", type));
    }

    private static String name(String actionId, String type) {
        Value now = core.eval("() => new Date(2026, 10, 16, 14, 0, 5)");
        return core.run("downloadName", actionId, now, core.run("downloadOf", type)).asString();
    }

    @Test
    void csvAndSqlAreDownloadsInUtf8() {
        assertEquals("{\"extension\":\"csv\",\"blob\":\"text/csv;charset=utf-8\"}", download("text/csv"));
        assertEquals("{\"extension\":\"sql\",\"blob\":\"application/sql;charset=utf-8\"}", download("application/sql"));
    }

    @Test
    void otherTypesAreNoDownload() {
        for (String type : new String[] {"text/plain", "application/json", "application/x-rows+json", "text/csvx"}) {
            assertTrue(core.run("downloadOf", type).isNull(), type);
        }
        assertTrue(core.run("downloadOf", (Object) null).isNull());
    }

    @Test
    void theFileIsNamedAfterTheActionAndTheTimeWithTheTypesExtension() {
        assertEquals("ddl.create-20261116-140005.sql", name("ddl.create", "application/sql"));
        assertEquals("jdql.export-20261116-140005.csv", name("jdql.export", "text/csv"));
    }

    @Test
    void anActionIdKeepsTheCharactersOfAFileNameOnly() {
        assertEquals("a-b-c.d_e-20261116-140005.sql", name("a b/c.d_e", "application/sql"));
    }
}
