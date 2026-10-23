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

import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.tagext.BodyContent;
import jakarta.servlet.jsp.tagext.BodyTag;
import jakarta.servlet.jsp.tagext.TagSupport;

/**
 * The actions that read an XML document from the {@code doc} attribute (or its deprecated
 * alias {@code xml}) or, without it, from their body, and export a result in {@code var} and
 * {@code scope}.
 *
 * <p>The attributes persist until {@link #release()}, as the {@code Tag} lifecycle lets a
 * container reuse an instance without calling the setters of unchanged attributes again. The
 * public setters reach other packages through the bridges javac emits in each public handler.
 *
 * <p>It implements {@link BodyTag} itself rather than extending {@code BodyTagSupport}, which
 * keeps the handler hierarchy within five parents; {@code doAfterBody} ({@code SKIP_BODY}) and
 * {@code doEndTag} ({@code EVAL_PAGE}) come from {@link TagSupport}, with the values
 * {@code BodyTagSupport} returns.
 */
abstract class DocumentTagSupport extends TagSupport implements BodyTag {

    private static final long serialVersionUID = 1L;

    /** The current body content, or {@code null} when no body was evaluated. */
    protected transient BodyContent bodyContent;

    private final String action;
    private transient Object doc;
    private boolean docSpecified;
    private String varName;
    private String scope;

    /** @param action the action name, for messages */
    DocumentTagSupport(String action) {
        this.action = action;
    }

    /** @param doc the XML document, of a type the action accepts; the body is then ignored */
    public void setDoc(Object doc) {
        this.doc = doc;
        this.docSpecified = true;
    }

    /**
     * @param xml the XML document
     * @deprecated the Tags 3.0 alias of {@link #setDoc(Object)}; use {@code doc}
     */
    @Deprecated
    public void setXml(Object xml) {
        setDoc(xml);
    }

    /** @param name the scoped variable receiving the result of the action */
    public void setVar(String name) {
        this.varName = name;
    }

    /** @param scope the scope of {@code var} */
    public void setScope(String scope) {
        this.scope = scope;
    }

    @Override
    public void setBodyContent(BodyContent b) {
        this.bodyContent = b;
    }

    @Override
    public void doInitBody() {
        // Nothing to prepare: the body is read from bodyContent once evaluated.
    }

    /** @return the current body content, or {@code null} when no body was evaluated */
    public BodyContent getBodyContent() {
        return bodyContent;
    }

    @Override
    public void release() {
        bodyContent = null;
        super.release();
        doc = null;
        docSpecified = false;
        varName = null;
        scope = null;
    }

    /** Whether {@code doc} (or {@code xml}) was set, even to {@code null}. */
    final boolean docSpecified() {
        return docSpecified;
    }

    /**
     * The document: the attribute when specified, even {@code null}; else the trimmed body,
     * {@code null} when no body was evaluated.
     */
    final Object document() {
        if (docSpecified) {
            return doc;
        }
        return bodyContent == null ? null : bodyContent.getString().trim();
    }

    /** The {@link jakarta.servlet.jsp.PageContext} constant of the {@code scope} attribute. */
    final int scopeId() throws JspTagException {
        return XmlSupport.scopeId(action, scope);
    }

    /** Whether {@code var} was set. */
    final boolean hasVar() {
        return varName != null;
    }

    /** Stores {@code value} in {@code var} and {@code scope}; nothing without {@code var}. */
    final void exportVar(Object value) throws JspTagException {
        if (varName != null) {
            pageContext.setAttribute(varName, value, scopeId());
        }
    }
}
