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

package io.vidocq.bodoni.core.tags;

import static io.vidocq.bodoni.core.testing.TestPageData.view;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;

import jakarta.servlet.jsp.tagext.TagLibraryValidator;
import jakarta.servlet.jsp.tagext.ValidationMessage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.vidocq.bodoni.core.tags.core.CoreTagLibraryValidator;
import io.vidocq.bodoni.core.tags.fmt.FmtTagLibraryValidator;
import io.vidocq.bodoni.core.tags.sql.SqlTagLibraryValidator;
import io.vidocq.bodoni.core.tags.xml.XmlTagLibraryValidator;
import io.vidocq.bodoni.core.testing.TestPageData;

/**
 * The constraints of Tags 3.0 that must be reported at translation time (§2.7), checked by the public
 * validators the descriptors name, on hand-built XML views of pages.
 */
class TagLibraryValidatorsTest {

    private static final String CORE = "jakarta.tags.core";
    private static final String CORE_RT = "http://java.sun.com/jstl/core_rt";
    private static final String FMT = "jakarta.tags.fmt";
    private static final String SQL = "jakarta.tags.sql";
    private static final String XML = "jakarta.tags.xml";
    private static final String READ_FAILURE = "cannot read the XML view of the page";

    private static TagLibraryValidator validator(String library) {
        return switch (library) {
            case "CORE" -> new CoreTagLibraryValidator();
            case "FMT" -> new FmtTagLibraryValidator();
            case "SQL" -> new SqlTagLibraryValidator();
            case "XML" -> new XmlTagLibraryValidator();
            default -> throw new IllegalArgumentException(library);
        };
    }

    private static ValidationMessage[] core(String body) {
        return new CoreTagLibraryValidator().validate("c", CORE, view(body, "c", CORE));
    }

    private static ValidationMessage[] validate(String library, String prefix, String uri, String body) {
        return validator(library).validate(prefix, uri, view(body, prefix, uri));
    }

    private static String describe(ValidationMessage[] messages) {
        return messages == null ? "null"
                : Arrays.stream(messages).map(m -> m.getId() + ": " + m.getMessage()).collect(Collectors.joining("; "));
    }

    /** Asserts exactly one message, on the element with the given {@code jsp:id}, naming every fragment. */
    private static void assertReported(ValidationMessage[] messages, String id, String... fragments) {
        assertNotNull(messages, "no message");
        assertEquals(1, messages.length, describe(messages));
        assertEquals(id, messages[0].getId(), describe(messages));
        for (String fragment : fragments) {
            assertTrue(messages[0].getMessage().contains(fragment), fragment + " in " + describe(messages));
        }
    }

    @Test
    void chooseWithoutWhenIsReported() {
        assertReported(core("<c:choose jsp:id=\"1\"><jsp:text jsp:id=\"2\">\n    </jsp:text></c:choose>"),
                "1", "c:choose", "§5.4");
    }

    @Test
    void whenAfterOtherwiseIsReported() {
        assertReported(core("<c:choose jsp:id=\"1\"><c:otherwise jsp:id=\"2\"/><c:when test=\"true\" jsp:id=\"3\"/>"
                + "</c:choose>"), "3", "c:choose", "c:when", "§5.4");
    }

    @Test
    void secondOtherwiseIsReported() {
        assertReported(core("<c:choose jsp:id=\"1\"><c:when test=\"true\" jsp:id=\"2\"/><c:otherwise jsp:id=\"3\"/>"
                + "<c:otherwise jsp:id=\"4\"/></c:choose>"), "4", "c:choose", "c:otherwise", "§5.4");
    }

    @ParameterizedTest
    @ValueSource(strings = { "<jsp:text jsp:id=\"3\">Hello</jsp:text>",
            "<jsp:text jsp:id=\"3\">\n  &lt;!-- a comment --&gt;\n</jsp:text>",
            "<jsp:scriptlet jsp:id=\"3\">int i = 0;</jsp:scriptlet>",
            "<jsp:expression jsp:id=\"3\">i</jsp:expression>",
            "<c:out value=\"x\" jsp:id=\"3\"/>" })
    void templateTextInsideChooseIsReported(String child) {
        assertReported(core("<c:choose jsp:id=\"1\"><c:when test=\"true\" jsp:id=\"2\"/>" + child + "</c:choose>"),
                "3", "c:choose", "§5.4");
    }

