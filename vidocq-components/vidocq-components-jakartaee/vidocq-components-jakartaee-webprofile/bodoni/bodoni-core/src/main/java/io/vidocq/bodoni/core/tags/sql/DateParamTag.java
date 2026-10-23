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

package io.vidocq.bodoni.core.tags.sql;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.jstl.sql.SQLExecutionTag;
import jakarta.servlet.jsp.tagext.TagSupport;

/**
 * {@code <sql:dateParam>} (Tags 3.0 §10.8): sets the next parameter marker of the enclosing
 * {@code <sql:query>} or {@code <sql:update>} to a {@link java.sql.Date}, {@link Time} or
 * {@link Timestamp} built from the {@code value}, as the {@code type} attribute says
 * ({@code date}, {@code time} or {@code timestamp}, the default). A {@code null} value is
 * the SQL NULL.
 */
public class DateParamTag extends TagSupport {

    private static final long serialVersionUID = 1L;

    private static final String ELEMENT = "sql:dateParam";
    private static final String DATE = "date";
    private static final String TIME = "time";
    private static final String TIMESTAMP = "timestamp";

    private transient java.util.Date value;
    private String type;

    /** Creates the handler. */
    public DateParamTag() {
        // attributes arrive through the setters
    }

    /** @param value the date; {@code null} is the SQL NULL */
    public void setValue(java.util.Date value) {
        this.value = value;
    }

    /** @param type {@code date}, {@code time} or {@code timestamp}; {@code null} means {@code timestamp} */
    public void setType(String type) {
        this.type = type;
    }

    @Override
    public int doEndTag() throws JspException {
        String kind = type == null ? TIMESTAMP : type;
        if (!DATE.equals(kind) && !TIME.equals(kind) && !TIMESTAMP.equals(kind)) {
            throw new JspTagException(ELEMENT + ": invalid type '" + type + "': expected date, time or timestamp");
        }
        SQLExecutionTag target = SqlSupport.executionAncestor(this, ELEMENT);
        target.addSQLParameter(convert(kind));
        return EVAL_PAGE;
    }

    @Override
    public void release() {
        super.release();
        value = null;
        type = null;
    }

    private Object convert(String kind) {
        if (value == null) {
            return null;
        }
        return switch (kind) {
            case DATE -> value instanceof Date date ? date : new Date(value.getTime());
            case TIME -> value instanceof Time time ? time : new Time(value.getTime());
            default -> value instanceof Timestamp stamp ? stamp : new Timestamp(value.getTime());
        };
    }
}
