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

import java.sql.SQLException;

import javax.sql.DataSource;

import io.vidocq.bodoni.core.internal.DriverManagerDataSource;
import io.vidocq.bodoni.core.internal.JdbcParameters;
import io.vidocq.bodoni.core.internal.Scopes;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.jstl.core.Config;
import jakarta.servlet.jsp.tagext.TagSupport;

/**
 * {@code <sql:setDataSource>} (Tags 3.0 §10.6): makes a {@link DataSource} from the
 * {@code dataSource} attribute (an object, a JNDI path or a JDBC parameter string) or, without
 * it, from {@code url}, {@code driver}, {@code user} and {@code password}, and exports it to
 * {@code var} in {@code scope} or, without {@code var}, as the {@link Config#SQL_DATA_SOURCE}
 * setting. The source is resolved at once; no connection is opened. The password stays inside
 * the data source and appears in no message.
 */
public class SetDataSourceTag extends TagSupport {

    private static final long serialVersionUID = 1L;

    private static final String ACTION = "sql:setDataSource";
    private static final String PREFIX = ACTION + ": ";

    private transient Object dataSource;
    private boolean dataSourceSpecified;
    private String url;
    private String driver;
    private String user;
    private transient String password;
    private String varName;
    private String scope;

    /** Creates the handler. */
    public SetDataSourceTag() {
        // attributes arrive through the setters
    }

    /** @param dataSource a {@code javax.sql.DataSource}, a JNDI path or a JDBC parameter string */
    public void setDataSource(Object dataSource) {
        this.dataSource = dataSource;
        this.dataSourceSpecified = true;
    }

    /** @param url the JDBC URL, used when {@code dataSource} is not specified */
    public void setUrl(String url) {
        this.url = url;
    }

    /** @param driver the JDBC driver class name; empty means none */
    public void setDriver(String driver) {
        this.driver = driver;
    }

    /** @param user the database user */
    public void setUser(String user) {
        this.user = user;
    }

    /** @param password the database password */
    public void setPassword(String password) {
        this.password = password;
    }

    /** @param name the scoped variable receiving the data source */
    public void setVar(String name) {
        this.varName = name;
    }

    /** @param scope the scope of {@code var}, or of the {@link Config#SQL_DATA_SOURCE} setting */
    public void setScope(String scope) {
        this.scope = scope;
    }

    @Override
    public int doEndTag() throws JspException {
        int scopeId = scopeId();
        DataSource source = source();
        if (varName != null) {
            pageContext.setAttribute(varName, source, scopeId);
        } else {
            Config.set(pageContext, Config.SQL_DATA_SOURCE, source, scopeId);
        }
        return EVAL_PAGE;
    }

    @Override
    public void release() {
        super.release();
        dataSource = null;
        dataSourceSpecified = false;
        url = null;
        driver = null;
        user = null;
        password = null;
        varName = null;
        scope = null;
    }

    private DataSource source() throws JspException {
        if (dataSourceSpecified) {
            if (dataSource == null) {
                throw new JspException(PREFIX + "dataSource is null");
            }
            return SqlSupport.resolve(ACTION, dataSource);
        }
        if (url == null) {
            throw new JspException(PREFIX + "no data source: set dataSource or url");
        }
        JdbcParameters parameters = new JdbcParameters(url, driver == null || driver.isEmpty() ? null : driver,
                user == null ? "" : user, password == null ? "" : password);
        try {
            return new DriverManagerDataSource(parameters);
        } catch (SQLException e) {
            throw new JspException(PREFIX + e.getMessage(), e);
        }
    }

    private int scopeId() throws JspTagException {
        try {
            return Scopes.of(scope);
        } catch (IllegalArgumentException e) {
            throw new JspTagException(PREFIX + e.getMessage(), e);
        }
    }
}
