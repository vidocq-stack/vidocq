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

package io.vidocq.bodoni.core.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.jsp.tagext.PageData;
import jakarta.servlet.jsp.tagext.ValidationMessage;

import org.xml.sax.Attributes;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/**
 * The constraints of the JSTL actions that Tags 3.0 §2.7 requires to be reported at translation
 * time, checked on the XML view of a page ({@link PageData}) for the tag library validators the
 * descriptors name.
 *
 * <p>Only elements in the namespace being validated are subjects of a rule: a container calls the
 * validator once per URI a page uses. {@code choose}, {@code when}, {@code otherwise} and
 * {@code transaction} are matched across every URI of the library family (Jakarta, JSTL 1.2 and
 * JSTL 1.0 request-time), as the handlers are at run time. An attribute is present when it is an
 * XML attribute or a {@code jsp:attribute} child of that name; only an XML attribute that holds no
 * request-time or EL expression is checked as a literal (§2.7: constraints on a dynamic value are
 * reported at run time).
 *
 * <p>The view is read with SAX ({@code java.xml}) and every external entity is refused. A view that
 * cannot be read gives one message: the validator fails closed. Each call walks the view with its
 * own handler; nothing is shared between calls.
 */
public final class PageConstraints {

    /** The namespace of the Pages standard actions and of {@code jsp:id}. */
    private static final String JSP_NAMESPACE = "http://java.sun.com/JSP/Page";

    private static final String CHOOSE = "choose";
    private static final String WHEN = "when";
    private static final String OTHERWISE = "otherwise";
    private static final String TRANSACTION = "transaction";
    private static final String PARSE = "parse";
    private static final String VAR = "var";
    private static final String VAR_DOM = "varDom";
    private static final String SCOPE = "scope";
    private static final String SCOPE_DOM = "scopeDom";
    private static final String DATA_SOURCE = "dataSource";
    private static final String UPDATE = "update";
    private static final String ERRORS_SECTION = "2.7";
    private static final String PARSE_SECTION = "11.2";
    private static final String TRANSACTION_SECTION = "10.5";

    private static final Set<String> SCOPES = Set.of("page", "request", "session", "application");
    private static final List<String> SCOPE_ATTRIBUTES = List.of(SCOPE, SCOPE_DOM);
    private static final List<String> VAR_ATTRIBUTES = List.of(VAR, VAR_DOM, "varStatus", "varReader");

    /** Refuses every external entity and the external DTD subset: nothing is fetched. */
    private static final EntityResolver REFUSE = (publicId, systemId) -> {
        throw new SAXException("external entity refused: " + systemId);
    };

    /** The sections of the {@code choose}, {@code when} and {@code otherwise} actions of a library. */
    private record Choice(String choose, String when, String otherwise) {
    }

    /** A Bodoni tag library: the URIs of its family and the constraints of its actions. */
    public enum Library {
        /** {@code c:}. */
        CORE(Set.of("jakarta.tags.core", "http://java.sun.com/jsp/jstl/core", "http://java.sun.com/jstl/core_rt"),
                new Choice("5.4", "5.5", "5.6"), Map.of("if", "5.3"), Map.of()),
        /** {@code fmt:}. */
        FMT(Set.of("jakarta.tags.fmt", "http://java.sun.com/jsp/jstl/fmt", "http://java.sun.com/jstl/fmt_rt"), null,
                Map.of("message", "8.8", "formatNumber", "9.7", "parseNumber", "9.8", "formatDate", "9.9",
                        "parseDate", "9.10"),
                Map.of()),
        /** {@code sql:}. */
        SQL(Set.of("jakarta.tags.sql", "http://java.sun.com/jsp/jstl/sql", "http://java.sun.com/jstl/sql_rt"), null,
                Map.of(UPDATE, "10.4"), Map.of("query", "10.3", UPDATE, "10.4")),
        /** {@code x:}. */
        XML(Set.of("jakarta.tags.xml", "http://java.sun.com/jsp/jstl/xml", "http://java.sun.com/jstl/xml_rt"),
                new Choice("12.3", "12.4", "12.5"), Map.of("if", "12.2", PARSE, PARSE_SECTION), Map.of());

        private final Set<String> family;
        private final Choice choice;
        private final Map<String, String> scopeNeedsVar;
        private final Map<String, String> noDataSourceInTransaction;

