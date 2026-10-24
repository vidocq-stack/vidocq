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

package io.vidocq.bodoni.core.tags.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.jsp.tagext.TagLibraryValidator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.vidocq.bodoni.core.testing.TldReader;

/** Checks the sql tag library descriptors against the attribute tables of Tags 3.0 §10.3-§10.8. */
class SqlTldTest {

    /** Expected attribute: required, rtexprvalue, declared type. */
    private record Attr(boolean required, boolean rtexprvalue, Class<?> type) {
    }

    private record Expected(String tagClass, String bodyContent, Map<String, Attr> attributes) {
    }

    private static final String PACKAGE = "io.vidocq.bodoni.core.tags.sql.";

    private static Attr dyn(Class<?> type) {
        return new Attr(false, true, type);
    }

    private static Attr reqDyn(Class<?> type) {
        return new Attr(true, true, type);
    }

    private static Attr fixed() {
        return new Attr(false, false, String.class);
    }

    private static final Map<String, Expected> SPEC = new LinkedHashMap<>();

    static {
        // §10.5
        SPEC.put("transaction", new Expected(PACKAGE + "TransactionTag", "JSP",
                attrs("dataSource", dyn(Object.class), "isolation", dyn(String.class))));
        // §10.3
        SPEC.put("query", new Expected(PACKAGE + "QueryTag", "JSP",
                attrs("sql", dyn(String.class), "dataSource", dyn(Object.class), "maxRows", dyn(int.class),
                        "startRow", dyn(int.class), "var", new Attr(true, false, String.class), "scope",
                        fixed())));
        // §10.4
        SPEC.put("update", new Expected(PACKAGE + "UpdateTag", "JSP", attrs("sql", dyn(String.class),
                "dataSource", dyn(Object.class), "var", fixed(), "scope", fixed())));
        // §10.7
        SPEC.put("param", new Expected(PACKAGE + "ParamTag", "JSP", attrs("value", dyn(Object.class))));
        // §10.8
        SPEC.put("dateParam", new Expected(PACKAGE + "DateParamTag", "empty",
                attrs("value", reqDyn(java.util.Date.class), "type", dyn(String.class))));
        // §10.6
        SPEC.put("setDataSource", new Expected(PACKAGE + "SetDataSourceTag", "empty",
                attrs("dataSource", dyn(Object.class), "driver", dyn(String.class), "url", dyn(String.class),
                        "user", dyn(String.class), "password", dyn(String.class), "var", fixed(), "scope",
                        fixed())));
    }

    private static Map<String, Attr> attrs(Object... pairs) {
        Map<String, Attr> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Attr) pairs[i + 1]);
        }
        return map;
    }

    @ParameterizedTest
    @CsvSource({ "META-INF/sql.tld,jakarta.tags.sql,sql", "META-INF/sql-1_2.tld,http://java.sun.com/jsp/jstl/sql,sql",
            "META-INF/sql-1_0-rt.tld,http://java.sun.com/jstl/sql_rt,sql_rt" })
    void libraryIdentity(String resource, String uri, String shortName) {
        TldReader tld = TldReader.read(resource);
        assertEquals(uri, tld.uri());
        assertEquals(shortName, tld.shortName());
    }

    @ParameterizedTest
    @CsvSource({ "META-INF/sql.tld", "META-INF/sql-1_2.tld", "META-INF/sql-1_0-rt.tld" })
    void declaresExactlyTheSixTags(String resource) {
        List<String> names = TldReader.read(resource).tags().stream().map(TldReader.Tag::name).toList();
        assertEquals(List.copyOf(SPEC.keySet()), names);
    }

    @ParameterizedTest
    @CsvSource({ "META-INF/sql.tld", "META-INF/sql-1_2.tld" })
    void attributesAndBodyContentMatchTheSpecification(String resource) {
        for (TldReader.Tag tag : TldReader.read(resource).tags()) {
            Expected expected = SPEC.get(tag.name());
            assertNotNull(expected, tag.name());
            assertEquals(expected.tagClass(), tag.tagClass(), tag.name() + " tag-class");
            assertEquals(expected.bodyContent(), tag.bodyContent(), tag.name() + " body-content");
            assertEquals(expected.attributes().keySet().stream().sorted().toList(),
                    tag.attributes().stream().map(TldReader.Attribute::name).sorted().toList(),
                    tag.name() + " attribute names");
            for (TldReader.Attribute a : tag.attributes()) {
                Attr want = expected.attributes().get(a.name());
                assertEquals(want.required(), a.required(), tag.name() + "@" + a.name() + " required");
                assertEquals(want.rtexprvalue(), a.rtexprvalue(), tag.name() + "@" + a.name() + " rtexprvalue");
                assertEquals(want.type().getName(), a.type(), tag.name() + "@" + a.name() + " type");
            }
        }
    }

    @ParameterizedTest
    @CsvSource({ "META-INF/sql.tld", "META-INF/sql-1_2.tld", "META-INF/sql-1_0-rt.tld" })
    void handlersExposeEveryDeclaredAttribute(String resource) throws ClassNotFoundException {
        for (TldReader.Tag tag : TldReader.read(resource).tags()) {
            Class<?> type = Class.forName(tag.tagClass());
            assertTrue(Modifier.isPublic(type.getModifiers()), tag.tagClass() + " is public");
            assertTrue(Modifier.isPublic(constructorModifiers(type)), tag.tagClass() + " no-arg constructor");
            for (TldReader.Attribute a : tag.attributes()) {
                String setter = "set" + Character.toUpperCase(a.name().charAt(0)) + a.name().substring(1);
                boolean found = false;
                for (Method m : type.getMethods()) {
                    if (m.getName().equals(setter) && m.getParameterCount() == 1
                            && m.getParameterTypes()[0].getName().equals(a.type())) {
                        found = true;
                    }
                }
                assertTrue(found, tag.tagClass() + " has public " + setter + "(" + a.type() + ")");
            }
        }
    }

    @ParameterizedTest
    @CsvSource({ "META-INF/sql.tld", "META-INF/sql-1_2.tld", "META-INF/sql-1_0-rt.tld" })
    void declaresTheLibraryValidator(String resource) throws ClassNotFoundException {
        String name = TldReader.read(resource).validatorClass();
        assertEquals(SqlTagLibraryValidator.class.getName(), name, resource + " validator-class");
        Class<?> type = Class.forName(name);
        assertTrue(Modifier.isPublic(type.getModifiers()), name + " is public");
        assertEquals(TagLibraryValidator.class, type.getSuperclass(), name + " extends the API class directly");
        assertTrue(Modifier.isPublic(constructorModifiers(type)), name + " no-arg constructor");
    }

    private static int constructorModifiers(Class<?> type) {
        try {
            return type.getConstructor().getModifiers();
        } catch (NoSuchMethodException e) {
            throw new AssertionError(type + " has no public no-arg constructor", e);
        }
    }

    @Test
    void thePrefixedAndLegacyDescriptorsDifferOnlyByUri() throws IOException {
        String current = read("META-INF/sql.tld");
        String legacy = read("META-INF/sql-1_2.tld");
        assertEquals(current.replace("<uri>jakarta.tags.sql</uri>", "<uri>X</uri>"),
                legacy.replace("<uri>http://java.sun.com/jsp/jstl/sql</uri>", "<uri>X</uri>"));
        assertTrue(current.contains("<uri>jakarta.tags.sql</uri>"));
        assertTrue(legacy.contains("<uri>http://java.sun.com/jsp/jstl/sql</uri>"));
    }

    private static String read(String resource) throws IOException {
        try (var in = SqlTldTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