    @Test
    void textOutsideAJspTextElementIsReportedOnTheChoose() {
        assertReported(core("<c:choose jsp:id=\"1\">text<c:when test=\"true\" jsp:id=\"2\"/></c:choose>"),
                "1", "c:choose", "§5.4");
    }

    @Test
    void whiteSpaceAndJspBodyInsideChooseAreAccepted() {
        assertNull(core("<c:choose jsp:id=\"1\">\n  <jsp:text jsp:id=\"2\">\n\t </jsp:text>"
                + "<jsp:body jsp:id=\"3\"><jsp:text jsp:id=\"4\"> </jsp:text>"
                + "<c:when test=\"a\" jsp:id=\"5\"><jsp:text jsp:id=\"6\">A</jsp:text></c:when>"
                + "<c:when test=\"b\" jsp:id=\"7\"/>\n<c:otherwise jsp:id=\"8\">C</c:otherwise></jsp:body>\n</c:choose>"));
    }

    @Test
    void jspAttributeChildOfChooseIsIgnored() {
        assertNull(core("<c:choose jsp:id=\"1\"><jsp:attribute name=\"x\" jsp:id=\"2\">text</jsp:attribute>"
                + "<c:when test=\"true\" jsp:id=\"3\"/></c:choose>"));
    }

    @Test
    void whenOutsideChooseIsReported() {
        assertReported(core("<c:if test=\"true\" jsp:id=\"1\"><c:when test=\"true\" jsp:id=\"2\"/></c:if>"),
                "2", "c:when", "§5.5");
        assertReported(core("<c:when test=\"true\" jsp:id=\"1\"/>"), "1", "c:when", "§5.5");
    }

    @Test
    void otherwiseOutsideChooseIsReported() {
        assertReported(core("<jsp:text jsp:id=\"1\">a</jsp:text><c:otherwise jsp:id=\"2\">b</c:otherwise>"),
                "2", "c:otherwise", "§5.6");
    }

    @Test
    void whenOfAnotherUriOfTheSameLibraryIsAccepted() {
        TestPageData page = view("<c:choose jsp:id=\"1\"><c_rt:when test=\"true\" jsp:id=\"2\"/>"
                + "<c_rt:otherwise jsp:id=\"3\"/></c:choose>", "c", CORE, "c_rt", CORE_RT);
        assertNull(new CoreTagLibraryValidator().validate("c", CORE, page));
        assertNull(new CoreTagLibraryValidator().validate("c_rt", CORE_RT, page));
    }

