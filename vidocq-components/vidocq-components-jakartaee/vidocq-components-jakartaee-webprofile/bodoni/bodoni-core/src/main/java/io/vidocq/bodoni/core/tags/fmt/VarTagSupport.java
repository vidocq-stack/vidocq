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

import java.io.IOException;

import io.vidocq.bodoni.core.internal.Scopes;

import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.jstl.core.Config;
import jakarta.servlet.jsp.tagext.BodyTagSupport;

/**
 * The {@code var} and {@code scope} attributes of the i18n and formatting actions that export
 * a result: the scope validation, and the result stored in {@code var}, written to the output
 * or stored in a configuration setting.
 *
 * <p>The attributes persist until {@link #release()}, as the {@code Tag} lifecycle lets a
 * container reuse an instance without calling the setters of unchanged attributes again. The
 * public setters reach other packages through the bridges javac emits in each public handler.
 */
abstract class VarTagSupport extends BodyTagSupport {

    private static final long serialVersionUID = 1L;

    private final String action;
    private String varName;
    private String scope;

    /** @param action the action name, for messages */
    VarTagSupport(String action) {
        this.action = action;
    }

    /** @param name the scoped variable receiving the result of the action */
    public void setVar(String name) {
        this.varName = name;
    }

    /** @param scope the scope of {@code var}, or of the configuration setting when there is no {@code var} */
    public void setScope(String scope) {
        this.scope = scope;
    }

    @Override
    public void release() {
        super.release();
        varName = null;
        scope = null;
    }

    /** The action name, for messages. */
    final String action() {
        return action;
    }

    /** A {@link JspTagException} that names the action and keeps {@code cause}. */
    final JspTagException failure(Exception cause) {
        return new JspTagException(action + ": " + cause.getMessage(), cause);
    }

    /** The {@link jakarta.servlet.jsp.PageContext} constant of the {@code scope} attribute. */
    final int scopeId() throws JspTagException {
        try {
            return Scopes.of(scope);
        } catch (IllegalArgumentException e) {
            throw failure(e);
        }
    }

    /** The trimmed body, or {@code null} when no body was evaluated. */
    final String bodyValue() {
        return bodyContent == null ? null : bodyContent.getString().trim();
    }

    /** Removes {@code var} from the scope, when {@code var} is set. */
    final void removeVar(int scopeId) {
        if (varName != null) {
            pageContext.removeAttribute(varName, scopeId);
        }
    }

    /** Stores {@code result} in {@code var}, or writes its string form to the output without {@code var}. */
    final void export(Object result, int scopeId) throws JspTagException {
        try {
            if (varName != null) {
                pageContext.setAttribute(varName, result, scopeId);
            } else {
                pageContext.getOut().print(String.valueOf(result));
            }
        } catch (IOException e) {
            throw failure(e);
        }
    }

    /** Stores {@code result} in {@code var}, or in the configuration setting {@code name} without {@code var}. */
    final void store(Object result, String name, int scopeId) {
        if (varName != null) {
            pageContext.setAttribute(varName, result, scopeId);
        } else {
            Config.set(pageContext, name, result, scopeId);
        }
    }
}
