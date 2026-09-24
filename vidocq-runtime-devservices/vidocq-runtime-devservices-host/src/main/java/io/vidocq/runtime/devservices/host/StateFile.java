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
package io.vidocq.runtime.devservices.host;

import io.vidocq.runtime.devservices.spi.DevServiceState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Hand-writes {@code vidocq-dev-services.json} (spec §4.2): the keys come in the order {@code host}, {@code state},
 * {@code startedAt}, {@code services[id, image, endpoints, injected[key + value|configured]]}, and no library is
 * pulled in for this one small, fixed-shape document. Secret values never reach the file — every value written
 * goes through {@link SecretMasking} first.
 */
public final class StateFile {

    /** The state file's name, under {@code <basedir>/target}. */
    public static final String FILE_NAME = "vidocq-dev-services.json";

    /** The system property the host sets so the application can find the state file. */
    public static final String PROPERTY = "vidocq.devservices.state";

    private StateFile() {}

    /**
     * Renders the state document (spec §4.2). A secret key ({@link SecretMasking#isSecret}) is written as
     * {@code "configured": true}, never with its value; every other injected value and every endpoint goes
     * through {@link SecretMasking#withoutCredentials}.
     */
    public static String json(String host, String state, Instant startedAt, List<DevServiceState> services,
            Map<String, String> injected) {
        StringBuilder b = new StringBuilder(256);
        b.append("{\"host\":").append(str(host))
                .append(",\"state\":").append(str(state))
                .append(",\"startedAt\":").append(str(startedAt.toString()))
                .append(",\"services\":[");
        for (int i = 0; i < services.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            appendService(b, services.get(i), injected);
        }
        return b.append("]}").toString();
    }

    private static void appendService(StringBuilder b, DevServiceState s, Map<String, String> injected) {
        b.append("{\"id\":").append(str(s.id()))
                .append(",\"image\":").append(s.image() == null ? "null" : str(s.image()))
                .append(",\"endpoints\":{");
        int j = 0;
        for (Map.Entry<String, String> e : s.endpoints().entrySet()) {
            if (j++ > 0) {
                b.append(',');
            }
            b.append(str(e.getKey())).append(':').append(str(SecretMasking.withoutCredentials(e.getValue())));
        }
        b.append("},\"injected\":[");
        for (int k = 0; k < s.injectedKeys().size(); k++) {
            if (k > 0) {
                b.append(',');
            }
            String key = s.injectedKeys().get(k);
            b.append("{\"key\":").append(str(key));
            if (SecretMasking.isSecret(key)) {
                b.append(",\"configured\":true}");
            } else {
                b.append(",\"value\":").append(str(SecretMasking.withoutCredentials(injected.get(key)))).append('}');
            }
        }
        b.append("]}");
    }

    /** Writes {@code json} to {@code file} atomically: a sibling temp file, then a move — never half a file. */
    public static void write(Path file, String json) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = Files.createTempFile(file.getParent(), ".vidocq-dev-services", ".tmp");
        try {
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String str(String v) {
        if (v == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder(v.length() + 2).append('"');
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}
