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
Path file = root.resolve('.run/ServerApp.run.xml')
assert Arrays.equals(Files.readAllBytes(file), Files.readAllBytes(root.resolve('expected/ServerApp.txt'))) :
        'the generated Maven run configuration differs from its expected/ file:\n' + file.toFile().text

String body = file.toFile().text
assert body.contains('type="MavenRunConfiguration" factoryName="Maven"')
assert body.contains('<option value="vidocq:run" />')
assert body.contains('<option name="pomFileName" value="server/pom.xml" />')
assert body.contains('<option name="workingDirPath" value="$PROJECT_DIR$" />')
assert !body.contains('Maven.BeforeRunTask') : 'vidocq:run compiles and indexes by itself'
assert !body.contains('MAIN_CLASS_NAME') && !body.contains('"Make"') : 'the IDE build is not what runs the application'

String log = root.resolve('build.log').toFile().text
assert log.contains('Vidocq idea: writing Maven run configurations of 1 application(s) in ')
assert log.contains('[INFO] Vidocq idea: IntelliJ needs JDK 25 or newer as the Maven runner JRE (Settings > Build, Execution, Deployment > Build Tools > Maven > Runner), which is also the JDK the application runs on: vidocq:run forks it from the JVM running Maven.')
assert log.contains('[WARNING] Vidocq idea: vidocq.idea.jre is not set, so the run configurations written do not pin a JDK: IntelliJ runs Maven on its Maven runner JRE')
assert log.contains('run configuration(s) in ') && log.contains('/.run are up to date.') : 'the strict check passes right after a write'
return true
