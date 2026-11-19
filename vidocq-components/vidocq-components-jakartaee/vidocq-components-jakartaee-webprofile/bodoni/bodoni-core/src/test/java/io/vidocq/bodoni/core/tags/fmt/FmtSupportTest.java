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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import io.vidocq.bodoni.core.internal.I18n;
import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestHttpServletRequest;
import io.vidocq.bodoni.core.testing.TestHttpServletResponse;
import io.vidocq.bodoni.core.testing.TestPageContext;

import jakarta.servlet.jsp.PageContext;
import jakarta.servlet.jsp.jstl.core.Config;
import jakarta.servlet.jsp.jstl.fmt.LocalizationContext;
import jakarta.servlet.jsp.tagext.TagSupport;

import org.junit.jupiter.api.Test;

class FmtSupportTest {

    private static final String ACTION = "fmt:test";
    private static final List<Locale> AVAILABLE = I18n.numberLocales();

    private final TestPageContext pc = TestPageContext.create();

    private TestHttpServletRequest request() {
        return (TestHttpServletRequest) pc.getRequest();
    }

    private TestHttpServletResponse response() {
        return (TestHttpServletResponse) pc.getResponse();
    }

    /** Runs a nested probe tag whose parent is the bundle. */
    private Locale nestedIn(BundleTag bundle) throws Exception {
        Locale[] seen = new Locale[1];
        TagHarness.run(bundle, pc, p -> {
            TagSupport child = new TagSupport();
            child.setParent(bundle);
            seen[0] = FmtSupport.formattingLocale(ACTION, child, pc, AVAILABLE);
        });
        return seen[0];
    }

    private BundleTag bundle(String basename) {
        BundleTag bundle = new BundleTag();
        bundle.setBasename(basename);
        return bundle;
    }

    @Test
    void enclosingBundleLocaleWins() throws Exception {
        request().setLocales(Locale.CANADA_FRENCH, Locale.ENGLISH);
        Config.set(pc, Config.FMT_LOCALIZATION_CONTEXT,
                new LocalizationContext(null, Locale.GERMAN), PageContext.REQUEST_SCOPE);
        assertEquals(Locale.CANADA_FRENCH, nestedIn(bundle("test-bundles.ex1.Resources")));
    }

    @Test
    void enclosingBundleWithoutLocaleFallsThrough() throws Exception {
        request().setLocales(Locale.ENGLISH);
        Config.set(pc, Config.FMT_LOCALIZATION_CONTEXT,
                new LocalizationContext(null, Locale.GERMAN), PageContext.REQUEST_SCOPE);
        assertEquals(Locale.GERMAN, nestedIn(bundle("test-bundles.root.Resources")));
    }

    @Test
    void localizationContextConfigurationLocaleIsNext() throws Exception {
        Config.set(pc, Config.FMT_LOCALIZATION_CONTEXT,
                new LocalizationContext(null, Locale.GERMAN), PageContext.PAGE_SCOPE);
        assertEquals(Locale.GERMAN, FmtSupport.formattingLocale(ACTION, new TagSupport(), pc, AVAILABLE));
    }

    @Test
    void stringConfigurationIsDeterminedAndSetsTheResponseLocale() throws Exception {
        request().setLocales(Locale.CANADA_FRENCH);
        Config.set(pc, Config.FMT_LOCALIZATION_CONTEXT, "test-bundles.ex1.Resources", PageContext.PAGE_SCOPE);
        assertEquals(Locale.CANADA_FRENCH, FmtSupport.formattingLocale(ACTION, new TagSupport(), pc, AVAILABLE));
        assertEquals(Locale.CANADA_FRENCH, response().getLocale());
    }

    @Test
    void defaultContextForALocalizationContextIsReturnedAsIs() throws Exception {
        LocalizationContext ctx = new LocalizationContext(null, Locale.GERMAN);
        Config.set(pc, Config.FMT_LOCALIZATION_CONTEXT, ctx, PageContext.PAGE_SCOPE);
        Locale before = response().getLocale();
        assertEquals(ctx, FmtSupport.defaultContext(ACTION, pc));
        assertEquals(before, response().getLocale());
    }