        Library(Set<String> family, Choice choice, Map<String, String> scopeNeedsVar,
                Map<String, String> noDataSourceInTransaction) {
            this.family = family;
            this.choice = choice;
            this.scopeNeedsVar = scopeNeedsVar;
            this.noDataSourceInTransaction = noDataSourceInTransaction;
        }
    }

    private PageConstraints() {
    }

    /**
     * Checks a page for one URI of a library.
     *
     * @param library the library of the validator
     * @param uri     the URI being validated; only its elements are subjects of a rule
     * @param page    the XML view of the page
     * @return {@code null} when the page is valid, else one message per violation, whose id is the
     *         {@code jsp:id} of the offending element ({@code null} when it has none)
     */
    public static ValidationMessage[] validate(Library library, String uri, PageData page) {
        Walker walker = new Walker(library, uri);
        try (InputStream in = page.getInputStream()) {
            if (in == null) {
                throw new IOException("the page data has no input stream");
            }
            XMLReader reader = XmlFactories.xmlReader(REFUSE);
            reader.setContentHandler(walker);
            reader.parse(new InputSource(in));
        } catch (IOException | SAXException e) {
            return new ValidationMessage[] {
                new ValidationMessage(null, "cannot read the XML view of the page: " + e.getMessage()) };
        }
        return walker.messages.isEmpty() ? null : walker.messages.toArray(new ValidationMessage[0]);
    }

    /** An open element of the view. */
    private static final class Frame {
        private final Frame parent;
        private final String namespace;
        private final String localName;
        private final String qName;
        private final String jspId;
        private final Map<String, String> attributes;
        private final Set<String> jspAttributes = new HashSet<>();
        private boolean nonBlankText;
        private int whens;
        private boolean otherwise;

        private Frame(Frame parent, String namespace, String localName, String qName, Attributes attributes) {
            this.parent = parent;
            this.namespace = namespace;
            this.localName = localName;
            this.qName = qName;
            this.jspId = attributes.getValue(JSP_NAMESPACE, "id");
            this.attributes = new HashMap<>();
            for (int i = 0; i < attributes.getLength(); i++) {
                if (attributes.getURI(i).isEmpty()) {
                    this.attributes.put(attributes.getLocalName(i), attributes.getValue(i));
                }
            }
        }

        private boolean isJsp(String name) {
            return JSP_NAMESPACE.equals(namespace) && name.equals(localName);
        }

        private boolean present(String name) {
            return attributes.containsKey(name) || jspAttributes.contains(name);
        }

        /** The XML attribute's value when it is a literal, else {@code null}. */
        private String literal(String name) {
            String value = attributes.get(name);
            return value == null || dynamic(value) ? null : value;
        }

        private static boolean dynamic(String value) {
            return (value.startsWith("%=") && value.endsWith("%")) || value.contains("${") || value.contains("#{");
        }
    }

    /** The SAX walk of one view for one URI; collects the messages. */
    private static final class Walker extends DefaultHandler {
        private final Library library;
        private final String uri;
        private final List<ValidationMessage> messages = new ArrayList<>();
        private Frame current;

        private Walker(Library library, String uri) {
            this.library = library;
            this.uri = uri;
        }

        @Override
        public void startElement(String namespace, String localName, String qName, Attributes attributes) {
            Frame frame = new Frame(current, namespace, localName, qName, attributes);
            if (current != null && frame.isJsp("attribute")) {
                String name = frame.attributes.get("name");
                if (name != null) {
                    current.jspAttributes.add(name);
                }
            } else if (current != null) {
                checkParent(frame);
            }
            current = frame;
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (current == null || current.nonBlankText) {
                return;
            }
            for (int i = start; i < start + length; i++) {
                if (!Character.isWhitespace(ch[i])) {
                    current.nonBlankText = true;
                    return;
                }
            }
        }

        @Override
        public void endElement(String namespace, String localName, String qName) {
            Frame frame = current;
            current = frame.parent;
            if (frame.nonBlankText) {
                Frame choose = frame.isJsp("text") || frame.isJsp("body") ? container(frame.parent) : frame;
                if (isSubjectChoose(choose)) {
                    reportChooseContent(frame, choose, "template text");
                }
            }
            if (uri.equals(frame.namespace)) {
                checkElement(frame);
            }
        }

        /** The nearest open ancestor that is not a {@code jsp:body}, from {@code frame} up. */
        private static Frame container(Frame frame) {
            Frame result = frame;
            while (result != null && result.isJsp("body")) {
                result = result.parent;
            }
            return result;
        }

