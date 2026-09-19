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
import org.mcpjava.server.Role;
import org.mcpjava.server.content.TextContent;
import org.mcpjava.server.prompts.Prompt;
import org.mcpjava.server.prompts.PromptArg;
import org.mcpjava.server.prompts.PromptResponse;

/** A prompt returning a {@link PromptResponse}, which needs the {@code McpServerSPI} provider. */
@ApplicationScoped
public class MeetingPrompts {

    /** CDI constructor. */
    public MeetingPrompts() {}

    @Prompt(name = "plan_meeting", description = "Proposes a meeting slot across time zones.")
    public PromptResponse planMeeting(
            @PromptArg(name = "zones", description = "Comma-separated IANA zone ids") String zones,
            @PromptArg(name = "durationMinutes", description = "Duration in minutes") String durationMinutes) {
        return PromptResponse.of(Role.USER, TextContent.of(
                "Propose a " + durationMinutes + "-minute meeting slot for attendees in " + zones + "."));
    }
}
