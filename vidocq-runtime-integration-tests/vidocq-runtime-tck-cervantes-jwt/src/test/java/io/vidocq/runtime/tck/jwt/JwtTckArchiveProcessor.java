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
package io.vidocq.runtime.tck.jwt;

import org.jboss.arquillian.container.test.spi.client.deployment.ApplicationArchiveProcessor;
import org.jboss.arquillian.test.spi.TestClass;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ArchivePath;
import org.jboss.shrinkwrap.api.Node;
import org.jboss.shrinkwrap.api.asset.StringAsset;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TCK glue: the MP JWT TCK generates each deployment's
 * {@code microprofile-config.properties} with self-referential URLs hardcoded
 * to {@code http://localhost:8080/} (JWKS endpoints,
 * {@code mp.jwt.verify.publickey.location}, {@code mp.jwt.decrypt.key.location},
 * {@code mp.jwt.tck.jwks.baseURL}). The embedded Vidocq container serves the
 * deployment at the pinned host/port from arquillian.xml, so those URLs are
 * rewritten before deployment — vendor-side plumbing the TCK expects, the
 * runtime under test is not modified.
 */
public class JwtTckArchiveProcessor implements ApplicationArchiveProcessor {

    static final String TCK_BASE_URL = "http://localhost:8080/";
    static final String CONTAINER_BASE_URL = "http://127.0.0.1:18086/";

    @Override
    public void process(Archive<?> archive, TestClass testClass) {
        Map<ArchivePath, String> rewritten = new LinkedHashMap<>();
        for (Map.Entry<ArchivePath, Node> entry : archive.getContent().entrySet()) {
            String path = entry.getKey().get();
            if (path == null || !path.endsWith("microprofile-config.properties")) {
                continue;
            }
            Node node = entry.getValue();
            if (node == null || node.getAsset() == null) {
                continue;
            }
            try (InputStream in = node.getAsset().openStream()) {
                String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                if (content.contains(TCK_BASE_URL)) {
                    // Path segments after the base (e.g. jwks/endp/...) are the
                    // deployment's @ApplicationPath + resource path, served as-is
                    // by the runtime — only the host:port needs rewriting.
                    rewritten.put(entry.getKey(), content.replace(TCK_BASE_URL, CONTAINER_BASE_URL));
                }
            } catch (IOException e) {
                throw new IllegalStateException("Cannot rewrite " + path, e);
            }
        }
        for (Map.Entry<ArchivePath, String> entry : rewritten.entrySet()) {
            archive.delete(entry.getKey());
            archive.add(new StringAsset(entry.getValue()), entry.getKey());
        }
    }
}
