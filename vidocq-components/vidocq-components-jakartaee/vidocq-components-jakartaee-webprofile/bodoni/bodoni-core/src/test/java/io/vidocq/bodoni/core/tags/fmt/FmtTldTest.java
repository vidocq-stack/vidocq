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

package io.vidocq.bodoni.core.tags.fmt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.vidocq.bodoni.core.testing.TldReader;
import jakarta.servlet.jsp.jstl.fmt.LocalizationContext;

/** Checks the fmt tag library descriptors against the attribute tables of Tags 3.0 §8.5-§8.10 and §9.5-§9.10. */
class FmtTldTest {

    /** Expected attribute: required, rtexprvalue, declared type. */
    private record Attr(boolean required, boolean rtexprvalue, Class<?> type) {
    }

    private record Expected(String bodyContent, Map<String, Attr> attributes) {
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
        // §8.10
        SPEC.put("requestEncoding", new Expected("empty", attrs("value", optDyn(String.class))));
        // §8.5
        SPEC.put("setLocale", new Expected("empty", attrs("value", reqDyn(Object.class), "variant",
                optDyn(String.class), "scope", optStatic(String.class))));
        // §9.5
        SPEC.put("timeZone", new Expected("JSP", attrs("value", reqDyn(Object.class))));
        // §9.6
        SPEC.put("setTimeZone", new Expected("empty", attrs("value", reqDyn(Object.class), "var",
                optStatic(String.class), "scope", optStatic(String.class))));
        // §8.6
        SPEC.put("bundle", new Expected("JSP", attrs("basename", reqDyn(String.class), "prefix",
                optDyn(String.class))));
        // §8.7
        SPEC.put("setBundle", new Expected("empty", attrs("basename", reqDyn(String.class), "var",
                optStatic(String.class), "scope", optStatic(String.class))));
        // §8.8
        SPEC.put("message", new Expected("JSP", attrs("key", optDyn(String.class), "bundle",
                optDyn(LocalizationContext.class), "var", optStatic(String.class), "scope",
                optStatic(String.class))));
        // §8.9
        SPEC.put("param", new Expected("JSP", attrs("value", optDyn(Object.class))));
        // §9.7
        SPEC.put("formatNumber", new Expected("JSP", attrs("value", optDyn(Object.class), "type",
                optDyn(String.class), "pattern", optDyn(String.class), "currencyCode", optDyn(String.class),
                "currencySymbol", optDyn(String.class), "groupingUsed", optDyn(boolean.class),
                "maxIntegerDigits", optDyn(int.class), "minIntegerDigits", optDyn(int.class),
                "maxFractionDigits", optDyn(int.class), "minFractionDigits", optDyn(int.class), "var",
                optStatic(String.class), "scope", optStatic(String.class))));
        // §9.8
        SPEC.put("parseNumber", new Expected("JSP", attrs("value", optDyn(String.class), "type",
                optDyn(String.class), "pattern", optDyn(String.class), "parseLocale", optDyn(Object.class),
                "integerOnly", optDyn(boolean.class), "var", optStatic(String.class), "scope",
                optStatic(String.class))));
        // §9.9
        SPEC.put("formatDate", new Expected("empty", attrs("value", reqDyn(Date.class), "type",
                optDyn(String.class), "dateStyle", optDyn(String.class), "timeStyle", optDyn(String.class),
                "pattern", optDyn(String.class), "timeZone", optDyn(Object.class), "var",
                optStatic(String.class), "scope", optStatic(String.class))));
        // §9.10
        SPEC.put("parseDate", new Expected("JSP", attrs("value", optDyn(String.class), "type",
                optDyn(String.class), "dateStyle", optDyn(String.class), "timeStyle", optDyn(String.class),
                "pattern", optDyn(String.class), "timeZone", optDyn(Object.class), "parseLocale",
                optDyn(Object.class), "var", optStatic(String.class), "scope", optStatic(String.class))));
    }

    private static Map<String, Attr> attrs(Object... pairs) {
        Map<String, Attr> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Attr) pairs[i + 1]);
        }
        return map;
    }

    @ParameterizedTest
    @CsvSource({ "META-INF/fmt.tld,jakarta.tags.fmt", "META-INF/fmt-1_2.tld,http://java.sun.com/jsp/jstl/fmt" })
    void libraryIdentity(String resource, String uri) {
        TldReader tld = TldReader.read(resource);
        assertEquals(uri, tld.uri());
        assertEquals("fmt", tld.shortName());
    }

    @ParameterizedTest
    @CsvSource({ "META-INF/fmt.tld", "META-INF/fmt-1_2.tld" })
    void declaresExactlyTheTwelveTags(String resource) {
        List<String> names = TldReader.read(resource).tags().stream().map(TldReader.Tag::name).toList();
        assertEquals(List.copyOf(SPEC.keySet()), names);
    }

    @ParameterizedTest
    @CsvSource({ "META-INF/fmt.tld", "META-INF/fmt-1_2.tld" })
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
    @CsvSource({ "META-INF/fmt.tld", "META-INF/fmt-1_2.tld" })
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

    private static int constructorModifiers(Class<?> type) {
        try {
            return type.getConstructor().getModifiers();
        } catch (NoSuchMethodException e) {
            throw new AssertionError(type + " has no public no-arg constructor", e);
        }
    }

    @Test
    void thePrefixedAndLegacyDescriptorsDifferOnlyByUri() throws IOException {
        String current = read("META-INF/fmt.tld");
        String legacy = read("META-INF/fmt-1_2.tld");
        assertEquals(current.replace("<uri>jakarta.tags.fmt</uri>", "<uri>X</uri>"),
                legacy.replace("<uri>http://java.sun.com/jsp/jstl/fmt</uri>", "<uri>X</uri>"));
        assertTrue(current.contains("<uri>jakarta.tags.fmt</uri>"));
        assertTrue(legacy.contains("<uri>http://java.sun.com/jsp/jstl/fmt</uri>"));
    }

    private static String read(String resource) throws IOException {
        try (var in = FmtTldTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
