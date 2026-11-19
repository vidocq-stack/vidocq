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

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Opens absolute URLs for {@code c:import} (Tags 3.0 §7.4, "Absolute URL"). Exists so tests
 * never touch the network; production code uses {@link #jdk()}.
 */
public interface ExternalResources {

    /**
     * Opens the resource and returns its content as characters.
     *
     * @param absoluteUrl the absolute URL
     * @param charEncoding the declared encoding, or {@code null}/empty to use the
     *                     {@code Content-Type} of the response, then {@code ISO-8859-1}
     * @throws IOException if the resource cannot be read, or an HTTP status is not 2xx
     */
    Reader open(String absoluteUrl, String charEncoding) throws IOException;

    /** The implementation backed by the JDK ({@code java.net.http} and {@code URLConnection}). */
    static ExternalResources jdk() {
        return JdkExternalResources.INSTANCE;
    }

    /**
     * Picks the character set: {@code charEncoding} when set, else the {@code charset}
     * parameter of {@code contentType}, else {@code ISO-8859-1} (§7.4).
     *
     * @throws IllegalArgumentException if {@code charEncoding} is not a supported charset
     */
    static Charset charsetOf(String charEncoding, String contentType) {
        if (charEncoding != null && !charEncoding.isBlank()) {
            return Charset.forName(charEncoding.trim());
        }
        if (contentType != null) {
            String lower = contentType.toLowerCase(Locale.ROOT);
            int at = lower.indexOf("charset=");
            if (at >= 0) {
                String value = contentType.substring(at + "charset=".length());
                int end = value.indexOf(';');
                if (end >= 0) {
                    value = value.substring(0, end);
                }
                value = value.trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                try {
                    return Charset.forName(value);
                } catch (IllegalArgumentException _) {
                    // an unusable declared charset falls back to the default below
                }
            }
        }
        return StandardCharsets.ISO_8859_1;
    }
}
