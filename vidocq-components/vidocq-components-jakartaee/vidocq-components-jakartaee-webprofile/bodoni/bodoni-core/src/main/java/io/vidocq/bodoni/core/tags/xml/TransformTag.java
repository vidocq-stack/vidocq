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

import java.util.ArrayList;
import java.util.List;

import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.stream.StreamResult;

import io.vidocq.bodoni.core.internal.SystemIdPolicy;
import io.vidocq.bodoni.core.internal.XmlDocuments;
import io.vidocq.bodoni.core.internal.XmlFactories;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;

/**
 * {@code <x:transform>} (Tags 3.0 section 13.2): applies the XSLT stylesheet {@code xslt} to
 * the XML document given by {@code doc} (or {@code xml}) or by the trimmed body, with the
 * parameters of the nested {@code <x:param>} actions.
 *
 * <p>The result goes to {@code result} when set, else to a {@link org.w3c.dom.Document}
 * exported to {@code var} and {@code scope}, else to the page. No DTD or schema validation is
 * performed. The external references of the document are resolved through
 * {@code docSystemId}, those of the stylesheet ({@code xsl:import}, {@code xsl:include},
 * {@code document()}) through {@code xsltSystemId}, under the "same origin" policy
 * ({@link SystemIdPolicy}); without a system id every one is refused. A page-supplied
 * {@code Reader} or {@code Source} is not closed. A new {@code Transformer} is created for
 * each invocation: none is cached.
 */
public class TransformTag extends DocumentTagSupport {

    private static final long serialVersionUID = 1L;

    private static final String ACTION = "x:transform";

    /** Message of a {@code null} or empty document. */
    private static final String NULL_OR_EMPTY_DOC = ACTION + ": the XML document is null or empty";

    /** Message of a {@code null} or empty stylesheet. */
    private static final String NULL_OR_EMPTY_XSLT = ACTION + ": the XSLT stylesheet is null or empty";

    /** A parameter set by a nested {@code <x:param>}. */
    private record Parameter(String name, Object value) {
    }

    private transient Object xslt;
    private String docSystemId;
    private String xsltSystemId;
    private transient Result result;

    /** The parameters of the current invocation, reset by {@link #doStartTag()}. */
    private transient List<Parameter> parameters = new ArrayList<>();

    /** Creates the handler. */
    public TransformTag() {
        super(ACTION);
    }

    /** @param xslt the stylesheet, a {@code String}, a {@code Reader} or a {@code Source} */
    public void setXslt(Object xslt) {
        this.xslt = xslt;
    }

    /** @param systemId the system identifier of the document, the base of its external references */
    public void setDocSystemId(String systemId) {
        this.docSystemId = systemId;
    }

    /**
     * @param systemId the system identifier of the document
     * @deprecated the Tags 3.0 alias of {@link #setDocSystemId(String)}; use {@code docSystemId}
     */
    @Deprecated(since = "JSTL 1.1")
    public void setXmlSystemId(String systemId) {
        setDocSystemId(systemId);
    }

    /** @param systemId the system identifier of the stylesheet, the base of its external references */
    public void setXsltSystemId(String systemId) {
        this.xsltSystemId = systemId;
    }

    /** @param result the object that receives the result of the transformation instead of the page */
    public void setResult(Result result) {
        this.result = result;
    }

    /**
     * Records a parameter of the current invocation, for a nested {@code <x:param>}; a
     * {@code null} value sets none, so the stylesheet's default applies.
     */
    void addParameter(String name, Object value) {
        parameters.add(new Parameter(name, value));
    }

    @Override
    public int doStartTag() throws JspException {
        // An empty element gets no setBodyContent: never reuse a pooled handler's old body.
        bodyContent = null;
        parameters = new ArrayList<>();
        scopeId();
        // The body is always evaluated: the nested <x:param> actions must run.
        return EVAL_BODY_BUFFERED;
    }

    @Override
    public int doEndTag() throws JspException {
        Object document = document();
        if (isNullOrEmpty(document)) {
            throw new JspException(NULL_OR_EMPTY_DOC);
        }
        if (isNullOrEmpty(xslt)) {
            throw new JspException(NULL_OR_EMPTY_XSLT);
        }
        SystemIdPolicy docPolicy = XmlSupport.policy(ACTION, docSystemId, pageContext);
        SystemIdPolicy xsltPolicy = XmlSupport.policy(ACTION, xsltSystemId, pageContext);
        Source docSource;
        Source stylesheet;
        try {
            docSource = XmlDocuments.documentSource(document, docPolicy);
            stylesheet = XmlDocuments.stylesheetSource(xslt, xsltPolicy);
        } catch (IllegalArgumentException e) {
            throw new JspTagException(ACTION + ": " + e.getMessage(), e);
        }
        // The failing document comes first: it names the system id when no location does.
        Transformer transformer;
        try {
            transformer = transformer(stylesheet, xsltPolicy);
        } catch (TransformerException e) {
            throw XmlSupport.documentFailure(ACTION, e, XmlDocuments.parseFailure(stylesheet), xsltPolicy, docPolicy);
        }
        try {
            transform(transformer, docSource);
        } catch (TransformerException e) {
            throw XmlSupport.documentFailure(ACTION, e, XmlDocuments.parseFailure(docSource), docPolicy, xsltPolicy);
        }
        return EVAL_PAGE;
    }

    @Override
    public void release() {
        super.release();
        xslt = null;
        docSystemId = null;
        xsltSystemId = null;
        result = null;
        parameters = new ArrayList<>();
    }

    /** A new transformer of {@code stylesheet}, its references resolved by {@code policy}. */
    private Transformer transformer(Source stylesheet, SystemIdPolicy policy) throws TransformerException {
        TransformerFactory factory = XmlFactories.transformerFactory(policy);
        Transformer transformer = factory.newTransformer(stylesheet);
        // A Transformer does not inherit the factory's ErrorListener; document() asks its URIResolver.
        transformer.setErrorListener(factory.getErrorListener());
        transformer.setURIResolver(policy);
        for (Parameter parameter : parameters) {
            if (parameter.value() != null) {
                transformer.setParameter(parameter.name(), parameter.value());
            }
        }
        return transformer;
    }

    /** Transforms into {@code result}, else into the {@code var} document, else onto the page. */
    private void transform(Transformer transformer, Source document) throws TransformerException, JspTagException {
        if (result != null) {
            transformer.transform(document, result);
        } else if (hasVar()) {
            DOMResult dom = new DOMResult();
            transformer.transform(document, dom);
            exportVar(dom.getNode());
        } else {
            transformer.transform(document, new StreamResult(pageContext.getOut()));
        }
    }

    private static boolean isNullOrEmpty(Object value) {
        return value == null || "".equals(value);
    }
}
