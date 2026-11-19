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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.jsp.PageContext;
import jakarta.servlet.jsp.jstl.core.Config;
import jakarta.servlet.jsp.jstl.fmt.LocalizationContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.vidocq.bodoni.core.testing.TestHttpServletRequest;
import io.vidocq.bodoni.core.testing.TestHttpServletResponse;
import io.vidocq.bodoni.core.testing.TestPageContext;
import io.vidocq.bodoni.core.testing.TestServletContext;

class I18nTest {

    private static final Locale EN_GB = Locale.of("en", "GB");
    private static final Locale SV = Locale.of("sv");

    private final TestPageContext pc = TestPageContext.create();

    private TestHttpServletRequest request() {
        return (TestHttpServletRequest) pc.getRequest();
    }

    private TestHttpServletResponse response() {
        return (TestHttpServletResponse) pc.getResponse();
    }

    private void fallback(Locale locale) {
        Config.set(pc, Config.FMT_FALLBACK_LOCALE, locale, PageContext.REQUEST_SCOPE);
    }

    private void assertSelected(String basename, String greeting, Locale locale) {
        LocalizationContext ctx = I18n.determine(pc, basename);
        assertNotNull(ctx.getResourceBundle());
        assertEquals(greeting, ctx.getResourceBundle().getString("greeting"));
        assertEquals(locale, ctx.getLocale());
    }

    @Test
    void example1() {
        request().setLocales(EN_GB, Locale.CANADA_FRENCH);
        fallback(Locale.CANADA_FRENCH);
        assertSelected("test-bundles.ex1.Resources", "en", EN_GB);
    }

    @Test
    void example2() {
        request().setLocales(Locale.GERMAN, Locale.FRENCH);
        fallback(Locale.ENGLISH);
        assertSelected("test-bundles.ex2.Resources", "en", Locale.ENGLISH);
    }

    @Test
    void example3() {
        request().setLocales(Locale.JAPANESE, EN_GB, Locale.US, Locale.CANADA, Locale.FRENCH);
        fallback(Locale.ENGLISH);
        assertSelected("test-bundles.ex3.Resources", "en", EN_GB);
    }

    @Test
    void example4() {
        request().setLocales(Locale.FRENCH, SV);
        fallback(Locale.ENGLISH);
        assertSelected("test-bundles.ex4.Resources", "sv", SV);
    }

    @Test
    void rootBundleWhenNothingMatches() {
        request().setLocales(Locale.GERMAN);
        assertSelected("test-bundles.root.Resources", "root", null);
    }

    @Test
    void noBundleAtAll() {
        request().setLocales(Locale.GERMAN);
        LocalizationContext unknown = I18n.determine(pc, "test-bundles.nothing.Resources");
        assertNull(unknown.getResourceBundle());
        assertNull(unknown.getLocale());
        LocalizationContext empty = I18n.determine(pc, "");
        assertNull(empty.getResourceBundle());
        assertNull(empty.getLocale());
        assertNull(I18n.determine(pc, null).getResourceBundle());
    }

