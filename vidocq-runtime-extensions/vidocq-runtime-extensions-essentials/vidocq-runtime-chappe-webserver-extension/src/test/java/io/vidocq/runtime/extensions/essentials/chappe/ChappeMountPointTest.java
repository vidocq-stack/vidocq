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
package io.vidocq.runtime.extensions.essentials.chappe;

import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ChappeMountPointTest {

    private ChappeMountPoint mp;

    @BeforeEach
    void setUp() {
        mp = new ChappeMountPoint();
        ChappeMountPoint.install(mp);
    }

    @AfterEach
    void tearDown() {
        ChappeMountPoint.uninstall();
    }

    @Test
    void instanceReturnsInstalledSingleton() {
        assertSame(mp, ChappeMountPoint.instance());
    }

    @Test
    void instanceThrowsWhenNotInstalled() {
        ChappeMountPoint.uninstall();
        assertThrows(IllegalStateException.class, ChappeMountPoint::instance);
    }

    @Test
    void mountWithoutListenerTargetsDefault() {
        Handler h = _ -> Response.ok("hi");
        mp.mount("/api", h);
        assertNotNull(mp.buildRouter(ChappeListener.DEFAULT));
    }

    @Test
    void mountOnNamedListenerStoresSeparately() {
        mp.mount("admin", "/a", _ -> Response.ok("a"));
        mp.mount(ChappeListener.DEFAULT, "/d", _ -> Response.ok("d"));
        assertNotNull(mp.buildRouter("admin"));
        assertNotNull(mp.buildRouter(ChappeListener.DEFAULT));
    }

    @Test
    void routerBuilderExposedForFineContribution() {
        mp.router(ChappeListener.DEFAULT).get("/ping", _ -> Response.ok("pong"));
        assertNotNull(mp.buildRouter(ChappeListener.DEFAULT));
    }

    @Test
    void declareListenerRegistersAndPrepares() {
        mp.declareListener(ChappeListener.http("admin", "127.0.0.1", 9090));
        assertEquals(1, mp.listeners().size());
        assertNotNull(mp.buildRouter("admin"));
    }

    @Test
    void freezeBlocksFurtherContributions() {
        mp.freeze();
        assertThrows(IllegalStateException.class,
                () -> mp.mount("/x", _ -> Response.ok("x")));
        assertThrows(IllegalStateException.class,
                () -> mp.router("default"));
        assertThrows(IllegalStateException.class,
                () -> mp.addBeforeStartHook(() -> {}));
        assertThrows(IllegalStateException.class,
                () -> mp.declareListener(ChappeListener.http("x", "0.0.0.0", 1234)));
        assertThrows(IllegalStateException.class,
                () -> mp.declareListener(ChappeListener.http("y", "127.0.0.1", 0), ListenerOptions.DEFAULTS));
    }

    @Test
    void beforeStartHooksRunInRegistrationOrder() {
        AtomicInteger counter = new AtomicInteger();
        int[] marks = new int[2];
        mp.addBeforeStartHook(() -> marks[0] = counter.incrementAndGet());
        mp.addBeforeStartHook(() -> marks[1] = counter.incrementAndGet());
        mp.beforeStartHooks().forEach(Runnable::run);
        assertArrayEquals(new int[] {1, 2}, marks);
    }

    @Test
    void afterStopHooksCollected() {
        mp.addAfterStopHook(() -> {});
        assertEquals(1, mp.afterStopHooks().size());
    }

    @Test
    void nullArgumentsRejected() {
        assertThrows(NullPointerException.class, () -> mp.mount(null, "/x", _ -> Response.ok("")));
        assertThrows(NullPointerException.class, () -> mp.mount("default", null, _ -> Response.ok("")));
        assertThrows(NullPointerException.class, () -> mp.mount("default", "/x", null));
        assertThrows(NullPointerException.class, () -> mp.addBeforeStartHook(null));
    }

    @Test
    void buildRouterForUnknownListenerReturnsEmpty() {
        assertNotNull(mp.buildRouter("ghost"));
    }

    // --- listeners declared by an extension ---------------------------------------------------------

    @Test
    void extensionDeclaresAListenerWithItsOptions() {
        ListenerOptions options = new ListenerOptions(true, true, Duration.ofSeconds(1), _ -> {});
        mp.declareListener(ChappeListener.http("dev", "127.0.0.1", 8888), options);

        List<ChappeMountPoint.Declaration> declared = mp.declarations();
        assertEquals(1, declared.size());
        assertEquals("dev", declared.get(0).listener().name());
        assertSame(options, declared.get(0).options());
        assertEquals(getClass().getSimpleName(), declared.get(0).owner(),
                "the class that declared it, named when the configuration lists it too");
        assertNotNull(mp.buildRouter("dev"));
    }

    @Test
    void configuredListenerHasDefaultOptionsAndNoOwner() {
        mp.declareListener(ChappeListener.http("admin", "127.0.0.1", 9090));

        ChappeMountPoint.Declaration declared = mp.declarations().get(0);
        assertSame(ListenerOptions.DEFAULTS, declared.options());
        assertNull(declared.owner());
    }

    @Test
    void aNameIsDeclaredByOneExtensionOnly() {
        mp.declareListener(ChappeListener.http("dev", "127.0.0.1", 8888), ListenerOptions.DEFAULTS);

        var ex = assertThrows(IllegalStateException.class,
                () -> mp.declareListener(ChappeListener.http("dev", "127.0.0.1", 8889), ListenerOptions.DEFAULTS));
        assertTrue(ex.getMessage().contains("'dev'"), ex.getMessage());
        assertEquals(8888, mp.listeners().iterator().next().port(), "the first declaration is kept");
    }

    @Test
    void configurationCannotListAListenerAnExtensionDeclared() {
        mp.declareListener(ChappeListener.http("dev", "127.0.0.1", 8888), ListenerOptions.DEFAULTS);

        var ex = assertThrows(IllegalStateException.class,
                () -> mp.declareListener(ChappeListener.http("dev", "0.0.0.0", 9000)));
        assertEquals("listener 'dev' is declared by an extension (ChappeMountPointTest); "
                + "remove it from vidocq.chappe.listeners", ex.getMessage());
    }

    @Test
    void extensionCannotDeclareAListenerTheConfigurationLists() {
        mp.declareListener(ChappeListener.http("admin", "127.0.0.1", 9090));

        var ex = assertThrows(IllegalStateException.class,
                () -> mp.declareListener(ChappeListener.http("admin", "127.0.0.1", 0), ListenerOptions.DEFAULTS));
        assertTrue(ex.getMessage().contains("vidocq.chappe.listeners"), ex.getMessage());
    }

    @Test
    void aNameRepeatedInTheConfigurationIsDeclaredOnce() {
        mp.declareListener(ChappeListener.http("default", "0.0.0.0", 8081));
        mp.declareListener(ChappeListener.http("default", "0.0.0.0", 8081));
        assertEquals(1, mp.listeners().size());
    }

    @Test
    void theDefaultListenerIsTheApplications() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> mp.declareListener(ChappeListener.http(ChappeListener.DEFAULT, "127.0.0.1", 0),
                        ListenerOptions.DEFAULTS));
        assertTrue(ex.getMessage().contains("default"), ex.getMessage());
        assertTrue(mp.listeners().isEmpty());
    }

    @Test
    void listenersKeepTheirDeclarationOrder() {
        mp.declareListener(ChappeListener.http("dev", "127.0.0.1", 0), ListenerOptions.DEFAULTS);
        mp.declareListener(ChappeListener.http("default", "0.0.0.0", 8080));
        mp.declareListener(ChappeListener.http("admin", "127.0.0.1", 9090));
        assertEquals(List.of("dev", "default", "admin"),
                mp.listeners().stream().map(ChappeListener::name).toList());
    }

    @Test
    void nullDeclarationArgumentsRejected() {
        assertThrows(NullPointerException.class, () -> mp.declareListener(null, ListenerOptions.DEFAULTS));
        assertThrows(NullPointerException.class,
                () -> mp.declareListener(ChappeListener.http("dev", "127.0.0.1", 0), null));
    }

    @Test
    void advertisedPagesAreKeptPerListenerInOrder() {
        ChappeMountPoint mp = new ChappeMountPoint();
        mp.advertise(ChappeListener.DEFAULT, "Swagger UI", "/openapi/ui/");
        mp.advertise(ChappeListener.DEFAULT, "OpenAPI document", "/openapi");

        assertEquals(List.of(new ChappeMountPoint.Page("Swagger UI", "/openapi/ui/"),
                new ChappeMountPoint.Page("OpenAPI document", "/openapi")), mp.pages(ChappeListener.DEFAULT));
        assertEquals(List.of(), mp.pages("admin"));
    }

    @Test
    void anAdvertisedPageIsAPathAndComesBeforeTheStart() {
        ChappeMountPoint mp = new ChappeMountPoint();

        assertThrows(IllegalArgumentException.class, () -> mp.advertise("default", "Evil", "javascript:alert(1)"));
        mp.freeze();
        assertThrows(IllegalStateException.class, () -> mp.advertise("default", "Late", "/late"));
    }
}
