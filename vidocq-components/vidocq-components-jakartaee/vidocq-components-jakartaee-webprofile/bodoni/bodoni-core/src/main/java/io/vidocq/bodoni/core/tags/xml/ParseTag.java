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

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;

import javax.xml.transform.TransformerException;

import io.vidocq.bodoni.core.internal.SystemIdPolicy;
import io.vidocq.bodoni.core.internal.XmlDocuments;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;

import org.w3c.dom.Document;
import org.xml.sax.SAXException;
import org.xml.sax.XMLFilter;

/**
 * {@code <x:parse>} (Tags 3.0 section 11.2): parses the XML document given by {@code doc}
 * (or {@code xml}) or by the trimmed body, and exports the {@link Document} to {@code var}
 * and to {@code varDom}.
 *
 * <p>No DTD or schema validation is performed. External references are resolved through
 * {@code systemId} under the "same origin" policy ({@link SystemIdPolicy}); without a system
 * id every one is refused. A page-supplied {@code Reader} is not closed.
 */
public class ParseTag extends DocumentTagSupport {

    private static final long serialVersionUID = 1L;

    private static final String ACTION = "x:parse";

    /** Message of a {@code null} or empty document. */
    private static final String NULL_OR_EMPTY = ACTION + ": the XML document is null or empty";

    /** Message of a document that is neither a {@code String} nor a {@code Reader}: its class. */
    private static final String NOT_A_DOCUMENT = ACTION + ": the XML document must be a String or a Reader, not %s";

    private String systemId;
    private transient XMLFilter filter;
    private String varDom;
    private String scopeDom;

    /** Creates the handler. */
    public ParseTag() {
        super(ACTION);
    }

    /** @param systemId the system identifier of the document, the base of its external references */
    public void setSystemId(String systemId) {
        this.systemId = systemId;
    }

    /** @param filter the filter applied to the document before it is built, {@code null} for none */
    public void setFilter(XMLFilter filter) {
        this.filter = filter;
    }

    /** @param name the scoped variable receiving the {@link Document} */
    public void setVarDom(String name) {
        this.varDom = name;
    }

    /** @param scope the scope of {@code varDom} */
    public void setScopeDom(String scope) {
        this.scopeDom = scope;
    }

    @Override
    public int doStartTag() throws JspException {
        // An empty element gets no setBodyContent: never reuse a pooled handler's old body.
        bodyContent = null;
        scopeId();
        XmlSupport.scopeId(ACTION, scopeDom);
        return docSpecified() ? SKIP_BODY : EVAL_BODY_BUFFERED;
    }

    @Override
    public int doEndTag() throws JspException {
        Reader input = reader(document());
        SystemIdPolicy policy;
        try {
            policy = SystemIdPolicy.of(ACTION, systemId, pageContext);
        } catch (IllegalArgumentException e) {
            throw new JspTagException(e.getMessage(), e);
        }
        Document parsed;
        try {
            parsed = XmlDocuments.parse(input, policy, filter);
        } catch (SAXException | IOException | TransformerException e) {
            throw XmlSupport.documentFailure(ACTION, e, policy);
        }
        exportVar(parsed);
        if (varDom != null) {
            pageContext.setAttribute(varDom, parsed, XmlSupport.scopeId(ACTION, scopeDom));
        }
        return EVAL_PAGE;
    }

    @Override
    public void release() {
        super.release();
        systemId = null;
        filter = null;
        varDom = null;
        scopeDom = null;
    }

    private static Reader reader(Object document) throws JspException {
        if (document == null || "".equals(document)) {
            throw new JspException(NULL_OR_EMPTY);
        }
        if (document instanceof String text) {
            return new StringReader(text);
        }
        if (document instanceof Reader reader) {
            return reader;
        }
        throw new JspTagException(NOT_A_DOCUMENT.formatted(document.getClass().getName()));
    }
}
