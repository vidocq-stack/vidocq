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
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.UIDetachedException;
import io.vidocq.runtime.spi.devconsole.RedefinedClass;
import io.vidocq.runtime.spi.devconsole.Redefinition;
import io.vidocq.runtime.spi.devconsole.RedefinitionListener;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Refreshes the open pages that show a class {@code vidocq:dev} redefined (hot swap design §5). Vaadin's own browser
 * refresh lives in {@code vaadin-dev-server}, which an application in production mode does not have.
 *
 * <ul>
 *   <li>When every changed method of every class is a lambda body, nothing is refreshed: the next event runs the new
 *       code, and a refresh would lose what the page holds, such as the value of a text field.</li>
 *   <li>Otherwise each open page whose component tree holds an instance of a redefined class reloads, through
 *       {@code UI.access}: with push the reload reaches the browser at once, without it the event of
 *       {@link RedefinedEvents} makes the page ask for it.</li>
 * </ul>
 * A class that is not a component, but that a view reads in its constructor, refreshes no page: it shows after the
 * next reload of the page, by hand.
 */
public final class VaadinRedefinitionListener implements RedefinitionListener {

    private static final System.Logger LOG = System.getLogger(VaadinRedefinitionListener.class.getName());

    private final OpenUis uis;
    private final BiConsumer<UI, Runnable> access;
    private final Consumer<UI> reload;
    private final Consumer<String> events;
    private final Consumer<String> info;

    /** The listener {@code ServiceLoader} creates for each redefinition. */
    public VaadinRedefinitionListener() {
        this(OpenUis.INSTANCE, (ui, task) -> ui.access(task::run), ui -> ui.getPage().reload(),
                RedefinedEvents::publish, line -> LOG.log(System.Logger.Level.INFO, line));
    }

    /**
     * @param access runs a task under the lock of a UI's session
     * @param reload reloads the page of a UI, under that lock
     * @param events the event the pages without push wait for
     */
    VaadinRedefinitionListener(OpenUis uis, BiConsumer<UI, Runnable> access, Consumer<UI> reload,
            Consumer<String> events, Consumer<String> info) {
        this.uis = uis;
        this.access = access;
        this.reload = reload;
        this.events = events;
        this.info = info;
    }

    @Override
    public void redefined(Redefinition redefinition) {
        Set<Class<?>> types = redefinition.classes().stream().filter(type -> !type.onlyLambdaBodies())
                .map(RedefinedClass::type).collect(Collectors.toCollection(LinkedHashSet::new));
        if (types.isEmpty()) {
            return;
        }
        List<UI> open = uis.all();
        var tally = new Tally(open.size(), info);
        for (UI ui : open) {
            try {
                access.accept(ui, () -> {
                    Set<String> names = shown(ui, types);
                    if (!names.isEmpty()) {
                        reload.accept(ui);
                    }
                    tally.answered(names);
                });
            } catch (UIDetachedException _) {
                // The page closed since the registry listed it: nothing to refresh.
                tally.answered(Set.of());
            }
        }
        events.accept(types.stream().map(Class::getSimpleName).collect(Collectors.joining(", ")));
    }

    /**
     * The pages refreshed for one redefinition. {@code UI.access} runs at once, or later when a request holds the
     * session: the line is logged when the last page has answered.
     */
    private static final class Tally {

        private final Consumer<String> info;
        private final Set<String> shown = new LinkedHashSet<>();
        private int waiting;
        private int refreshed;

        Tally(int pages, Consumer<String> info) {
            this.waiting = pages;
            this.info = info;
        }

        synchronized void answered(Set<String> names) {
            if (!names.isEmpty()) {
                refreshed++;
                shown.addAll(names);
            }
            if (--waiting == 0 && refreshed > 0) {
                info.accept("Hot reload - Vaadin: refreshed " + refreshed
                        + (refreshed == 1 ? " open page" : " open pages") + " showing " + String.join(", ", shown));
            }
        }
    }

    /** The simple names of the redefined classes among the components of {@code ui}. */
    private static Set<String> shown(UI ui, Set<Class<?>> types) {
        return tree(ui).map(Component::getClass).filter(types::contains).map(Class::getSimpleName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Stream<Component> tree(Component component) {
        return Stream.concat(Stream.of(component), component.getChildren().flatMap(VaadinRedefinitionListener::tree));
    }
}
