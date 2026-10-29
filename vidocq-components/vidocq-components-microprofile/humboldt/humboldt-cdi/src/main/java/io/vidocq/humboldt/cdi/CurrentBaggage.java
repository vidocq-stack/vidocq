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
package io.vidocq.humboldt.cdi;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageBuilder;
import io.opentelemetry.api.baggage.BaggageEntry;
import io.opentelemetry.context.Context;

import java.util.Map;
import java.util.function.BiConsumer;

/**
 * The injected {@link Baggage}: every call reads {@link Baggage#current()} at the time of the call, not of the
 * injection ({@code BaggageBeanTest.baggageBeanChange}). Equality is identity, as for {@link CurrentSpan}.
 */
final class CurrentBaggage implements Baggage {

    @Override
    public int size() {
        return Baggage.current().size();
    }

    @Override
    public boolean isEmpty() {
        return Baggage.current().isEmpty();
    }

    @Override
    public void forEach(BiConsumer<? super String, ? super BaggageEntry> consumer) {
        Baggage.current().forEach(consumer);
    }

    @Override
    public Map<String, BaggageEntry> asMap() {
        return Baggage.current().asMap();
    }

    @Override
    public String getEntryValue(String entryKey) {
        return Baggage.current().getEntryValue(entryKey);
    }

    @Override
    public BaggageEntry getEntry(String entryKey) {
        return Baggage.current().getEntry(entryKey);
    }

    @Override
    public BaggageBuilder toBuilder() {
        return Baggage.current().toBuilder();
    }

    @Override
    public Context storeInContext(Context context) {
        return Baggage.current().storeInContext(context);
    }

    @Override
    public String toString() {
        return "CurrentBaggage[" + Baggage.current() + "]";
    }
}
