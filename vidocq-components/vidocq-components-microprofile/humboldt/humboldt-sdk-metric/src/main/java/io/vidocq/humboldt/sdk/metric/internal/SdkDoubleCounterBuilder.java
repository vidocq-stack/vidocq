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
package io.vidocq.humboldt.sdk.metric.internal;

import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.api.metrics.DoubleCounterBuilder;
import io.opentelemetry.api.metrics.ObservableDoubleCounter;
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement;
import io.vidocq.humboldt.sdk.metric.aggregation.DoubleSumAggregator;
import io.vidocq.humboldt.sdk.metric.data.AggregationTemporality;
import io.vidocq.humboldt.sdk.metric.data.InstrumentType;

import java.util.function.Consumer;

public final class SdkDoubleCounterBuilder implements DoubleCounterBuilder {

    private final String name;
    private final SdkMeter meter;
    private String description = "";
    private String unit = "";

    SdkDoubleCounterBuilder(String name, SdkMeter meter) { this.name = name; this.meter = meter; }

    @Override public DoubleCounterBuilder setDescription(String d) { this.description = d != null ? d : ""; return this; }
    @Override public DoubleCounterBuilder setUnit(String u) { this.unit = u != null ? u : ""; return this; }

    @Override
    public DoubleCounter build() {
        DoubleSumAggregator agg = new DoubleSumAggregator();
        SdkDoubleCounter counter = new SdkDoubleCounter(agg);
        meter.register(new InstrumentEntry(name, description, unit,
                InstrumentType.COUNTER, AggregationTemporality.CUMULATIVE, true, agg));
        return counter;
    }

    @Override
    public ObservableDoubleCounter buildWithCallback(Consumer<ObservableDoubleMeasurement> callback) {
        DoubleSumAggregator agg = new DoubleSumAggregator();
        meter.register(new InstrumentEntry(name, description, unit,
                InstrumentType.OBSERVABLE_COUNTER, AggregationTemporality.CUMULATIVE, true, agg));
        var measurement = new ObservableDoubleMeasurementImpl(agg);
        meter.registerObservableCallback(() -> callback.accept(measurement));
        return new ObservableDoubleCounter() {};
    }

    @Override
    public ObservableDoubleMeasurement buildObserver() {
        DoubleSumAggregator agg = new DoubleSumAggregator();
        meter.register(new InstrumentEntry(name, description, unit,
                InstrumentType.OBSERVABLE_COUNTER, AggregationTemporality.CUMULATIVE, true, agg));
        return new ObservableDoubleMeasurementImpl(agg);
    }
}
