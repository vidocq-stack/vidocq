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
package io.vidocq.runtime.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The stamp {@code vidocq:generate} writes into the application's classes, {@value #PATH}, recording the bean
 * index ({@value #INDEX}) it leaves there. That index holds the beans of the dependencies compiled without
 * Vauban's processor, which nothing else indexes. An application whose module lacks the stamp was built without
 * the goal, as an IDE builds it; one whose index no longer matches the stamp had it rewritten since, as an IDE's
 * incremental build rewrites it through the annotation processor. In both cases the boot scans the unindexed bean
 * archives itself (Vidocq/vidocq#241).
 */
public final class GenerateStamp {

    /** Where the stamp lives in the application's classes. */
    public static final String PATH = "META-INF/vidocq/generate.stamp";

    /** The bean index the stamp records. */
    public static final String INDEX = "META-INF/vauban-beans.list";

    private GenerateStamp() {
    }

    /**
     * The stamp for {@code index}, the bytes of {@value #INDEX} as {@code vidocq:generate} leaves it, or
     * {@code null} when it leaves none. It depends on them only, so that the build stays reproducible.
     */
    public static String content(byte[] index) {
        return "vidocq:generate\nindex=" + (index == null ? "none" : "sha256:" + sha256(index)) + "\n";
    }

    /**
     * Whether {@code application}, a named module, lacks the stamp, or holds an index the stamp does not record.
     * {@code null} or an unnamed module tells nothing, and answers {@code false}: the boot then changes nothing.
     */
    static boolean staleIn(Module application) {
        if (application == null || !application.isNamed()) {
            return false;
        }
        try {
            byte[] stamp = read(application, PATH);
            return stamp == null || !content(read(application, INDEX)).equals(new String(stamp, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] read(Module module, String path) throws IOException {
        try (InputStream in = module.getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a mandatory JDK algorithm", e);
        }
    }
}
