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
package io.vidocq.runtime.cli.ext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the list of available extensions with graceful degradation:
 * <ol>
 *   <li>fetch the remote registry JSON (injected {@link Fetcher}); on success it is
 *       cached and parsed;</li>
 *   <li>otherwise read the on-disk cache file;</li>
 *   <li>otherwise fall back to the built-in {@link KnownExtensions} catalog.</li>
 * </ol>
 *
 * <p>I/O is confined to an injected {@link Fetcher} and the cache {@link Path}, so the
 * whole strategy is unit-testable with a fake fetcher and a temp directory.</p>
 */
public final class ExtensionRegistry {

    /** Source of the raw registry JSON. Returns empty when offline / on error. */
    @FunctionalInterface
    public interface Fetcher {
        Optional<String> fetch();
    }

    private final Fetcher fetcher;
    private final Path cacheFile;
    private final List<RegistryEntry> fallback;

    public ExtensionRegistry(Fetcher fetcher, Path cacheFile, List<RegistryEntry> fallback) {
        this.fetcher = fetcher;
        this.cacheFile = cacheFile;
        this.fallback = List.copyOf(fallback);
    }

    /** Where the resolved list came from — surfaced to the user for transparency. */
    public enum Origin { REMOTE, CACHE, CATALOG }

    public record Listing(List<RegistryEntry> entries, Origin origin) {}

    public Listing list() {
        Optional<String> remote = safeFetch();
        if (remote.isPresent()) {
            List<RegistryEntry> entries = tryParse(remote.get());
            if (!entries.isEmpty()) {
                writeCache(remote.get());
                return new Listing(entries, Origin.REMOTE);
            }
        }
        if (cacheFile != null && Files.isRegularFile(cacheFile)) {
            try {
                List<RegistryEntry> cached = tryParse(Files.readString(cacheFile));
                if (!cached.isEmpty()) {
                    return new Listing(cached, Origin.CACHE);
                }
            } catch (IOException ignored) {
                // fall through to catalog
            }
        }
        return new Listing(fallback, Origin.CATALOG);
    }

    private Optional<String> safeFetch() {
        try {
            Optional<String> body = fetcher.fetch();
            return body == null ? Optional.empty() : body;
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Parses registry JSON into entries, skipping any object missing required fields. */
    static List<RegistryEntry> parse(String json) {
        List<RegistryEntry> entries = new ArrayList<>();
        for (Map<String, String> obj : MiniJson.parseArrayOfObjects(json)) {
            String id = obj.get("id");
            String groupId = obj.get("groupId");
            String artifactId = obj.get("artifactId");
            String description = obj.getOrDefault("description", "");
            if (id == null || id.isBlank()
                    || groupId == null || groupId.isBlank()
                    || artifactId == null || artifactId.isBlank()) {
                continue;
            }
            entries.add(new RegistryEntry(id, groupId, artifactId, description));
        }
        return entries;
    }

    private static List<RegistryEntry> tryParse(String json) {
        try {
            return parse(json);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private void writeCache(String body) {
        if (cacheFile == null) {
            return;
        }
        try {
            Path parent = cacheFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(cacheFile, body);
        } catch (IOException ignored) {
            // caching is best-effort
        }
    }
}
