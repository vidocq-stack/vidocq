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

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import io.vidocq.bodoni.core.testing.Body;
import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestPageContext;

import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.PageContext;
import jakarta.servlet.jsp.jstl.core.Config;

import org.junit.jupiter.api.Test;

class FormatDateTagTest {

    private static final Date INSTANT = new Date(1018656000000L);
    private static final TimeZone GMT = TimeZone.getTimeZone("GMT");
    private static final String[] STYLE_NAMES = {"default", "short", "medium", "long", "full"};
    private static final int[] STYLES = {DateFormat.DEFAULT, DateFormat.SHORT, DateFormat.MEDIUM, DateFormat.LONG,
            DateFormat.FULL};

    private final TestPageContext pc = TestPageContext.create();

    private FormatDateTag us() {
        Config.set(pc, Config.FMT_LOCALE, Locale.US, PageContext.PAGE_SCOPE);
        FormatDateTag tag = new FormatDateTag();
        tag.setValue(INSTANT);
        tag.setTimeZone("GMT");
        return tag;
    }

    private String run(FormatDateTag tag) throws Exception {
        return TagHarness.run(tag, pc, Body.NONE);
    }

    private static DateFormat expected(DateFormat format) {
        format.setTimeZone(GMT);
        return format;
    }

    @Test
    void patternFormatsInTheGivenTimeZone() throws Exception {
        FormatDateTag tag = us();
        tag.setPattern("dd.MM.yy");
        assertEquals("13.04.02", run(tag));
    }

    @Test
    void defaultTypeIsDateWithTheLongStyle() throws Exception {
        FormatDateTag tag = us();
        tag.setDateStyle("long");
        assertEquals("April 13, 2002", run(tag));
    }

    @Test
    void everyDateStyleMatchesTheJdk() throws Exception {
        for (int i = 0; i < STYLES.length; i++) {
            FormatDateTag tag = us();
            tag.setDateStyle(STYLE_NAMES[i]);
            assertEquals(expected(DateFormat.getDateInstance(STYLES[i], Locale.US)).format(INSTANT), run(tag),
                    STYLE_NAMES[i]);
        }
    }

    @Test
    void everyTimeStyleMatchesTheJdk() throws Exception {
        for (int i = 0; i < STYLES.length; i++) {
            FormatDateTag tag = us();
            tag.setType("time");
            tag.setTimeStyle(STYLE_NAMES[i]);
            assertEquals(expected(DateFormat.getTimeInstance(STYLES[i], Locale.US)).format(INSTANT), run(tag),
                    STYLE_NAMES[i]);
        }
    }

    @Test
    void bothUsesTheDateAndTheTimeStyle() throws Exception {
        for (int i = 0; i < STYLES.length; i++) {
            FormatDateTag tag = us();
            tag.setType("both");
            tag.setDateStyle(STYLE_NAMES[i]);
            tag.setTimeStyle(STYLE_NAMES[STYLES.length - 1 - i]);
            DateFormat jdk = expected(DateFormat.getDateTimeInstance(STYLES[i], STYLES[STYLES.length - 1 - i],
                    Locale.US));
            assertEquals(jdk.format(INSTANT), run(tag), STYLE_NAMES[i]);
        }
    }

    @Test
    void dateStyleIsIgnoredForTypeTime() throws Exception {
        FormatDateTag tag = us();
        tag.setType("time");
        tag.setDateStyle("full");
        assertEquals(expected(DateFormat.getTimeInstance(DateFormat.DEFAULT, Locale.US)).format(INSTANT), run(tag));
    }

    @Test
    void timeStyleIsIgnoredForTypeDate() throws Exception {
        FormatDateTag tag = us();
        tag.setTimeStyle("full");
        assertEquals(expected(DateFormat.getDateInstance(DateFormat.DEFAULT, Locale.US)).format(INSTANT), run(tag));
    }

    @Test
    void patternOverridesTypeAndStyles() throws Exception {
        FormatDateTag tag = us();
        tag.setType("time");
        tag.setTimeStyle("full");
        tag.setPattern("yyyy");
        assertEquals("2002", run(tag));
    }

    @Test
    void emptyPatternIsIgnored() throws Exception {
        FormatDateTag tag = us();
        tag.setPattern("");
        assertEquals(expected(DateFormat.getDateInstance(DateFormat.DEFAULT, Locale.US)).format(INSTANT), run(tag));
    }

