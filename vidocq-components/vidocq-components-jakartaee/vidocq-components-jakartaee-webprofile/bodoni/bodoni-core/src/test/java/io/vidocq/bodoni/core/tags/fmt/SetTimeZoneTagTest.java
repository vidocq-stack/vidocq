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

import java.util.TimeZone;

import io.vidocq.bodoni.core.testing.Body;
import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestPageContext;

import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.PageContext;
import jakarta.servlet.jsp.jstl.core.Config;

import org.junit.jupiter.api.Test;

class SetTimeZoneTagTest {

    private static final TimeZone LA = TimeZone.getTimeZone("America/Los_Angeles");

    private final TestPageContext pc = TestPageContext.create();

    @Test
    void withoutVarTheZoneBecomesTheConfiguration() throws Exception {
        SetTimeZoneTag tag = new SetTimeZoneTag();
        tag.setValue("America/Los_Angeles");
        tag.setScope("request");
        TagHarness.run(tag, pc, Body.NONE);
        assertEquals(LA, Config.get(pc, Config.FMT_TIME_ZONE, PageContext.REQUEST_SCOPE));
        assertNull(Config.get(pc, Config.FMT_TIME_ZONE, PageContext.PAGE_SCOPE));
    }

    @Test
    void varStoresATimeZoneAttribute() throws Exception {
        SetTimeZoneTag tag = new SetTimeZoneTag();
        tag.setValue(LA);
        tag.setVar("tz");
        tag.setScope("session");
        TagHarness.run(tag, pc, Body.NONE);
        assertEquals(LA, pc.getAttribute("tz", PageContext.SESSION_SCOPE));
        assertNull(Config.find(pc, Config.FMT_TIME_ZONE));
    }

    @Test
    void nullOrEmptyMeansGmt() throws Exception {
        SetTimeZoneTag tag = new SetTimeZoneTag();
        tag.setValue("");
        tag.setVar("tz");
        TagHarness.run(tag, pc, Body.NONE);
        assertEquals(TimeZone.getTimeZone("GMT"), pc.getAttribute("tz"));
        tag.release();
        tag.setValue(null);
        tag.setVar("tz2");
        TagHarness.run(tag, pc, Body.NONE);
        assertEquals(TimeZone.getTimeZone("GMT"), pc.getAttribute("tz2"));
    }

    @Test
    void invalidScopeIsRejected() {
        SetTimeZoneTag tag = new SetTimeZoneTag();
        tag.setValue(LA);
        tag.setScope("nope");
        assertThrows(JspTagException.class, () -> TagHarness.run(tag, pc, Body.NONE));
    }

    @Test
    void reusedInstanceDoesNotKeepPreviousVarOrScope() throws Exception {
        SetTimeZoneTag tag = new SetTimeZoneTag();
        tag.setValue(LA);
        tag.setVar("tz");
        tag.setScope("request");
        TagHarness.run(tag, pc, Body.NONE);
        tag.release();
        tag.setValue("Europe/Paris");
        TagHarness.run(tag, pc, Body.NONE);
        assertEquals(TimeZone.getTimeZone("Europe/Paris"),
                Config.get(pc, Config.FMT_TIME_ZONE, PageContext.PAGE_SCOPE));
    }
}
