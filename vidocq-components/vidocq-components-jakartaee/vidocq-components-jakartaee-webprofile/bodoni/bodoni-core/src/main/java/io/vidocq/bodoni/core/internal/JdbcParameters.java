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

package io.vidocq.bodoni.core.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * The JDBC parameters of a string data source, {@code url[,[driver][,[user][,password]]]}
 * (Tags 3.0 §10.1.1, §10.2). References nothing outside {@code java.base}.
 *
 * @param url the JDBC URL
 * @param driver the driver class name, {@code null} when absent or empty
 * @param user the user, {@code ""} when absent
 * @param password the password, {@code ""} when absent
 */
public record JdbcParameters(String url, String driver, String user, String password) {

    private static final char SEPARATOR = ',';
    private static final char ESCAPE = '\\';
    private static final int FIELDS = 4;
    private static final String AUTHORITY = "//";

    /**
     * Parses a JDBC parameter string. It splits on unescaped {@code ,} into at most four
     * fields, the fourth (the password) taking the rest of the string, commas included;
     * {@code \,} stands for {@code ,} and {@code \\} for {@code \}, a {@code \} before any
     * other character is kept as is.
     *
     * @throws NullPointerException when {@code parameters} is {@code null}
     */
    public static JdbcParameters parse(String parameters) {
        List<String> fields = new ArrayList<>(FIELDS);
        StringBuilder field = new StringBuilder();
        int length = parameters.length();
        int index = 0;
        while (index < length) {
            char current = parameters.charAt(index);
            if (current == ESCAPE && index + 1 < length && isEscaped(parameters.charAt(index + 1))) {
                field.append(parameters.charAt(index + 1));
                index += 2;
                continue;
            }
            if (current == SEPARATOR && fields.size() < FIELDS - 1) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(current);
            }
            index++;
        }
        fields.add(field.toString());
        String driver = fields.size() > 1 && !fields.get(1).isEmpty() ? fields.get(1) : null;
        String user = fields.size() > 2 ? fields.get(2) : "";
        String password = fields.size() > 3 ? fields.get(3) : "";
        return new JdbcParameters(fields.get(0), driver, user, password);
    }

    /**
     * The name every message uses for a string data source, a JNDI name or a JDBC parameter
     * string: {@code parse(dataSource).redactedUrl()}.
     */
    public static String redact(String dataSource) {
        return parse(dataSource).redactedUrl();
    }

    private static boolean isEscaped(char next) {
        return next == SEPARATOR || next == ESCAPE;
    }

    /**
     * The URL without what may carry a secret: cut at its first {@code ;} or {@code ?}, and
     * without the user information of a {@code //user:password@host} authority.
     */
    public String redactedUrl() {
        String cut = url;
        for (int index = 0; index < cut.length(); index++) {
            char current = cut.charAt(index);
            if (current == ';' || current == '?') {
                cut = cut.substring(0, index);
                break;
            }
        }
        int slashes = cut.indexOf(AUTHORITY);
        if (slashes < 0) {
            return cut;
        }
        int start = slashes + AUTHORITY.length();
        int end = cut.indexOf('/', start);
        if (end < 0) {
            end = cut.length();
        }
        int at = cut.lastIndexOf('@', end - 1);
        return at < start ? cut : cut.substring(0, start) + cut.substring(at + 1);
    }

    /** The redacted URL and the driver, never the user nor the password. */
    @Override
    public String toString() {
        return "JdbcParameters[url=" + redactedUrl() + ", driver=" + driver + "]";
    }
}
