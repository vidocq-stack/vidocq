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
package io.vidocq.runtime.maven.dev;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VidocqDevMojoTest {

    @Test
    void addsJdwpAgentWhenDebugEnabled() {
        VidocqDevMojo mojo = new VidocqDevMojo();
        mojo.setExtraJvmArgs("");
        mojo.setDebugOptions(true, 5005, false);

        List<String> args = mojo.debugJvmArgs();

        assertEquals(List.of("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"), args);
    }

    @Test
    void honoursCustomPortAndSuspend() {
        VidocqDevMojo mojo = new VidocqDevMojo();
        mojo.setExtraJvmArgs("");
        mojo.setDebugOptions(true, 6789, true);

        assertEquals(List.of("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:6789"),
                mojo.debugJvmArgs());
    }

    @Test
    void noAgentWhenDebugDisabled() {
        VidocqDevMojo mojo = new VidocqDevMojo();
        mojo.setExtraJvmArgs("-Xmx512m");
        mojo.setDebugOptions(false, 5005, false);

        List<String> args = mojo.debugJvmArgs();

        assertEquals(List.of("-Xmx512m"), args);
        assertFalse(args.stream().anyMatch(a -> a.contains("jdwp")));
    }

    @Test
    void extraJvmArgsComeBeforeTheDebugAgent() {
        VidocqDevMojo mojo = new VidocqDevMojo();
        mojo.setExtraJvmArgs("-Xmx256m -XX:+UseZGC");
        mojo.setDebugOptions(true, 5005, false);

        List<String> args = mojo.debugJvmArgs();

        assertEquals("-Xmx256m", args.get(0));
        assertEquals("-XX:+UseZGC", args.get(1));
        assertTrue(args.get(2).startsWith("-agentlib:jdwp="));
    }
}
