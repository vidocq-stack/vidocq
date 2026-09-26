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

// Vidocq/vidocq#143: the Maven kind now writes three shared configurations per application.
Path devFile = root.resolve('.run/ServerApp.run.xml')
Path packagedFile = root.resolve('.run/ServerApp (packaged).run.xml')
Path debugFile = root.resolve('.run/ServerApp (debug).run.xml')
assert Arrays.equals(Files.readAllBytes(devFile), Files.readAllBytes(root.resolve('expected/ServerApp.txt'))) :
        'the generated Dev configuration differs from its expected/ file:\n' + devFile.toFile().text
assert Arrays.equals(Files.readAllBytes(packagedFile), Files.readAllBytes(root.resolve('expected/ServerApp (packaged).txt'))) :
        'the generated packaged configuration differs from its expected/ file:\n' + packagedFile.toFile().text
assert Arrays.equals(Files.readAllBytes(debugFile), Files.readAllBytes(root.resolve('expected/ServerApp (debug).txt'))) :
        'the generated debug configuration differs from its expected/ file:\n' + debugFile.toFile().text

String devBody = devFile.toFile().text
assert devBody.contains('type="MavenRunConfiguration" factoryName="Maven"')
assert devBody.contains('<option value="vidocq:dev" />')
assert devBody.contains('<option name="pomFileName" value="server/pom.xml" />')
assert devBody.contains('<option name="workingDirPath" value="$PROJECT_DIR$" />')
assert !devBody.contains('Maven.BeforeRunTask') : 'vidocq:dev compiles and indexes by itself'
assert !devBody.contains('MAIN_CLASS_NAME') && !devBody.contains('"Make"') : 'the IDE build is not what runs the application'

String packagedBody = packagedFile.toFile().text
assert packagedBody.contains('<option value="vidocq:run" />')
assert packagedBody.contains('name="ServerApp (packaged)" type="MavenRunConfiguration"')

String debugBody = debugFile.toFile().text
assert debugBody.contains('name="ServerApp (debug)" type="Remote"')
assert debugBody.contains('<option name="HOST" value="127.0.0.1" />')
assert debugBody.contains('<option name="PORT" value="5005" />')

String log = root.resolve('build.log').toFile().text
assert log.contains('Vidocq idea: writing Maven run configurations of 1 application(s) in ')
assert log.contains('[INFO] Vidocq idea: IntelliJ needs JDK 25 or newer as the Maven runner JRE (Settings > Build, Execution, Deployment > Build Tools > Maven > Runner), which is also the JDK the application runs on: vidocq:run forks it from the JVM running Maven.')
assert log.contains('[WARNING] Vidocq idea: vidocq.idea.jre is not set, so the run configurations written do not pin a JDK: IntelliJ runs Maven on its Maven runner JRE')
assert log.contains('Vidocq idea: 3 run configuration(s): 3 created')
assert log.contains('3 run configuration(s) in ') && log.contains('/.run are up to date.') : 'the strict check passes right after a write'
return true
