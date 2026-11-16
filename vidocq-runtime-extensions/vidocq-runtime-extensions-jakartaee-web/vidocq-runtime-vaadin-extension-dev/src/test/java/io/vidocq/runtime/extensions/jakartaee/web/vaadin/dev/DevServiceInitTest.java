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

import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.UIInitEvent;
import com.vaadin.flow.server.VaadinServletService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevServiceInitTest {

    @Test
    void eachPageGetsTheScript() {
        var event = new ServiceInitEvent(new VaadinServletService(null, null));

        new DevServiceInit().serviceInit(event);

        assertEquals(List.of(RefreshScript.class),
                event.getAddedIndexHtmlRequestListeners().map(Object::getClass).toList());
    }

    @Test
    void eachStartedUiIsKeptUntilItDetaches() {
        var ui = new UI();

        new DevServiceInit().uiInit(new UIInitEvent(ui, new VaadinServletService(null, null)));

        assertTrue(DevServiceInit.OPEN_UIS.all().contains(ui));
        ComponentUtil.fireEvent(ui, new DetachEvent(ui));
        assertFalse(DevServiceInit.OPEN_UIS.all().contains(ui));
    }
}
