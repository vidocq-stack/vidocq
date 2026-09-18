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
package io.vidocq.runtime.spi.report;

import java.util.Collection;

/**
 * The section a {@link StartupReportContributor} writes, provided by Vidocq. Every method returns
 * this section, so that calls chain.
 *
 * <p>Vidocq renders every value: it replaces control characters, line breaks included, and cuts
 * long values and long lists to size. A contributor passes plain values and never formats, pads or
 * logs them.
 *
 * <p>At {@link Verbosity#OFF} every method is a no-op for the report, except that
 * {@link #anomaly} is still logged.
 */
public interface StartupReportSection {

    /**
     * The one line that stands for this section, printed at {@link Verbosity#SUMMARY} and
     * {@link Verbosity#DETAILED}, such as {@code 2 tools, 1 prompt, 0 resources}. The last call wins.
     *
     * @param text the line
     * @return this section
     */
    StartupReportSection summary(String text);

    /**
     * A {@code key value} row, printed at {@link Verbosity#DETAILED} only.
     *
     * @param key   the name of the row, such as {@code protocols}
     * @param value the value, printed with {@link String#valueOf(Object)}
     * @return this section
     */
    StartupReportSection row(String key, Object value);

    /**
     * A named list, printed at {@link Verbosity#DETAILED} only. Vidocq prints the first items and
     * then how many more there are.
     *
     * @param key   the name of the list, such as {@code tools}
     * @param items the items; {@code null} or empty prints nothing
     * @return this section
     */
    StartupReportSection list(String key, Collection<String> items);

    /**
     * Whether a secret is set, printed as {@code configured} or {@code not configured}. The value
     * itself is never passed, so it can never be printed.
     *
     * @param key        the name of the secret, such as {@code requestStateSecret}
     * @param configured whether it is set
     * @return this section
     */
    StartupReportSection secret(String key, boolean configured);

    /**
     * An HTTP listener this contributor started, for HTTP servers only. The other sections' routes
     * on it are printed with this base URI in front.
     *
     * @param name         the listener name, such as {@code default}
     * @param boundBaseUri the URI it is bound to, such as {@code http://localhost:8081/}
     * @return this section
     */
    StartupReportSection listener(String name, String boundBaseUri);

    /**
     * A route this contributor serves, for routing bricks only. Vidocq joins it with the base URI of
     * the {@linkplain #listener listener} of that name when it renders the report, whichever section
     * declared the listener and in whatever order.
     *
     * @param listener the listener name, such as {@code default}
     * @param method   the HTTP method, such as {@code POST}
     * @param path     the path on that listener, such as {@code /mcp}
     * @param handler  what handles it: the binary name of its class, then {@code #} and its method, such
     *                 as {@code com.acme.McpEndpoint#handlePost}. The class is what
     *                 {@link StartupReportContext#routeUrls} matches; the report prints its simple name,
     *                 {@code McpEndpoint#handlePost}
     * @return this section
     */
    StartupReportSection route(String listener, String method, String path, String handler);

    /**
     * Something wrong that does not stop the boot. Logged at once as its own WARNING record,
     * {@code [CODE] message}, at every {@link Verbosity}, {@link Verbosity#OFF} included, and recalled
     * in the report's {@code anomalies} section.
     *
     * @param code    a stable code, such as {@code VAUBAN-003}; never {@code null}
     * @param message what is broken and its probable cause; never {@code null}
     * @param hint    how to fix it, or {@code null}
     * @return this section
     */
    StartupReportSection anomaly(String code, String message, String hint);
}
