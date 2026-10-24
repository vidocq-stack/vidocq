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

package io.vidocq.bodoni.core.tags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.vidocq.bodoni.core.testing.TldReader;

/**
 * The JSTL 1.0 request-time URIs ({@code http://java.sun.com/jstl/core_rt} and its three siblings) are
 * served by the 3.0 handlers: each request-time descriptor is its 3.0 descriptor with the legacy URI and
 * short name, and with no deferred value, since JSTL 1.0 predates deferred expressions.
 */
class LegacyRtTldTest {

    private static final String ROWS = """
            META-INF/c.tld,   META-INF/c-1_0-rt.tld,   http://java.sun.com/jstl/core_rt, c_rt
            META-INF/fmt.tld, META-INF/fmt-1_0-rt.tld, http://java.sun.com/jstl/fmt_rt,  fmt_rt
            META-INF/sql.tld, META-INF/sql-1_0-rt.tld, http://java.sun.com/jstl/sql_rt,  sql_rt
            META-INF/x.tld,   META-INF/x-1_0-rt.tld,   http://java.sun.com/jstl/xml_rt,  x_rt
            """;

    @ParameterizedTest
    @CsvSource(textBlock = ROWS)
    void servesTheJstl10RequestTimeUri(String current, String legacy, String uri, String shortName) {
        TldReader rt = TldReader.read(legacy);
        assertEquals(uri, rt.uri());
        assertEquals(shortName, rt.shortName());
        assertEquals("3.0", rt.tlibVersion());
    }

    @ParameterizedTest
    @CsvSource(textBlock = ROWS)
    void exposesTheSameTagsOnTheSameHandlers(String current, String legacy, String uri, String shortName) {
        List<TldReader.Tag> base = TldReader.read(current).tags();
        List<TldReader.Tag> rt = TldReader.read(legacy).tags();
        assertEquals(base.stream().map(TldReader.Tag::name).toList(), rt.stream().map(TldReader.Tag::name).toList());
        for (int i = 0; i < base.size(); i++) {
            TldReader.Tag want = base.get(i);
            TldReader.Tag got = rt.get(i);
            assertEquals(want.tagClass(), got.tagClass(), want.name() + " tag-class");
            assertEquals(want.bodyContent(), got.bodyContent(), want.name() + " body-content");
        }
    }

    @ParameterizedTest
    @CsvSource(textBlock = ROWS)
    void keepsEveryAttributeWithItsDynamicBehaviour(String current, String legacy, String uri, String shortName) {
        List<TldReader.Tag> base = TldReader.read(current).tags();
        List<TldReader.Tag> rt = TldReader.read(legacy).tags();
        assertEquals(base.size(), rt.size(), legacy + " tag count");
        for (int i = 0; i < base.size(); i++) {
            TldReader.Tag tag = base.get(i);
            List<TldReader.Attribute> want = tag.attributes();
            List<TldReader.Attribute> got = rt.get(i).attributes();
            assertEquals(want.stream().map(TldReader.Attribute::name).toList(),
                    got.stream().map(TldReader.Attribute::name).toList(), tag.name() + " attribute names");
            for (int j = 0; j < want.size(); j++) {
                TldReader.Attribute w = want.get(j);
                TldReader.Attribute g = got.get(j);
                String where = tag.name() + "@" + w.name();
                assertEquals(w.required(), g.required(), where + " required");
                assertEquals(w.rtexprvalue(), g.rtexprvalue(), where + " rtexprvalue");
                assertFalse(g.deferred(), where + " has no deferred value in JSTL 1.0");
                String type = w.deferred() && w.rtexprvalue() ? "java.lang.Object" : w.type();
                assertEquals(type, g.type(), where + " type");
            }
        }
    }
}
