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

import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands a resource template's URI for a read (RFC 6570, level 1): each {@code {name}} replaced by its value, every
 * byte of its UTF-8 form but the unreserved characters percent-encoded, which langchain4j-cdi's matcher decodes.
 */
final class UriTemplates {

    private static final Pattern VARIABLE = Pattern.compile("\\{([^}]+)}");
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private UriTemplates() {}

    /**
     * @param template the URI template, such as {@code time://zone/{zone}}
     * @param values   the variables by name; a missing one expands to nothing, a non-string one to its JSON text
     * @return the URI
     */
    static String expand(String template, jakarta.json.JsonObject values) {
        Matcher variable = VARIABLE.matcher(template);
        StringBuilder out = new StringBuilder();
        while (variable.find()) {
            JsonValue value = values.get(variable.group(1));
            String text = value == null || value.getValueType() == JsonValue.ValueType.NULL ? ""
                    : value instanceof JsonString string ? string.getString() : value.toString();
            variable.appendReplacement(out, Matcher.quoteReplacement(encode(text)));
        }
        variable.appendTail(out);
        return out.toString();
    }

    /** {@code text} with every byte of its UTF-8 form but {@code A-Z a-z 0-9 - . _ ~} percent-encoded. */
    static String encode(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~';
            if (unreserved) {
                out.append((char) c);
            } else {
                out.append('%').append(HEX[c >> 4]).append(HEX[c & 0xf]);
            }
        }
        return out.toString();
    }
}
