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
package io.vidocq.humboldt.tck.arquillian;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.testng.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.testng.annotations.Test;

import static org.testng.Assert.assertTrue;

/**
 * Smoke test M7b.4b.1 — verifies that the Arquillian → HumboldtDeployableContainer
 * chain starts without errors:
 * <ol>
 *   <li>{@link HumboldtLoadableExtension} is loaded through {@code META-INF/services}</li>
 *   <li>{@link HumboldtDeployableContainer} is registered and setup/start are executed</li>
 *   <li>{@link HumboldtDeployableContainer#deploy(org.jboss.shrinkwrap.api.Archive)}
 *       is invoked with the {@link #deployment()} archive</li>
 *   <li>The test itself runs (proof that the Local protocol returns
 *       sufficient {@code ProtocolMetaData})</li>
 *   <li>undeploy + stop in teardown</li>
 * </ol>
 *
 * <p>No CDI/HTTP verification yet — those steps will come in M7b.4b.2 and later.</p>
 */
public class HumboldtArquillianBootSmokeTest extends Arquillian {

    @Deployment
    public static JavaArchive deployment() {
        return ShrinkWrap.create(JavaArchive.class, "humboldt-arq-boot-smoke.jar")
                .addClass(HumboldtArquillianBootSmokeTest.class);
    }

    @Test
    public void container_boots_and_executes_in_test_method() {
        // If we get here, it means:
        //  - Arquillian started
        //  - HumboldtDeployableContainer.start() succeeded
        //  - HumboldtDeployableContainer.deploy(Archive) returned valid ProtocolMetaData
        //  - The test was invoked by the Local protocol
        // → the M7b.4b.1 skeleton is working.
        assertTrue(true, "Boot chain Arquillian → Humboldt container OK");
    }
}
