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

import java.util.List;
import java.util.Locale;

import io.vidocq.bodoni.core.internal.Locales;

import jakarta.servlet.jsp.JspException;

/**
 * The formatting and parsing actions (Tags 3.0 §9.7-§9.10): the value comes from the
 * {@code value} attribute or, when it was not set, from the trimmed body; a {@code null} or
 * empty value removes {@code var}, any other is converted and exported.
 */
abstract class ValueTagSupport extends VarTagSupport {

    private static final long serialVersionUID = 1L;

    private transient Object value;
    private boolean valueSet;

    /** @param action the action name, for messages */
    ValueTagSupport(String action) {
        super(action);
    }

    /** Records the {@code value} attribute: once set, the body is no longer the value. */
    final void valueAttribute(Object value) {
        this.value = value;
        this.valueSet = true;
    }

    @Override
    public int doStartTag() throws JspException {
        // An empty element gets no setBodyContent: never reuse a pooled handler's old body.
        bodyContent = null;
        scopeId();
        return valueSet ? SKIP_BODY : EVAL_BODY_BUFFERED;
    }

    @Override
    public int doEndTag() throws JspException {
        int scopeId = scopeId();
        Object raw = valueSet ? value : bodyValue();
        if (raw == null || "".equals(raw)) {
            removeVar(scopeId);
        } else {
            export(convert(raw), scopeId);
        }
        return EVAL_PAGE;
    }

    @Override
    public void release() {
        super.release();
        value = null;
        valueSet = false;
    }

    /**
     * Formats or parses the value.
     *
     * @param raw the value, neither {@code null} nor empty
     * @return the result to export
     */
    abstract Object convert(Object raw) throws JspException;

    /**
     * The locale of a parsing action (§9.8, §9.10): the {@code parseLocale} attribute, else the
     * formatting locale (§9.2) among {@code available}.
     *
     * @param attribute the {@code parseLocale} attribute: a {@link Locale}, its text form, or
     *                  {@code null} / empty when not specified
     * @param text      the text to parse, for the message when there is no locale
     * @throws JspException when the attribute is malformed or no locale applies
     */
    final Locale parseLocale(Object attribute, List<Locale> available, String text) throws JspException {
        Locale locale;
        try {
            locale = Locales.of(attribute);
        } catch (IllegalArgumentException e) {
            throw failure(e);
        }
        if (locale == null) {
            locale = FmtSupport.formattingLocale(action(), this, pageContext, available);
        }
        if (locale == null) {
            throw new JspException(action() + ": no formatting locale to parse '" + text + "'");
        }
        return locale;
    }
}