    @Test
    void elementsOfOtherUrisAreIgnored() {
        TestPageData page = view("<x:choose jsp:id=\"1\"/><x:when select=\"$a\" jsp:id=\"2\"/>"
                + "<x:set var=\"v\" select=\"$a\" scope=\"invalid\" jsp:id=\"3\"/>", "c", CORE, "x", XML);
        assertNull(new CoreTagLibraryValidator().validate("c", CORE, page));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            <x:choose jsp:id="1"></x:choose> | 1 | x:choose | §12.3
            <x:choose jsp:id="1"><x:otherwise jsp:id="2"/><x:when select="$a" jsp:id="3"/></x:choose> | 3 | x:when | §12.3
            <x:choose jsp:id="1"><x:when select="$a" jsp:id="2"/><x:otherwise jsp:id="3"/><x:otherwise jsp:id="4"/></x:choose> | 4 | x:otherwise | §12.3
            <x:choose jsp:id="1"><x:when select="$a" jsp:id="2"/><jsp:text jsp:id="3">text</jsp:text></x:choose> | 3 | x:choose | §12.3
            <x:when select="$a" jsp:id="1"/> | 1 | x:when | §12.4
            <x:otherwise jsp:id="1"/> | 1 | x:otherwise | §12.5
            """)
    void xmlChooseRulesMatchCore(String body, String id, String qName, String section) {
        assertReported(validate("XML", "x", XML, body), id, qName, section);
    }

    @Test
    void validXmlChooseIsAccepted() {
        assertNull(validate("XML", "x", XML, "<x:choose jsp:id=\"1\"><jsp:text jsp:id=\"2\"> </jsp:text>"
                + "<x:when select=\"$a\" jsp:id=\"3\"/><x:otherwise jsp:id=\"4\"/></x:choose>"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', textBlock = """
            CORE; c;   jakarta.tags.core; if;           test="true";             §5.3
            FMT;  fmt; jakarta.tags.fmt;  message;      key="k";                 §8.8
            FMT;  fmt; jakarta.tags.fmt;  formatNumber; value="1";               §9.7
            FMT;  fmt; jakarta.tags.fmt;  parseNumber;  value="1";               §9.8
            FMT;  fmt; jakarta.tags.fmt;  formatDate;   value="${d}";            §9.9
            FMT;  fmt; jakarta.tags.fmt;  parseDate;    value="x";               §9.10
            SQL;  sql; jakarta.tags.sql;  update;       sql="x";                 §10.4
            XML;  x;   jakarta.tags.xml;  if;           select="$a";             §12.2
            XML;  x;   jakarta.tags.xml;  transform;    doc="${d}" xslt="${s}";  §13.2
            """)
    void scopeWithoutVarIsReported(String library, String prefix, String uri, String element, String attributes,
            String section) {
        String qName = prefix + ":" + element;
        assertReported(validate(library, prefix, uri, "<" + qName + " " + attributes + " scope=\"page\" jsp:id=\"1\"/>"),
                "1", qName, "scope", section);
        assertNull(validate(library, prefix, uri,
                "<" + qName + " " + attributes + " var=\"v\" scope=\"page\" jsp:id=\"1\"/>"));
    }

    @Test
    void parseScopeWithoutVarIsReported() {
        assertReported(validate("XML", "x", XML, "<x:parse varDom=\"d\" scope=\"page\" jsp:id=\"1\"/>"),
                "1", "x:parse", "scope requires var", "§11.2");
        assertNull(validate("XML", "x", XML, "<x:parse var=\"v\" scope=\"page\" jsp:id=\"1\"/>"));
    }

    @Test
    void scopeDomWithoutVarDomIsReported() {
        assertReported(validate("XML", "x", XML, "<x:parse var=\"v\" scopeDom=\"page\" jsp:id=\"1\"/>"),
                "1", "x:parse", "scopeDom", "§11.2");
        assertNull(validate("XML", "x", XML, "<x:parse varDom=\"d\" scopeDom=\"page\" jsp:id=\"1\"/>"));
    }

    @Test
    void parseWithoutVarOrVarDomIsReported() {
        assertReported(validate("XML", "x", XML, "<x:parse jsp:id=\"1\"><jsp:text jsp:id=\"2\">&lt;a/&gt;</jsp:text>"
                + "</x:parse>"), "1", "x:parse", "§11.2");
        ValidationMessage[] tckShape = validate("XML", "x", XML, "<x:parse scope=\"page\" jsp:id=\"1\"/>");
        assertNotNull(tckShape);
        assertEquals(2, tckShape.length, describe(tckShape));
    }

    @Test
    void jspAttributeCountsAsAPresentAttribute() {
        assertNull(core("<c:if test=\"true\" scope=\"page\" jsp:id=\"1\"><jsp:attribute name=\"var\" jsp:id=\"2\">v"
                + "</jsp:attribute><jsp:body jsp:id=\"3\">x</jsp:body></c:if>"));
        assertNull(validate("XML", "x", XML, "<x:parse scopeDom=\"page\" jsp:id=\"1\">"
                + "<jsp:attribute name=\"varDom\" jsp:id=\"2\">d</jsp:attribute></x:parse>"));
    }

    @Test
    void staticAttributeThroughJspAttributeIsCheckedAsALiteral() {
        assertReported(core("<c:set var=\"v\" value=\"1\" jsp:id=\"1\"><jsp:attribute name=\"scope\" jsp:id=\"2\">"
                + "<jsp:text jsp:id=\"3\"> invalid </jsp:text></jsp:attribute></c:set>"), "1", "c:set",
                "invalid scope \"invalid\"", "§2.7");
        assertReported(core("<c:set value=\"1\" jsp:id=\"1\"><jsp:attribute name=\"var\" jsp:id=\"2\"></jsp:attribute>"
                + "</c:set>"), "1", "c:set", "var must not be empty", "§2.7");
        assertReported(core("<c:set value=\"1\" jsp:id=\"1\"><jsp:attribute name=\"var\" trim=\"false\" jsp:id=\"2\">"
                + "</jsp:attribute></c:set>"), "1", "c:set", "var must not be empty", "§2.7");
        // Content that is not template text alone is a request-time value: left to the run time.
        assertNull(core("<c:set var=\"v\" value=\"1\" jsp:id=\"1\"><jsp:attribute name=\"scope\" jsp:id=\"2\">"
                + "<jsp:expression jsp:id=\"3\">s</jsp:expression></jsp:attribute></c:set>"));
        assertNull(core("<c:set var=\"v\" value=\"1\" jsp:id=\"1\"><jsp:attribute name=\"scope\" jsp:id=\"2\">"
                + "<jsp:text jsp:id=\"3\">request</jsp:text></jsp:attribute></c:set>"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "/WEB-INF/tlds/c.tld", "urn:jsptld:/WEB-INF/c.tld" })
    void aliasUriIsValidatedAsTheLibrary(String alias) {
        CoreTagLibraryValidator validator = new CoreTagLibraryValidator();
        assertNull(validator.validate("c", alias, view("<c:choose jsp:id=\"1\"><jsp:text jsp:id=\"2\">\n </jsp:text>"
                + "<c:when test=\"${x}\" jsp:id=\"3\">a</c:when><c:otherwise jsp:id=\"4\">b</c:otherwise></c:choose>",
                "c", alias)));
        assertReported(validator.validate("c", alias, view("<c:choose jsp:id=\"1\"><c:otherwise jsp:id=\"2\"/>"
                + "</c:choose>", "c", alias)), "1", "c:choose", "§5.4");
        assertReported(validator.validate("c", alias, view("<c:when test=\"t\" jsp:id=\"1\"/>", "c", alias)), "1",
                "c:when", "§5.5");
    }

    @ParameterizedTest
    @ValueSource(strings = { "/WEB-INF/tlds/sql.tld", "urn:jsptld:/WEB-INF/sql.tld" })
    void transactionUnderAnAliasUriIsMatched(String alias) {
        SqlTagLibraryValidator validator = new SqlTagLibraryValidator();
        assertNull(validator.validate("sql", alias, view("<sql:transaction dataSource=\"d\" jsp:id=\"1\">"
                + "<sql:query var=\"r\" sql=\"x\" jsp:id=\"2\"/></sql:transaction>", "sql", alias)));
        assertReported(validator.validate("sql", alias, view("<sql:transaction jsp:id=\"1\">"
                + "<sql:update sql=\"x\" dataSource=\"d\" jsp:id=\"2\"/></sql:transaction>", "sql", alias)), "2",
                "sql:update", "§10.5");
    }

    @Test
    void elIgnoredMakesExpressionsLiterals() {
        assertReported(core("<jsp:directive.page isELIgnored=\"true\" jsp:id=\"1\"/>"
                + "<c:set var=\"v\" value=\"1\" scope=\"${s}\" jsp:id=\"2\"/>"), "2", "c:set", "§2.7");
        // A page directive applies to the whole page, wherever it appears.
        assertReported(core("<c:set var=\"v\" value=\"1\" scope=\"${s}\" jsp:id=\"1\"/>"
                + "<jsp:directive.page isELIgnored=\"TRUE\" jsp:id=\"2\"/>"), "1", "c:set", "§2.7");
        assertReported(core("<jsp:directive.page deferredSyntaxAllowedAsLiteral=\"true\" jsp:id=\"1\"/>"
                + "<c:set var=\"v\" value=\"1\" scope=\"#{s}\" jsp:id=\"2\"/>"), "2", "c:set", "§2.7");
        assertNull(core("<jsp:directive.page deferredSyntaxAllowedAsLiteral=\"true\" jsp:id=\"1\"/>"
                + "<c:set var=\"v\" value=\"1\" scope=\"${s}\" jsp:id=\"2\"/>"));
        assertNull(core("<jsp:directive.page isELIgnored=\"true\" jsp:id=\"1\"/>"
                + "<c:set var=\"v\" value=\"1\" scope=\"%= s %\" jsp:id=\"2\"/>"));
    }

    /**
     * §2.7 leaves to the run time a constraint that operates on a dynamic attribute (the "Dynamic"
     * column of the attribute tables): {@code begin}, {@code step} (§6.2, §6.3, §12.6), {@code maxRows}
     * (§10.3) and {@code currencyCode} (§9.7) are dynamic, so even a literal value is checked by the
     * handler. The TCK page {@code sql.query#negativeQueryMaxRowsAttributeTest2} catches the
     * {@code JspException} of {@code maxRows="-5"} at run time.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            CORE | c   | jakarta.tags.core | <c:forEach items="${l}" begin="-1" step="0" jsp:id="1"/>
            CORE | c   | jakarta.tags.core | <c:forTokens items="a" delims="," begin="-2" step="-1" jsp:id="1"/>
            XML  | x   | jakarta.tags.xml  | <x:forEach select="$a" begin="-1" step="0" jsp:id="1"/>
            SQL  | sql | jakarta.tags.sql  | <sql:query var="r" sql="s" maxRows="-5" jsp:id="1"/>
            FMT  | fmt | jakarta.tags.fmt  | <fmt:formatNumber value="1" type="currency" currencyCode="XXXX" jsp:id="1"/>
            """)
    void constraintsOnDynamicAttributesAreLeftToTheRunTime(String library, String prefix, String uri, String body) {
        assertNull(validate(library, prefix, uri, body));
    }

