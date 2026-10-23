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
package io.vidocq.bodoni.core.tags.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vidocq.bodoni.core.testing.Body;
import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestPageContext;

import jakarta.servlet.jsp.JspTagException;

import org.junit.jupiter.api.Test;

/** {@code <x:param>} (Tags 3.0 section 13.3). */
class ParamTagTest {

    /** Prints the parameter {@code message}, {@code DEFAULT} unless set. */
    private static final String MESSAGE = "<xsl:stylesheet xmlns:xsl='http://www.w3.org/1999/XSL/Transform' "
            + "version='1.0'><xsl:output method='text'/><xsl:param name='message'>DEFAULT</xsl:param>"
            + "<xsl:template match='/'><xsl:value-of select='$message'/></xsl:template></xsl:stylesheet>";

    private final TestPageContext pc = TestPageContext.create();

    /** Runs an {@code <x:transform>} of {@link #MESSAGE} whose body runs {@code param} with {@code paramBody}. */
    private String transformWith(ParamTag param, Body paramBody) throws Exception {
        TransformTag transform = new TransformTag();
        transform.setDoc("<a/>");
        transform.setXslt(MESSAGE);
        return TagHarness.run(transform, pc, p -> TagHarness.run(param, pc, transform, paramBody));
    }

    private static ParamTag message() {
        ParamTag tag = new ParamTag();
        tag.setName("message");
        return tag;
    }

    @Test
    void theValueAttributeIsTheParameter() throws Exception {
        ParamTag tag = message();
        tag.setValue("from value");

        assertEquals("from value", transformWith(tag, Body.NONE));
    }

    @Test
    void theValueAttributeWinsOverTheBody() throws Exception {
        ParamTag tag = message();
        tag.setValue("from value");

        assertEquals("from value", transformWith(tag, p -> p.getOut().write("from body")));
    }

    @Test
    void withoutValueTheTrimmedBodyIsTheParameter() throws Exception {
        assertEquals("from body", transformWith(message(), p -> p.getOut().write("\n   from body  \n")));
    }

    @Test
    void aNullValueSetsNoParameter() throws Exception {
        ParamTag tag = message();
        tag.setValue(null);

        assertEquals("DEFAULT", transformWith(tag, Body.NONE));
    }

    @Test
    void noValueAndNoBodySetsNoParameter() throws Exception {
        assertEquals("DEFAULT", transformWith(message(), Body.NONE));
    }

    @Test
    void aReusedInstanceDoesNotReadThePreviousBody() throws Exception {
        ParamTag tag = message();

        assertEquals("first", transformWith(tag, p -> p.getOut().write("first")));
        assertEquals("DEFAULT", transformWith(tag, Body.NONE));
    }

    @Test
    void releaseClearsTheAttributes() throws Exception {
        ParamTag tag = message();
        tag.setValue("from value");
        tag.release();
        tag.setName("message");

        assertEquals("from body", transformWith(tag, p -> p.getOut().write("from body")));
    }

    @Test
    void outsideAnXTransformItIsATagException() {
        ParamTag tag = message();
        tag.setValue("v");

        JspTagException e = assertThrows(JspTagException.class, () -> TagHarness.run(tag, pc, Body.NONE));

        assertEquals("<x:param> must be nested in <x:transform>", e.getMessage());
    }
}
