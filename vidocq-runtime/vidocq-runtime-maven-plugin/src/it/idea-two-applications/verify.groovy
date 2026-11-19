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
Path run = root.resolve('.run')
List<String> names = Files.list(run).withCloseable { s -> s.map { it.fileName.toString() }.sorted().toList() }
assert names == ['AlphaApp.run.xml', 'Beta server.run.xml'] : "files in .run/: $names"
names.each { name ->
    assert Arrays.equals(Files.readAllBytes(run.resolve(name)), Files.readAllBytes(root.resolve('expected').resolve(name.replace('.run.xml', '.txt')))) :
            "$name differs from its expected/ file:\n" + run.resolve(name).toFile().text
}
assert !Files.exists(root.resolve('alpha/.run')) : 'the refused run inside a module wrote nothing'
assert !Files.exists(root.resolve('.idea')) && !Files.exists(root.resolve('alpha/.idea'))
Files.walk(root).withCloseable { s -> assert s.noneMatch { it.fileName.toString().endsWith('.iml') } }

String log = root.resolve('build.log').toFile().text
assert log.contains('Vidocq idea: 2 run configuration(s): 2 created')
assert log.contains('[WARNING] Vidocq idea: vidocq.idea.jre is not set, so the run configurations written do not pin a JDK') :
        'a configuration without a pinned JDK is a warning'
assert log.contains('run configuration(s) in ') && log.contains('/.run are up to date.')
assert log.contains('alpha is a module of ') : 'invocation 3 fails because alpha is a module of the root reactor'
return true
