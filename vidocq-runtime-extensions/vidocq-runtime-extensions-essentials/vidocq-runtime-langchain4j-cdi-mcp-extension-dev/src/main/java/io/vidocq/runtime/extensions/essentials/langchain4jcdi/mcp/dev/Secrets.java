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
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Spec §3.5: an argument whose name holds, ignoring case, one of the {@link #MARKERS} is shown as {@value #MASK} in
 * the history, the details and the console's log line. The MCP server receives the real value, and a result's body
 * is shown as the server returned it. Since a server's error message may quote a value, the string values of such
 * arguments, {@value #MIN_SCRUBBED} characters or longer, are also replaced wherever they appear in the summary and
 * the details.
 */
final class Secrets {

    /** What stands for a secret. */
    static final String MASK = "***";
    /** The shortest value scrubbed from a text: a shorter one would mask ordinary words. */
    static final int MIN_SCRUBBED = 4;
    /** What makes a name a secret's, ignoring case. */
    static final List<String> MARKERS = List.of("password", "passwd", "secret", "token", "apikey", "api-key",
            "api_key", "credential", "authorization");

    private Secrets() {}

    /** Whether {@code name} is a secret's. */
    static boolean secret(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return MARKERS.stream().anyMatch(lower::contains);
    }

    /** {@code value} with every member whose name is a secret's replaced by {@value #MASK}, at any depth. */
    static JsonValue mask(JsonValue value) {
        if (value instanceof JsonObject object) {
            JsonObjectBuilder out = Json.createObjectBuilder();
            object.forEach((name, member) -> out.add(name, secret(name) ? Json.createValue(MASK) : mask(member)));
            return out.build();
        }
        if (value instanceof JsonArray array) {
            JsonArrayBuilder out = Json.createArrayBuilder();
            array.forEach(item -> out.add(mask(item)));
            return out.build();
        }
        return value;
    }

    /** The string values of the members of {@code value} whose name is a secret's, at any depth. */
    static Set<String> values(JsonValue value) {
        Set<String> found = new LinkedHashSet<>();
        collect(value, found);
        return found;
    }

    /** {@code text} with each of {@code secrets} of {@value #MIN_SCRUBBED} characters or more replaced. */
    static String scrub(String text, Collection<String> secrets) {
        if (text == null) {
            return null;
        }
        String out = text;
        for (String secret : secrets.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            if (secret.length() >= MIN_SCRUBBED) {
                out = out.replace(secret, MASK);
            }
        }
        return out;
    }

    private static void collect(JsonValue value, Set<String> found) {
        if (value instanceof JsonObject object) {
            object.forEach((name, member) -> {
                if (secret(name) && member instanceof JsonString string) {
                    found.add(string.getString());
                } else {
                    collect(member, found);
                }
            });
        } else if (value instanceof JsonArray array) {
            array.forEach(item -> collect(item, found));
        }
    }
}
