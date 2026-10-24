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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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
 * The constraints and syntax rules of the JSTL actions that Tags 3.0 §2.7 requires to be reported
 * at translation time, checked on the XML view of a page ({@link PageData}) for the tag library
 * validators the descriptors name.
 *
 * <p>Only elements in the namespace being validated are subjects of a rule: a container calls the
 * validator once per URI a page uses, whatever that URI is (a {@code jakarta.tags.*} URI, a TLD
 * path, a {@code web.xml} alias, a {@code urn:jsptld:} namespace). {@code choose}, {@code when},
 * {@code otherwise}, {@code transaction}, {@code message}, {@code query} and {@code update} are
 * matched in that namespace and across every standard URI of the library family (Jakarta, JSTL
 * 1.2 and JSTL 1.0 request-time), as the handlers are at run time.
 *
 * <p>An attribute is present when it is an XML attribute or a {@code jsp:attribute} child of that
 * name. §2.7 leaves to the run time a constraint that operates on a dynamic attribute (the
 * "Dynamic" column of the attribute tables), such as {@code begin}, {@code step}, {@code maxRows}
 * or {@code currencyCode}: values are only checked on the static attributes {@code var},
 * {@code varDom}, {@code varStatus}, {@code varReader}, {@code scope} and {@code scopeDom}, and
 * only when literal. A literal is an XML attribute, or a {@code jsp:attribute} whose content is
 * template text alone (trimmed unless {@code trim="false"}), that holds no request-time value
 * ({@code %= … %}) and, unless the page ignores EL, no {@code ${…}} or {@code #{…}} expression
 * ({@code #{…}} is literal too with {@code deferredSyntaxAllowedAsLiteral}).
 *
 * <p>The view is read with SAX ({@code java.xml}) and every external entity is refused. A view that
 * cannot be read gives one message: the validator fails closed. Each call reads the view twice
 * (page directives, then the rules) with its own handlers; nothing is shared between calls.
 */
public final class PageConstraints {

    /** The namespace of the Pages standard actions and of {@code jsp:id}. */
    private static final String JSP_NAMESPACE = "http://java.sun.com/JSP/Page";

    private static final String JSP_ATTRIBUTE = "attribute";
    private static final String JSP_TEXT = "text";
    private static final String JSP_BODY = "body";
    private static final String CHOOSE = "choose";
    private static final String WHEN = "when";
    private static final String OTHERWISE = "otherwise";
    private static final String TRANSACTION = "transaction";
    private static final String QUERY = "query";
    private static final String UPDATE = "update";
    private static final String MESSAGE = "message";
    private static final String PARAM = "param";
    private static final String PARSE = "parse";
    private static final String TRANSFORM = "transform";
    private static final String FOR_EACH = "forEach";
    private static final String VAR = "var";
    private static final String VAR_DOM = "varDom";
    private static final String VAR_READER = "varReader";
    private static final String SCOPE = "scope";
    private static final String SCOPE_DOM = "scopeDom";
    private static final String DATA_SOURCE = "dataSource";
    private static final String TARGET = "target";
    private static final String PROPERTY = "property";
    private static final String ITEMS = "items";
    private static final String BEGIN = "begin";
    private static final String END = "end";
    private static final String DOC = "doc";
    private static final String XML = "xml";
    private static final String TRUE = "true";

    private static final String ERRORS_SECTION = "2.7";
    private static final String SET_SECTION = "4.3";
    private static final String PARSE_SECTION = "11.2";
    private static final String TRANSFORM_SECTION = "13.2";
    private static final String QUERY_SECTION = "10.3";
    private static final String UPDATE_SECTION = "10.4";
    private static final String SET_DATA_SOURCE_SECTION = "10.6";

    private static final Set<String> SCOPES = Set.of("page", "request", "session", "application");
    private static final List<String> SCOPE_ATTRIBUTES = List.of(SCOPE, SCOPE_DOM);
    private static final List<String> VAR_ATTRIBUTES = List.of(VAR, VAR_DOM, "varStatus", VAR_READER);
    private static final List<String> URL_GROUP = List.of("url", "driver", "user", "password");

    /** Elements that mark the view of a tag file, whose actions may be nested in the invoking page's. */
    private static final Set<String> TAG_FILE_MARKERS = Set.of("directive.tag", "directive.attribute",
            "directive.variable", "doBody", "invoke");

    /** Refuses every external entity and the external DTD subset: nothing is fetched. */
    private static final EntityResolver REFUSE = (publicId, systemId) -> {
        throw new SAXException("external entity refused: " + systemId);
    };

    /** The sections of the {@code choose}, {@code when} and {@code otherwise} actions of a library. */
    private record Choice(String choose, String when, String otherwise) {
    }

    /** A Bodoni tag library: the URIs of its family and the per-library constraints. */
    public enum Library {
        /** {@code c:}. */
        CORE(Set.of("jakarta.tags.core", "http://java.sun.com/jsp/jstl/core", "http://java.sun.com/jstl/core_rt"),
                new Choice("5.4", "5.5", "5.6"), Map.of("if", "5.3", "set", SET_SECTION)),
        /** {@code fmt:}. */
        FMT(Set.of("jakarta.tags.fmt", "http://java.sun.com/jsp/jstl/fmt", "http://java.sun.com/jstl/fmt_rt"), null,
                Map.of(MESSAGE, "8.8", "formatNumber", "9.7", "parseNumber", "9.8", "formatDate", "9.9",
                        "parseDate", "9.10")),
        /** {@code sql:}. */
        SQL(Set.of("jakarta.tags.sql", "http://java.sun.com/jsp/jstl/sql", "http://java.sun.com/jstl/sql_rt"), null,
                Map.of(UPDATE, UPDATE_SECTION)),
        /** {@code x:}. */
        XML(Set.of("jakarta.tags.xml", "http://java.sun.com/jsp/jstl/xml", "http://java.sun.com/jstl/xml_rt"),
                new Choice("12.3", "12.4", "12.5"),
                Map.of("if", "12.2", PARSE, PARSE_SECTION, TRANSFORM, TRANSFORM_SECTION));

        private final Set<String> family;
        private final Choice choice;
        private final Map<String, String> scopeNeedsVar;

        Library(Set<String> family, Choice choice, Map<String, String> scopeNeedsVar) {
            this.family = family;
            this.choice = choice;
            this.scopeNeedsVar = scopeNeedsVar;
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
        List<ValidationMessage> messages;
        try {
            byte[] view = read(page);
            Directives directives = new Directives();
            parse(view, directives);
            Walker walker = new Walker(library, uri, directives);
            parse(view, walker);
            messages = walker.messages;
        } catch (IOException | SAXException e) {
            return new ValidationMessage[] {
                new ValidationMessage(null, "cannot read the XML view of the page: " + e.getMessage()) };
        }
        return messages.isEmpty() ? null : messages.toArray(new ValidationMessage[0]);
    }

    private static byte[] read(PageData page) throws IOException {
        try (InputStream in = page.getInputStream()) {
            if (in == null) {
                throw new IOException("the page data has no input stream");
            }
            return in.readAllBytes();
        }
    }

    private static void parse(byte[] view, DefaultHandler handler) throws IOException, SAXException {
        XMLReader reader = XmlFactories.xmlReader(REFUSE);
        reader.setContentHandler(handler);
        reader.parse(new InputSource(new ByteArrayInputStream(view)));
    }

    /** First pass: the page directives that change what a literal is, and whether the view is a tag file's. */
    private static final class Directives extends DefaultHandler {
        private boolean elIgnored;
        private boolean deferredAsLiteral;
        private boolean tagFile;

        @Override
        public void startElement(String namespace, String localName, String qName, Attributes attributes) {
            if (!JSP_NAMESPACE.equals(namespace)) {
                return;
            }
            tagFile |= TAG_FILE_MARKERS.contains(localName);
            if ("directive.page".equals(localName) || "directive.tag".equals(localName)) {
                elIgnored |= TRUE.equalsIgnoreCase(attributes.getValue("", "isELIgnored"));
                deferredAsLiteral |= TRUE.equalsIgnoreCase(attributes.getValue("", "deferredSyntaxAllowedAsLiteral"));
            }
        }
    }

    /** An open element of the view. */
    private static final class Frame {
        private final Frame parent;
        private final String namespace;
        private final String localName;
        private final String qName;
        private final String jspId;
        private final Map<String, String> attributes;
        /** Names given by {@code jsp:attribute} children, to their literal content or {@code null}. */
        private final Map<String, String> jspAttributes = new HashMap<>();
        private final StringBuilder text = new StringBuilder();
        private boolean onlyText = true;
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
            return attributes.containsKey(name) || jspAttributes.containsKey(name);
        }

        private boolean presentAny(List<String> names) {
            return names.stream().anyMatch(this::present);
        }
    }

    /** The SAX walk of one view for one URI; collects the messages. */
    private static final class Walker extends DefaultHandler {
        private final Library library;
        private final String uri;
        private final Directives directives;
        private final List<ValidationMessage> messages = new ArrayList<>();
        private Frame current;

        private Walker(Library library, String uri, Directives directives) {
            this.library = library;
            this.uri = uri;
            this.directives = directives;
        }

        @Override
        public void startElement(String namespace, String localName, String qName, Attributes attributes) {
            Frame frame = new Frame(current, namespace, localName, qName, attributes);
            if (current != null && current.isJsp(JSP_ATTRIBUTE) && !frame.isJsp(JSP_TEXT)) {
                current.onlyText = false;
            }
            if (current != null && !frame.isJsp(JSP_ATTRIBUTE)) {
                checkParent(frame);
            }
            current = frame;
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (current == null) {
                return;
            }
            Frame attribute = enclosingJspAttribute(current);
            if (attribute != null) {
                attribute.text.append(ch, start, length);
            }
            for (int i = start; !current.nonBlankText && i < start + length; i++) {
                current.nonBlankText = !Character.isWhitespace(ch[i]);
            }
        }

        /** The {@code jsp:attribute} whose content the text of {@code frame} is, or {@code null}. */
        private static Frame enclosingJspAttribute(Frame frame) {
            if (frame.isJsp(JSP_ATTRIBUTE)) {
                return frame;
            }
            boolean textOfAttribute = frame.isJsp(JSP_TEXT) && frame.parent != null && frame.parent.isJsp(JSP_ATTRIBUTE);
            return textOfAttribute ? frame.parent : null;
        }

        @Override
        public void endElement(String namespace, String localName, String qName) {
            Frame frame = current;
            current = frame.parent;
            if (frame.isJsp(JSP_ATTRIBUTE)) {
                endJspAttribute(frame);
                return;
            }
            if (frame.nonBlankText) {
                Frame choose = frame.isJsp(JSP_TEXT) || frame.isJsp(JSP_BODY) ? container(frame.parent) : frame;
                if (isSubjectChoose(choose)) {
                    reportChooseContent(frame, choose, "template text");
                }
            }
            if (uri.equals(frame.namespace)) {
                checkElement(frame);
            }
        }

        /** Records a {@code jsp:attribute} on its action, with its literal content when it has one. */
        private void endJspAttribute(Frame attribute) {
            String name = attribute.attributes.get("name");
            if (name == null || attribute.parent == null) {
                return;
            }
            String content = null;
            if (attribute.onlyText) {
                String raw = attribute.text.toString();
                content = "false".equalsIgnoreCase(attribute.attributes.get("trim")) ? raw : raw.strip();
            }
            attribute.parent.jspAttributes.put(name, content);
        }

        /** The nearest open ancestor that is not a {@code jsp:body}, from {@code frame} up. */
        private static Frame container(Frame frame) {
            Frame result = frame;
            while (result != null && result.isJsp(JSP_BODY)) {
                result = result.parent;
            }
            return result;
        }

        /** In the validated namespace or in the library family. */
        private boolean ofLibrary(Frame frame) {
            return uri.equals(frame.namespace) || library.family.contains(frame.namespace);
        }

        private boolean inFamily(Frame frame, String localName) {
            return frame != null && localName.equals(frame.localName) && ofLibrary(frame);
        }

        private boolean isSubjectChoose(Frame frame) {
            return library.choice != null && frame != null && uri.equals(frame.namespace)
                    && CHOOSE.equals(frame.localName);
        }

        /** Whether a value is dynamic, hence left to the run time. */
        private boolean dynamic(String value) {
            if (value.startsWith("%=") && value.endsWith("%")) {
                return true;
            }
            if (directives.elIgnored) {
                return false;
            }
            return value.contains("${") || (!directives.deferredAsLiteral && value.contains("#{"));
        }

        /** The literal value of a static attribute, or {@code null}; see the class comment. */
        private String literal(Frame frame, String name) {
            String value = frame.attributes.containsKey(name) ? frame.attributes.get(name) : frame.jspAttributes.get(name);
            return value == null || dynamic(value) ? null : value;
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
            } else if (!child.isJsp(JSP_TEXT) && !child.isJsp(JSP_BODY)) {
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
            switch (library) {
                case CORE -> checkCore(frame);
                case FMT -> checkFmt(frame);
                case SQL -> checkSql(frame);
                case XML -> checkXml(frame);
            }
        }

        private void checkScopes(Frame frame) {
            for (String name : SCOPE_ATTRIBUTES) {
                String value = literal(frame, name);
                if (value != null && !SCOPES.contains(value)) {
                    report(frame, frame.qName, "invalid " + name + " \"" + value
                            + "\", expected page, request, session or application", ERRORS_SECTION);
                }
            }
        }

        private void checkVars(Frame frame) {
            for (String name : VAR_ATTRIBUTES) {
                if ("".equals(literal(frame, name))) {
                    report(frame, frame.qName, name + " must not be empty", ERRORS_SECTION);
                }
            }
        }

        private void checkCore(Frame frame) {
            switch (frame.localName) {
                case "set" -> checkSet(frame);
                case FOR_EACH -> {
                    if (!frame.present(ITEMS) && !(frame.present(BEGIN) && frame.present(END))) {
                        report(frame, frame.qName, "needs items, or begin and end", "6.2");
                    }
                }
                case "import" -> {
                    if (frame.present(VAR_READER) && (frame.present(VAR) || frame.present(SCOPE))) {
                        report(frame, frame.qName, "varReader excludes var and scope", "7.4");
                    }
                }
                default -> {
                    // No other syntax rule in the core library.
                }
            }
        }

        /** §4.3: {@code var} (syntaxes 1, 2, 5) or {@code target} with {@code property} (syntaxes 3, 4). */
        private void checkSet(Frame frame) {
            boolean var = frame.present(VAR);
            boolean target = frame.present(TARGET);
            boolean property = frame.present(PROPERTY);
            if (!var && !target && !property) {
                report(frame, frame.qName, "needs var or target", SET_SECTION);
            } else if (var && (target || property)) {
                report(frame, frame.qName, "var excludes target and property", SET_SECTION);
            } else if (target && !property) {
                report(frame, frame.qName, "target requires property", SET_SECTION);
            } else if (property && !target) {
                report(frame, frame.qName, "property requires target", SET_SECTION);
            }
        }

        private void checkFmt(Frame frame) {
            if (PARAM.equals(frame.localName) && !nestedIn(frame, MESSAGE)) {
                report(frame, frame.qName, "must be nested inside a fmt:message action", "8.9");
            }
        }

        private void checkSql(Frame frame) {
            switch (frame.localName) {
                case QUERY -> checkTransaction(frame, QUERY_SECTION);
                case UPDATE -> checkTransaction(frame, UPDATE_SECTION);
                case "setDataSource" -> checkSetDataSource(frame);
                case PARAM, "dateParam" -> {
                    if (!nestedIn(frame, QUERY, UPDATE)) {
                        report(frame, frame.qName, "must be nested inside an action whose handler is a SQLExecutionTag",
                                PARAM.equals(frame.localName) ? "10.7" : "10.8");
                    }
                }
                default -> {
                    // No other syntax rule in the SQL library.
                }
            }
        }

        /** §10.3-§10.5: no {@code dataSource} on an action nested in a transaction. */
        private void checkTransaction(Frame frame, String section) {
            if (frame.present(DATA_SOURCE) && ancestor(frame.parent, TRANSACTION)) {
                report(frame, frame.qName, "dataSource must not be specified inside a transaction",
                        section + ", §10.5");
            }
        }

        /** §10.6: {@code dataSource}, or {@code url} with its optional {@code driver}, {@code user}, {@code password}. */
        private void checkSetDataSource(Frame frame) {
            if (!frame.present(DATA_SOURCE) && !frame.present("url")) {
                report(frame, frame.qName, "needs dataSource or url", SET_DATA_SOURCE_SECTION);
            } else if (frame.present(DATA_SOURCE) && frame.presentAny(URL_GROUP)) {
                report(frame, frame.qName, "dataSource excludes url, driver, user and password",
                        SET_DATA_SOURCE_SECTION);
            }
        }

        private void checkXml(Frame frame) {
            if (PARSE.equals(frame.localName)) {
                checkParse(frame);
            } else if (TRANSFORM.equals(frame.localName)) {
                exclusive(frame, DOC, XML, TRANSFORM_SECTION);
                exclusive(frame, "docSystemId", "xmlSystemId", TRANSFORM_SECTION);
                exclusive(frame, VAR, "result", TRANSFORM_SECTION);
            }
        }

        /** §11.2: {@code {doc|xml}}, {@code {var [scope] | varDom [scopeDom]}}. */
        private void checkParse(Frame frame) {
            exclusive(frame, DOC, XML, PARSE_SECTION);
            if (!frame.present(VAR) && !frame.present(VAR_DOM)) {
                report(frame, frame.qName, "var or varDom must be specified", PARSE_SECTION);
            }
            exclusive(frame, VAR, VAR_DOM, PARSE_SECTION);
            if (frame.present(SCOPE_DOM) && !frame.present(VAR_DOM)) {
                report(frame, frame.qName, "scopeDom requires varDom", PARSE_SECTION);
            }
        }

        private void exclusive(Frame frame, String first, String second, String section) {
            if (frame.present(first) && frame.present(second)) {
                report(frame, frame.qName, first + " and " + second + " are exclusive", section);
            }
        }

        private boolean ancestor(Frame from, String localName) {
            for (Frame f = from; f != null; f = f.parent) {
                if (inFamily(f, localName)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Whether an element is nested in one of the given actions of the library. In a tag file, or
         * under an action of another library (whose handler may be the expected type), the answer is
         * left to the handler at run time: the method then answers {@code true}.
         */
        private boolean nestedIn(Frame frame, String... localNames) {
            if (directives.tagFile) {
                return true;
            }
            List<String> names = Arrays.asList(localNames);
            for (Frame f = frame.parent; f != null; f = f.parent) {
                if (ofLibrary(f) ? names.contains(f.localName) : foreignAction(f)) {
                    return true;
                }
            }
            return false;
        }

        /** An action that is neither a standard action nor one of the Bodoni libraries. */
        private static boolean foreignAction(Frame frame) {
            if (frame.namespace.isEmpty() || JSP_NAMESPACE.equals(frame.namespace)) {
                return false;
            }
            for (Library known : Library.values()) {
                if (known.family.contains(frame.namespace)) {
                    return false;
                }
            }
            return true;
        }

        private void report(Frame at, String subject, String rule, String section) {
            messages.add(new ValidationMessage(at.jspId, subject + ": " + rule + " (Tags 3.0 §" + section + ")"));
        }
    }
}
