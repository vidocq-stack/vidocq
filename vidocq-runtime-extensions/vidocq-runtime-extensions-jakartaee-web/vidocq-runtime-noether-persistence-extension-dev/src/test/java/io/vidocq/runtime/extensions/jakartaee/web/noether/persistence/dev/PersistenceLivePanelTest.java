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
package io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev;

import io.vidocq.noether.jpa.core.spi.UnitDescription;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Index;
import io.vidocq.noether.jpa.core.spi.UnitDescription.NamedQuery;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Table;
import io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev.RecordedSample.Absent;
import io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev.RecordedSample.Gauge;
import io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.live.NoetherPersistenceLive;
import io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.live.NoetherPersistenceLive.Unit;
import io.vidocq.runtime.spi.devconsole.LivePanel;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import io.vidocq.runtime.spi.devconsole.PanelLanguage;
import io.vidocq.runtime.spi.devconsole.PanelSelector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Noether Persistence panel: its dropdown, a group per unit with its counts, and its actions. */
class PersistenceLivePanelTest {

    static final UnitDescription LEDGER = new UnitDescription("ledger", List.of(), List.of(),
            List.of(new NamedQuery("Entry.all", "SELECT e FROM Entry e", false),
                    new NamedQuery("Entry.raw", "SELECT * FROM ENTRY", true)),
            List.of(new Table("ENTRY", List.of(), List.of("ID"), List.of(
                    new Index("idx_entry_day", List.of("DAY"), false), new Index(null, List.of("CODE"), true)),
                    List.of())));

    static Unit unit(String name, UnitDescription description) {
        return new Unit(name, true, name, "create", new StubFactory(name, description));
    }

    static PersistenceLivePanel panel(Unit... units) {
        return new PersistenceLivePanel(() -> List.of(units), () -> null);
    }

