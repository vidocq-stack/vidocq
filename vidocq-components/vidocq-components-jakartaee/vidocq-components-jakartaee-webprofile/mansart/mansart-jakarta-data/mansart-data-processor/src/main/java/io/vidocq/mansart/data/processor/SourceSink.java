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
package io.vidocq.mansart.data.processor;

import java.io.IOException;
import java.io.Writer;

/**
 * Abstraction over the destination of a generated {@code .java} source, decoupling the code
 * emitters ({@link MansartMetamodelWriter}, {@link RepositoryWriter}, {@link JpaMetamodelWriter})
 * from the annotation-processing {@link javax.annotation.processing.Filer}.
 *
 * <p>Inside the APT this is backed by {@link FilerSourceSink}. The {@code mansart-data-maven-plugin}
 * supplies a directory-backed implementation so the very same emitters can generate ahead-of-time
 * implementations for {@code @Repository} interfaces that live in pre-compiled dependency jars
 * (where APT never ran). This is the entry point for {@link ExternalRepositoryCodegen}.
 */
@FunctionalInterface
public interface SourceSink {

    /**
     * Opens a writer for a new compilation unit named {@code fqn} (e.g. {@code com.acme._Book}).
     * The caller closes the writer.
     */
    Writer createSource(String fqn) throws IOException;
}
