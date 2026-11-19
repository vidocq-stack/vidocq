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
package io.vidocq.runtime.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * The CycloneDX SBOM of a runtime distribution ({@code vidocq:package}, {@code vidocq:jlink}), written from the jars
 * the distribution ships, with the SHA-256 of each file as it ships (an enriched or modularized copy is not the jar
 * on Central). Vidocq/vidocq#270. No dependency and no clock: two builds of the same inputs write the same file.
 */
final class DistributionSbom {

    /** The application the distribution is built from. */
    record Subject(String groupId, String artifactId, String version) {}

    /** A jar of the distribution, with the Maven coordinates of the artifact it comes from. */
    record Component(String groupId, String artifactId, String version, Path jar) {}

    private DistributionSbom() {
    }

    /** Writes the SBOM of {@code subject} and its shipped {@code components} to {@code out}, creating its directory. */
    static void write(Path out, Subject subject, List<Component> components) throws IOException {
        StringBuilder json = new StringBuilder(1024);
        String subjectPurl = purl(subject.groupId(), subject.artifactId(), subject.version());
        json.append("{\n")
                .append("  \"bomFormat\": \"CycloneDX\",\n")
                .append("  \"specVersion\": \"1.6\",\n")
                .append("  \"serialNumber\": \"urn:uuid:")
                .append(UUID.nameUUIDFromBytes(subjectPurl.getBytes(StandardCharsets.UTF_8))).append("\",\n")
                .append("  \"version\": 1,\n")
                .append("  \"metadata\": {\n    \"component\": ");
        component(json, "application", subject.groupId(), subject.artifactId(), subject.version(), null, "    ");
        json.append("\n  },\n  \"components\": [");
        for (int i = 0; i < components.size(); i++) {
            Component c = components.get(i);
            json.append(i == 0 ? "\n    " : ",\n    ");
            component(json, "library", c.groupId(), c.artifactId(), c.version(), sha256(c.jar()), "    ");
        }
        json.append(components.isEmpty() ? "]\n}\n" : "\n  ]\n}\n");
        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(out, json, StandardCharsets.UTF_8);
    }

    private static void component(StringBuilder json, String type, String group, String name, String version,
            String sha256, String indent) {
        String purl = purl(group, name, version);
        json.append("{\n")
                .append(indent).append("  \"type\": \"").append(type).append("\",\n")
                .append(indent).append("  \"bom-ref\": ").append(quote(purl)).append(",\n")
                .append(indent).append("  \"group\": ").append(quote(group)).append(",\n")
                .append(indent).append("  \"name\": ").append(quote(name)).append(",\n")
                .append(indent).append("  \"version\": ").append(quote(version)).append(",\n");
        if (sha256 != null) {
            json.append(indent).append("  \"hashes\": [{\"alg\": \"SHA-256\", \"content\": \"").append(sha256)
                    .append("\"}],\n");
        }
        json.append(indent).append("  \"purl\": ").append(quote(purl)).append('\n')
                .append(indent).append('}');
    }

    private static String purl(String group, String name, String version) {
        return "pkg:maven/" + group + "/" + name + "@" + version;
    }

    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JDK", e);
        }
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
