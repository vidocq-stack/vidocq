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
package io.vidocq.runtime.it.lc4jcdimcp;

import jakarta.enterprise.context.ApplicationScoped;
import org.mcpjava.server.resources.ResourceTemplate;
import org.mcpjava.server.resources.ResourceTemplateArg;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/** A resource template: {@code time://zone/{zone}}. */
@ApplicationScoped
public class TimeResource {

    /** CDI constructor. */
    public TimeResource() {}

    @ResourceTemplate(name = "time-in-zone", description = "The current date-time in an IANA time zone.",
            uriTemplate = "time://zone/{zone}", mimeType = "text/plain")
    public String timeInZone(@ResourceTemplateArg(name = "zone") String zone) {
        return ZonedDateTime.now(ZoneId.of(zone)).toString();
    }
}
