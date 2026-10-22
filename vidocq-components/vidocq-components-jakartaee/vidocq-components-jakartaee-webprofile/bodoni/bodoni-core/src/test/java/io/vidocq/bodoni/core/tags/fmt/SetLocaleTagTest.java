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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import org.junit.jupiter.api.Test;

class SetLocaleTagTest {

    private final TestPageContext pc = TestPageContext.create();

    private TestHttpServletResponse response() {
        return (TestHttpServletResponse) pc.getResponse();
    }

    @Test
    void stringValueIsParsedAndStoredInPageScope() throws Exception {
        SetLocaleTag tag = new SetLocaleTag();
        tag.setValue("fr_CA");
        TagHarness.run(tag, pc, Body.NONE);
        assertEquals(Locale.CANADA_FRENCH, Config.find(pc, Config.FMT_LOCALE));
        assertEquals(Locale.CANADA_FRENCH, Config.get(pc, Config.FMT_LOCALE, PageContext.PAGE_SCOPE));
        assertNull(Config.get(pc, Config.FMT_LOCALE, PageContext.SESSION_SCOPE));
    }

    @Test
    void scopeSelectsWhereItIsStored() throws Exception {
        SetLocaleTag tag = new SetLocaleTag();
        tag.setValue("fr-CA");
        tag.setScope("session");
        TagHarness.run(tag, pc, Body.NONE);
        assertEquals(Locale.CANADA_FRENCH, Config.get(pc, Config.FMT_LOCALE, PageContext.SESSION_SCOPE));
        assertNull(Config.get(pc, Config.FMT_LOCALE, PageContext.PAGE_SCOPE));
    }

    @Test
    void variantCompletesAStringValue() throws Exception {
        SetLocaleTag tag = new SetLocaleTag();
        tag.setValue("en_US");
        tag.setVariant("WIN");
        TagHarness.run(tag, pc, Body.NONE);
        assertEquals(Locale.of("en", "US", "WIN"), Config.find(pc, Config.FMT_LOCALE));
    }

    @Test
    void variantIsIgnoredForALocaleValue() throws Exception {
        SetLocaleTag tag = new SetLocaleTag();
        tag.setValue(Locale.GERMANY);
        tag.setVariant("WIN");
        TagHarness.run(tag, pc, Body.NONE);
        assertEquals(Locale.GERMANY, Config.find(pc, Config.FMT_LOCALE));
    }

    @Test
    void nullOrEmptyValueUsesTheRuntimeDefault() throws Exception {
        SetLocaleTag tag = new SetLocaleTag();
        tag.setValue("");
        TagHarness.run(tag, pc, Body.NONE);
        assertEquals(Locale.getDefault(), Config.find(pc, Config.FMT_LOCALE));
        pc.removeAttribute(Config.FMT_LOCALE);
        SetLocaleTag nullTag = new SetLocaleTag();
        nullTag.setValue(null);
        TagHarness.run(nullTag, pc, Body.NONE);
        assertEquals(Locale.getDefault(), Config.find(pc, Config.FMT_LOCALE));
    }

    @Test
    void responseLocaleIsAlwaysSet() throws Exception {
        SetLocaleTag tag = new SetLocaleTag();
        tag.setValue("fr");
        TagHarness.run(tag, pc, Body.NONE);
        assertEquals(Locale.FRENCH, response().getLocale());
    }

    @Test
    void sessionCharsetIsRecordedOnlyWhenASessionExists() throws Exception {
        response().mapLocaleEncoding(Locale.JAPANESE, "Shift_JIS");
        SetLocaleTag tag = new SetLocaleTag();
        tag.setValue("ja");
        TagHarness.run(tag, pc, Body.NONE);
        assertEquals("Shift_JIS", pc.getSession().getAttribute("jakarta.servlet.jsp.jstl.fmt.request.charset"));

        TestServletContextHolder none = new TestServletContextHolder();
        SetLocaleTag again = new SetLocaleTag();
        again.setValue("ja");
        TagHarness.run(again, none.pc, Body.NONE);
        assertFalse(none.request.hasSession());
    }

    @Test
    void invalidScopeIsRejected() {
        SetLocaleTag tag = new SetLocaleTag();
        tag.setValue("fr");
        tag.setScope("nope");
        assertThrows(JspTagException.class, () -> TagHarness.run(tag, pc, Body.NONE));
    }

    @Test
    void malformedLocaleIsRejected() {
        SetLocaleTag tag = new SetLocaleTag();
        tag.setValue("french");
        assertThrows(JspTagException.class, () -> TagHarness.run(tag, pc, Body.NONE));
    }

    @Test
    void reusedInstanceDoesNotKeepPreviousAttributes() throws Exception {
        SetLocaleTag tag = new SetLocaleTag();
        tag.setValue("en_US");
        tag.setVariant("WIN");
        tag.setScope("request");
        TagHarness.run(tag, pc, Body.NONE);
        tag.release();
        tag.setValue("fr");
        TagHarness.run(tag, pc, Body.NONE);
        assertEquals(Locale.FRENCH, Config.get(pc, Config.FMT_LOCALE, PageContext.PAGE_SCOPE));
    }

    /** A page context whose request has no session. */
    private static final class TestServletContextHolder {
        final TestHttpServletRequest request;
        final TestPageContext pc;

        TestServletContextHolder() {
            io.vidocq.bodoni.core.testing.TestServletContext ctx = new io.vidocq.bodoni.core.testing.TestServletContext();
            request = new TestHttpServletRequest(ctx).withoutSession();
            pc = new TestPageContext(request, new TestHttpServletResponse(), ctx);
        }
    }
}