    @Test
    void fmtParamOutsideMessageIsReported() {
        assertReported(validate("FMT", "fmt", FMT, "<fmt:bundle basename=\"b\" jsp:id=\"1\"><fmt:param value=\"x\""
                + " jsp:id=\"2\"/></fmt:bundle>"), "2", "fmt:param", "§8.9");
        TestPageData nested = view("<fmt:message key=\"k\" jsp:id=\"1\"><c:if test=\"t\" jsp:id=\"2\">"
                + "<fmt:param value=\"x\" jsp:id=\"3\"/></c:if></fmt:message>", "fmt", FMT, "c", CORE);
        assertNull(new FmtTagLibraryValidator().validate("fmt", FMT, nested));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            <sql:transaction jsp:id="0"><sql:param value="x" jsp:id="1"/></sql:transaction>         | sql:param     | §10.7
            <sql:transaction jsp:id="0"><jsp:body jsp:id="2"><sql:dateParam value="${d}" jsp:id="1"/></jsp:body></sql:transaction> | sql:dateParam | §10.8
            """)
    void sqlParamOutsideAnExecutionActionIsReported(String body, String qName, String section) {
        assertReported(validate("SQL", "sql", SQL, body), "1", qName, "SQLExecutionTag", section);
    }

    @Test
    void sqlParamInsideAnExecutionActionOrACustomActionIsAccepted() {
        assertNull(validate("SQL", "sql", SQL, "<sql:update sql=\"x\" jsp:id=\"1\"><sql:param value=\"x\" jsp:id=\"2\"/>"
                + "<sql:dateParam value=\"${d}\" jsp:id=\"3\"/></sql:update>"));
        // A custom action may implement SQLExecutionTag: the validator cannot tell, the handler checks at run time.
        TestPageData custom = view("<my:exec jsp:id=\"1\"><sql:param value=\"x\" jsp:id=\"2\"/></my:exec>", "sql", SQL,
                "my", "urn:example:custom");
        assertNull(new SqlTagLibraryValidator().validate("sql", SQL, custom));
    }

    /**
     * A parameter action with no enclosing action may be the top level of a tag file that has no
     * tag directive, invoked inside the expected action: the view cannot tell, the handler checks.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            FMT | fmt | jakarta.tags.fmt | <fmt:param value="x" jsp:id="1"/>
            SQL | sql | jakarta.tags.sql | <sql:param value="x" jsp:id="1"/>
            SQL | sql | jakarta.tags.sql | <sql:dateParam value="${d}" jsp:id="1"/>
            FMT | fmt | jakarta.tags.fmt | <jsp:text jsp:id="2">text</jsp:text><fmt:param value="x" jsp:id="1"/>
            SQL | sql | jakarta.tags.sql | <jsp:directive.page import="x.Y" jsp:id="2"/><sql:param value="x" jsp:id="1"/>
            """)
    void topLevelParamActionIsLeftToTheRunTime(String library, String prefix, String uri, String body) {
        assertNull(validate(library, prefix, uri, body));
    }

