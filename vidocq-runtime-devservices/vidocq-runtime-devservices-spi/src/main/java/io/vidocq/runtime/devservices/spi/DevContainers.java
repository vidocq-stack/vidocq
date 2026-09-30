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
package io.vidocq.runtime.devservices.spi;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * The Docker name and labels of a container a {@link DevService} starts, so {@code docker ps} tells which ones are
 * Vidocq's and whose: {@code <prefix><application>-<service>[-<qualifier>]-<suffix>}, such as
 * {@code vidocq-dev-mcp-tasks-server-postgres-3f9a2c01}.
 *
 * <p>The prefix is {@code vidocq.dev.container-prefix}, {@code vidocq-dev-} by default; the application is the name
 * of the project base directory. A Docker name is unique on the machine, so the suffix keeps two applications, two
 * datasources, or a {@code vidocq:dev} session and the tests of the same project apart: random for a container that
 * is not reused, and for a reused one ({@code vidocq.dev.reuse}) a digest of the application's path, the service, the
 * qualifier and its configuration — Testcontainers finds a reused container by a hash that covers its name, so the
 * name must stay the same while the configuration does, and change with it.</p>
 */
public final class DevContainers {

    /** The property that replaces {@link #DEFAULT_PREFIX}. */
    public static final String PREFIX_PROPERTY = "vidocq.dev.container-prefix";

    /** The prefix of every name unless {@link #PREFIX_PROPERTY} says otherwise. */
    public static final String DEFAULT_PREFIX = "vidocq-dev-";

    /** What Docker accepts as a container name. */
    private static final Pattern DOCKER_NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]*");

    private DevContainers() {
    }

    /**
     * The name of a container.
     *
     * @param ctx       the context of the provider starting it
     * @param service   the provider's {@link DevService#id() id}, such as {@code postgres}
     * @param qualifier what tells two containers of one provider apart, such as a datasource name; {@code null} or
     *                  blank for none
     * @param reuseKey  for a reused container, everything its configuration holds, such as its image and database;
     *                  {@code null} when it is not reused
     * @return the name
     * @throws IllegalArgumentException when {@link #PREFIX_PROPERTY} is not the start of a Docker name
     */
    public static String name(DevServiceContext ctx, String service, String qualifier, String reuseKey) {
        String prefix = ctx.property(PREFIX_PROPERTY).orElse(DEFAULT_PREFIX).strip();
        if (!DOCKER_NAME.matcher(prefix).matches()) {
            throw new IllegalArgumentException(PREFIX_PROPERTY + " \"" + prefix + "\" cannot start a Docker container"
                    + " name: it takes letters, digits, '_', '.' and '-', and starts with a letter or a digit.");
        }
        StringBuilder name = new StringBuilder(prefix).append(application(ctx)).append('-').append(part(service));
        if (qualifier != null && !qualifier.isBlank()) {
            name.append('-').append(part(qualifier));
        }
        return name.append('-').append(suffix(ctx, service, qualifier, reuseKey)).toString();
    }

    /**
     * The labels of a container: {@code io.vidocq.dev=true}, so {@code docker ps --filter label=io.vidocq.dev} lists
     * every one of them, {@code io.vidocq.dev.app} and {@code io.vidocq.dev.service}.
     *
     * @param ctx     the context of the provider starting it
     * @param service the provider's {@link DevService#id() id}
     * @return the labels, in that order
     */
    public static Map<String, String> labels(DevServiceContext ctx, String service) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("io.vidocq.dev", "true");
        labels.put("io.vidocq.dev.app", application(ctx));
        labels.put("io.vidocq.dev.service", service);
        return labels;
    }

    private static String application(DevServiceContext ctx) {
        Path directory = absolute(ctx).getFileName();
        return directory == null ? "app" : part(directory.toString());
    }

    private static Path absolute(DevServiceContext ctx) {
        return ctx.basedir().toAbsolutePath().normalize();
    }

    /** Lower case, every run of what Docker refuses as one {@code -}, no {@code -} at either end. */
    private static String part(String text) {
        String part = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "-").replaceAll("^[-_.]+|-+$", "");
        return part.isEmpty() ? "x" : part;
    }

    private static String suffix(DevServiceContext ctx, String service, String qualifier, String reuseKey) {
        if (reuseKey == null) {
            return HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
        }
        String identity = absolute(ctx) + "\n" + service + "\n" + (qualifier == null ? "" : qualifier) + "\n" + reuseKey;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }
}
