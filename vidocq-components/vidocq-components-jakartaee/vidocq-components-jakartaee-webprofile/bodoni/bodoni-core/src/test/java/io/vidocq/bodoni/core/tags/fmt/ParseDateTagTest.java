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

import java.text.DateFormat;
import java.text.ParseException;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.function.Consumer;
import java.util.stream.Stream;

import io.vidocq.bodoni.core.testing.Body;
import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestPageContext;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.PageContext;
import jakarta.servlet.jsp.jstl.core.Config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ParseDateTagTest {

    private static final Date INSTANT = new Date(1018656000000L);
    private static final TimeZone GMT = TimeZone.getTimeZone("GMT");

    private final TestPageContext pc = TestPageContext.create();

    private ParseDateTag us(String value) {
        Config.set(pc, Config.FMT_LOCALE, Locale.US, PageContext.PAGE_SCOPE);
        ParseDateTag tag = new ParseDateTag();
        tag.setValue(value);
        tag.setTimeZone("GMT");
        return tag;
    }

    private String run(ParseDateTag tag) throws Exception {
        return TagHarness.run(tag, pc, Body.NONE);
    }

    private Object parsed() {
        return pc.getAttribute("d", PageContext.PAGE_SCOPE);
    }

    @Test
    void patternParsesInTheGivenTimeZone() throws Exception {
        ParseDateTag tag = us("4/13/02");
        tag.setPattern("M/d/yy");
        tag.setVar("d");
        assertEquals("", run(tag));
        assertEquals(INSTANT, parsed());
    }

    @Test
    void defaultTypeIsDateWithTheShortStyle() throws Exception {
        DateFormat jdk = DateFormat.getDateInstance(DateFormat.SHORT, Locale.US);
        jdk.setTimeZone(GMT);
        ParseDateTag tag = us(jdk.format(INSTANT));
        tag.setDateStyle("short");
        tag.setVar("d");
        run(tag);
        assertEquals(INSTANT, parsed());
    }

    @Test
    void timeAndBothTypesUseTheirStyles() throws Exception {
        DateFormat time = DateFormat.getTimeInstance(DateFormat.MEDIUM, Locale.US);
        time.setTimeZone(GMT);
        ParseDateTag timeTag = us(time.format(new Date(3_723_000L)));
        timeTag.setType("time");
        timeTag.setTimeStyle("medium");
        timeTag.setVar("d");
        run(timeTag);
        assertEquals(new Date(3_723_000L), parsed());

        DateFormat both = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT, Locale.US);
        both.setTimeZone(GMT);
        ParseDateTag bothTag = us(both.format(INSTANT));
        bothTag.setType("both");
        bothTag.setDateStyle("long");
        bothTag.setTimeStyle("short");
        bothTag.setVar("d");
        run(bothTag);
        assertEquals(INSTANT, parsed());
    }

    @Test
    void parseLocaleSelectsTheStyles() throws Exception {
        DateFormat jdk = DateFormat.getDateInstance(DateFormat.FULL, Locale.FRANCE);
        jdk.setTimeZone(GMT);
        ParseDateTag tag = us(jdk.format(INSTANT));
        tag.setParseLocale("fr_FR");
        tag.setDateStyle("full");
        tag.setVar("d");
        run(tag);
        assertEquals(INSTANT, parsed());
    }

    @Test
    void parseLocaleAcceptsALocaleAndEmptyIsIgnored() throws Exception {
        ParseDateTag tag = us("13.04.02");
        tag.setParseLocale(Locale.GERMANY);
        tag.setPattern("dd.MM.yy");
        tag.setVar("d");
        run(tag);
        assertEquals(INSTANT, parsed());
        tag.setParseLocale("");
        run(tag);
        assertEquals(INSTANT, parsed());
    }

    @Test
    void malformedParseLocaleIsATagException() {
        ParseDateTag tag = us("1");
        tag.setParseLocale("not a locale");
        assertThrows(JspTagException.class, () -> run(tag));
    }

    @Test
    void trailingTextIsAJspExceptionNamingTheValue() {
        ParseDateTag tag = us("4/13/02x");
        tag.setPattern("M/d/yy");
        JspException e = assertThrows(JspException.class, () -> run(tag));
        assertTrue(e.getMessage().contains("4/13/02x"));
        assertInstanceOf(ParseException.class, e.getCause());
    }

    @Test
    void impossibleDateIsAJspExceptionNamingTheValue() {
        ParseDateTag tag = us("2/30/02");
        tag.setPattern("M/d/yy");
        JspException e = assertThrows(JspException.class, () -> run(tag));
        assertTrue(e.getMessage().contains("2/30/02"));
        assertInstanceOf(ParseException.class, e.getCause());
    }

    @Test
    void unparsableValueWithStylesIsAJspException() {
        ParseDateTag tag = us("garbage");
        JspException e = assertThrows(JspException.class, () -> run(tag));
        assertTrue(e.getMessage().contains("garbage"));
        assertInstanceOf(ParseException.class, e.getCause());
    }

    @Test
    void nullAndEmptyValuesRemoveVarAndWriteNothing() throws Exception {
        for (String empty : new String[] {null, ""}) {
            pc.setAttribute("d", 1, PageContext.PAGE_SCOPE);
            ParseDateTag tag = us(empty);
            tag.setVar("d");
            assertEquals("", run(tag));
            assertNull(parsed());
        }
    }

    @Test
    void configuredTimeZoneApplies() throws Exception {
        Config.set(pc, Config.FMT_TIME_ZONE, "Asia/Tokyo", PageContext.PAGE_SCOPE);
        ParseDateTag tag = us("4/13/02");
        tag.setTimeZone(null);
        tag.setPattern("M/d/yy");
        tag.setVar("d");
        run(tag);
        assertEquals(new Date(INSTANT.getTime() - 9 * 3_600_000L), parsed());
    }

    @Test
    void enclosingTimeZoneApplies() throws Exception {
        Config.set(pc, Config.FMT_LOCALE, Locale.US, PageContext.PAGE_SCOPE);
        TimeZoneTag enclosing = new TimeZoneTag();
        enclosing.setValue("Asia/Tokyo");
        TagHarness.run(enclosing, pc, p -> {
            ParseDateTag tag = new ParseDateTag();
            tag.setValue("4/13/02");
            tag.setPattern("M/d/yy");
            tag.setVar("d");
            TagHarness.run(tag, pc, enclosing, Body.NONE);
        });
        assertEquals(new Date(INSTANT.getTime() - 9 * 3_600_000L), parsed());
    }

    @Test
    void outputIsDateToStringWithoutVar() throws Exception {
        ParseDateTag tag = us("4/13/02");
        tag.setPattern("M/d/yy");
        assertEquals(INSTANT.toString(), run(tag));
    }

    @Test
    void valueComesFromTheTrimmedBody() throws Exception {
        Config.set(pc, Config.FMT_LOCALE, Locale.US, PageContext.PAGE_SCOPE);
        ParseDateTag tag = new ParseDateTag();
        tag.setTimeZone("GMT");
        tag.setPattern("M/d/yy");
        assertEquals(INSTANT.toString(), TagHarness.run(tag, pc, p -> p.getOut().print(" 4/13/02\n")));
    }

    @Test
    void noFormattingLocaleIsAJspExceptionNamingTheValue() {
        Config.set(pc, Config.FMT_LOCALE, Locale.of("xx"), PageContext.PAGE_SCOPE);
        ParseDateTag tag = new ParseDateTag();
        tag.setValue("4/13/02");
        JspException e = assertThrows(JspException.class, () -> run(tag));
        assertTrue(e.getMessage().contains("4/13/02"));
    }

    static Stream<Arguments> invalidAttributes() {
        return Stream.of(
                arguments("type", (Consumer<ParseDateTag>) t -> t.setType("century")),
                arguments("dateStyle", (Consumer<ParseDateTag>) t -> t.setDateStyle("huge")),
                arguments("timeStyle", (Consumer<ParseDateTag>) t -> {
                    t.setType("both");
                    t.setTimeStyle("huge");
                }),
                arguments("pattern", (Consumer<ParseDateTag>) t -> t.setPattern("bbbbbbbbbb{")),
                arguments("scope", (Consumer<ParseDateTag>) t -> {
                    t.setPattern("M/d/yy");
                    t.setVar("d");
                    t.setScope("galaxy");
                }));
    }

    @ParameterizedTest(name = "invalid {0}")
    @MethodSource("invalidAttributes")
    void invalidAttributeIsATagException(String attribute, Consumer<ParseDateTag> invalid) {
        ParseDateTag tag = us("4/13/02");
        invalid.accept(tag);
        assertThrows(JspTagException.class, () -> run(tag), attribute);
    }

    @Test
    void reusedInstanceWithoutSetterCallsReproducesTheOutput() throws Exception {
        ParseDateTag tag = us("4/13/02");
        tag.setPattern("M/d/yy");
        tag.setTimeZone("Asia/Tokyo");
        String first = run(tag);
        assertEquals(new Date(INSTANT.getTime() - 9 * 3_600_000L).toString(), first);
        assertEquals(first, run(tag));
    }

    @Test
    void reusedInstanceKeepsTheAttributesWhoseSetterIsNotCalledAgain() throws Exception {
        ParseDateTag tag = us("13.04.02");
        tag.setPattern("dd.MM.yy");
        tag.setParseLocale("de_DE");
        tag.setTimeZone("Asia/Tokyo");
        tag.setVar("d");
        run(tag);
        tag.setValue("14.04.02");
        run(tag);
        assertEquals(new Date(INSTANT.getTime() + 86_400_000L - 9 * 3_600_000L), parsed());
    }

    @Test
    void releasedInstanceStartsClean() throws Exception {
        ParseDateTag tag = us("13.04.02");
        tag.setPattern("dd.MM.yy");
        tag.setParseLocale("de_DE");
        tag.setTimeZone("Asia/Tokyo");
        run(tag);
        tag.release();
        DateFormat jdk = DateFormat.getDateInstance(DateFormat.SHORT, Locale.US);
        jdk.setTimeZone(GMT);
        tag.setValue(jdk.format(INSTANT));
        tag.setTimeZone("GMT");
        tag.setDateStyle("short");
        tag.setVar("d");
        run(tag);
        assertEquals(INSTANT, parsed());
    }

    @Test
    void malformedFallbackLocaleIsAJspTagException() {
        Config.set(pc, Config.FMT_FALLBACK_LOCALE, "1x", PageContext.PAGE_SCOPE);
        ParseDateTag tag = new ParseDateTag();
        tag.setValue("4/13/02");
        assertThrows(JspTagException.class, () -> run(tag));
    }
}
