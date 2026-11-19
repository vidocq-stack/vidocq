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
import java.lang.module.ModuleDescriptor
import java.util.concurrent.TimeUnit

assert new File(basedir, 'target/classes/META-INF/peano/accessor-providers/consumer.Model__PeanoAccessors.txt').isFile()
def descriptor=ModuleDescriptor.read(java.nio.ByteBuffer.wrap(Files.readAllBytes(new File(basedir,'target/classes/module-info.class').toPath())))
assert descriptor.opens().isEmpty()
assert descriptor.exports().isEmpty()
assert descriptor.provides().any { it.service()=='io.vidocq.peano.spi.AccessorProvider' }
def modules=new File(basedir,'target/modules').absolutePath+File.pathSeparator+new File(basedir,'target/classes').absolutePath
for (String mode:['allow','deny']) {
    def log=new File(basedir,'target/consumer-'+mode+'.log')
    def process=new ProcessBuilder(new File(System.getProperty('java.home'),'bin/java').absolutePath,
        '-Dpeano.reflection='+mode,'--module-path',modules,'--module','consumer.peano/consumer.Main')
        .redirectErrorStream(true).redirectOutput(log).start()
    assert process.waitFor(60,TimeUnit.SECONDS)
    assert process.exitValue()==0 : log.text
    assert log.text.contains('PEANO_MAVEN_ACCESSORS_OK')
}
assert new File(basedir,'build.log').text.contains('Peano accessor providers registered: 1')
return true