        private boolean inFamily(Frame frame, String localName) {
            return frame != null && library.family.contains(frame.namespace) && localName.equals(frame.localName);
        }

        private boolean isSubjectChoose(Frame frame) {
            return library.choice != null && frame != null && uri.equals(frame.namespace)
                    && CHOOSE.equals(frame.localName);
        }

        /** Rules on the place of an element: {@code when} and {@code otherwise}, children of {@code choose}. */
        private void checkParent(Frame frame) {
            Frame container = container(frame.parent);
            if (library.choice != null && uri.equals(frame.namespace) && !inFamily(container, CHOOSE)) {
                if (WHEN.equals(frame.localName)) {
                    report(frame, frame.qName, "must have a choose action as immediate parent", library.choice.when());
                } else if (OTHERWISE.equals(frame.localName)) {
                    report(frame, frame.qName, "must have a choose action as immediate parent",
                            library.choice.otherwise());
                }
            }
            if (isSubjectChoose(container)) {
                checkChooseChild(container, frame);
            }
        }

        private void checkChooseChild(Frame choose, Frame child) {
            String section = library.choice.choose();
            if (inFamily(child, WHEN)) {
                if (choose.otherwise) {
                    report(child, choose.qName, child.qName + " after the otherwise action: every when must come before it",
                            section);
                }
                choose.whens++;
            } else if (inFamily(child, OTHERWISE)) {
                if (choose.otherwise) {
                    report(child, choose.qName, "more than one otherwise action (" + child.qName + ")", section);
                }
                choose.otherwise = true;
            } else if (!child.isJsp("text") && !child.isJsp("body")) {
                reportChooseContent(child, choose, child.qName);
            }
        }

        private void reportChooseContent(Frame at, Frame choose, String found) {
            report(at, choose.qName, "only white space, when and otherwise actions may appear in the body, found "
                    + found, library.choice.choose());
        }

        /** Rules on an element of the URI, once its attributes and children are known. */
        private void checkElement(Frame frame) {
            if (isSubjectChoose(frame) && frame.whens == 0) {
                report(frame, frame.qName, "needs one or more when actions", library.choice.choose());
            }
            checkScopes(frame);
            checkVars(frame);
            String section = library.scopeNeedsVar.get(frame.localName);
            if (section != null && frame.present(SCOPE) && !frame.present(VAR)) {
                report(frame, frame.qName, "scope requires var", section);
            }
            if (library == Library.XML && PARSE.equals(frame.localName)) {
                checkParse(frame);
            }
            String transaction = library.noDataSourceInTransaction.get(frame.localName);
            if (transaction != null && frame.present(DATA_SOURCE) && insideTransaction(frame.parent)) {
                report(frame, frame.qName, "dataSource must not be specified inside a transaction",
                        transaction + ", §" + TRANSACTION_SECTION);
            }
        }

        private void checkScopes(Frame frame) {
            for (String name : SCOPE_ATTRIBUTES) {
                String value = frame.literal(name);
                if (value != null && !SCOPES.contains(value)) {
                    report(frame, frame.qName, "invalid " + name + " \"" + value
                            + "\", expected page, request, session or application", ERRORS_SECTION);
                }
            }
        }

        private void checkVars(Frame frame) {
            for (String name : VAR_ATTRIBUTES) {
                if ("".equals(frame.attributes.get(name))) {
                    report(frame, frame.qName, name + " must not be empty", ERRORS_SECTION);
                }
            }
        }

        private void checkParse(Frame frame) {
            if (!frame.present(VAR) && !frame.present(VAR_DOM)) {
                report(frame, frame.qName, "var or varDom must be specified", PARSE_SECTION);
            }
            if (frame.present(SCOPE_DOM) && !frame.present(VAR_DOM)) {
                report(frame, frame.qName, "scopeDom requires varDom", PARSE_SECTION);
            }
        }

        private boolean insideTransaction(Frame frame) {
            for (Frame f = frame; f != null; f = f.parent) {
                if (inFamily(f, TRANSACTION)) {
                    return true;
                }
            }
            return false;
        }

        private void report(Frame at, String subject, String rule, String section) {
            messages.add(new ValidationMessage(at.jspId, subject + ": " + rule + " (Tags 3.0 §" + section + ")"));
        }
    }
}
