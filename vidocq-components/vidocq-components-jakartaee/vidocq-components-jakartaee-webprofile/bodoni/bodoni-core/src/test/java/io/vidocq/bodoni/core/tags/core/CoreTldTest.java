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

package io.vidocq.bodoni.core.tags.core;

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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.vidocq.bodoni.core.testing.TldReader;

/** Checks the core tag library descriptors against the attribute tables of Tags 3.0 §4-§7. */
class CoreTldTest {

    /** Expected attribute: required, rtexprvalue. */
    private record Attr(boolean required, boolean rtexprvalue) {
    }

    private record Expected(String bodyContent, Map<String, Attr> attributes) {
    }

    private static final Attr REQ_DYN = new Attr(true, true);
    private static final Attr OPT_DYN = new Attr(false, true);
    private static final Attr REQ_STATIC = new Attr(true, false);
    private static final Attr OPT_STATIC = new Attr(false, false);

    private static final Map<String, Expected> SPEC = new LinkedHashMap<>();

    static {
        // §4.2
        SPEC.put("out", new Expected("JSP", attrs("value", REQ_DYN, "escapeXml", OPT_DYN, "default", OPT_DYN)));
        // §4.3
        SPEC.put("set", new Expected("JSP", attrs("value", OPT_DYN, "var", OPT_STATIC, "scope", OPT_STATIC,
                "target", OPT_DYN, "property", OPT_DYN)));
        // §4.4
        SPEC.put("remove", new Expected("empty", attrs("var", REQ_STATIC, "scope", OPT_STATIC)));
        // §4.5
        SPEC.put("catch", new Expected("JSP", attrs("var", OPT_STATIC)));
        // §5.3
        SPEC.put("if", new Expected("JSP", attrs("test", REQ_DYN, "var", OPT_STATIC, "scope", OPT_STATIC)));
        // §5.4
        SPEC.put("choose", new Expected("JSP", attrs()));
        // §5.5
        SPEC.put("when", new Expected("JSP", attrs("test", REQ_DYN)));
        // §5.6
        SPEC.put("otherwise", new Expected("JSP", attrs()));
        // §6.2
        SPEC.put("forEach", new Expected("JSP", attrs("var", OPT_STATIC, "items", OPT_DYN, "varStatus", OPT_STATIC,
                "begin", OPT_DYN, "end", OPT_DYN, "step", OPT_DYN)));
        // §6.3
        SPEC.put("forTokens", new Expected("JSP", attrs("var", OPT_STATIC, "items", REQ_DYN, "delims", REQ_DYN,
                "varStatus", OPT_STATIC, "begin", OPT_DYN, "end", OPT_DYN, "step", OPT_DYN)));
        // §7.4
        SPEC.put("import", new Expected("JSP", attrs("url", REQ_DYN, "context", OPT_DYN, "var", OPT_STATIC,
                "scope", OPT_STATIC, "charEncoding", OPT_DYN, "varReader", OPT_STATIC)));
        // §7.5
        SPEC.put("url", new Expected("JSP", attrs("value", REQ_DYN, "context", OPT_DYN, "var", OPT_STATIC,
                "scope", OPT_STATIC)));
        // §7.6
        SPEC.put("redirect", new Expected("JSP", attrs("url", REQ_DYN, "context", OPT_DYN)));
        // §7.7
        SPEC.put("param", new Expected("JSP", attrs("name", REQ_DYN, "value", OPT_DYN)));
    }

    private static Map<String, Attr> attrs(Object... pairs) {
        Map<String, Attr> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Attr) pairs[i + 1]);
        }
        return map;
    }

    @ParameterizedTest
    @CsvSource({ "META-INF/c.tld,jakarta.tags.core", "META-INF/c-1_2.tld,http://java.sun.com/jsp/jstl/core" })
    void libraryIdentity(String resource, String uri) {
        TldReader tld = TldReader.read(resource);
        assertEquals(uri, tld.uri());
        assertEquals("c", tld.shortName());
    }

    @ParameterizedTest
    @CsvSource({ "META-INF/c.tld", "META-INF/c-1_2.tld" })
    void declaresExactlyTheFourteenTags(String resource) {
        List<String> names = TldReader.read(resource).tags().stream().map(TldReader.Tag::name).toList();
        assertEquals(List.copyOf(SPEC.keySet()), names);
    }

    @ParameterizedTest
    @CsvSource({ "META-INF/c.tld", "META-INF/c-1_2.tld" })
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
                boolean deferred = (tag.name().equals("set") && a.name().equals("value"))
                        || (tag.name().equals("forEach") && a.name().equals("items"));
                assertEquals(deferred, a.deferred(), tag.name() + "@" + a.name() + " deferred-value");
            }
        }
    }

    @ParameterizedTest
    @CsvSource({ "META-INF/c.tld", "META-INF/c-1_2.tld" })
    void handlersExposeEveryDeclaredAttribute(String resource) throws ClassNotFoundException {
        for (TldReader.Tag tag : TldReader.read(resource).tags()) {
            Class<?> type = Class.forName(tag.tagClass());
            assertTrue(Modifier.isPublic(type.getModifiers()), tag.tagClass() + " is public");
            assertTrue(Modifier.isPublic(constructorModifiers(type)), tag.tagClass() + " no-arg constructor");
            for (TldReader.Attribute a : tag.attributes()) {
                String setter = "set" + Character.toUpperCase(a.name().charAt(0)) + a.name().substring(1);
                boolean found = false;
                for (Method m : type.getMethods()) {
                    if (m.getName().equals(setter) && m.getParameterCount() == 1) {
                        found = true;
                    }
                }
                assertTrue(found, tag.tagClass() + " has public " + setter);
            }
        }
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
        String current = read("META-INF/c.tld");
        String legacy = read("META-INF/c-1_2.tld");
        assertEquals(current.replace("<uri>jakarta.tags.core</uri>", "<uri>X</uri>"),
                legacy.replace("<uri>http://java.sun.com/jsp/jstl/core</uri>", "<uri>X</uri>"));
        assertTrue(current.contains("<uri>jakarta.tags.core</uri>"));
        assertTrue(legacy.contains("<uri>http://java.sun.com/jsp/jstl/core</uri>"));
    }

    private static String read(String resource) throws IOException {
        try (var in = CoreTldTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
