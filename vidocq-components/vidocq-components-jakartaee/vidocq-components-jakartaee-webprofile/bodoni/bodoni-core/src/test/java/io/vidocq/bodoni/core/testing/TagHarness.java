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

import jakarta.servlet.jsp.JspWriter;
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

    /** Outcome of a run: the text written and the {@code doEndTag} result. */
    public record Result(String output, int endTagResult) {
    }

    /** Runs {@code tag} without a parent. */
    public static String run(Tag tag, TestPageContext pc, Body body) throws Exception {
        return run(tag, pc, null, body);
    }

    /** Runs {@code tag} and returns only the text written during the run. */
    public static String run(Tag tag, TestPageContext pc, Tag parent, Body body) throws Exception {
        return runForResult(tag, pc, parent, body).output();
    }

    /**
     * Runs {@code tag}; returns the text written to the writer current at entry (the page
     * writer, or the enclosing {@code BodyContent} when nested in a buffered body) during
     * the run, and the {@code doEndTag} result ({@code -1} when it was not reached).
     * {@code release()} is not called. With {@link Body#NONE} the tag is run as an empty
     * element: no body lifecycle at all, so a {@code BodyTag} receives no
     * {@code BodyContent} for that use.
     */
    public static Result runForResult(Tag tag, TestPageContext pc, Tag parent, Body body) throws Exception {
        JspWriter target = pc.getOut();
        int start = text(target).length();
        int[] end = {-1};
        tag.setPageContext(pc);
        tag.setParent(parent);
        try {
            lifecycle(tag, pc, body, end);
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
        return new Result(text(target).substring(start), end[0]);
    }

    private static String text(JspWriter w) {
        if (w instanceof TestJspWriter t) {
            return t.getString();
        }
        if (w instanceof BodyContent bc) {
            return bc.getString();
        }
        throw new IllegalStateException("unsupported writer " + w.getClass());
    }

    private static void lifecycle(Tag tag, TestPageContext pc, Body body, int[] end) throws Exception {
        int r = tag.doStartTag();
        if (r == Tag.SKIP_BODY || body == Body.NONE) {
            // no body evaluated; for an empty element the body lifecycle never starts
            if (r != Tag.SKIP_BODY && r != Tag.EVAL_BODY_INCLUDE
                    && !(r == BodyTag.EVAL_BODY_BUFFERED && tag instanceof BodyTag)) {
                throw new IllegalStateException("invalid doStartTag result " + r + " for " + tag.getClass().getName());
            }
        } else if (r == Tag.EVAL_BODY_INCLUDE) {
            loop(tag, pc, body);
        } else if (r == BodyTag.EVAL_BODY_BUFFERED && tag instanceof BodyTag bt) {
            BodyContent bc = pc.pushBody();
            try {
                bt.setBodyContent(bc);
                bt.doInitBody();
                loop(tag, pc, body);
            } finally {
                pc.popBody();
            }
        } else {
            throw new IllegalStateException("invalid doStartTag result " + r + " for " + tag.getClass().getName());
        }
        end[0] = tag.doEndTag();
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
