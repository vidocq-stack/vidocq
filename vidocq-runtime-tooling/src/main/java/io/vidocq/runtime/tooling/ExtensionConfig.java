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
package io.vidocq.runtime.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The configuration an extension needs, appended to the application's {@code vidocq.properties} by
 * {@code vidocq extension add} and {@code vidocq create} (BUG-20261031-09): one block per extension, written once,
 * from the {@code config.<NN>} lines of its descriptor. A key the application already sets is left out, so nothing is
 * ever overwritten; {@code vidocq extension remove} leaves the file alone, the configuration being the application's.
 */
public final class ExtensionConfig {

    private static final String APP = "{app}";

    private ExtensionConfig() {}

    /**
     * {@code text} with the block of each of {@code ids} that has one and is not there yet, {@code app} (the project's
     * artifactId) standing for {@code {app}}.
     */
    public static String append(String text, Collection<String> ids, String app) {
        StringBuilder out = new StringBuilder(text);
        for (String id : ids) {
            List<String> lines = KnownExtensions.configLines(id);
            if (lines.isEmpty() || out.toString().contains(header(id))) {
                continue;
            }
            Map<String, String> set = PropertiesText.entries(out.toString());
            List<String> kept = new ArrayList<>();
            for (String line : lines) {
                String filled = line.replace(APP, app);
                if (!set.containsKey(key(filled))) {
                    kept.add(filled);
                }
            }
            if (kept.stream().noneMatch(line -> !key(line).isEmpty())) {
                continue; // every key is the application's already
            }
            if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            }
            out.append('\n').append(header(id)).append('\n');
            kept.forEach(line -> out.append(line).append('\n'));
        }
        return out.toString();
    }

    /**
     * Appends the blocks of {@code ids} to the project's configuration file, created where {@code vidocq create} puts
     * it when there is none.
     *
     * @return whether the file changed
     */
    public static boolean update(Path projectDir, Collection<String> ids, String app, Reporter reporter) {
        Path file = ConfigFile.locate(projectDir, Files::isRegularFile)
                .orElseGet(() -> ConfigFile.resolveTarget(projectDir, Files::isDirectory));
        try {
            String text = Files.isRegularFile(file) ? Files.readString(file) : "";
            String updated = append(text, ids, app);
            if (updated.equals(text)) {
                return false;
            }
            Files.writeString(file, updated);
            reporter.success("vidocq.properties completed", "the settings of " + String.join(", ", ids)
                    + ", to review: " + projectDir.toAbsolutePath().relativize(file.toAbsolutePath()));
            return true;
        } catch (IOException e) {
            reporter.warning("Could not update " + file.getFileName() + ": " + e.getMessage());
            return false;
        }
    }

    /** The comment that opens the block of {@code id}, by which a second run finds it. */
    private static String header(String id) {
        return "# " + id + ": settings written by the Vidocq CLI, yours to edit";
    }

    /** The key of a {@code key=value} line, or of a commented {@code #key=value} one; empty for a plain comment. */
    private static String key(String line) {
        String body = line.startsWith("#") ? line.substring(1) : line;
        int equals = body.indexOf('=');
        if (equals <= 0 || body.substring(0, equals).contains(" ")) {
            return "";
        }
        return body.substring(0, equals).strip();
    }
}