    @Test
    void defaultLocaleIsNeverConsulted() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.FRENCH);
            request().setLocales(Locale.GERMAN);
            assertSelected("test-bundles.deflt.Resources", "root", null);
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void languageMatchBeatsALaterExactMatch() {
        request().setLocales(Locale.ENGLISH, Locale.CANADA_FRENCH);
        assertSelected("test-bundles.ex1.Resources", "en", Locale.ENGLISH);
    }

    @Test
    void applicationLocaleDisablesBrowserLocales() {
        Config.set(pc, Config.FMT_LOCALE, "sv", PageContext.PAGE_SCOPE);
        request().setLocales(Locale.ENGLISH);
        assertEquals(List.of(SV), I18n.preferredLocales(pc));
        assertSelected("test-bundles.ex4.Resources", "sv", SV);
    }

    @Test
    void browserLocalesAreInRequestOrder() {
        request().setLocales(Locale.FRENCH, Locale.GERMAN);
        assertEquals(List.of(Locale.FRENCH, Locale.GERMAN), I18n.preferredLocales(pc));
    }

    @Test
    void withoutAnAcceptLanguageHeaderThereAreNoBrowserLocales() {
        request().withoutAcceptLanguage(Locale.US);
        assertEquals(List.of(), I18n.preferredLocales(pc));
    }

    @Test
    void withoutAnAcceptLanguageHeaderTheFallbackLocaleSelectsTheBundle() {
        request().withoutAcceptLanguage(Locale.US);
        fallback(Locale.FRENCH);
        assertSelected("test-bundles.ex3.Resources", "fr", Locale.FRENCH);
    }

    @Test
    void withoutAnAcceptLanguageHeaderNorFallbackThereIsNoFormattingLocale() {
        request().withoutAcceptLanguage(Locale.US);
        assertNull(I18n.formattingLocale(pc, List.of(Locale.US, Locale.ENGLISH)));
    }

    @Test
    void usesTheContextClassLoader(@TempDir Path dir) throws IOException {
        Path pkg = Files.createDirectories(dir.resolve("ccl-only"));
        Files.writeString(pkg.resolve("Messages_en.properties"), "greeting=ccl\n");
        Thread thread = Thread.currentThread();
        ClassLoader saved = thread.getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {dir.toUri().toURL()}, null)) {
            thread.setContextClassLoader(loader);
            assertEquals("ccl", I18n.lookup("ccl-only.Messages", Locale.ENGLISH).getString("greeting"));
            thread.setContextClassLoader(null);
            assertNull(I18n.lookup("ccl-only.Messages", Locale.ENGLISH));
            assertEquals("en",
                    I18n.lookup("test-bundles.ex2.Resources", Locale.ENGLISH).getString("greeting"));
        } finally {
            thread.setContextClassLoader(saved);
        }
    }

    @Test
    void lookupIgnoresTheRootBundle() {
        assertNull(I18n.lookup("test-bundles.root.Resources", Locale.GERMAN));
        assertNull(I18n.lookup("test-bundles.nothing.Resources", Locale.ENGLISH));
    }

    @Test
    void setResponseLocale() {
        response().mapLocaleEncoding(Locale.FRANCE, "UTF-8");
        I18n.setResponseLocale(pc, Locale.FRANCE);
        assertEquals(Locale.FRANCE, response().getLocale());
        assertEquals("UTF-8", request().getSession(false).getAttribute(I18n.REQUEST_CHARSET));
        assertEquals("jakarta.servlet.jsp.jstl.fmt.request.charset", I18n.REQUEST_CHARSET);
    }

    @Test
    void setResponseLocaleCreatesNoSession() {
        request().withoutSession();
        response().mapLocaleEncoding(Locale.FRANCE, "UTF-8");
        I18n.setResponseLocale(pc, Locale.FRANCE);
        assertEquals(Locale.FRANCE, response().getLocale());
        assertFalse(request().hasSession());
    }

    @Test
    void setResponseLocaleIgnoresAnInvalidatedSession() {
        request().getSession(false).invalidate();
        I18n.setResponseLocale(pc, Locale.FRANCE);
        assertEquals(Locale.FRANCE, response().getLocale());
    }

    @Test
    void setResponseLocaleAcceptsANonHttpRequest() {
        TestServletContext context = new TestServletContext();
        TestHttpServletRequest http = new TestHttpServletRequest(context);
        TestHttpServletResponse plainResponse = new TestHttpServletResponse();
        TestPageContext plain = new TestPageContext(http, plainResponse, context) {
            @Override
            public ServletRequest getRequest() {
                return new ServletRequestWrapper(http);
            }
        };
        I18n.setResponseLocale(plain, Locale.FRANCE);
        assertEquals(Locale.FRANCE, plainResponse.getLocale());
    }

    @Test
    void intersectionToleratesDuplicateLocales() {
        List<Locale> withDuplicates = List.of(Locale.FRENCH, Locale.GERMAN, Locale.FRENCH);
        assertEquals(List.of(Locale.FRENCH),
                I18n.intersection(List.of(Locale.FRENCH, Locale.JAPANESE, Locale.FRENCH), withDuplicates));
    }

    @Test
    void setResponseLocaleNullDoesNothing() {
        I18n.setResponseLocale(pc, null);
        assertFalse(request().getSession(false).getAttributeNames().hasMoreElements());
        assertEquals(Locale.getDefault(), response().getLocale());
    }

    @Test
    void formattingLocaleLookup() {
        List<Locale> available = List.of(Locale.ENGLISH, Locale.FRANCE, Locale.of("de", "CH"),
                Locale.of("es", "ES", "x"));
        request().setLocales(Locale.FRANCE);
        assertEquals(Locale.FRANCE, I18n.formattingLocale(pc, available));
        request().setLocales(EN_GB);
        assertEquals(Locale.ENGLISH, I18n.formattingLocale(pc, available));
        request().setLocales(Locale.of("es", "ES"));
        assertEquals(Locale.of("es", "ES", "x"), I18n.formattingLocale(pc, available));
        request().setLocales(Locale.GERMANY);
        assertNull(I18n.formattingLocale(pc, available));
        fallback(Locale.ENGLISH);
        assertEquals(Locale.ENGLISH, I18n.formattingLocale(pc, available));
        request().setLocales(Locale.JAPANESE);
        Config.remove(pc, Config.FMT_FALLBACK_LOCALE, PageContext.REQUEST_SCOPE);
        assertNull(I18n.formattingLocale(pc, available));
    }

    @Test
    void availableLocaleSets() {
        assertTrue(I18n.numberLocales().contains(Locale.FRANCE));
        assertTrue(I18n.dateLocales().contains(Locale.GERMANY));
        assertTrue(I18n.messageLocales().contains(Locale.FRANCE));
        assertTrue(I18n.numberLocales().containsAll(I18n.messageLocales()));
        assertTrue(I18n.dateLocales().containsAll(I18n.messageLocales()));
    }

    @Test
    void availableLocaleSetsAreUnmodifiable() {
        List<Locale> number = I18n.numberLocales();
        List<Locale> date = I18n.dateLocales();
        List<Locale> message = I18n.messageLocales();
        assertThrows(UnsupportedOperationException.class, () -> number.add(Locale.ROOT));
        assertThrows(UnsupportedOperationException.class, () -> date.add(Locale.ROOT));
        assertThrows(UnsupportedOperationException.class, () -> message.add(Locale.ROOT));
    }
}
