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

import org.apache.maven.project.MavenProject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The module names IntelliJ IDEA gives the Maven projects of a build on a first import with its default
 * settings, as {@code MavenModuleNameMapper} computes them in IntelliJ IDEA 2026.2 (tag
 * {@code idea/262.10968.63}).
 *
 * <p>A module is named after its artifactId, or after its directory when IntelliJ does not accept the
 * artifactId as a name. When names are equal ignoring case, every one of those modules gets a counter, in
 * the order of their pom paths ignoring case, followed by its groupId unless they share it:
 * {@code m (1) (test.group1)}, {@code m (2) (test.group2)}.
 *
 * <p>These are only the names of a first import: IntelliJ keeps the name of a module it imported before
 * (registry key {@code maven.import.keep.existing.module.names}), and the registry key
 * {@code maven.import.module.name.template} can name modules otherwise. The result only feeds a warning.
 */
final class IntelliJModuleNames {

    /** {@code MavenId.UNKNOWN_VALUE}: IntelliJ never takes it as a name. */
    private static final String UNKNOWN = "Unknown";

    private IntelliJModuleNames() {
    }

    /** The first-import module name of each project, by {@link #key(MavenProject)}. */
    static Map<String, String> firstImport(List<MavenProject> projects) {
        List<Item> items = new ArrayList<>();
        for (MavenProject project : projects) {
            if (project.getFile() != null) {
                items.add(new Item(project));
            }
        }
        items.sort((a, b) -> a.path.compareToIgnoreCase(b.path));

        Map<String, Integer> counters = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (item.duplicatedGroup) {
                continue;
            }
            for (int k = i + 1; k < items.size(); k++) {
                Item other = items.get(k);
                if (item.originalName.equalsIgnoreCase(other.originalName)) {
                    counters.put(item.originalName.toLowerCase(Locale.ROOT), 0);
                    if (item.groupId.equals(other.groupId)) {
                        item.duplicatedGroup = true;
                        other.duplicatedGroup = true;
                    }
                }
            }
        }

        Set<String> taken = new HashSet<>();
        Map<String, String> names = new HashMap<>();
        for (Item item : items) {
            String counterKey = item.originalName.toLowerCase(Locale.ROOT);
            Integer counter = counters.get(counterKey);
            if (counter != null) {
                item.number = counter;
                counters.put(counterKey, counter + 1);
            }
            while (!taken.add(item.name())) {
                item.number++;
                counters.put(counterKey, item.number + 1);
            }
            names.put(item.path, item.name());
        }
        return names;
    }

    /** The key of a project in {@link #firstImport(List)}: its pom path. */
    static String key(MavenProject project) {
        return project.getFile() == null ? "" : project.getFile().getAbsolutePath().replace(File.separatorChar, '/');
    }

    /** The name a project's module starts from: its artifactId, or its directory name. */
    static String originalName(MavenProject project) {
        String artifactId = project.getArtifactId();
        return isAccepted(artifactId) ? artifactId : project.getFile().getAbsoluteFile().getParentFile().getName();
    }

    /** Whether IntelliJ takes a value as a module name ({@code MavenModuleNameMapper.isValidName}). */
    static boolean isAccepted(String name) {
        if (name == null || name.trim().isEmpty() || UNKNOWN.equals(name)) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(Character.isDigit(c) || Character.isLetter(c) || c == '-' || c == '_' || c == '.')) {
                return false;
            }
        }
        return true;
    }

    private static final class Item {
        final String path;
        final String originalName;
        final String groupId;
        int number = -1;
        boolean duplicatedGroup;

        Item(MavenProject project) {
            this.path = key(project);
            this.originalName = originalName(project);
            this.groupId = isAccepted(project.getGroupId()) ? project.getGroupId() : "";
        }

        String name() {
            if (number == -1) {
                return originalName;
            }
            String name = originalName + " (" + (number + 1) + ")";
            return duplicatedGroup || groupId.isEmpty() ? name : name + " (" + groupId + ")";
        }
    }
}
