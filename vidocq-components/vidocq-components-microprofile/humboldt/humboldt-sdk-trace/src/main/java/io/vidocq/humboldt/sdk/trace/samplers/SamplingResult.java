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
package io.vidocq.humboldt.sdk.trace.samplers;

import io.opentelemetry.api.common.Attributes;

/**
 * Decision from a {@link Sampler}: recorded (and exported) / recorded only / dropped,
 * optionally with additional attributes and a trace-state override.
 */
public record SamplingResult(Decision decision, Attributes attributes) {

    private static final SamplingResult DROP = new SamplingResult(Decision.DROP, Attributes.empty());
    private static final SamplingResult RECORD_ONLY = new SamplingResult(Decision.RECORD_ONLY, Attributes.empty());
    private static final SamplingResult RECORD_AND_SAMPLE = new SamplingResult(Decision.RECORD_AND_SAMPLE, Attributes.empty());

    public SamplingResult {
        if (decision == null) throw new NullPointerException("decision");
        if (attributes == null) attributes = Attributes.empty();
    }

    public static SamplingResult drop() {
        return DROP;
    }

    public static SamplingResult recordOnly() {
        return RECORD_ONLY;
    }

    public static SamplingResult recordAndSample() {
        return RECORD_AND_SAMPLE;
    }

    public enum Decision {
        DROP,
        RECORD_ONLY,
        RECORD_AND_SAMPLE
    }
}
