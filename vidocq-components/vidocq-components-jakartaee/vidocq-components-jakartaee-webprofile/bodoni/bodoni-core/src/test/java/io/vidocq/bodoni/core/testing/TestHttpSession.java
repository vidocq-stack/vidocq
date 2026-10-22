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

import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSession;

/** A map-backed {@link HttpSession}. */
public class TestHttpSession implements HttpSession {

    private final TestServletContext context;
    private final Map<String, Object> attributes = new LinkedHashMap<>();

    public TestHttpSession(TestServletContext context) {
        this.context = context;
    }

    @Override
    public String getId() {
        return "T";
    }

    @Override
    public ServletContext getServletContext() {
        return context;
    }

    @Override
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(attributes.keySet().stream().toList());
    }

    @Override
    public void setAttribute(String name, Object value) {
        if (value == null) {
            attributes.remove(name);
        } else {
            attributes.put(name, value);
        }
    }

    @Override
    public void removeAttribute(String name) {
        attributes.remove(name);
    }

    @Override
    public long getCreationTime() {
        throw nope();
    }

    @Override
    public long getLastAccessedTime() {
        throw nope();
    }

    @Override
    public void setMaxInactiveInterval(int interval) {
        throw nope();
    }

    @Override
    public int getMaxInactiveInterval() {
        throw nope();
    }

    @Override
    public void invalidate() {
        throw nope();
    }

    @Override
    public boolean isNew() {
        throw nope();
    }

    private static UnsupportedOperationException nope() {
        return new UnsupportedOperationException("not needed by Bodoni tests");
    }
}
