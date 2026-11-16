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
package io.vidocq.runtime.extensions.jakartaee.web.vaadin.dev;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.HasComponents;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.UI;
import io.vidocq.runtime.spi.devconsole.RedefinedClass;
import io.vidocq.runtime.spi.devconsole.Redefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Vaadin refresh after a redefinition (hot swap design §5). */
class VaadinRedefinitionListenerTest {

    @Tag("main-view")
    static final class MainView extends Component {
    }

    @Tag("other-view")
    static final class OtherView extends Component {
    }

    @Tag("div")
    static final class Box extends Component implements HasComponents {
    }

    private final OpenUis uis = new OpenUis();
    private final List<UI> reloaded = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final List<String> lines = new ArrayList<>();
    private final VaadinRedefinitionListener listener = new VaadinRedefinitionListener(uis,
            (ui, task) -> task.run(), reloaded::add, events::add, lines::add);

    @Test
    void onlyLambdaBodiesRefreshNothing() {
        UI ui = showing(new MainView());

        listener.redefined(redefinition(MainView.class, "lambda$new$0", "lambda$new$1"));

        assertEquals(List.of(), reloaded);
        assertEquals(List.of(), events);
        assertEquals(List.of(), lines);
        assertEquals(List.of(ui), uis.all());
    }

    @Test
    void aRedefinedViewConstructorQueuesAReloadOnTheUisShowingIt() {
        UI main = showing(new MainView());
        showing(new OtherView());

        listener.redefined(redefinition(MainView.class, "<init>"));

        assertEquals(List.of(main), reloaded);
        assertEquals(List.of("Hot reload - Vaadin: refreshed 1 open page showing MainView"), lines);
    }

    @Test
    void aViewDeepInTheTreeCounts() {
        var box = new Box();
        box.add(new Box(), new MainView());
        UI ui = showing(box);

        listener.redefined(redefinition(MainView.class, "refresh"));

        assertEquals(List.of(ui), reloaded);
    }

    @Test
    void oneEventPerRedefinition() {
        showing(new MainView());
        showing(new MainView());

        listener.redefined(redefinition(MainView.class, "<init>"));

        assertEquals(2, reloaded.size());
        assertEquals(List.of("MainView"), events);
        assertEquals(List.of("Hot reload - Vaadin: refreshed 2 open pages showing MainView"), lines);
    }

    /** A page without push polls once on each event: a class no page shows still sends one, and logs nothing. */
    @Test
    void aClassNoPageShowsReloadsNothing() {
        showing(new OtherView());

        listener.redefined(redefinition(MainView.class, "<init>"));

        assertEquals(List.of(), reloaded);
        assertEquals(List.of("MainView"), events);
        assertEquals(List.of(), lines);
    }

    /** UI.access queues the task while a request holds the session: the line waits for the last page. */
    @Test
    void aQueuedAccessIsCountedWhenItRuns() {
        List<Runnable> queued = new ArrayList<>();
        var later = new VaadinRedefinitionListener(uis, (ui, task) -> queued.add(task), reloaded::add, events::add,
                lines::add);
        showing(new MainView());
        showing(new MainView());

        later.redefined(redefinition(MainView.class, "<init>"));
        assertEquals(List.of(), lines);
        queued.forEach(Runnable::run);

        assertEquals(List.of("Hot reload - Vaadin: refreshed 2 open pages showing MainView"), lines);
    }

    @Test
    void aDetachedUiLeavesTheRegistry() {
        UI ui = showing(new MainView());

        ComponentUtil.fireEvent(ui, new DetachEvent(ui));

        assertEquals(List.of(), uis.all());
    }

    private UI showing(Component view) {
        var ui = new UI();
        ui.add(view);
        uis.opened(ui);
        return ui;
    }

    private static Redefinition redefinition(Class<?> type, String... methods) {
        return new Redefinition(List.of(new RedefinedClass(type, Set.of(methods))));
    }
}
