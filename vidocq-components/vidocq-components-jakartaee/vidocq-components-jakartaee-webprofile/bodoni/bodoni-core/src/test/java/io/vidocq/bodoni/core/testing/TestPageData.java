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

package io.vidocq.bodoni.core.testing;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.jsp.tagext.PageData;

/** A {@link PageData} whose XML view is a string, served as UTF-8 bytes. */
public final class TestPageData extends PageData {

    /** The namespace of the Pages standard actions. */
    public static final String JSP_NAMESPACE = "http://java.sun.com/JSP/Page";

    private final String xml;

    /**
     * Serves the given XML view.
     *
     * @param xml the whole document
     */
    public TestPageData(String xml) {
        this.xml = xml;
    }

    /**
     * Wraps a body in the {@code jsp:root} element a container gives to validators.
     *
     * @param body           the content of the root element
     * @param prefixUriPairs alternating prefixes and namespace URIs to declare on the root
     * @return the page data
     */
    public static TestPageData view(String body, String... prefixUriPairs) {
        StringBuilder root = new StringBuilder("<jsp:root xmlns:jsp=\"").append(JSP_NAMESPACE).append('"');
        for (int i = 0; i < prefixUriPairs.length; i += 2) {
            root.append(" xmlns:").append(prefixUriPairs[i]).append("=\"").append(prefixUriPairs[i + 1]).append('"');
        }
        root.append(" version=\"2.0\" jsp:id=\"0\">").append(body).append("</jsp:root>");
        return new TestPageData(root.toString());
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }
}
