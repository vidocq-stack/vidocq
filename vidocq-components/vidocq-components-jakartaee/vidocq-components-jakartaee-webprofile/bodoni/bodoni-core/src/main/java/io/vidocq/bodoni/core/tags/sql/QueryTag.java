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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import io.vidocq.bodoni.core.internal.QueryResult;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.jstl.sql.Result;

/**
 * {@code <sql:query>} (Tags 3.0 §10.3): runs a query and exports a disconnected
 * {@link Result} to {@code var}. {@code maxRows} comes from the attribute, else from the
 * {@code jakarta.servlet.jsp.jstl.sql.maxRows} setting (§10.9.2), else no limit;
 * {@code startRow} rows are skipped first. With a limit, the driver is asked for at most
 * {@code startRow + maxRows + 1} rows: the one extra row only tells whether the limit cut
 * the result.
 */
public class QueryTag extends ExecutionTagSupport {

    private static final long serialVersionUID = 1L;

    private static final String ACTION = "sql:query";

    private int maxRows;
    private boolean maxRowsSpecified;
    private int startRow;

    /** The limit of the current invocation: the attribute, else the setting, else {@code -1}. */
    private int limit = -1;

    /** Creates the handler. */
    public QueryTag() {
        super(ACTION);
    }

    /** @param maxRows the maximum number of rows in the result, {@code -1} for no limit */
    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
        this.maxRowsSpecified = true;
    }

    /** @param startRow the index of the first row included in the result, {@code 0} for the first */
    public void setStartRow(int startRow) {
        this.startRow = startRow;
    }

    /** Checks {@code var}, {@code maxRows} and {@code startRow}, before any connection is opened. */
    @Override
    public int doStartTag() throws JspException {
        int result = super.doStartTag();
        if (!hasVar()) {
            throw new JspTagException(ACTION + ": var must be set");
        }
        limit = maxRowsSpecified ? maxRows : SqlSupport.maxRowsSetting(pageContext);
        if (limit < -1) {
            throw new JspTagException(ACTION + ": maxRows must be -1 or more, got " + limit);
        }
        if (startRow < 0) {
            throw new JspTagException(ACTION + ": startRow must be 0 or more, got " + startRow);
        }
        return result;
    }

    @Override
    protected Object execute(PreparedStatement statement) throws SQLException {
        if (limit != -1) {
            long fetched = (long) startRow + limit + 1;
            if (fetched <= Integer.MAX_VALUE) {
                statement.setMaxRows((int) fetched);
            }
        }
        try (ResultSet rows = statement.executeQuery()) {
            return QueryResult.of(rows, startRow, limit);
        }
    }

    @Override
    public void release() {
        super.release();
        maxRows = 0;
        maxRowsSpecified = false;
        startRow = 0;
    }
}
