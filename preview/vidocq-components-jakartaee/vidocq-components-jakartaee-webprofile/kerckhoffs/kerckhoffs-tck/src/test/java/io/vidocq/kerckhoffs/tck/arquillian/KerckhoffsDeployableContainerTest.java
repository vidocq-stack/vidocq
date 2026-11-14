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
package io.vidocq.kerckhoffs.tck.arquillian;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Stage 1 of the Arquillian adapter (PLAN.md section 7): a trivial war is deployed on Foy through
 * the container and fetched over HTTP.
 */
class KerckhoffsDeployableContainerTest {

    /** The one servlet of the trivial war. */
    @WebServlet("/hello")
    public static class HelloServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            response.setContentType("text/plain");
            response.getWriter().print("hello " + request.getContextPath());
        }
    }

    private static KerckhoffsDeployableContainer started() throws Exception {
        var container = new KerckhoffsDeployableContainer();
        container.setup(new KerckhoffsContainerConfiguration());
        container.start();
        return container;
    }

    @Test
    void a_trivial_war_is_deployed_and_served() throws Exception {
        var container = started();
        try {
            WebArchive war = ShrinkWrap.create(WebArchive.class, "trivial.war").addClass(HelloServlet.class);

            var metaData = container.deploy(war);
            HTTPContext context = metaData.getContext(HTTPContext.class);
            URI uri = URI.create("http://" + context.getHost() + ":" + context.getPort() + "/trivial/hello");
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals("hello /trivial", response.body());
            container.undeploy(war);
        } finally {
            container.stop();
        }
    }

    @Test
    void a_war_is_not_served_once_undeployed() throws Exception {
        var container = started();
        try {
            WebArchive war = ShrinkWrap.create(WebArchive.class, "gone.war").addClass(HelloServlet.class);
            HTTPContext context = container.deploy(war).getContext(HTTPContext.class);
            URI uri = URI.create("http://" + context.getHost() + ":" + context.getPort() + "/gone/hello");
            container.undeploy(war);

            assertFalse(reachable(uri), "an undeployed war must not answer");
        } finally {
            container.stop();
        }
    }

    @Test
    void an_archive_that_is_not_a_war_is_refused() throws Exception {
        var container = started();
        try {
            var jar = ShrinkWrap.create(org.jboss.shrinkwrap.api.spec.JavaArchive.class, "lib.jar");
            assertThrows(org.jboss.arquillian.container.spi.client.container.DeploymentException.class,
                    () -> container.deploy(jar));
        } finally {
            container.stop();
        }
    }

    private static boolean reachable(URI uri) throws InterruptedException {
        try {
            return HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
        } catch (IOException e) {
            return false;
        }
    }
}
