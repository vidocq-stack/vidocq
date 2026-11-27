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
package io.vidocq.champollion.protobuf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * An enum whose constants have a body: each constant is an instance of an anonymous subclass, whose
 * {@code getClass()} is not the enum (BUG-20261127-91).
 */
class EnumConstantWithBodyTest {

    public enum Level {
        LOW {
            @Override String label() { return "low"; }
        },
        HIGH {
            @Override String label() { return "high"; }
        };

        abstract String label();
    }

    @ProtobufMessage
    public record Reading(@ProtobufField(number = 1, type = FieldType.ENUM) Level level) {}

    @Test
    void aConstantWithABodyIsWrittenAsJson() {
        assertEquals("{\"level\":\"HIGH\"}", ProtobufJson.toJson(new Reading(Level.HIGH)));
        assertEquals("{}", ProtobufJson.toJson(new Reading(Level.LOW)));
    }

    @Test
    void aConstantWithABodyRoundTripsOnTheWire() throws IOException {
        byte[] wire = Protobuf.toByteArray(new Reading(Level.HIGH));
        assertEquals(Level.HIGH, Protobuf.parser(Reading.class).parseFrom(wire).level());
        assertEquals(0, Protobuf.toByteArray(new Reading(Level.LOW)).length);
    }
}
