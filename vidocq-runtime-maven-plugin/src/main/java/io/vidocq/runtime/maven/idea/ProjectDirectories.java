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
package io.vidocq.runtime.maven.idea;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Finds the aggregator a directory is a module of, by reading the {@code pom.xml} of every ancestor
 * directory, whatever the depth: a reactor may list {@code apps/server} from its root while {@code apps/}
 * has no pom. Top-level and profile {@code <modules>} both count; a module that names a pom file counts as
 * its directory. An unreadable or malformed ancestor pom is ignored.
 */
final class ProjectDirectories {

    private ProjectDirectories() {
    }

    /** The real path of the nearest ancestor {@code pom.xml} that lists {@code directory} as a module, or {@code null}. */
    static Path aggregatorOf(Path directory) throws IOException {
        Path target = directory.toRealPath();
        for (Path ancestor = target.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
            Path pom = ancestor.resolve("pom.xml");
            if (!Files.isRegularFile(pom)) {
                continue;
            }
            for (String module : modules(pom)) {
                Path listed = ancestor.resolve(module).normalize();
                if (Files.isRegularFile(listed)) {
                    listed = listed.getParent();
                }
                if (Files.isDirectory(listed) && listed.toRealPath().equals(target)) {
                    return pom.toRealPath();
                }
            }
        }
        return null;
    }

    /** The {@code <module>} entries of a pom, top-level and in profiles; empty when the pom cannot be read. */
    static List<String> modules(Path pom) {
        List<String> modules = new ArrayList<>();
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        try (InputStream in = Files.newInputStream(pom)) {
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            Deque<String> path = new ArrayDeque<>();
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    if ("module".equals(name) && isModulesOfProjectOrProfile(path)) {
                        String module = reader.getElementText().strip();
                        if (!module.isEmpty()) {
                            modules.add(module);
                        }
                    } else {
                        path.push(name);
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    path.pop();
                }
            }
            reader.close();
            return modules;
        } catch (IOException | XMLStreamException | RuntimeException e) {
            return List.of();
        }
    }

    /** {@code project/modules} or {@code project/profiles/profile/modules}; {@code path} is innermost first. */
    private static boolean isModulesOfProjectOrProfile(Deque<String> path) {
        List<String> names = List.copyOf(path);
        return names.equals(List.of("modules", "project"))
                || names.equals(List.of("modules", "profile", "profiles", "project"));
    }
}
