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

import java.util.TimeZone;

import io.vidocq.bodoni.core.testing.Body;
import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestPageContext;

import jakarta.servlet.jsp.tagext.Tag;

import org.junit.jupiter.api.Test;

class TimeZoneTagTest {

    private static final TimeZone LA = TimeZone.getTimeZone("America/Los_Angeles");

    private final TestPageContext pc = TestPageContext.create();

    private TimeZone seenFor(Object value) throws Exception {
        TimeZoneTag tag = new TimeZoneTag();
        tag.setValue(value);
        TimeZone[] seen = new TimeZone[1];
        TagHarness.run(tag, pc, p -> seen[0] = tag.getTimeZone());
        return seen[0];
    }

    @Test
    void stringIdIsParsed() throws Exception {
        assertEquals(LA, seenFor("America/Los_Angeles"));
    }

    @Test
    void customIdIsParsed() throws Exception {
        assertEquals(TimeZone.getTimeZone("GMT-8"), seenFor("GMT-8"));
    }

    @Test
    void timeZoneValueIsUsedAsIs() throws Exception {
        assertEquals(LA, seenFor(LA));
    }

    @Test
    void nullOrEmptyMeansGmt() throws Exception {
        assertEquals(TimeZone.getTimeZone("GMT"), seenFor(null));
        assertEquals(TimeZone.getTimeZone("GMT"), seenFor(""));
    }

    @Test
    void bodyIsRendered() throws Exception {
        TimeZoneTag tag = new TimeZoneTag();
        tag.setValue(LA);
        assertEquals("body", TagHarness.run(tag, pc, p -> p.getOut().print("body")));
        TimeZoneTag fresh = new TimeZoneTag();
        fresh.setPageContext(pc);
        assertEquals(Tag.EVAL_BODY_INCLUDE, fresh.doStartTag());
    }

    @Test
    void reusedInstanceDoesNotKeepThePreviousZone() throws Exception {
        TimeZoneTag tag = new TimeZoneTag();
        tag.setValue(LA);
        TagHarness.run(tag, pc, Body.EMPTY);
        tag.release();
        assertNull(tag.getTimeZone());
        tag.setValue("Europe/Paris");
        TagHarness.run(tag, pc, Body.EMPTY);
        assertEquals(TimeZone.getTimeZone("Europe/Paris"), tag.getTimeZone());
    }
}