    static PanelAction action(PersistenceLivePanel panel, String id) {
        return panel.actions().stream().filter(a -> a.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no action " + id));
    }

    @Test
    void thePanelIsTheSectionsAndItsDropdownChoosesTheUnit() {
        PersistenceLivePanel panel = panel();

        assertEquals("noether-persistence", panel.id());
        assertEquals(Optional.of(new PanelSelector("unit", "Persistence unit")), panel.selector());
    }

    @Test
    void eachUnitIsAGroupWithItsCountsInTheUnitsOrder() {
        RecordedSample sample = new RecordedSample();

        panel(unit("notes", ModelGraphTest.UNIT), unit("ledger", LEDGER)).sample(sample);

        assertEquals(List.of("notes", "ledger"), sample.groupNames());
        RecordedSample notes = sample.written("notes");
        assertEquals(List.of("entities", "embeddables", "named-queries", "indexes"), notes.keys());
        assertEquals(new Gauge(6, null, io.vidocq.runtime.spi.devconsole.Unit.COUNT), notes.value("entities"));
        assertEquals(new Gauge(1, null, io.vidocq.runtime.spi.devconsole.Unit.COUNT), notes.value("embeddables"));
        RecordedSample ledger = sample.written("ledger");
        assertEquals(new Gauge(2, null, io.vidocq.runtime.spi.devconsole.Unit.COUNT), ledger.value("named-queries"));
        assertEquals(new Gauge(2, null, io.vidocq.runtime.spi.devconsole.Unit.COUNT), ledger.value("indexes"));
    }

    @Test
    void withoutAUnitTheSampleSaysSoAndThereIsNoAction() {
        RecordedSample sample = new RecordedSample();
        PersistenceLivePanel panel = panel();

        panel.sample(sample);

        assertEquals(new Absent("no persistence unit"), sample.value("units"));
        assertEquals(List.of(), panel.actions());
    }

    @Test
    void aUnitThatCannotDescribeItselfSaysWhyInItsGroup() {
        RecordedSample sample = new RecordedSample();

        panel(new Unit("closed", false, null, "none", new StubFactory("closed", null))).sample(sample);

        assertEquals(new Absent("no description: Unsupported unwrap type " + UnitDescription.class.getName()),
                sample.written("closed").value("entities"));
    }

    @Test
    void theModelActionDrawsTheChosenUnit() {
        PanelAction model = action(panel(unit("notes", ModelGraphTest.UNIT), unit("ledger", LEDGER)), "model");

        assertEquals("Model", model.group());
        assertEquals(List.of("notes", "ledger"), model.arguments().getFirst().allowedValues());
        ActionResult drawn = model.call().apply(Map.of("unit", "notes"));
        assertEquals(ActionResult.GRAPH, drawn.contentType());
        assertTrue(drawn.body().contains("\"id\":\"entity:Note\""), drawn.body());
        assertFalse(drawn.error());
    }

    static PanelAction.Argument argument(PanelAction action, String name) {
        return action.arguments().stream().filter(a -> a.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(action.id() + " takes no " + name));
    }

    @Test
    void eachUnitHasItsOwnExploreAndJpqlActionsOnItsOwnEntities() {
        PersistenceLivePanel panel = panel(unit("notes", ModelGraphTest.UNIT), unit("items", ResultRowsTest.ITEMS));

        PanelAction notes = action(panel, "explore.page.1");
        PanelAction items = action(panel, "explore.page.2");
        assertEquals(List.of("notes"), argument(notes, "unit").allowedValues());
        assertEquals(List.of("items"), argument(items, "unit").allowedValues());
        assertEquals(List.of("Item"), argument(items, "entity").allowedValues());
        assertFalse(argument(notes, "entity").allowedValues().contains("Item"), "no entity of another unit");
        assertEquals(List.of("items"), argument(action(panel, "jpql.write.2"), "unit").allowedValues());
        String statement = argument(action(panel, "jpql.query.2"), "statement").schema();
        assertTrue(statement.contains("SELECT e FROM Item e ORDER BY e.id"), statement);
        assertTrue(statement.contains("\"x-language\":\"jpql.2\""), statement);
        assertEquals(List.of("notes", "items"), argument(action(panel, "model"), "unit").allowedValues(),
                "a tab that only needs the unit stays one action");
    }

    @Test
    void eachUnitsQueriesCompleteItsOwnEntities() {
        List<PanelLanguage> languages = panel(unit("notes", ModelGraphTest.UNIT), unit("items", ResultRowsTest.ITEMS))
                .languages();

        assertEquals(List.of("jpql.1", "jpql.2"), languages.stream().map(PanelLanguage::id).toList());
        assertTrue(languages.get(1).json().contains("\"Item\""), languages.get(1).json());
        assertFalse(languages.get(1).json().contains("\"Note\""), languages.get(1).json());
    }

    @Test
    void theModuleProvidesThePanelOfTheUnitsTheExtensionPublished() {
        PersistenceLivePanelTest.class.getModule().addUses(LivePanel.class);
        LivePanel found = ServiceLoader.load(LivePanel.class).stream().map(ServiceLoader.Provider::get)
                .filter(p -> p.id().equals(PersistenceLivePanel.ID)).findFirst()
                .orElseThrow(() -> new AssertionError("the module provides no " + PersistenceLivePanel.ID + " panel"));
        NoetherPersistenceLive.publish(List.of(unit("ledger", LEDGER)));
        try {
            assertEquals(List.of("ledger"), action((PersistenceLivePanel) found, "model").arguments().getFirst()
                    .allowedValues());
        } finally {
            NoetherPersistenceLive.clear();
        }
        assertEquals(List.of(), found.actions());
    }

    @Test
    void anActionOnAUnitNoLongerStartedAnswersAnError() {
        PanelAction model = action(panel(unit("notes", ModelGraphTest.UNIT)), "model");

        ActionResult gone = model.call().apply(Map.of("unit", "archive"));

        assertTrue(gone.error());
        assertEquals("no persistence unit named archive in this boot", gone.summary());
    }
}
