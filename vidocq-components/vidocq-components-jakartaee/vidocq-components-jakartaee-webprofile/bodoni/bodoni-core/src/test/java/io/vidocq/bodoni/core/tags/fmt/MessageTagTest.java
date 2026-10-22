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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.ResourceBundle;

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

class MessageTagTest {

    private static final String BASENAME = "test-bundles.msg.Messages";

    private final TestPageContext pc = TestPageContext.create();

    private void preferred(Locale... locales) {
        ((TestHttpServletRequest) pc.getRequest()).setLocales(locales);
    }

    private void configure(String basename) {
        Config.set(pc, Config.FMT_LOCALIZATION_CONTEXT, basename, PageContext.PAGE_SCOPE);
    }

    private static MessageTag message(String key) {
        MessageTag tag = new MessageTag();
        tag.setKey(key);
        return tag;
    }

    private BundleTag bundle(String prefix) throws Exception {
        BundleTag bundle = new BundleTag();
        bundle.setPageContext(pc);
        bundle.setBasename(BASENAME);
        bundle.setPrefix(prefix);
        bundle.doStartTag();
        return bundle;
    }

    private static ParamTag param(Object value) {
        ParamTag param = new ParamTag();
        param.setValue(value);
        return param;
    }

    private String withParam(MessageTag tag, Object value) throws Exception {
        return TagHarness.run(tag, pc, p -> TagHarness.run(param(value), pc, tag, Body.NONE));
    }

    @Test
    void bundleAttributeBeatsEnclosingBundleBeatsDefault() throws Exception {
        preferred(Locale.FRENCH);
        configure("test-bundles.deflt.Resources");
        assertEquals("fr", TagHarness.run(message("greeting"), pc, Body.NONE));

        BundleTag enclosing = bundle(null);
        assertEquals("Bonjour", TagHarness.run(message("greeting"), pc, enclosing, Body.NONE));

        MessageTag withAttribute = message("greeting");
        withAttribute.setBundle(new LocalizationContext(
                ResourceBundle.getBundle("test-bundles.ex2.Resources", Locale.ENGLISH), Locale.ENGLISH));
        assertEquals("en", TagHarness.run(withAttribute, pc, enclosing, Body.NONE));
    }

    @Test
    void configuredBasenameEstablishesAContextAndSetsTheResponseLocale() throws Exception {
        preferred(Locale.FRENCH);
        configure(BASENAME);
        assertEquals("Bonjour", TagHarness.run(message("greeting"), pc, Body.NONE));
        assertEquals(Locale.FRENCH, ((TestHttpServletResponse) pc.getResponse()).getLocale());
    }

    @Test
    void configuredLocalizationContextIsUsedAsIs() throws Exception {
        preferred(Locale.FRENCH);
        Config.set(pc, Config.FMT_LOCALIZATION_CONTEXT, new LocalizationContext(
                ResourceBundle.getBundle(BASENAME, Locale.ROOT), Locale.ENGLISH), PageContext.PAGE_SCOPE);
        assertEquals("Hello", TagHarness.run(message("greeting"), pc, Body.NONE));
    }

    @Test
    void prefixOfTheEnclosingBundleIsPrepended() throws Exception {
        preferred(Locale.ENGLISH);
        assertEquals("First", TagHarness.run(message("firstName"), pc, bundle("com.acme."), Body.NONE));
    }

    @Test
    void unknownKeyGivesTheKeyBetweenQuestionMarks() throws Exception {
        preferred(Locale.ENGLISH);
        configure(BASENAME);
        assertEquals("???nope???", TagHarness.run(message("nope"), pc, Body.NONE));
        assertEquals("???com.acme.nope???", TagHarness.run(message("nope"), pc, bundle("com.acme."), Body.NONE));
    }

    @Test
    void noBundleInTheContextGivesTheKeyBetweenQuestionMarks() throws Exception {
        assertEquals("???greeting???", TagHarness.run(message("greeting"), pc, Body.NONE));
    }

    @Test
    void emptyOrNullKeyGivesSixQuestionMarks() throws Exception {
        preferred(Locale.ENGLISH);
        configure(BASENAME);
        assertEquals("??????", TagHarness.run(message(""), pc, Body.NONE));
        assertEquals("??????", TagHarness.run(message(null), pc, Body.NONE));
    }

    @Test
    void keyComesFromTheTrimmedBodyWithoutKeyAttribute() throws Exception {
        preferred(Locale.FRENCH);
        configure(BASENAME);
        assertEquals("Bonjour", TagHarness.run(new MessageTag(), pc, p -> p.getOut().print("  greeting\n ")));
    }

    @Test
    void paramChildrenDoNotContributeToTheKey() throws Exception {
        preferred(Locale.ENGLISH);
        configure(BASENAME);
        MessageTag tag = new MessageTag();
        String out = TagHarness.run(tag, pc, p -> {
            p.getOut().print(" athletes ");
            TagHarness.run(param(7), pc, tag, Body.NONE);
        });
        assertEquals("There are 7 athletes registered.", out);
    }

    @Test
    void parametersAreFormattedByMessageFormatInOrder() throws Exception {
        preferred(Locale.ENGLISH);
        configure(BASENAME);
        assertEquals("There are 10,582 athletes registered.", withParam(message("athletes"), 10582));
        MessageTag pair = message("pair");
        assertEquals("b-a", TagHarness.run(pair, pc, p -> {
            TagHarness.run(param("a"), pc, pair, Body.NONE);
            TagHarness.run(param("b"), pc, pair, Body.NONE);
        }));
    }

    @Test
    void compoundMessageWithoutParamsIsLeftUnmodified() throws Exception {
        preferred(Locale.ENGLISH);
        configure(BASENAME);
        assertEquals("There are {0} athletes registered.", TagHarness.run(message("athletes"), pc, Body.EMPTY));
    }

    @Test
    void messageFormatLocaleFallsBackToTheFormattingLocaleLookup() throws Exception {
        preferred(Locale.GERMANY);
        configure(BASENAME);
        String expected = NumberFormat.getIntegerInstance(Locale.GERMANY).format(10582);
        assertEquals("There are " + expected + " athletes registered.", withParam(message("athletes"), 10582));
        assertEquals("There are 10.582 athletes registered.", withParam(message("athletes"), 10582));
    }

    @Test
    void varAndScopeExportInsteadOfWriting() throws Exception {
        preferred(Locale.ENGLISH);
        configure(BASENAME);
        MessageTag tag = message("greeting");
        tag.setVar("m");
        tag.setScope("request");
        assertEquals("", TagHarness.run(tag, pc, Body.NONE));
        assertEquals("Hello", pc.getAttribute("m", PageContext.REQUEST_SCOPE));
        assertNull(pc.getAttribute("m", PageContext.PAGE_SCOPE));
    }

    @Test
    void invalidScopeIsAJspTagException() {
        MessageTag tag = message("greeting");
        tag.setVar("m");
        tag.setScope("nowhere");
        assertThrows(JspTagException.class, () -> TagHarness.run(tag, pc, Body.NONE));
    }

    @Test
    void reusedInstanceKeepsNoParameterFromThePreviousUse() throws Exception {
        preferred(Locale.ENGLISH);
        configure(BASENAME);
        MessageTag tag = message("athletes");
        assertEquals("There are 3 athletes registered.", withParam(tag, 3));
        assertEquals("There are {0} athletes registered.", TagHarness.run(tag, pc, Body.EMPTY));
        assertEquals("There are {0} athletes registered.", TagHarness.run(tag, pc, Body.NONE));
    }
}
