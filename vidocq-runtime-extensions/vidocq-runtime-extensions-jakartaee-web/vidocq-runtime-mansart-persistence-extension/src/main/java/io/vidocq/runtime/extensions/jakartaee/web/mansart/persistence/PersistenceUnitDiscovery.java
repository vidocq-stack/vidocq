/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.extensions.jakartaee.web.mansart.persistence;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PersistenceUnitDiscovery {
    private static final String DESCRIPTOR = "META-INF/persistence.xml";

    record Unit(String name, String transactionType, String jtaDataSource, String nonJtaDataSource) {
    }

    private PersistenceUnitDiscovery() {
    }

    static List<String> discover(ClassLoader loader) {
        return discoverUnits(loader).stream().map(Unit::name).toList();
    }

    static List<Unit> discoverUnits(ClassLoader loader) {
        Set<String> urls = new HashSet<>();
        Map<String, Unit> units = new LinkedHashMap<>();
        Map<String, byte[]> descriptorsByUnit = new LinkedHashMap<>();
        try {
            Enumeration<URL> descriptors = loader.getResources(DESCRIPTOR);
            while (descriptors.hasMoreElements()) {
                URL descriptor = descriptors.nextElement();
                if (!urls.add(descriptor.toExternalForm())) {
                    continue;
                }
                try (InputStream input = descriptor.openConnection().getInputStream()) {
                    byte[] content = input.readAllBytes();
                    for (Unit unit : parseUnits(new ByteArrayInputStream(content))) {
                        byte[] previousDescriptor = descriptorsByUnit.putIfAbsent(unit.name(), content);
                        if (previousDescriptor != null && !Arrays.equals(previousDescriptor, content)) {
                            throw new IllegalStateException(
                                    "Conflicting persistence unit definitions across descriptors: " + unit.name());
                        }
                        units.putIfAbsent(unit.name(), unit);
                    }
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read persistence unit descriptors", failure);
        }
        return List.copyOf(units.values());
    }

    static List<String> parse(InputStream input) {
        return parseUnits(input).stream().map(Unit::name).toList();
    }

    static List<Unit> parseUnits(InputStream input) {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setXMLResolver((publicId, systemId, base, namespace) -> {
            throw new XMLStreamException("External XML resources are forbidden");
        });
        List<Unit> units = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        String unitName = null;
        String transactionType = null;
        String jtaDataSource = null;
        String nonJtaDataSource = null;
        try {
            var reader = factory.createXMLStreamReader(input);
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD) {
                        throw new IllegalStateException("Persistence descriptors must not contain a DTD");
                    }
                    if (event == XMLStreamConstants.START_ELEMENT
                            && reader.getLocalName().equals("persistence-unit")) {
                        unitName = reader.getAttributeValue(null, "name");
                        transactionType = reader.getAttributeValue(null, "transaction-type");
                        jtaDataSource = null;
                        nonJtaDataSource = null;
                        if (unitName == null || unitName.isBlank()) {
                            throw new IllegalStateException("Every persistence unit must have a name");
                        }
                        if (!names.add(unitName)) {
                            throw new IllegalStateException("Duplicate persistence unit name: " + unitName);
                        }
                    } else if (event == XMLStreamConstants.START_ELEMENT && unitName != null
                            && reader.getLocalName().equals("jta-data-source")) {
                        jtaDataSource = reader.getElementText().strip();
                    } else if (event == XMLStreamConstants.START_ELEMENT && unitName != null
                            && reader.getLocalName().equals("non-jta-data-source")) {
                        nonJtaDataSource = reader.getElementText().strip();
                    } else if (event == XMLStreamConstants.END_ELEMENT
                            && reader.getLocalName().equals("persistence-unit")) {
                        String effectiveTransactionType = transactionType == null
                                ? (jtaDataSource == null ? "RESOURCE_LOCAL" : "JTA")
                                : transactionType;
                        units.add(new Unit(unitName, effectiveTransactionType,
                                jtaDataSource, nonJtaDataSource));
                        unitName = null;
                        transactionType = null;
                        jtaDataSource = null;
                        nonJtaDataSource = null;
                    }
                }
            } finally {
                reader.close();
            }
        } catch (XMLStreamException failure) {
            throw new IllegalStateException("Unable to parse persistence unit descriptor", failure);
        }
        return List.copyOf(units);
    }
}
