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

package io.vidocq.bodoni.core.testing;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Test helper: parses a tag library descriptor from the class path with
 * {@code javax.xml.parsers} and exposes what the tests assert on.
 */
public final class TldReader {

    /** A {@code <function>} element. */
    public record Function(String name, String functionClass, String signature) {
    }

    /** An {@code <attribute>} element of a tag. */
    public record Attribute(String name, boolean required, boolean rtexprvalue, boolean deferred,
            String type) {
    }

    /** A {@code <tag>} element. */
    public record Tag(String name, String tagClass, String bodyContent, List<Attribute> attributes) {
    }

    private final Element root;

    private TldReader(Element root) {
        this.root = root;
    }

    /**
     * Parses the descriptor found at the given class-path resource.
     *
     * @param resource absolute class-path name, for example {@code META-INF/fn.tld}
     * @return the reader
     */
    public static TldReader read(String resource) {
        try (InputStream in = TldReader.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("No such class-path resource: " + resource);
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(in);
            return new TldReader(document.getDocumentElement());
        } catch (IOException | ParserConfigurationException | SAXException e) {
            throw new IllegalStateException("Cannot parse " + resource, e);
        }
    }

    /** Returns the {@code <uri>} of the library. */
    public String uri() {
        return childText(root, "uri");
    }

    /** Returns the {@code <short-name>} of the library. */
    public String shortName() {
        return childText(root, "short-name");
    }

    /** Returns the declared functions, in document order. */
    public List<Function> functions() {
        List<Function> result = new ArrayList<>();
        for (Element e : children(root, "function")) {
            result.add(new Function(childText(e, "name"), childText(e, "function-class"),
                    childText(e, "function-signature")));
        }
        return result;
    }

    /** Returns the declared tags, in document order. */
    public List<Tag> tags() {
        List<Tag> result = new ArrayList<>();
        for (Element e : children(root, "tag")) {
            List<Attribute> attributes = new ArrayList<>();
            for (Element a : children(e, "attribute")) {
                attributes.add(new Attribute(childText(a, "name"),
                        Boolean.parseBoolean(childText(a, "required")),
                        Boolean.parseBoolean(childText(a, "rtexprvalue")),
                        a.getElementsByTagName("deferred-value").getLength() > 0
                                || a.getElementsByTagName("deferred-method").getLength() > 0,
                        attributeType(a)));
            }
            result.add(new Tag(childText(e, "name"), childText(e, "tag-class"),
                    childText(e, "body-content"), attributes));
        }
        return result;
    }

    /** The {@code <type>} of the attribute, or of its {@code <deferred-value>}. */
    private static String attributeType(Element attribute) {
        String type = childText(attribute, "type");
        if (type != null) {
            return type;
        }
        List<Element> deferred = children(attribute, "deferred-value");
        return deferred.isEmpty() ? null : childText(deferred.get(0), "type");
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(name)) {
                result.add(e);
            }
        }
        return result;
    }

    private static String childText(Element parent, String name) {
        List<Element> found = children(parent, name);
        return found.isEmpty() ? null : found.get(0).getTextContent().trim();
    }
}