    @Test
    void defaultContextIsAbsentWithoutConfiguration() throws Exception {
        assertNull(FmtSupport.defaultContext(ACTION, pc));
    }

    @Test
    void lookupIsTheLastResortAndSetsTheResponseLocale() throws Exception {
        request().setLocales(Locale.FRENCH);
        assertEquals(Locale.FRENCH, FmtSupport.formattingLocale(ACTION, new TagSupport(), pc, AVAILABLE));
        assertEquals(Locale.FRENCH, response().getLocale());
    }

    @Test
    void noMatchYieldsNull() throws Exception {
        request().setLocales(Locale.FRENCH);
        Locale before = response().getLocale();
        assertNull(FmtSupport.formattingLocale(ACTION, new TagSupport(), pc, List.of(Locale.JAPANESE)));
        assertEquals(before, response().getLocale());
    }

    @Test
    void timeZoneAttributeBeatsEverything() throws Exception {
        Config.set(pc, Config.FMT_TIME_ZONE, "Asia/Tokyo", PageContext.PAGE_SCOPE);
        TimeZoneTag enclosing = new TimeZoneTag();
        enclosing.setValue("Europe/Paris");
        TimeZone[] seen = new TimeZone[2];
        TagHarness.run(enclosing, pc, p -> {
            TagSupport child = new TagSupport();
            child.setParent(enclosing);
            seen[0] = FmtSupport.timeZone(child, pc, "America/Los_Angeles");
            seen[1] = FmtSupport.timeZone(child, pc, TimeZone.getTimeZone("GMT-3"));
        });
        assertEquals(TimeZone.getTimeZone("America/Los_Angeles"), seen[0]);
        assertEquals(TimeZone.getTimeZone("GMT-3"), seen[1]);
    }

    @Test
    void enclosingTimeZoneBeatsConfiguration() throws Exception {
        Config.set(pc, Config.FMT_TIME_ZONE, "Asia/Tokyo", PageContext.PAGE_SCOPE);
        TimeZoneTag enclosing = new TimeZoneTag();
        enclosing.setValue("Europe/Paris");
        TimeZone[] seen = new TimeZone[2];
        TagHarness.run(enclosing, pc, p -> {
            TagSupport child = new TagSupport();
            child.setParent(enclosing);
            seen[0] = FmtSupport.timeZone(child, pc, null);
            seen[1] = FmtSupport.timeZone(child, pc, "");
        });
        assertEquals(TimeZone.getTimeZone("Europe/Paris"), seen[0]);
        assertEquals(TimeZone.getTimeZone("Europe/Paris"), seen[1]);
    }

    @Test
    void configurationStringOrObjectBeatsTheDefault() {
        Config.set(pc, Config.FMT_TIME_ZONE, "Asia/Tokyo", PageContext.PAGE_SCOPE);
        assertEquals(TimeZone.getTimeZone("Asia/Tokyo"), FmtSupport.timeZone(new TagSupport(), pc, null));
        Config.set(pc, Config.FMT_TIME_ZONE, TimeZone.getTimeZone("GMT+2"), PageContext.PAGE_SCOPE);
        assertEquals(TimeZone.getTimeZone("GMT+2"), FmtSupport.timeZone(new TagSupport(), pc, null));
    }

    @Test
    void containerDefaultIsTheLastResort() {
        assertEquals(TimeZone.getDefault(), FmtSupport.timeZone(new TagSupport(), pc, null));
    }

    @Test
    void ancestorsAreFoundThroughUnrelatedParents() throws Exception {
        request().setLocales(Locale.ENGLISH);
        BundleTag bundle = bundle("test-bundles.ex1.Resources");
        Locale[] seen = new Locale[1];
        TagHarness.run(bundle, pc, p -> {
            TagSupport mid = new TagSupport();
            mid.setParent(bundle);
            TagSupport leaf = new TagSupport();
            leaf.setParent(mid);
            seen[0] = FmtSupport.formattingLocale(ACTION, leaf, pc, AVAILABLE);
        });
        assertEquals(Locale.ENGLISH, seen[0]);
    }
}
