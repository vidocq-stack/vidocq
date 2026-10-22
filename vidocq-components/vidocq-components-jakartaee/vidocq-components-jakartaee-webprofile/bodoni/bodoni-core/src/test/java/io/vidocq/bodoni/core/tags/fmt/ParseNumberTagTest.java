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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.text.ParseException;
import java.util.Locale;

import io.vidocq.bodoni.core.testing.Body;
import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestPageContext;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.PageContext;
import jakarta.servlet.jsp.jstl.core.Config;

import org.junit.jupiter.api.Test;

class ParseNumberTagTest {

    private final TestPageContext pc = TestPageContext.create();

    private ParseNumberTag us(String value) {
        Config.set(pc, Config.FMT_LOCALE, Locale.US, PageContext.PAGE_SCOPE);
        ParseNumberTag tag = new ParseNumberTag();
        tag.setValue(value);
        return tag;
    }

    private String run(ParseNumberTag tag) throws Exception {
        return TagHarness.run(tag, pc, Body.NONE);
    }

    @Test
    void currencyTypeParsesALong() throws Exception {
        ParseNumberTag tag = us("$123,456,789.00");
        tag.setType("currency");
        tag.setVar("n");
        run(tag);
        assertEquals(123456789L, pc.getAttribute("n", PageContext.PAGE_SCOPE));
    }

    @Test
    void parseLocaleStringOverridesTheFormattingLocale() throws Exception {
        ParseNumberTag tag = us("1.234,5");
        tag.setParseLocale("de_DE");
        tag.setVar("n");
        run(tag);
        assertEquals(1234.5, pc.getAttribute("n", PageContext.PAGE_SCOPE));
    }

    @Test
    void parseLocaleAcceptsALocale() throws Exception {
        ParseNumberTag tag = us("1.234,5");
        tag.setParseLocale(Locale.GERMANY);
        assertEquals("1234.5", run(tag));
    }

    @Test
    void emptyParseLocaleIsIgnored() throws Exception {
        ParseNumberTag tag = us("1,234.5");
        tag.setParseLocale("");
        assertEquals("1234.5", run(tag));
    }

    @Test
    void malformedParseLocaleIsATagException() {
        ParseNumberTag tag = us("1");
        tag.setParseLocale("not a locale");
        assertThrows(JspTagException.class, () -> run(tag));
    }

    @Test
    void integerOnlyStopsAtTheDecimalPoint() throws Exception {
        ParseNumberTag tag = us("12.75");
        tag.setIntegerOnly(true);
        assertEquals("12", run(tag));
    }

    @Test
    void patternIsAppliedWithTheLocaleSymbols() throws Exception {
        ParseNumberTag tag = us("1,234.5");
        tag.setPattern("#,##0.0#");
        assertEquals("1234.5", run(tag));
    }

    @Test
    void percentType() throws Exception {
        ParseNumberTag tag = us("25%");
        tag.setType("percent");
        assertEquals("0.25", run(tag));
    }

    @Test
    void invalidTypeIsATagException() {
        ParseNumberTag tag = us("1");
        tag.setType("scientific");
        assertThrows(JspTagException.class, () -> run(tag));
    }

    @Test
    void nullAndEmptyValuesRemoveVarAndWriteNothing() throws Exception {
        for (String empty : new String[] {null, ""}) {
            pc.setAttribute("n", 1, PageContext.PAGE_SCOPE);
            ParseNumberTag tag = us(empty);
            tag.setVar("n");
            assertEquals("", run(tag));
            assertNull(pc.getAttribute("n", PageContext.PAGE_SCOPE));
        }
    }

    @Test
    void unparsableValueIsAJspExceptionWithTheParseExceptionAsCause() {
        ParseNumberTag tag = us("abc");
        JspException e = assertThrows(JspException.class, () -> run(tag));
        assertTrue(e.getMessage().contains("abc"));
        assertInstanceOf(ParseException.class, e.getCause());
    }

    @Test
    void noFormattingLocaleIsAJspExceptionNamingTheValue() {
        Config.set(pc, Config.FMT_LOCALE, Locale.of("xx"), PageContext.PAGE_SCOPE);
        ParseNumberTag tag = new ParseNumberTag();
        tag.setValue("12");
        JspException e = assertThrows(JspException.class, () -> run(tag));
        assertTrue(e.getMessage().contains("12"));
    }

    @Test
    void valueComesFromTheTrimmedBody() throws Exception {
        Config.set(pc, Config.FMT_LOCALE, Locale.US, PageContext.PAGE_SCOPE);
        ParseNumberTag tag = new ParseNumberTag();
        assertEquals("1234", TagHarness.run(tag, pc, p -> p.getOut().print(" 1,234\n")));
    }

    @Test
    void invalidScopeIsATagException() {
        ParseNumberTag tag = us("1");
        tag.setVar("n");
        tag.setScope("galaxy");
        assertThrows(JspTagException.class, () -> run(tag));
    }

    @Test
    void reusedInstanceKeepsNoSettingOfThePreviousUse() throws Exception {
        ParseNumberTag tag = us("1.234,5");
        tag.setParseLocale("de_DE");
        tag.setIntegerOnly(true);
        assertEquals("1234", run(tag));
        tag.setValue("1,234.5");
        assertEquals("1234.5", run(tag));
    }

    @Test
    void releasedInstanceKeepsNoSettingOfThePreviousUse() throws Exception {
        ParseNumberTag tag = us("12.75");
        tag.setIntegerOnly(true);
        tag.setParseLocale("de_DE");
        run(tag);
        tag.release();
        tag.setValue("12.75");
        assertEquals("12.75", run(tag));
    }
}
