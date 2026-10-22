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

package io.vidocq.bodoni.core.testing;

import jakarta.servlet.jsp.tagext.BodyContent;
import jakarta.servlet.jsp.tagext.BodyTag;
import jakarta.servlet.jsp.tagext.IterationTag;
import jakarta.servlet.jsp.tagext.Tag;
import jakarta.servlet.jsp.tagext.TryCatchFinally;

/**
 * Drives a classic tag handler through the JSP lifecycle the way a Pages container does.
 */
public final class TagHarness {

    private TagHarness() {
    }

    /** Runs {@code tag} without a parent. */
    public static String run(Tag tag, TestPageContext pc, Body body) throws Exception {
        return run(tag, pc, null, body);
    }

    /**
     * Runs {@code tag} and returns the text written to the page's {@code JspWriter} during
     * the run. {@code doEndTag}'s return value is ignored. {@code release()} is not called.
     */
    public static String run(Tag tag, TestPageContext pc, Tag parent, Body body) throws Exception {
        TestJspWriter page = (TestJspWriter) pc.getOut();
        int start = page.getString().length();
        tag.setPageContext(pc);
        tag.setParent(parent);
        try {
            lifecycle(tag, pc, body);
        } catch (Throwable t) {
            if (tag instanceof TryCatchFinally tcf) {
                try {
                    tcf.doCatch(t);
                } catch (Exception | Error e) {
                    throw e;
                } catch (Throwable e) {
                    throw new IllegalStateException(e);
                }
            } else {
                throw t;
            }
        } finally {
            if (tag instanceof TryCatchFinally tcf) {
                tcf.doFinally();
            }
        }
        return page.getString().substring(start);
    }

    private static void lifecycle(Tag tag, TestPageContext pc, Body body) throws Exception {
        int r = tag.doStartTag();
        if (r != Tag.SKIP_BODY) {
            if (r == BodyTag.EVAL_BODY_BUFFERED && tag instanceof BodyTag bt) {
                BodyContent bc = pc.pushBody();
                try {
                    bt.setBodyContent(bc);
                    bt.doInitBody();
                    loop(tag, pc, body);
                } finally {
                    pc.popBody();
                }
            } else {
                loop(tag, pc, body);
            }
        }
        tag.doEndTag();
    }

    private static void loop(Tag tag, TestPageContext pc, Body body) throws Exception {
        if (tag instanceof IterationTag it) {
            do {
                body.render(pc);
            } while (it.doAfterBody() == IterationTag.EVAL_BODY_AGAIN);
        } else {
            body.render(pc);
        }
    }
}