    @ParameterizedTest
    @ValueSource(strings = { "<jsp:directive.tag body-content=\"empty\" jsp:id=\"9\"/>",
            "<jsp:directive.attribute name=\"a\" jsp:id=\"9\"/>", "<jsp:doBody jsp:id=\"9\"/>" })
    void paramNestingIsNotCheckedInATagFile(String tagFileMarker) {
        // A tag file's actions can be nested, at run time, in an action of the page that invokes it.
        assertNull(validate("FMT", "fmt", FMT, tagFileMarker + "<fmt:param value=\"x\" jsp:id=\"1\"/>"));
        assertNull(validate("SQL", "sql", SQL, tagFileMarker + "<sql:param value=\"x\" jsp:id=\"1\"/>"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            <c:set value="1" jsp:id="1"/>                                         | var or target
            <c:set target="${t}" value="1" jsp:id="1"/>                           | target requires property
            <c:set property="p" value="1" jsp:id="1"/>                            | property requires target
            <c:set var="v" target="${t}" property="p" value="1" jsp:id="1"/>      | var excludes target and property
            <c:set target="${t}" property="p" value="1" scope="page" jsp:id="1"/> | scope requires var
            """)
    void setSyntaxErrorsAreReported(String body, String rule) {
        assertReported(core(body), "1", "c:set", rule, "§4.3");
    }

    @ParameterizedTest
    @ValueSource(strings = { "<c:set var=\"v\" value=\"1\" jsp:id=\"1\"/>",
            "<c:set var=\"v\" jsp:id=\"1\"><jsp:text jsp:id=\"2\">body</jsp:text></c:set>",
            "<c:set target=\"${t}\" property=\"p\" value=\"1\" jsp:id=\"1\"/>",
            "<c:set value=\"1\" jsp:id=\"1\"><jsp:attribute name=\"target\" jsp:id=\"2\">"
                    + "<jsp:expression jsp:id=\"3\">t</jsp:expression></jsp:attribute>"
                    + "<jsp:attribute name=\"property\" jsp:id=\"4\">p</jsp:attribute></c:set>" })
    void validSetSyntaxIsAccepted(String body) {
        assertNull(core(body));
    }

    @Test
    void forEachWithoutItemsNeedsBeginAndEnd() {
        assertReported(core("<c:forEach var=\"i\" jsp:id=\"1\"/>"), "1", "c:forEach", "items, or begin and end",
                "§6.2");
        assertReported(core("<c:forEach var=\"i\" begin=\"1\" jsp:id=\"1\"/>"), "1", "c:forEach", "§6.2");
        assertNull(core("<c:forEach var=\"i\" begin=\"1\" end=\"2\" jsp:id=\"1\"/>"));
    }

    @Test
    void importWithVarReaderExcludesVarAndScope() {
        assertReported(core("<c:import url=\"u\" varReader=\"r\" var=\"v\" jsp:id=\"1\"/>"), "1", "c:import",
                "varReader excludes var and scope", "§7.4");
        assertReported(core("<c:import url=\"u\" varReader=\"r\" scope=\"page\" jsp:id=\"1\"/>"), "1", "c:import",
                "§7.4");
        assertNull(core("<c:import url=\"u\" varReader=\"r\" jsp:id=\"1\"><c:out value=\"x\" jsp:id=\"2\"/></c:import>"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            <sql:setDataSource var="v" jsp:id="1"/>                                | dataSource or url
            <sql:setDataSource dataSource="d" url="jdbc:x" jsp:id="1"/>            | dataSource excludes url, driver, user and password
            <sql:setDataSource dataSource="d" user="u" jsp:id="1"/>                | dataSource excludes
            """)
    void setDataSourceSyntaxErrorsAreReported(String body, String rule) {
        assertReported(validate("SQL", "sql", SQL, body), "1", "sql:setDataSource", rule, "§10.6");
        assertNull(validate("SQL", "sql", SQL, "<sql:setDataSource url=\"jdbc:x\" driver=\"D\" user=\"u\" password=\"p\""
                + " jsp:id=\"1\"/>"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            <x:parse var="v" varDom="d" jsp:id="1"/>                                       | x:parse     | var and varDom are exclusive         | §11.2
            <x:parse var="v" doc="${a}" xml="${b}" jsp:id="1"/>                            | x:parse     | doc and xml are exclusive            | §11.2
            <x:transform doc="${a}" xml="${b}" xslt="${s}" jsp:id="1"/>                    | x:transform | doc and xml are exclusive            | §13.2
            <x:transform doc="${a}" xslt="${s}" docSystemId="a" xmlSystemId="b" jsp:id="1"/> | x:transform | docSystemId and xmlSystemId are exclusive | §13.2
            <x:transform doc="${a}" xslt="${s}" var="v" result="${r}" jsp:id="1"/>         | x:transform | var and result are exclusive         | §13.2
            """)
    void xmlSyntaxErrorsAreReported(String body, String qName, String rule, String section) {
        assertReported(validate("XML", "x", XML, body), "1", qName, rule, section);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            CORE | c   | jakarta.tags.core | <c:set var="v" value="1" scope="invalid" jsp:id="1"/>                | c:set
            SQL  | sql | jakarta.tags.sql  | <sql:setDataSource dataSource="d" var="v" scope="invalid" jsp:id="1"/> | sql:setDataSource
            FMT  | fmt | jakarta.tags.fmt  | <fmt:setLocale value="fr" scope="Page" jsp:id="1"/>                 | fmt:setLocale
            XML  | x   | jakarta.tags.xml  | <x:parse varDom="d" scopeDom="nowhere" jsp:id="1"/>                  | x:parse
            """)
    void invalidLiteralScopeIsReported(String library, String prefix, String uri, String body, String qName) {
        assertReported(validate(library, prefix, uri, body), "1", qName, "scope", "§2.7");
    }

    @ParameterizedTest
    @ValueSource(strings = { "page", "request", "session", "application" })
    void everyValidScopeIsAccepted(String scope) {
        assertNull(core("<c:set var=\"v\" value=\"1\" scope=\"" + scope + "\" jsp:id=\"1\"/>"));
        assertNull(validate("XML", "x", XML, "<x:parse varDom=\"d\" scopeDom=\"" + scope + "\" jsp:id=\"1\"/>"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "%= s %", "${s}", "#{s}" })
    void requestTimeScopeIsLeftToTheRunTime(String scope) {
        assertNull(core("<c:set var=\"v\" value=\"1\" scope=\"" + scope + "\" jsp:id=\"1\"/>"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            SQL  | sql | jakarta.tags.sql  | <sql:update var="" sql="x" jsp:id="1"/>             | sql:update  | var
            CORE | c   | jakarta.tags.core | <c:forEach items="${l}" varStatus="" jsp:id="1"/>   | c:forEach   | varStatus
            CORE | c   | jakarta.tags.core | <c:import url="u" varReader="" jsp:id="1"/>          | c:import    | varReader
            XML  | x   | jakarta.tags.xml  | <x:parse varDom="" jsp:id="1"/>                      | x:parse     | varDom
            """)
    void emptyVarIsReported(String library, String prefix, String uri, String body, String qName, String attribute) {
        assertReported(validate(library, prefix, uri, body), "1", qName, attribute, "§2.7");
    }

    @Test
    void dataSourceInsideTransactionIsReported() {
        TestPageData direct = view("<sql:transaction dataSource=\"d\" jsp:id=\"1\">"
                + "<sql:query var=\"r\" sql=\"x\" dataSource=\"d\" jsp:id=\"2\"/></sql:transaction>", "sql", SQL);
        assertReported(new SqlTagLibraryValidator().validate("sql", SQL, direct), "2", "sql:query", "dataSource",
                "§10.5");
        TestPageData deep = view("<sql:transaction jsp:id=\"1\"><c:if test=\"true\" jsp:id=\"2\">"
                + "<sql:update sql=\"x\" jsp:id=\"3\"><jsp:attribute name=\"dataSource\" jsp:id=\"4\">d</jsp:attribute>"
                + "</sql:update></c:if></sql:transaction>", "sql", SQL, "c", CORE);
        assertReported(new SqlTagLibraryValidator().validate("sql", SQL, deep), "3", "sql:update", "dataSource",
                "§10.5");
        TestPageData legacyTransaction = view("<sql_rt:transaction jsp:id=\"1\">"
                + "<sql:query var=\"r\" sql=\"x\" dataSource=\"d\" jsp:id=\"2\"/></sql_rt:transaction>", "sql", SQL,
                "sql_rt", "http://java.sun.com/jstl/sql_rt");
        assertReported(new SqlTagLibraryValidator().validate("sql", SQL, legacyTransaction), "2", "sql:query",
                "§10.5");
    }

    @Test
    void malformedViewIsReported() {
        ValidationMessage[] messages = new CoreTagLibraryValidator().validate("c", CORE,
                new TestPageData("<jsp:root xmlns:jsp=\"http://java.sun.com/JSP/Page\"><c:if>"));
        assertReported(messages, null, READ_FAILURE);
    }

    @Test
    void externalEntityIsRefusedNotFetched(@TempDir Path directory) throws IOException {
        // A DTD that is valid and harmless: had it been read, the page would be valid.
        Path dtd = directory.resolve("page.dtd");
        Files.writeString(dtd, "<!ENTITY e \"fetched\">", StandardCharsets.UTF_8);
        String root = "<jsp:root xmlns:jsp=\"http://java.sun.com/JSP/Page\" xmlns:c=\"jakarta.tags.core\" jsp:id=\"0\">";
        String external = "<!DOCTYPE jsp:root SYSTEM \"" + dtd.toUri() + "\">" + root
                + "<c:choose jsp:id=\"1\"><c:when test=\"t\" jsp:id=\"2\"/></c:choose></jsp:root>";
        assertReported(new CoreTagLibraryValidator().validate("c", CORE, new TestPageData(external)), null,
                READ_FAILURE, dtd.toUri().toString());
        String entity = "<!DOCTYPE jsp:root [<!ENTITY e SYSTEM \"" + dtd.toUri() + "\">]>" + root
                + "<jsp:text jsp:id=\"1\">&e;</jsp:text></jsp:root>";
        assertReported(new CoreTagLibraryValidator().validate("c", CORE, new TestPageData(entity)), null,
                READ_FAILURE, dtd.toUri().toString());
    }

    @Test
    void aValidPageGivesNull() {
        assertNull(core("<jsp:directive.page contentType=\"text/html\" jsp:id=\"1\"/><jsp:text jsp:id=\"2\">Hi</jsp:text>"
                + "<c:set var=\"v\" value=\"1\" scope=\"request\" jsp:id=\"3\"/>"
                + "<c:if test=\"%= true %\" var=\"t\" scope=\"session\" jsp:id=\"4\"/>"
                + "<c:forEach items=\"${l}\" var=\"i\" varStatus=\"s\" jsp:id=\"5\"><c:choose jsp:id=\"6\">"
                + "<c:when test=\"${s.first}\" jsp:id=\"7\"><jsp:text jsp:id=\"8\">first</jsp:text></c:when>"
                + "<c:otherwise jsp:id=\"9\"><c:out value=\"${i}\" jsp:id=\"10\"/></c:otherwise></c:choose></c:forEach>"
                + "<c:import url=\"u\" var=\"text\" scope=\"page\" jsp:id=\"11\"/>"));
        assertNull(validate("FMT", "fmt", FMT, "<fmt:setLocale value=\"fr\" scope=\"session\" jsp:id=\"1\"/>"
                + "<fmt:formatDate value=\"${d}\" var=\"f\" scope=\"page\" jsp:id=\"2\"/>"
                + "<fmt:formatNumber value=\"1\" jsp:id=\"3\"/><fmt:message key=\"k\" jsp:id=\"4\"/>"));
        assertNull(validate("SQL", "sql", SQL, "<sql:query var=\"r\" sql=\"x\" dataSource=\"d\" jsp:id=\"1\"/>"
                + "<sql:transaction dataSource=\"d\" jsp:id=\"2\"><sql:update sql=\"x\" var=\"n\" scope=\"page\" jsp:id=\"3\"/>"
                + "<sql:query var=\"q\" sql=\"y\" jsp:id=\"4\"/></sql:transaction>"
                + "<sql:setDataSource dataSource=\"d\" var=\"v\" scope=\"application\" jsp:id=\"5\"/>"));
        assertNull(validate("XML", "x", XML, "<x:parse var=\"doc\" scope=\"page\" jsp:id=\"1\">"
                + "<jsp:text jsp:id=\"2\">&lt;a&gt;text&lt;/a&gt;</jsp:text></x:parse>"
                + "<x:if select=\"$doc//a\" var=\"found\" scope=\"request\" jsp:id=\"3\"/>"
                + "<x:choose jsp:id=\"4\"><x:when select=\"$found\" jsp:id=\"5\"/></x:choose>"));
    }
}
