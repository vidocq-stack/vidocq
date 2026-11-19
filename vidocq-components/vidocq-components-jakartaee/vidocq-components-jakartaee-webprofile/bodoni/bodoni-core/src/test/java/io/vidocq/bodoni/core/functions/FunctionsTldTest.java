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

package io.vidocq.bodoni.core.functions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.vidocq.bodoni.core.testing.TldReader;

class FunctionsTldTest {

    private static final Set<String> NAMES = Set.of("contains", "containsIgnoreCase", "endsWith",
            "escapeXml", "indexOf", "join", "length", "replace", "split", "startsWith", "substring",
            "substringAfter", "substringBefore", "toLowerCase", "toUpperCase", "trim");

    @ParameterizedTest
    @CsvSource({
            "META-INF/fn.tld,jakarta.tags.functions",
            "META-INF/fn-1_2.tld,http://java.sun.com/jsp/jstl/functions"})
    void declaresTheSixteenFunctions(String resource, String uri) throws Exception {
        TldReader tld = TldReader.read(resource);
        assertEquals(uri, tld.uri());
        assertEquals("fn", tld.shortName());
        List<TldReader.Function> functions = tld.functions();
        assertEquals(NAMES, new TreeSet<>(functions.stream().map(TldReader.Function::name).toList()));
        assertEquals(16, functions.size());
        for (TldReader.Function f : functions) {
            assertEquals(Functions.class.getName(), f.functionClass());
            Method m = resolve(f.signature());
            assertNotNull(m, f.signature());
            assertTrue(Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers()),
                    f.signature());
            assertEquals(f.name(), m.getName());
        }
    }

    @Test
    void theTwoFilesDifferOnlyByTheUri() throws IOException {
        String a = read("META-INF/fn.tld");
        String b = read("META-INF/fn-1_2.tld");
        assertEquals(a.replaceAll("<uri>[^<]*</uri>", "<uri/>"), b.replaceAll("<uri>[^<]*</uri>", "<uri/>"));
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = FunctionsTldTest.class.getClassLoader().getResourceAsStream(resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Method resolve(String signature) throws Exception {
        int open = signature.indexOf('(');
        String head = signature.substring(0, open).trim();
        String name = head.substring(head.lastIndexOf(' ') + 1);
        String paramList = signature.substring(open + 1, signature.lastIndexOf(')')).trim();
        String[] names = paramList.isEmpty() ? new String[0] : paramList.split("\\s*,\\s*");
        Class<?>[] types = new Class<?>[names.length];
        for (int i = 0; i < names.length; i++) {
            types[i] = type(names[i]);
        }
        return Functions.class.getMethod(name, types);
    }

    private static Class<?> type(String n) throws ClassNotFoundException {
        if (n.endsWith("[]")) {
            return type(n.substring(0, n.length() - 2)).arrayType();
        }
        return switch (n) {
            case "int" -> int.class;
            case "boolean" -> boolean.class;
            default -> Class.forName(n);
        };
    }
}
