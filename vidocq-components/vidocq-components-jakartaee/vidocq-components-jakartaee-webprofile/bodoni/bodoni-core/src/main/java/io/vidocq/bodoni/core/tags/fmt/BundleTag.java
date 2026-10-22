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

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.jstl.fmt.LocalizationContext;
import jakarta.servlet.jsp.tagext.TagSupport;

/**
 * {@code <fmt:bundle>} (Tags 3.0 §8.6): creates an i18n localization context for its body, which
 * nested actions read through {@link #getLocalizationContext()} and {@link #getPrefix()}.
 */
public class BundleTag extends TagSupport {

    private static final long serialVersionUID = 1L;

    private String basename;
    private String prefix;
    private transient LocalizationContext localizationContext;

    /** @param basename the resource bundle base name; {@code null} or empty gives a context without bundle */
    public void setBasename(String basename) {
        this.basename = basename;
    }

    /** @param prefix the prefix prepended to the key of nested {@code <fmt:message>} actions */
    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    /** @return the context established for the body; {@code null} outside it */
    public LocalizationContext getLocalizationContext() {
        return localizationContext;
    }

    /** @return the prefix of nested message keys, or {@code null} */
    public String getPrefix() {
        return prefix;
    }

    @Override
    public int doStartTag() throws JspException {
        localizationContext = I18n.determine(pageContext, basename);
        I18n.setResponseLocale(pageContext, localizationContext.getLocale());
        return EVAL_BODY_INCLUDE;
    }

    @Override
    public void release() {
        super.release();
        basename = null;
        prefix = null;
        localizationContext = null;
    }
}
