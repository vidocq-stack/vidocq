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
package io.vidocq.grimm.internal.reader;

import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where {@link StaticFileReader} looks for the static file (spec §4.2 static files): an explicit
 * document installed by the deployment harness, the deployment class loader, then the class
 * loaders of the known deployment types.
 */
class StaticFileReaderSourcesTest {

    private static final String YAML_PATH = "META-INF/openapi.yaml";
    private static final String TITLE = "explicit";
    private static final String EXPLICIT_YAML = "openapi: 3.1.0\ninfo:\n  title: explicit\n";

    /** A class loader that sees nothing at all, not even its parent's resources. */
    private static final class EmptyLoader extends ClassLoader {
        private EmptyLoader() {
            super(null);
        }
    }

    /** A class loader whose static file cannot be read. */
    private static final class FailingLoader extends ClassLoader {
        private FailingLoader() {
            super(null);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    throw new IOException("disk error");
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    throw new IOException("disk error");
                }
            };
        }
    }

    @AfterEach
    void clearOverride() {
        StaticFileReader.clearExplicitDocument();
    }

    private static <T> T withContextClassLoader(ClassLoader classLoader, Supplier<T> supplier) {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        thread.setContextClassLoader(classLoader);
        try {
            return supplier.get();
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    private static String title(Optional<OpenAPI> document) {
        return document.orElseThrow().getInfo().getTitle();
    }

    @Test
    void explicitYamlDocumentWinsOverTheClassPath() {
        StaticFileReader.setExplicitDocument(YAML_PATH, EXPLICIT_YAML);
        StaticFileReader reader = new StaticFileReader();

        assertEquals(TITLE, title(reader.readOpenAPI()));
        assertEquals(TITLE, title(reader.readOpenAPI(List.of(StaticFileReaderSourcesTest.class))));
    }

    @Test
    void explicitJsonAndYmlDocuments() {
        StaticFileReader reader = new StaticFileReader();

        StaticFileReader.setExplicitDocument("META-INF/openapi.json", "{\"info\": {\"title\": \"json\"}}");
        assertEquals("json", title(reader.readOpenAPI()));
        StaticFileReader.setExplicitDocument("META-INF/openapi.yml", "info:\n  title: yml\n");
        assertEquals("yml", title(reader.readOpenAPI()));
    }

    @Test
    void explicitDocumentOfAnUnsupportedFormatIsRejected() {
        StaticFileReader.setExplicitDocument("META-INF/openapi.txt", "info: {}");
        StaticFileReader reader = new StaticFileReader();

        assertThrows(IllegalArgumentException.class, reader::readOpenAPI);
    }

    @Test
    void nullContentOrPathClearsTheExplicitDocument() {
        StaticFileReader reader = new StaticFileReader();

        StaticFileReader.setExplicitDocument(YAML_PATH, EXPLICIT_YAML);
        StaticFileReader.setExplicitDocument(YAML_PATH, null);
        assertFalse(withContextClassLoader(new EmptyLoader(), reader::readOpenAPI).isPresent());
        StaticFileReader.setExplicitDocument(YAML_PATH, EXPLICIT_YAML);
        StaticFileReader.setExplicitDocument(null, EXPLICIT_YAML);
        assertFalse(withContextClassLoader(new EmptyLoader(), reader::readOpenAPI).isPresent());
    }

    @Test
    void withoutAContextClassLoaderTheReaderOwnLoaderIsSearched() {
        StaticFileReader reader = new StaticFileReader();

        // The test class path holds META-INF/openapi.yaml.
        assertTrue(withContextClassLoader(null, reader::readOpenAPI).isPresent());
    }

    @Test
    void knownTypeLoaderIsSearchedAfterTheContextClassLoader() {
        StaticFileReader reader = new StaticFileReader();

        Optional<OpenAPI> found = withContextClassLoader(new EmptyLoader(),
                () -> reader.readOpenAPI(Arrays.asList(null, String.class, StaticFileReaderSourcesTest.class)));

        assertTrue(found.isPresent());
    }

    @Test
    void nothingFoundWithoutKnownTypes() {
        StaticFileReader reader = new StaticFileReader();

        assertFalse(withContextClassLoader(new EmptyLoader(), () -> reader.readOpenAPI(null)).isPresent());
        assertFalse(withContextClassLoader(null, () -> reader.readOpenAPI(Arrays.asList(null, String.class)))
                .isPresent());
    }

    @Test
    void unreadableFileIsReported() {
        StaticFileReader reader = new StaticFileReader();
        ClassLoader failing = new FailingLoader();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> withContextClassLoader(failing, reader::readOpenAPI));
        assertTrue(error.getMessage().contains(YAML_PATH), error.getMessage());
    }

    @Test
    void unreadableFileOfAKnownTypeLoaderIsReported() {
        StaticFileReader reader = new StaticFileReader();
        List<Class<?>> types = List.of(StaticFileReaderSourcesTest.class);
        ClassLoader failing = new FailingLoader();

        assertThrows(IllegalArgumentException.class,
                () -> withContextClassLoader(failing, () -> reader.readOpenAPI(types)));
    }
}
