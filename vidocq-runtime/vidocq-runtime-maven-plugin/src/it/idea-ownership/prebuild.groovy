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
import java.nio.file.Files
import java.nio.file.Path

// The run configurations that are already in .run/ before the first invocation. They are kept under seed/ with
// a .txt name: a committed *.run.xml would be loaded by IntelliJ when the vidocq repository itself is opened.
Path root = basedir.toPath()
Path run = Files.createDirectories(root.resolve('.run'))
Files.list(root.resolve('seed')).withCloseable { seeds ->
    seeds.each { seed ->
        Files.copy(seed, run.resolve(seed.fileName.toString().replaceFirst(/\.txt$/, '.run.xml')))
    }
}
return true
