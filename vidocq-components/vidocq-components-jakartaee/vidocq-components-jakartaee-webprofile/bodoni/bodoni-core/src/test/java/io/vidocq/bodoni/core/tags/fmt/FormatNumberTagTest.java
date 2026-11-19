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
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;

import io.vidocq.bodoni.core.testing.Body;
import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestHttpServletRequest;
import io.vidocq.bodoni.core.testing.TestPageContext;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.PageContext;
import jakarta.servlet.jsp.jstl.core.Config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FormatNumberTagTest {

    private final TestPageContext pc = TestPageContext.create();

    private FormatNumberTag tag(Locale locale, Object value) {
        Config.set(pc, Config.FMT_LOCALE, locale, PageContext.PAGE_SCOPE);
        FormatNumberTag tag = new FormatNumberTag();
        tag.setValue(value);
        return tag;
    }

    private FormatNumberTag us(Object value) {
        return tag(Locale.US, value);
    }

    private String run(FormatNumberTag tag) throws Exception {
        return TagHarness.run(tag, pc, Body.NONE);
    }

    @Test
    void currencyTypeUsesTheLocaleCurrencyPattern() throws Exception {
        FormatNumberTag tag = us("9876543.21");
        tag.setType("currency");
        assertEquals("$9,876,543.21", run(tag));
    }

    @Test
    void patternFormatsWithTheLocaleSymbols() throws Exception {
        FormatNumberTag tag = us("12.3");
        tag.setPattern(".000");
        assertEquals("12.300", run(tag));
    }

    @Test
    void patternWithGroupingAndOptionalDigits() throws Exception {
        FormatNumberTag tag = us("123456.7891");
        tag.setPattern("#,#00.0#");
        assertEquals("123,456.79", run(tag));
    }

    @Test
    void patternBeatsType() throws Exception {
        FormatNumberTag tag = us(12.3);
        tag.setType("percent");
        tag.setPattern("0.0");
        assertEquals("12.3", run(tag));
    }

    @Test
    void emptyPatternIsIgnored() throws Exception {
        FormatNumberTag tag = us(0.25);
        tag.setType("percent");
        tag.setPattern("");
        assertEquals("25%", run(tag));
    }

    @Test
    void percentType() throws Exception {
        FormatNumberTag tag = us("0.25");
        tag.setType("percent");
        assertEquals("25%", run(tag));
    }

    @Test
    void currencyCodeSelectsTheCurrency() throws Exception {
        FormatNumberTag tag = us(1234.5);
        tag.setType("currency");
        tag.setCurrencyCode("EUR");
        assertEquals("€1,234.50", run(tag));
    }

    @Test
    void currencySymbolOverridesTheSymbol() throws Exception {
        FormatNumberTag tag = us(1234.5);
        tag.setType("currency");
        tag.setCurrencySymbol("CHF ");
        assertEquals("CHF 1,234.50", run(tag));
    }

    @Test
    void currencyCodeWinsOverCurrencySymbol() throws Exception {
        FormatNumberTag tag = us(1234.5);
        tag.setType("currency");
        tag.setCurrencyCode("EUR");
        tag.setCurrencySymbol("CHF ");
        assertEquals("€1,234.50", run(tag));
    }

    @Test
    void currencyAttributesAreIgnoredForOtherTypes() throws Exception {
        FormatNumberTag tag = us(1234.5);
        tag.setCurrencyCode("not-a-code");
        tag.setCurrencySymbol("CHF ");
        assertEquals("1,234.5", run(tag));
    }

    static Stream<Arguments> invalidAttributes() {
        return Stream.of(
                arguments("currencyCode", (Consumer<FormatNumberTag>) t -> {
                    t.setType("currency");
                    t.setCurrencyCode("ZZZZ");
                }),
                arguments("type", (Consumer<FormatNumberTag>) t -> t.setType("scientific")),
                arguments("pattern", (Consumer<FormatNumberTag>) t -> t.setPattern("#.#.#")),
                arguments("scope", (Consumer<FormatNumberTag>) t -> {
                    t.setVar("p");
                    t.setScope("galaxy");
                }));
    }

    @ParameterizedTest(name = "invalid {0}")
    @MethodSource("invalidAttributes")
    void invalidAttributeIsATagException(String attribute, Consumer<FormatNumberTag> invalid) {
        FormatNumberTag tag = us(1);
        invalid.accept(tag);
        assertThrows(JspTagException.class, () -> run(tag), attribute);
    }

    @Test
    void groupingUsedFalseRemovesTheSeparators() throws Exception {
        FormatNumberTag tag = us(1234567);
        tag.setGroupingUsed(false);
        assertEquals("1234567", run(tag));
    }

    @Test
    void maxFractionDigitsRounds() throws Exception {
        FormatNumberTag tag = us(1.26);
        tag.setMaxFractionDigits(1);
        assertEquals("1.3", run(tag));
    }

    @Test
    void minFractionDigitsPads() throws Exception {
        FormatNumberTag tag = us(1.5);
        tag.setMinFractionDigits(3);
        assertEquals("1.500", run(tag));
    }

    @Test
    void minIntegerDigitsPads() throws Exception {
        FormatNumberTag tag = us(7);
        tag.setMinIntegerDigits(3);
        assertEquals("007", run(tag));
    }

    @Test
    void maxIntegerDigitsTruncates() throws Exception {
        FormatNumberTag tag = us(12345);
        tag.setGroupingUsed(false);
        tag.setMaxIntegerDigits(2);
        assertEquals("45", run(tag));
    }

    @Test
    void frenchLocaleFollowsTheJdkData() throws Exception {
        FormatNumberTag tag = tag(Locale.FRANCE, "345987.246");
        assertEquals(NumberFormat.getNumberInstance(Locale.FRANCE).format(345987.246), run(tag));
    }

    @Test
    void nullAndEmptyValuesWriteNothingAndRemoveVar() throws Exception {
        for (Object empty : new Object[] {null, ""}) {
            pc.setAttribute("n", "old", PageContext.PAGE_SCOPE);
            FormatNumberTag tag = us(empty);
            tag.setVar("n");
            assertEquals("", run(tag));
            assertNull(pc.getAttribute("n", PageContext.PAGE_SCOPE));
        }
        assertEquals("", run(us(null)));
    }

    @Test
    void stringWithoutPointIsALongAndWithPointADouble() throws Exception {
        assertEquals("12", run(us("12")));
        assertEquals("1.5", run(us("1.5")));
        assertEquals("1,234,567,890,123", run(us("1234567890123")));
    }

    @Test
    void stringWithoutPointIsNotWidenedToADouble() throws Exception {
        // A Long beyond 2^53 keeps every digit, and without a formatting locale its toString() has no ".0".
        assertEquals("9,007,199,254,740,993", run(us("9007199254740993")));
        assertEquals("12", run(tag(Locale.of("xx"), "12")));
    }

    @Test
    void unparsableStringIsAJspExceptionNamingTheValue() {
        FormatNumberTag tag = us("abc");
        JspException e = assertThrows(JspException.class, () -> run(tag));
        assertTrue(e.getMessage().contains("abc"));
        assertInstanceOf(NumberFormatException.class, e.getCause());
    }

    @Test
    void noFormattingLocaleUsesNumberToString() throws Exception {
        assertEquals("1234.5", run(tag(Locale.of("xx"), 1234.5)));
    }

    @Test
    void valueComesFromTheTrimmedBody() throws Exception {
        Config.set(pc, Config.FMT_LOCALE, Locale.US, PageContext.PAGE_SCOPE);
        FormatNumberTag tag = new FormatNumberTag();
        assertEquals("1,234", TagHarness.run(tag, pc, p -> p.getOut().print("  1234 \n")));
    }

    @Test
    void varReceivesTheTextInTheScope() throws Exception {
        FormatNumberTag tag = us(0.5);
        tag.setType("percent");
        tag.setVar("p");
        tag.setScope("request");
        assertEquals("", run(tag));
        assertEquals("50%", pc.getAttribute("p", PageContext.REQUEST_SCOPE));
    }

    @Test
    void reusedInstanceWithoutSetterCallsReproducesTheOutput() throws Exception {
        FormatNumberTag tag = us(1234.5);
        tag.setType("currency");
        tag.setCurrencyCode("EUR");
        tag.setGroupingUsed(false);
        tag.setMinIntegerDigits(6);
        assertEquals("€001234.50", run(tag));
        assertEquals("€001234.50", run(tag));
    }

    @Test
    void reusedInstanceKeepsTheAttributesWhoseSetterIsNotCalledAgain() throws Exception {
        FormatNumberTag tag = us(1234.5);
        tag.setType("currency");
        tag.setCurrencyCode("EUR");
        assertEquals("€1,234.50", run(tag));
        tag.setValue(1.5);
        assertEquals("€1.50", run(tag));
    }

    @Test
    void releasedInstanceKeepsNoSettingOfThePreviousUse() throws Exception {
        FormatNumberTag tag = us(1234.5);
        tag.setType("currency");
        tag.setCurrencySymbol("CHF ");
        tag.setGroupingUsed(false);
        tag.setMinFractionDigits(3);
        run(tag);
        tag.release();
        tag.setValue(1234.5);
        assertEquals("1,234.5", run(tag));
    }

    @Test
    void withoutAnAcceptLanguageHeaderThereIsNoFormattingLocale() throws Exception {
        ((TestHttpServletRequest) pc.getRequest()).withoutAcceptLanguage(Locale.US);
        FormatNumberTag tag = new FormatNumberTag();
        tag.setValue(1234.5);
        assertEquals("1234.5", run(tag));
    }

    @Test
    void malformedConfiguredLocaleIsAJspTagException() {
        Config.set(pc, Config.FMT_LOCALE, "1x", PageContext.PAGE_SCOPE);
        FormatNumberTag tag = new FormatNumberTag();
        tag.setValue(1);
        assertThrows(JspTagException.class, () -> run(tag));
    }
}
