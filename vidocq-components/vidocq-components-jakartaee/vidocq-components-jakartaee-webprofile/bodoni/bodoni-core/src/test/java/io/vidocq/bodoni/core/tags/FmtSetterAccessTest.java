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

import org.junit.jupiter.api.Test;

import io.vidocq.bodoni.core.testing.TldReader;

/**
 * The fmt handlers share package-private support classes in {@code tags.fmt}: from outside that
 * package, as a container or generated page code calls them, every setter the TLD declares must
 * still be found on the public handler class and be invocable.
 */
class FmtSetterAccessTest {

    @Test
    void everyDeclaredSetterIsInvocableFromAnotherPackage() throws ReflectiveOperationException {
        for (TldReader.Tag tag : TldReader.read("META-INF/fmt.tld").tags()) {
            Class<?> type = Class.forName(tag.tagClass());
            Object handler = type.getConstructor().newInstance();
            for (TldReader.Attribute a : tag.attributes()) {
                String setter = "set" + Character.toUpperCase(a.name().charAt(0)) + a.name().substring(1);
                Class<?> parameter = parameterType(a.type());
                Method method = type.getMethod(setter, parameter);
                assertTrue(Modifier.isPublic(method.getDeclaringClass().getModifiers()),
                        tag.tagClass() + "." + setter + " is declared by a public class");
                method.invoke(handler, defaultValue(parameter));
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
