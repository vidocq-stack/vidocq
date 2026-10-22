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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Locale;

import io.vidocq.bodoni.core.testing.Body;
import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestHttpServletRequest;
import io.vidocq.bodoni.core.testing.TestHttpServletResponse;
import io.vidocq.bodoni.core.testing.TestPageContext;

import jakarta.servlet.jsp.tagext.Tag;
import jakarta.servlet.jsp.jstl.fmt.LocalizationContext;

import org.junit.jupiter.api.Test;

class BundleTagTest {

    private final TestPageContext pc = TestPageContext.create();

    private TestHttpServletRequest request() {
        return (TestHttpServletRequest) pc.getRequest();
    }

    private TestHttpServletResponse response() {
        return (TestHttpServletResponse) pc.getResponse();
    }

    @Test
    void bodyIsRendered() throws Exception {
        BundleTag tag = new BundleTag();
        tag.setBasename("test-bundles.ex1.Resources");
        request().setLocales(Locale.ENGLISH);
        assertEquals("hello", TagHarness.run(tag, pc, p -> p.getOut().print("hello")));
        assertEquals(Tag.EVAL_BODY_INCLUDE, new BundleTag().doStartTag());
    }

    @Test
    void contextFollowsTheDeterminationAlgorithm() throws Exception {
        request().setLocales(Locale.CANADA_FRENCH);
        BundleTag tag = new BundleTag();
        tag.setBasename("test-bundles.ex1.Resources");
        LocalizationContext[] seen = new LocalizationContext[1];
        TagHarness.run(tag, pc, p -> seen[0] = tag.getLocalizationContext());
        assertEquals("fr_CA", seen[0].getResourceBundle().getString("greeting"));
        assertEquals(Locale.CANADA_FRENCH, seen[0].getLocale());
    }

    @Test
    void responseLocaleIsSetWhenTheContextHasOne() throws Exception {
        request().setLocales(Locale.CANADA_FRENCH);
        BundleTag tag = new BundleTag();
        tag.setBasename("test-bundles.ex1.Resources");
        TagHarness.run(tag, pc, Body.EMPTY);
        assertEquals(Locale.CANADA_FRENCH, response().getLocale());
    }

    @Test
    void rootBundleHasNoLocaleSoTheResponseIsUntouched() throws Exception {
        request().setLocales(Locale.JAPANESE);
        Locale before = response().getLocale();
        BundleTag tag = new BundleTag();
        tag.setBasename("test-bundles.root.Resources");
        LocalizationContext[] seen = new LocalizationContext[1];
        TagHarness.run(tag, pc, p -> seen[0] = tag.getLocalizationContext());
        assertNotNull(seen[0].getResourceBundle());
        assertNull(seen[0].getLocale());
        assertEquals(before, response().getLocale());
    }

    @Test
    void emptyBasenameGivesAContextWithoutBundle() throws Exception {
        BundleTag tag = new BundleTag();
        tag.setBasename("");
        LocalizationContext[] seen = new LocalizationContext[1];
        TagHarness.run(tag, pc, p -> seen[0] = tag.getLocalizationContext());
        assertNull(seen[0].getResourceBundle());
        assertNull(seen[0].getLocale());
    }

    @Test
    void unknownBundleGivesAContextWithoutBundle() throws Exception {
        BundleTag tag = new BundleTag();
        tag.setBasename("test-bundles.nothing.Resources");
        TagHarness.run(tag, pc, Body.EMPTY);
        assertNull(tag.getLocalizationContext().getResourceBundle());
    }

    @Test
    void prefixIsExposed() throws Exception {
        BundleTag tag = new BundleTag();
        tag.setBasename("test-bundles.ex1.Resources");
        tag.setPrefix("com.acme.");
        TagHarness.run(tag, pc, Body.EMPTY);
        assertEquals("com.acme.", tag.getPrefix());
    }

    @Test
    void reusedInstanceDoesNotKeepPreviousContextOrPrefix() throws Exception {
        request().setLocales(Locale.ENGLISH);
        BundleTag tag = new BundleTag();
        tag.setBasename("test-bundles.ex1.Resources");
        tag.setPrefix("p.");
        TagHarness.run(tag, pc, Body.EMPTY);
        tag.release();
        assertNull(tag.getPrefix());
        assertNull(tag.getLocalizationContext());

        BundleTag second = new BundleTag();
        second.setBasename("test-bundles.ex1.Resources");
        TagHarness.run(second, pc, Body.EMPTY);
        assertNull(second.getPrefix());
    }
}
