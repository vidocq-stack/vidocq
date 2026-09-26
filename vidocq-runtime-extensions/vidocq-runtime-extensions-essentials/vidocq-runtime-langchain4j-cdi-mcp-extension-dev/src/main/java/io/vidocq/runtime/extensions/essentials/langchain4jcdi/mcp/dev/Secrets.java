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
import jakarta.json.JsonNumber;
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
 * is shown as the server returned it. Since a server's error message may quote a value, the string and number values
 * of such arguments, {@value #MIN_SCRUBBED} characters or longer, are also replaced wherever they appear in the
 * summary, the details and the body of an error.
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

    /** The string and number values of the members of {@code value} whose name is a secret's, at any depth. */
    static Set<String> values(JsonValue value) {
        Set<String> found = new LinkedHashSet<>();
        collect(value, found);
        return found;
    }

    /**
     * {@code value} with every {@link JsonString} leaf (never a member name) scrubbed of {@code secrets} (see
     * {@link #scrub}), at any depth. Scrubbing the tree before it is serialized, rather than the serialized text,
     * is what lets a secret containing a quote, a backslash, a control character or a non-ASCII character (escaped
     * by the JSON-P provider) be found and replaced: as a decoded string it matches, where the escaped text would
     * not.
     */
    static JsonValue scrubTree(JsonValue value, Collection<String> secrets) {
        if (value instanceof JsonObject object) {
            JsonObjectBuilder out = Json.createObjectBuilder();
            object.forEach((name, member) -> out.add(name, scrubTree(member, secrets)));
            return out.build();
        }
        if (value instanceof JsonArray array) {
            JsonArrayBuilder out = Json.createArrayBuilder();
            array.forEach(item -> out.add(scrubTree(item, secrets)));
            return out.build();
        }
        if (value instanceof JsonString string) {
            return Json.createValue(scrub(string.getString(), secrets));
        }
        return value;
    }

    /**
     * {@code text} with each of {@code secrets} of {@value #MIN_SCRUBBED} characters or more replaced, along with
     * its JSON-escaped form (what {@link Json#createValue(String)} would serialize it as, without the surrounding
     * quotes): {@code text} may itself hold raw JSON (an SSE event, a response kept as text), where a secret with a
     * quote, a backslash or another character the provider escapes appears in its escaped form instead of its
     * decoded one.
     */
    static String scrub(String text, Collection<String> secrets) {
        if (text == null) {
            return null;
        }
        Set<String> patterns = new LinkedHashSet<>();
        for (String secret : secrets) {
            if (secret.length() >= MIN_SCRUBBED) {
                patterns.add(secret);
                patterns.add(escapedBody(secret));
            }
        }
        String out = text;
        for (String pattern : patterns.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            out = out.replace(pattern, MASK);
        }
        return out;
    }

    /** {@code secret} as a JSON string's body, without its surrounding quotes. */
    private static String escapedBody(String secret) {
        String quoted = Json.createValue(secret).toString();
        return quoted.substring(1, quoted.length() - 1);
    }

    private static void collect(JsonValue value, Set<String> found) {
        if (value instanceof JsonObject object) {
            object.forEach((name, member) -> {
                if (secret(name)) {
                    collectLeaves(member, found);
                } else {
                    collect(member, found);
                }
            });
        } else if (value instanceof JsonArray array) {
            array.forEach(item -> collect(item, found));
        }
    }

    /**
     * Every {@link JsonString} leaf beneath {@code value}, and every {@link JsonNumber} leaf whose text is
     * {@value #MIN_SCRUBBED} characters or longer (a one-time code), at any depth: the value of a secret-named member.
     * A boolean is left out: {@code true} or {@code false} would mask ordinary words.
     */
    private static void collectLeaves(JsonValue value, Set<String> found) {
        if (value instanceof JsonString string) {
            found.add(string.getString());
        } else if (value instanceof JsonNumber number && number.toString().length() >= MIN_SCRUBBED) {
            found.add(number.toString());
        } else if (value instanceof JsonObject object) {
            object.forEach((name, member) -> collectLeaves(member, found));
        } else if (value instanceof JsonArray array) {
            array.forEach(item -> collectLeaves(item, found));
        }
    }
}
