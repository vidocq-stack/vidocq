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

Path root = basedir.toPath()
List<String> names = Files.list(root.resolve('.run')).withCloseable { s -> s.map { it.fileName.toString() }.sorted().toList() }
// Vidocq/vidocq#143: the default (Maven) kind writes three files for beta, none for excluded alpha.
assert names == ['BetaApp (debug).run.xml', 'BetaApp (packaged).run.xml', 'BetaApp.run.xml'] : "files in .run/: $names"

String log = root.resolve('build.log').toFile().text
// 1. alpha is the top-level project and leaves only itself out.
assert log.contains('[INFO] Vidocq idea: partial build (-pl, -rf or similar)')
assert log.contains('[INFO] Vidocq idea: com.example.it:alpha excluded (vidocq.idea.exclude=true in its pom)')
assert log.contains('[WARNING] Vidocq idea: com.example.it:beta sets vidocq.idea.skip=true in its pom, which does not leave a module out')
assert log.contains('[INFO] Vidocq idea: created .run/BetaApp.run.xml (com.example.it:beta, main class com.example.beta.BetaApp, IntelliJ module beta)')
// 2. The check from the root.
assert log.contains('run configuration(s) in ') && log.contains('/.run are up to date.')
// 3. and 4. Each skip names its source; nothing else was skipped.
assert log.contains('[INFO] Vidocq idea: skipped (vidocq.idea.skip=true in the properties of com.example.it:beta, the top-level project of this build)')
assert log.contains('[INFO] Vidocq idea: skipped (-Dvidocq.idea.skip=true on the command line)')
assert log.count('Vidocq idea: skipped') == 2 : log
return true
