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
package io.vidocq.bodoni.tck;

import io.vidocq.ibarra.arquillian.PagesConfiguration;
import io.vidocq.ibarra.arquillian.PagesContainer;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins the {@code arquillian.xml} of the Tags TCK runner: the Ibarra container and the three adapter
 * properties decided for the suite, and that the adapter accepts them on this module's test class path.
 */
class ArquillianConfigurationTest {
    private static final String ARQUILLIAN_NAMESPACE = "http://jboss.org/schema/arquillian";

    private static final String APPLICATION_LIBRARIES = "io.vidocq.bodoni.core,io.vidocq.bodoni.peano,jakarta.servlet.jsp.jstl,com.h2database";
    private static final String JDK_MODULES = "java.sql,java.naming,jdk.localedata";
    private static final String APPLICATION_OPENS = "com.sun.ts.tests.jstl.common.resources";

    @Test
    void declaresTheDecidedContainerProperties() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Element root;
        try (InputStream xml = ArquillianConfigurationTest.class.getClassLoader().getResourceAsStream("arquillian.xml")) {
            assertNotNull(xml, "arquillian.xml is not on the test class path");
            root = factory.newDocumentBuilder().parse(xml).getDocumentElement();
        }

        NodeList containers = root.getElementsByTagNameNS(ARQUILLIAN_NAMESPACE, "container");
        assertEquals(1, containers.getLength());
        var container = (Element) containers.item(0);
        assertEquals("ibarra", container.getAttribute("qualifier"));
        assertEquals("true", container.getAttribute("default"));

        var properties = new LinkedHashMap<String, String>();
        NodeList nodes = container.getElementsByTagNameNS(ARQUILLIAN_NAMESPACE, "property");
        for (int i = 0; i < nodes.getLength(); i++) {
            var property = (Element) nodes.item(i);
            properties.put(property.getAttribute("name"), property.getTextContent().trim());
        }
        assertEquals(Map.of(
                "applicationLibraries", APPLICATION_LIBRARIES,
                "jdkModules", JDK_MODULES,
                "applicationOpens", APPLICATION_OPENS), properties);
    }

    @Test
    void decidedPropertiesValidateOnTheTestClassPath() {
        var configuration = new PagesConfiguration();
        configuration.setApplicationLibraries(APPLICATION_LIBRARIES);
        configuration.setJdkModules(JDK_MODULES);
        configuration.setApplicationOpens(APPLICATION_OPENS);

        assertDoesNotThrow(configuration::validate);
        // validate() checks the names only; setup() also resolves each library on the test class path.
        var container = new PagesContainer();
        assertDoesNotThrow(() -> container.setup(configuration));
    }
}
