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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.vidocq.bodoni.core.testing.TldReader;

/**
 * The fmt and xml handlers share package-private support classes ({@code tags.fmt}, {@code tags.xml}):
 * from outside those packages, as a container or generated page code calls them, every setter the TLD
 * declares must still be found on the public handler class and be invocable.
 */
class SetterAccessTest {

    @ParameterizedTest
    @CsvSource({ "META-INF/fmt.tld", "META-INF/x.tld", "META-INF/sql.tld", "META-INF/fmt-1_0-rt.tld", "META-INF/x-1_0-rt.tld",
            "META-INF/sql-1_0-rt.tld" })
    void everyDeclaredSetterIsInvocableFromAnotherPackage(String resource) throws ReflectiveOperationException {
        for (TldReader.Tag tag : TldReader.read(resource).tags()) {
            Class<?> type = Class.forName(tag.tagClass());
            Object handler = type.getConstructor().newInstance();
            for (TldReader.Attribute a : tag.attributes()) {
                String setter = "set" + Character.toUpperCase(a.name().charAt(0)) + a.name().substring(1);
                Class<?> parameter = parameterType(a.type());
                Method method = type.getMethod(setter, parameter);
                assertTrue(Modifier.isPublic(method.getDeclaringClass().getModifiers()),
                        tag.tagClass() + "." + setter + " is declared by a public class");
                // The JSTL API's ConditionalTagSupport.setScope rejects null: give scope a legal value.
                method.invoke(handler, a.name().startsWith("scope") ? "page" : defaultValue(parameter));
            }
        }
    }

    private static Class<?> parameterType(String name) throws ClassNotFoundException {
        return switch (name) {
            case "boolean" -> boolean.class;
            case "int" -> int.class;
            default -> Class.forName(name);
        };
    }

    private static Object defaultValue(Class<?> parameter) {
        if (parameter == boolean.class) {
            return Boolean.FALSE;
        }
        if (parameter == int.class) {
            return 0;
        }
        return null;
    }
}
