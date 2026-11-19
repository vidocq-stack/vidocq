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

import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Removes extensions from the project, undoing {@code vidocq:add-extension}: the dependency, the codegen
 * bundle's processor path and the {@code module-info.java} directives it marked, as
 * {@code vidocq extension remove} does (Vidocq/vidocq#314):
 *
 * <pre>mvn vidocq:remove-extension -Dextensions=knock-health</pre>
 *
 * <p>Has no default phase. It edits the project it runs on, once.</p>
 */
@Mojo(name = "remove-extension",
      aggregator = true,
      requiresDependencyResolution = ResolutionScope.NONE,
      threadSafe = true)
public class VidocqRemoveExtensionMojo extends AbstractExtensionEditMojo {

    @Override
    boolean add() {
        return false;
    }
}