    @Test
    void enclosingTimeZoneAppliesWhenTheAttributeIsMissing() throws Exception {
        Config.set(pc, Config.FMT_LOCALE, Locale.US, PageContext.PAGE_SCOPE);
        TimeZoneTag enclosing = new TimeZoneTag();
        enclosing.setValue("Asia/Tokyo");
        String[] out = new String[2];
        TagHarness.run(enclosing, pc, p -> {
            FormatDateTag tag = new FormatDateTag();
            tag.setValue(INSTANT);
            tag.setPattern("HH");
            out[0] = TagHarness.run(tag, pc, enclosing, Body.NONE);
            tag.setTimeZone("");
            out[1] = TagHarness.run(tag, pc, enclosing, Body.NONE);
        });
        assertEquals("09", out[0]);
        assertEquals("09", out[1]);
    }

    @Test
    void configuredTimeZoneApplies() throws Exception {
        Config.set(pc, Config.FMT_TIME_ZONE, "Asia/Tokyo", PageContext.PAGE_SCOPE);
        FormatDateTag tag = us();
        tag.setTimeZone(null);
        tag.setPattern("HH");
        assertEquals("09", run(tag));
    }

    @Test
    void timeZoneObjectApplies() throws Exception {
        FormatDateTag tag = us();
        tag.setTimeZone(TimeZone.getTimeZone("GMT+2"));
        tag.setPattern("HH");
        assertEquals("02", run(tag));
    }

    @Test
    void nullValueWritesNothingAndRemovesVar() throws Exception {
        pc.setAttribute("d", "old", PageContext.PAGE_SCOPE);
        FormatDateTag tag = us();
        tag.setValue(null);
        tag.setVar("d");
        assertEquals("", run(tag));
        assertNull(pc.getAttribute("d", PageContext.PAGE_SCOPE));
    }

    @Test
    void varReceivesTheTextInTheGivenScope() throws Exception {
        FormatDateTag tag = us();
        tag.setPattern("yyyy");
        tag.setVar("d");
        tag.setScope("request");
        assertEquals("", run(tag));
        assertEquals("2002", pc.getAttribute("d", PageContext.REQUEST_SCOPE));
    }

    @Test
    void noFormattingLocaleFallsBackToDateToString() throws Exception {
        Config.set(pc, Config.FMT_LOCALE, Locale.of("xx"), PageContext.PAGE_SCOPE);
        FormatDateTag tag = new FormatDateTag();
        tag.setValue(INSTANT);
        assertEquals(INSTANT.toString(), run(tag));
    }

    @Test
    void invalidTypeIsATagException() {
        FormatDateTag tag = us();
        tag.setType("century");
        assertThrows(JspTagException.class, () -> run(tag));
    }

    @Test
    void invalidDateStyleIsATagException() {
        FormatDateTag tag = us();
        tag.setDateStyle("huge");
        assertThrows(JspTagException.class, () -> run(tag));
    }

    @Test
    void invalidTimeStyleIsATagException() {
        FormatDateTag tag = us();
        tag.setType("both");
        tag.setTimeStyle("huge");
        assertThrows(JspTagException.class, () -> run(tag));
    }

    @Test
    void invalidPatternIsATagException() {
        FormatDateTag tag = us();
        tag.setPattern("bbbbbbbbbb{");
        assertThrows(JspTagException.class, () -> run(tag));
    }

    @Test
    void invalidScopeIsATagException() {
        FormatDateTag tag = us();
        tag.setVar("d");
        tag.setScope("galaxy");
        assertThrows(JspTagException.class, () -> run(tag));
    }

    @Test
    void reusedInstanceWithoutSetterCallsReproducesTheOutput() throws Exception {
        FormatDateTag tag = us();
        tag.setPattern("HH");
        tag.setTimeZone("Asia/Tokyo");
        assertEquals("09", run(tag));
        assertEquals("09", run(tag));
    }

    @Test
    void reusedInstanceKeepsTheAttributesWhoseSetterIsNotCalledAgain() throws Exception {
        FormatDateTag tag = us();
        tag.setPattern("HH");
        tag.setTimeZone("Asia/Tokyo");
        assertEquals("09", run(tag));
        tag.setValue(new Date(INSTANT.getTime() + 3_600_000L));
        assertEquals("10", run(tag));
    }

    @Test
    void releasedInstanceKeepsNoSettingOfThePreviousUse() throws Exception {
        FormatDateTag tag = us();
        tag.setPattern("HH");
        tag.setTimeZone("Asia/Tokyo");
        run(tag);
        tag.release();
        tag.setValue(INSTANT);
        tag.setTimeZone("GMT");
        assertEquals(expected(DateFormat.getDateInstance(DateFormat.DEFAULT, Locale.US)).format(INSTANT), run(tag));
    }
}
