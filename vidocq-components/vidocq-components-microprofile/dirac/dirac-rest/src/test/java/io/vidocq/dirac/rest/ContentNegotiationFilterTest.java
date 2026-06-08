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
package io.vidocq.dirac.rest;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitaires M7 — ContentNegotiationFilter (§2.3 spec MP Metrics 5.1.1).
 */
class ContentNegotiationFilterTest {

    private final ContentNegotiationFilter filter = new ContentNegotiationFilter();

    @Test
    void nullAcceptDefaultsToTextPlain() {
        var ctx = new FakeRequestContext(null);
        filter.filter(ctx);
        assertEquals(MediaType.TEXT_PLAIN, ctx.getHeaderString(HttpHeaders.ACCEPT));
    }

    @Test
    void blankAcceptDefaultsToTextPlain() {
        var ctx = new FakeRequestContext("  ");
        filter.filter(ctx);
        assertEquals(MediaType.TEXT_PLAIN, ctx.getHeaderString(HttpHeaders.ACCEPT));
    }

    @Test
    void wildcardAcceptDefaultsToTextPlain() {
        var ctx = new FakeRequestContext(MediaType.WILDCARD);
        filter.filter(ctx);
        assertEquals(MediaType.TEXT_PLAIN, ctx.getHeaderString(HttpHeaders.ACCEPT));
    }

    @Test
    void jsonAcceptIsPreserved() {
        var ctx = new FakeRequestContext(MediaType.APPLICATION_JSON);
        filter.filter(ctx);
        assertEquals(MediaType.APPLICATION_JSON, ctx.getHeaderString(HttpHeaders.ACCEPT));
    }

    @Test
    void textPlainAcceptIsPreserved() {
        var ctx = new FakeRequestContext(MediaType.TEXT_PLAIN);
        filter.filter(ctx);
        assertEquals(MediaType.TEXT_PLAIN, ctx.getHeaderString(HttpHeaders.ACCEPT));
    }

    // Minimal fake ContainerRequestContext — only what the filter touches.
    private static final class FakeRequestContext implements ContainerRequestContext {
        private final MultivaluedHashMap<String, String> headers = new MultivaluedHashMap<>();

        FakeRequestContext(String accept) {
            if (accept != null) {
                headers.putSingle(HttpHeaders.ACCEPT, accept);
            }
        }

        @Override
        public String getHeaderString(String name) {
            var values = headers.get(name);
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        @Override
        public jakarta.ws.rs.core.MultivaluedMap<String, String> getHeaders() {
            return headers;
        }

        // All other methods throw UnsupportedOperationException — not needed for this filter.
        @Override public Object getProperty(String name) { throw new UnsupportedOperationException(); }
        @Override public java.util.Collection<String> getPropertyNames() { throw new UnsupportedOperationException(); }
        @Override public void setProperty(String name, Object object) { throw new UnsupportedOperationException(); }
        @Override public void removeProperty(String name) { throw new UnsupportedOperationException(); }
        @Override public jakarta.ws.rs.core.UriInfo getUriInfo() { throw new UnsupportedOperationException(); }
        @Override public void setRequestUri(java.net.URI requestUri) { throw new UnsupportedOperationException(); }
        @Override public void setRequestUri(java.net.URI baseUri, java.net.URI requestUri) { throw new UnsupportedOperationException(); }
        @Override public jakarta.ws.rs.core.Request getRequest() { throw new UnsupportedOperationException(); }
        @Override public String getMethod() { throw new UnsupportedOperationException(); }
        @Override public void setMethod(String method) { throw new UnsupportedOperationException(); }
        @Override public java.util.Date getDate() { throw new UnsupportedOperationException(); }
        @Override public java.util.Locale getLanguage() { throw new UnsupportedOperationException(); }
        @Override public int getLength() { throw new UnsupportedOperationException(); }
        @Override public MediaType getMediaType() { throw new UnsupportedOperationException(); }
        @Override public java.util.List<MediaType> getAcceptableMediaTypes() { throw new UnsupportedOperationException(); }
        @Override public java.util.List<java.util.Locale> getAcceptableLanguages() { throw new UnsupportedOperationException(); }
        @Override public java.util.Map<String, jakarta.ws.rs.core.Cookie> getCookies() { throw new UnsupportedOperationException(); }
        @Override public boolean hasEntity() { throw new UnsupportedOperationException(); }
        @Override public java.io.InputStream getEntityStream() { throw new UnsupportedOperationException(); }
        @Override public void setEntityStream(java.io.InputStream input) { throw new UnsupportedOperationException(); }
        @Override public jakarta.ws.rs.core.SecurityContext getSecurityContext() { throw new UnsupportedOperationException(); }
        @Override public void setSecurityContext(jakarta.ws.rs.core.SecurityContext context) { throw new UnsupportedOperationException(); }
        @Override public void abortWith(jakarta.ws.rs.core.Response response) { throw new UnsupportedOperationException(); }
        @Override public boolean containsHeaderString(String name, String valueSeparatorRegex, java.util.function.Predicate<String> valuePredicate) { throw new UnsupportedOperationException(); }
    }
}
