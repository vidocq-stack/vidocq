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
package io.vidocq.noether.jpa.core.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import io.vidocq.noether.jpa.core.model.xml.MappingFile;
import jakarta.persistence.PersistenceUnitTransactionType;
import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.ValidationMode;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The mapping files of a unit whose root is a module of the runtime image. jlink links the application module into
 * the image, and the root of a unit it declares is then {@code jrt:/<module>/}: a directory of the image's file
 * system, not an archive (BUG-20261123-01, the mapping-file half of BUG-20261113-02).
 */
class MappingFilesTest {

    /** A module of the image, which has no {@code META-INF/orm.xml}. */
    private static final String MODULE_ROOT = "jrt:/java.transaction.xa/";

    @Test
    void aRootInTheRuntimeImageWithoutOrmXmlHasNoMappingFile() throws Exception {
        URL root = new URI(MODULE_ROOT).toURL();

        assertThat(MappingFiles.of(unit(root, List.of()), loader())).isEmpty();
    }

    /** A listed file that the root does not have is looked up on the unit's class loader, as for any root. */
    @Test
    void aListedMappingFileMissingFromAnImageRootIsFoundOnTheClassLoader() throws Exception {
        URL root = new URI(MODULE_ROOT).toURL();

        List<MappingFile> files = MappingFiles.of(unit(root, List.of("orm/units.xml")), loader());

        assertThat(files).extracting(MappingFile::name).containsExactly("orm/units.xml");
    }

    /** An entry of an image module is a {@code jrt:} URL of that entry, which reads as any resource. */
    @Test
    void anEntryOfAnImageRootIsFoundInTheModule() throws Exception {
        URL root = new URI("jrt:/java.base/").toURL();

        List<MappingFile> files = MappingFiles.of(unit(root, List.of("java/lang/Object.class")), loader());

        assertThat(files).extracting(file -> file.url().toExternalForm())
            .containsExactly("jrt:/java.base/java/lang/Object.class");
    }

    private static ClassLoader loader() {
        return MappingFilesTest.class.getClassLoader();
    }

    private static PersistenceUnitDefinition unit(URL root, List<String> mappingFiles) {
        return new PersistenceUnitDefinition("unit", null, null, PersistenceUnitTransactionType.RESOURCE_LOCAL, null, null,
            mappingFiles, List.of(), List.of(), true, SharedCacheMode.UNSPECIFIED, ValidationMode.AUTO, Map.of(), root,
            "3.2", List.of(), null, loader(), List.of());
    }
}
