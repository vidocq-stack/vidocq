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

package io.vidocq.bodoni.core.tags.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.transform.Result;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.xml.sax.XMLFilter;

import io.vidocq.bodoni.core.testing.TldReader;

/** Checks the x tag library descriptors against the attribute tables of Tags 3.0 §11.2-§13.3. */
class XmlTldTest {

    /** Expected attribute: required, rtexprvalue, declared type. */
    private record Attr(boolean required, boolean rtexprvalue, Class<?> type) {
    }

    private record Expected(String bodyContent, Map<String, Attr> attributes) {
    }

    private static Attr reqStatic(Class<?> type) {
        return new Attr(true, false, type);
    }

    private static Attr reqDyn(Class<?> type) {
        return new Attr(true, true, type);
    }

    private static Attr optDyn(Class<?> type) {
        return new Attr(false, true, type);
    }

    private static Attr optStatic(Class<?> type) {
        return new Attr(false, false, type);
    }

    private static final Map<String, Expected> SPEC = new LinkedHashMap<>();

    static {
        // §11.2
        SPEC.put("parse", new Expected("JSP", attrs("doc", optDyn(Object.class), "xml", optDyn(Object.class),
                "systemId", optDyn(String.class), "filter", optDyn(XMLFilter.class), "var",
                optStatic(String.class), "scope", optStatic(String.class), "varDom", optStatic(String.class),
                "scopeDom", optStatic(String.class))));
        // §11.3
        SPEC.put("out", new Expected("empty",
                attrs("select", reqStatic(String.class), "escapeXml", optDyn(boolean.class))));
        // §11.4
        SPEC.put("set", new Expected("empty", attrs("select", reqStatic(String.class), "var",
                new Attr(true, false, String.class), "scope", optStatic(String.class))));
        // §12.2
        SPEC.put("if", new Expected("JSP", attrs("select", reqStatic(String.class), "var",
                optStatic(String.class), "scope", optStatic(String.class))));
        // §12.3
        SPEC.put("choose", new Expected("JSP", attrs()));
        // §12.4
        SPEC.put("when", new Expected("JSP", attrs("select", reqStatic(String.class))));
        // §12.5
        SPEC.put("otherwise", new Expected("JSP", attrs()));
        // §12.6
        SPEC.put("forEach", new Expected("JSP", attrs("select", reqStatic(String.class), "var",
                optStatic(String.class), "varStatus", optStatic(String.class), "begin", optDyn(int.class), "end",
                optDyn(int.class), "step", optDyn(int.class))));
        // §13.2
        SPEC.put("transform", new Expected("JSP", attrs("doc", optDyn(Object.class), "xml",
                optDyn(Object.class), "xslt", reqDyn(Object.class), "docSystemId", optDyn(String.class),
                "xmlSystemId", optDyn(String.class), "xsltSystemId", optDyn(String.class), "var",
                optStatic(String.class), "scope", optStatic(String.class), "result", optDyn(Result.class))));
        // §13.3
        SPEC.put("param", new Expected("JSP",
                attrs("name", reqDyn(String.class), "value", optDyn(Object.class))));
    }

    private static Map<String, Attr> attrs(Object... pairs) {
        Map<String, Attr> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Attr) pairs[i + 1]);
        }
        return map;
    }

    @ParameterizedTest
    @CsvSource({ "META-INF/x.tld,jakarta.tags.xml", "META-INF/x-1_2.tld,http://java.sun.com/jsp/jstl/xml" })
    void libraryIdentity(String resource, String uri) {
        TldReader tld = TldReader.read(resource);
        assertEquals(uri, tld.uri());
        assertEquals("x", tld.shortName());
    }

    @ParameterizedTest
    @CsvSource({ "META-INF/x.tld", "META-INF/x-1_2.tld" })
    void declaresExactlyTheTenTags(String resource) {
        List<String> names = TldReader.read(resource).tags().stream().map(TldReader.Tag::name).toList();
        assertEquals(List.copyOf(SPEC.keySet()), names);
    }

    @ParameterizedTest
    @CsvSource({ "META-INF/x.tld", "META-INF/x-1_2.tld" })
    void attributesAndBodyContentMatchTheSpecification(String resource) {
        for (TldReader.Tag tag : TldReader.read(resource).tags()) {
            Expected expected = SPEC.get(tag.name());
            assertNotNull(expected, tag.name());
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
    @CsvSource({ "META-INF/x.tld", "META-INF/x-1_2.tld" })
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
    @CsvSource({ "META-INF/x.tld", "META-INF/x-1_2.tld" })
    void declaresNoValidator(String resource) throws IOException {
        assertFalse(read(resource).contains("<validator>"));
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
        String current = read("META-INF/x.tld");
        String legacy = read("META-INF/x-1_2.tld");
        assertEquals(current.replace("<uri>jakarta.tags.xml</uri>", "<uri>X</uri>"),
                legacy.replace("<uri>http://java.sun.com/jsp/jstl/xml</uri>", "<uri>X</uri>"));
        assertTrue(current.contains("<uri>jakarta.tags.xml</uri>"));
        assertTrue(legacy.contains("<uri>http://java.sun.com/jsp/jstl/xml</uri>"));
    }

    private static String read(String resource) throws IOException {
        try (var in = XmlTldTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
