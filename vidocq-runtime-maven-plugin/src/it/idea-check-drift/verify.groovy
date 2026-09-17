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
String log = root.resolve('build.log').toFile().text
assert log.contains('[ERROR] Vidocq idea: .run/Drift.run.xml is out of date for com.example.it:check-drift:')
assert log.contains('[ERROR]   -     <option name="MAIN_CLASS_NAME" value="com.example.drift.OldApp" />')
assert log.contains('[ERROR]   +     <option name="MAIN_CLASS_NAME" value="com.example.drift.DriftApp" />')
assert log.contains('file="$PROJECT_DIR$/pom.xml"') : 'a root application points at the root pom'
assert log.contains('1 of 1 run configuration(s) in .run/ do not match the Maven projects.')
assert Arrays.equals(Files.readAllBytes(root.resolve('.run/Drift.run.xml')), Files.readAllBytes(root.resolve('seed/Drift.txt'))) :
        'check mode writes nothing'
return true
