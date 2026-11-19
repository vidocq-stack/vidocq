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
package io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.units;

import io.vidocq.noether.jpa.core.spi.PersistenceUnitDeclaration;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PersistenceUnitTransactionType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The persistence units the {@code vidocq.persistence.<unit>.*} keys declare or override. A unit name with a dot is
 * quoted: {@code vidocq.persistence."com.acme.ledger".datasource}.
 *
 * <ul>
 *   <li>{@code module}, {@code packages}, {@code classes}, {@code transaction-type}, {@code qualifier} and
 *       {@code scope} declare the unit: a {@code persistence.xml} unit of the same name is a duplicate;</li>
 *   <li>{@code datasource}, {@code non-jta-datasource} and {@code properties.<name>} alone override the unit a
 *       {@code persistence.xml} declares, and declare it otherwise.</li>
 * </ul>
 *
 * A unit the keys declare manages the entities of the application module unless {@code module} names another, and is
 * JTA unless {@code transaction-type} says otherwise. Without any key, the application has a unit named
 * {@value #DEFAULT_UNIT}, of the entities of its module, which Noether declares only when nothing else declares a
 * unit and the module has entities.
 */
final class UnitKeys {

    static final String PREFIX = "vidocq.persistence.";
    static final String DEFAULT_UNIT = "default";

    private static final String PROPERTIES = "properties.";
    private static final String ATTRIBUTES = "module, packages, classes, datasource, non-jta-datasource,"
            + " transaction-type, qualifier, scope or properties.<name>";

    private UnitKeys() {
    }

    /**
     * The units of these keys, by name.
     *
     * @param keys the configuration keys and their values; those outside {@code vidocq.persistence.} are ignored
     * @param modules the roots of the application's modules
     * @throws PersistenceException for a key that sets nothing a unit has, a module the application does not have,
     *         or a transaction type that is not one
     */
    static List<PersistenceUnitDeclaration> declarations(Map<String, String> keys, ModuleRoots modules) {
        Map<String, PersistenceUnitDeclaration.Builder> units = new TreeMap<>();
        for (Map.Entry<String, String> entry : new TreeMap<>(keys).entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue().strip();
            if (entry.getKey().startsWith(PREFIX) && !value.isEmpty()) {
                Key key = Key.parse(entry.getKey());
                set(units.computeIfAbsent(key.unit(), name -> unit(name, modules)), key, value, modules);
            }
        }
        if (units.isEmpty()) {
            return modules.application().map(root -> PersistenceUnitDeclaration
                    .builder(DEFAULT_UNIT, "the default unit of the application module, as no vidocq.persistence key"
                            + " declares a unit")
                    .defaultRoot(root).defaultTransactionType(PersistenceUnitTransactionType.JTA).whenAlone().build())
                    .stream().toList();
        }
        List<PersistenceUnitDeclaration> declarations = new ArrayList<>();
        units.values().forEach(unit -> declarations.add(unit.build()));
        return List.copyOf(declarations);
    }

    private static PersistenceUnitDeclaration.Builder unit(String name, ModuleRoots modules) {
        String quoted = name.indexOf('.') < 0 ? name : '"' + name + '"';
        PersistenceUnitDeclaration.Builder unit = PersistenceUnitDeclaration
                .builder(name, "the " + PREFIX + quoted + ".* keys")
                .defaultTransactionType(PersistenceUnitTransactionType.JTA);
        modules.application().ifPresent(unit::defaultRoot);
        return unit;
    }

    private static void set(PersistenceUnitDeclaration.Builder unit, Key key, String value, ModuleRoots modules) {
        switch (key.attribute()) {
            case "module" -> unit.root(modules.of(value).orElseThrow(() -> new PersistenceException(key.name()
                    + " names the module " + value + ", which the application does not have")));
            case "packages" -> unit.packages(list(value));
            case "classes" -> unit.classes(list(value));
            case "datasource" -> unit.dataSource(value);
            case "non-jta-datasource" -> unit.nonJtaDataSource(value);
            case "transaction-type" -> unit.transactionType(transactionType(key, value));
            case "qualifier" -> unit.qualifiers(list(value));
            case "scope" -> unit.scope(value);
            default -> unit.property(property(key), value);
        }
    }

    private static String property(Key key) {
        String attribute = key.attribute();
        if (!attribute.startsWith(PROPERTIES) || attribute.length() == PROPERTIES.length()) {
            throw unknown(key.name());
        }
        return attribute.substring(PROPERTIES.length());
    }

    private static PersistenceUnitTransactionType transactionType(Key key, String value) {
        return Arrays.stream(PersistenceUnitTransactionType.values())
                .filter(type -> type.name().equalsIgnoreCase(value)).findFirst()
                .orElseThrow(() -> new PersistenceException(key.name() + " is " + value
                        + ": a persistence unit is JTA or RESOURCE_LOCAL"));
    }

    private static List<String> list(String value) {
        return Arrays.stream(value.split(",")).map(String::strip).filter(item -> !item.isEmpty()).toList();
    }

    private static PersistenceException unknown(String key) {
        return new PersistenceException("Unknown key " + key + ": a key of a persistence unit is "
                + PREFIX + "<unit>.<attribute>, where <attribute> is " + ATTRIBUTES);
    }

    /** A key, split into its unit and its attribute. */
    private record Key(String name, String unit, String attribute) {

        static Key parse(String name) {
            String rest = name.substring(PREFIX.length());
            int end;
            String unit;
            if (rest.startsWith("\"")) {
                int quote = rest.indexOf('"', 1);
                if (quote < 0) {
                    throw new PersistenceException("The key " + name + " opens a quote around the name of its"
                            + " persistence unit and does not close it");
                }
                unit = rest.substring(1, quote);
                end = quote + 1;
            } else {
                end = rest.indexOf('.');
                unit = end < 0 ? rest : rest.substring(0, end);
            }
            if (unit.isEmpty() || end < 0 || end >= rest.length() - 1 || rest.charAt(end) != '.') {
                throw unknown(name);
            }
            return new Key(name, unit, rest.substring(end + 1));
        }
    }
}
