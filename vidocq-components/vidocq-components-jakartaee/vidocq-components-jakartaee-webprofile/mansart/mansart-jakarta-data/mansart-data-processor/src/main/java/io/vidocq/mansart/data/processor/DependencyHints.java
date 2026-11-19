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

import java.util.List;

/**
 * What a build that runs this processor over the compiled classes of a dependency jar needs to know (mansart#40,
 * Vidocq/vidocq#246): which jars to select, how to tell a jar already processed, and which index to merge. The
 * Vidocq codegen bundle of Mansart declares the same values in {@code META-INF/vidocq/dependency-codegen.properties};
 * a test there keeps both in step.
 */
public final class DependencyHints {

    /**
     * Strings whose presence in a class file selects the jar, and the classes the processor is given: the
     * {@code @Repository} interfaces. Their entities' metamodels are generated with them.
     */
    public static final List<String> CLASS_BYTE_HINTS = List.of("jakarta/data/repository/Repository");

    /** Written by the processor when it generated something without an error. */
    public static final String MARKER = "META-INF/io.vidocq.mansart/generated";

    /** The repository index the CDI extension reads; one per jar, merged across a jar's processing. */
    public static final String INDEX = "META-INF/mansart-repositories.list";

    private DependencyHints() {
    }
}
