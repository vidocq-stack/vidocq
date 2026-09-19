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
import org.mcpjava.server.tools.Tool;
import org.mcpjava.server.tools.ToolArg;
import org.mcpjava.server.tools.ToolResponse;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Two tools; {@code convert_time} returns a {@link ToolResponse}, which needs the {@code McpServerSPI} provider. */
@ApplicationScoped
public class TimeTools {

    /** CDI constructor. */
    public TimeTools() {}

    @Tool(name = "current_time", description = "The current date-time in an IANA time zone.")
    public String currentTime(@ToolArg(name = "zone", description = "IANA time zone id") String zone) {
        return ZonedDateTime.now(ZoneId.of(zone)).toString();
    }

    @Tool(name = "convert_time", description = "Converts a local date-time from one IANA time zone to another.")
    public ToolResponse convertTime(
            @ToolArg(name = "localDateTime", description = "ISO-8601 local date-time") String localDateTime,
            @ToolArg(name = "fromZone", description = "IANA zone of the date-time") String fromZone,
            @ToolArg(name = "toZone", description = "IANA zone to convert to") String toZone) {
        ZonedDateTime from = LocalDateTime.parse(localDateTime).atZone(ZoneId.of(fromZone));
        return ToolResponse.ofText(from.withZoneSameInstant(ZoneId.of(toZone)).toString());
    }
}
