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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.security.enterprise.authentication.mechanism.http.BasicAuthenticationMechanismDefinition;
import jakarta.security.enterprise.credential.Credential;
import jakarta.security.enterprise.credential.UsernamePasswordCredential;
import jakarta.security.enterprise.identitystore.CredentialValidationResult;
import jakarta.security.enterprise.identitystore.IdentityStore;
import jakarta.servlet.annotation.HttpConstraint;
import jakarta.servlet.annotation.ServletSecurity;
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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** A CDI bean of the war: stage 2 injects it into a servlet. */
    @ApplicationScoped
    public static class Greeter {
        public String greet(String name) {
            return "hello " + name + " from CDI";
        }
    }

    /** A servlet whose collaborator comes from the CDI container of the war. */
    @WebServlet("/cdi")
    public static class InjectedServlet extends HttpServlet {
        @Inject
        Greeter greeter;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            response.setContentType("text/plain");
            response.getWriter().print(greeter.greet("kerckhoffs"));
        }
    }

    /** A servlet only the role {@code user} may call, behind the BASIC mechanism of the war. */
    @WebServlet("/secret")
    @ServletSecurity(@HttpConstraint(rolesAllowed = "user"))
    @BasicAuthenticationMechanismDefinition(realmName = "tck")
    public static class SecretServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            response.setContentType("text/plain");
            response.getWriter().print("secret for " + request.getRemoteUser());
        }
    }

    /** The identity store of the secured war: {@code reza} with the password {@code secret1}, in the role user. */
    @ApplicationScoped
    public static class OneUserStore implements IdentityStore {
        @Override
        public CredentialValidationResult validate(Credential credential) {
            return credential instanceof UsernamePasswordCredential login && login.compareTo("reza", "secret1")
                    ? new CredentialValidationResult("reza", Set.of("user"))
                    : CredentialValidationResult.INVALID_RESULT;
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
    void a_servlet_gets_its_cdi_bean_injected() throws Exception {
        var container = started();
        try {
            WebArchive war = ShrinkWrap.create(WebArchive.class, "cdi.war")
                    .addClasses(Greeter.class, InjectedServlet.class);
            HTTPContext context = container.deploy(war).getContext(HTTPContext.class);
            URI uri = URI.create("http://" + context.getHost() + ":" + context.getPort() + "/cdi/cdi");
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals("hello kerckhoffs from CDI", response.body());
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

    /**
     * A war that declares the BASIC mechanism, its qualifiers left to their default, is served through it
     * ({@code KerckhoffsDeployableContainer.mechanismOf}): a call without credentials is challenged, a call with
     * them reaches the servlet (KERCK-20261118-01).
     */
    @Test
    void a_war_declaring_the_basic_mechanism_is_authenticated_by_it() throws Exception {
        var container = started();
        try {
            WebArchive war = ShrinkWrap.create(WebArchive.class, "basic.war")
                    .addClasses(SecretServlet.class, OneUserStore.class);
            HTTPContext context = container.deploy(war).getContext(HTTPContext.class);
            URI uri = URI.create("http://" + context.getHost() + ":" + context.getPort() + "/basic/secret");
            var client = HttpClient.newHttpClient();
            String login = Base64.getEncoder().encodeToString("reza:secret1".getBytes(StandardCharsets.UTF_8));

            var challenged = client.send(HttpRequest.newBuilder(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            var authenticated = client.send(HttpRequest.newBuilder(uri).header("Authorization", "Basic " + login)
                    .GET().build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(401, challenged.statusCode());
            assertTrue(challenged.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Basic"),
                    "the challenge of the BASIC mechanism");
            assertEquals(200, authenticated.statusCode());
            assertEquals("secret for reza", authenticated.body());
            container.undeploy(war);
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
