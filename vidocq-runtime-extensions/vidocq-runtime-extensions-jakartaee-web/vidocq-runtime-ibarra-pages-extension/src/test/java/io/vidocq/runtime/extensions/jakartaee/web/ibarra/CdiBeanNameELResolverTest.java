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
package io.vidocq.runtime.extensions.jakartaee.web.ibarra;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.el.ELContext;
import jakarta.el.ELResolver;
import jakarta.el.ExpressionFactory;
import jakarta.el.PropertyNotWritableException;
import jakarta.el.StandardELContext;
import jakarta.el.ELContextListener;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.jsp.JspApplicationContext;
import jakarta.servlet.jsp.JspEngineInfo;
import jakarta.servlet.jsp.JspFactory;
import jakarta.servlet.jsp.PageContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

class CdiBeanNameELResolverTest {

    private final AtomicInteger lookups = new AtomicInteger();
    private final CdiBeanNameELResolver resolver = new CdiBeanNameELResolver((name, ctx) -> {
        lookups.incrementAndGet();
        return "cart".equals(name) ? Optional.<Supplier<Object>>of(() -> "the cart") : Optional.empty();
    });
    private final ELContext context = new StandardELContext(ExpressionFactory.newInstance());

    @Test
    void aBeanNameResolvesToItsReference() {
        assertEquals("the cart", resolver.getValue(context, null, "cart"));
        assertTrue(context.isPropertyResolved());
    }

    @Test
    void anUnknownNameAPropertyOfABaseAndANonStringAreLeftToTheChain() {
        assertNull(resolver.getValue(context, null, "unknown"));
        assertFalse(context.isPropertyResolved());
        assertNull(resolver.getValue(context, "base", "cart"));
        assertNull(resolver.getValue(context, null, 7));
        assertFalse(context.isPropertyResolved());
        assertFalse(resolver.isReadOnly(context, null, "unknown"));
        assertNull(resolver.getType(context, null, "unknown"));
        resolver.setValue(context, null, "unknown", "x");
        assertFalse(context.isPropertyResolved());
    }

    @Test
    void aBeanNameIsReadOnly() {
        assertTrue(resolver.isReadOnly(context, null, "cart"));
        assertTrue(context.isPropertyResolved());
        context.setPropertyResolved(false);
        assertNull(resolver.getType(context, null, "cart"));
        assertTrue(context.isPropertyResolved());
        assertThrows(PropertyNotWritableException.class, () -> resolver.setValue(context, null, "cart", "x"));
    }

    @Test
    void namesAreStringsAtTheTopLevelOnly() {
        assertEquals(String.class, resolver.getCommonPropertyType(context, null));
        assertNull(resolver.getCommonPropertyType(context, "base"));
        assertThrows(NullPointerException.class, () -> resolver.getValue(null, null, "cart"));
        assertThrows(NullPointerException.class, () -> new CdiBeanNameELResolver(null));
        assertThrows(NullPointerException.class, () -> CdiBeanNameELResolver.of(null));
    }

    @Test
    void thePagesGetTheResolverOnceDeployedWhenThereIsAJspEngineAndACdiContainer() {
        var listener = new CdiPageNames();
        JspFactory previous = JspFactory.getDefaultFactory();
        try {
            JspFactory.setDefaultFactory(null);
            listener.deployed(null, null);

            var application = new RecordingApplication();
            JspFactory.setDefaultFactory(new SingleApplicationFactory(application));
            listener.deployed(null, null);
            assertTrue(application.resolvers.isEmpty(), "no CDI container, no resolver");

            CdiPageNames.register(null, null, (name, ctx) -> Optional.empty());
            CdiPageNames.register(JspFactory.getDefaultFactory(), null, (name, ctx) -> Optional.empty());
            assertEquals(1, application.resolvers.size());
            assertInstanceOf(CdiBeanNameELResolver.class, application.resolvers.getFirst());
        } finally {
            JspFactory.setDefaultFactory(previous);
        }
    }

    private static final class RecordingApplication implements JspApplicationContext {
        final List<ELResolver> resolvers = new ArrayList<>();
        @Override public void addELResolver(ELResolver resolver) { resolvers.add(resolver); }
        @Override public ExpressionFactory getExpressionFactory() { return ExpressionFactory.newInstance(); }
        @Override public void addELContextListener(ELContextListener listener) {
            // Not used by the listener under test.
        }
    }

    private static final class SingleApplicationFactory extends JspFactory {
        private final JspApplicationContext application;
        SingleApplicationFactory(JspApplicationContext application) { this.application = application; }
        @Override public PageContext getPageContext(Servlet s, ServletRequest q, ServletResponse r, String e,
                boolean session, int buffer, boolean flush) { return null; }
        @Override public void releasePageContext(PageContext page) {
            // Not used by the listener under test.
        }
        @Override public JspEngineInfo getEngineInfo() { return null; }
        @Override public JspApplicationContext getJspApplicationContext(ServletContext context) { return application; }
    }
}
