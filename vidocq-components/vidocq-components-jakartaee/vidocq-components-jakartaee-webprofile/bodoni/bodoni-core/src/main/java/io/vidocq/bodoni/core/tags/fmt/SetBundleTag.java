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

import io.vidocq.bodoni.core.internal.I18n;
import io.vidocq.bodoni.core.internal.Scopes;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.jstl.core.Config;
import jakarta.servlet.jsp.jstl.fmt.LocalizationContext;
import jakarta.servlet.jsp.tagext.TagSupport;

/**
 * {@code <fmt:setBundle>} (Tags 3.0 §8.7): creates an i18n localization context and stores it in
 * a scoped variable, or in the {@code jakarta.servlet.jsp.jstl.fmt.localizationContext}
 * configuration setting when there is no {@code var}.
 */
public class SetBundleTag extends TagSupport {

    private static final long serialVersionUID = 1L;
    private static final String ACTION = "fmt:setBundle";

    private String basename;
    private String varName;
    private String scope;

    /** @param basename the resource bundle base name; {@code null} or empty gives a context without bundle */
    public void setBasename(String basename) {
        this.basename = basename;
    }

    /** @param name the scoped variable receiving the {@link LocalizationContext} */
    public void setVar(String name) {
        this.varName = name;
    }

    /** @param scope the scope of the variable or of the configuration setting */
    public void setScope(String scope) {
        this.scope = scope;
    }

    @Override
    public int doStartTag() throws JspException {
        int scopeId;
        try {
            scopeId = Scopes.of(scope);
        } catch (IllegalArgumentException e) {
            throw new JspTagException(ACTION + ": " + e.getMessage(), e);
        }
        LocalizationContext context = FmtSupport.determine(ACTION, pageContext, basename);
        if (varName != null) {
            pageContext.setAttribute(varName, context, scopeId);
        } else {
            Config.set(pageContext, Config.FMT_LOCALIZATION_CONTEXT, context, scopeId);
        }
        I18n.setResponseLocale(pageContext, context.getLocale());
        return SKIP_BODY;
    }

    @Override
    public void release() {
        super.release();
        basename = null;
        varName = null;
        scope = null;
    }
}
