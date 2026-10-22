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

package io.vidocq.bodoni.core.tags.fmt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Locale;

import io.vidocq.bodoni.core.testing.Body;
import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestHttpServletRequest;
import io.vidocq.bodoni.core.testing.TestHttpServletResponse;
import io.vidocq.bodoni.core.testing.TestPageContext;

import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.PageContext;
import jakarta.servlet.jsp.jstl.core.Config;
import jakarta.servlet.jsp.jstl.fmt.LocalizationContext;

import org.junit.jupiter.api.Test;

class SetBundleTagTest {

    private final TestPageContext pc = TestPageContext.create();

    @Test
    void varStoresTheContextInTheScopedVariable() throws Exception {
        ((TestHttpServletRequest) pc.getRequest()).setLocales(Locale.ENGLISH);
        SetBundleTag tag = new SetBundleTag();
        tag.setBasename("test-bundles.ex1.Resources");
        tag.setVar("b");
        tag.setScope("request");
        TagHarness.run(tag, pc, Body.NONE);
        LocalizationContext ctx = assertInstanceOf(LocalizationContext.class,
                pc.getAttribute("b", PageContext.REQUEST_SCOPE));
        assertEquals("en", ctx.getResourceBundle().getString("greeting"));
        assertEquals(Locale.ENGLISH, ctx.getLocale());
        assertNull(Config.get(pc, Config.FMT_LOCALIZATION_CONTEXT, PageContext.REQUEST_SCOPE));
    }

    @Test
    void withoutVarTheContextBecomesTheDefault() throws Exception {
        ((TestHttpServletRequest) pc.getRequest()).setLocales(Locale.CANADA_FRENCH);
        SetBundleTag tag = new SetBundleTag();
        tag.setBasename("test-bundles.ex1.Resources");
        tag.setScope("session");
        TagHarness.run(tag, pc, Body.NONE);
        LocalizationContext ctx = assertInstanceOf(LocalizationContext.class,
                Config.get(pc, Config.FMT_LOCALIZATION_CONTEXT, PageContext.SESSION_SCOPE));
        assertEquals("fr_CA", ctx.getResourceBundle().getString("greeting"));
        assertNull(Config.get(pc, Config.FMT_LOCALIZATION_CONTEXT, PageContext.PAGE_SCOPE));
    }

    @Test
    void responseLocaleIsSet() throws Exception {
        ((TestHttpServletRequest) pc.getRequest()).setLocales(Locale.CANADA_FRENCH);
        SetBundleTag tag = new SetBundleTag();
        tag.setBasename("test-bundles.ex1.Resources");
        TagHarness.run(tag, pc, Body.NONE);
        assertEquals(Locale.CANADA_FRENCH, ((TestHttpServletResponse) pc.getResponse()).getLocale());
    }

    @Test
    void emptyBasenameStoresAContextWithoutBundle() throws Exception {
        SetBundleTag tag = new SetBundleTag();
        tag.setBasename("");
        tag.setVar("b");
        TagHarness.run(tag, pc, Body.NONE);
        LocalizationContext ctx = assertInstanceOf(LocalizationContext.class, pc.getAttribute("b"));
        assertNull(ctx.getResourceBundle());
    }

    @Test
    void invalidScopeIsRejected() {
        SetBundleTag tag = new SetBundleTag();
        tag.setBasename("test-bundles.ex1.Resources");
        tag.setScope("nope");
        assertThrows(JspTagException.class, () -> TagHarness.run(tag, pc, Body.NONE));
    }

    @Test
    void reusedInstanceDoesNotKeepPreviousVarOrScope() throws Exception {
        SetBundleTag tag = new SetBundleTag();
        tag.setBasename("test-bundles.ex1.Resources");
        tag.setVar("b");
        tag.setScope("request");
        TagHarness.run(tag, pc, Body.NONE);
        tag.release();
        tag.setBasename("test-bundles.ex1.Resources");
        TagHarness.run(tag, pc, Body.NONE);
        assertInstanceOf(LocalizationContext.class,
                Config.get(pc, Config.FMT_LOCALIZATION_CONTEXT, PageContext.PAGE_SCOPE));
    }
}
