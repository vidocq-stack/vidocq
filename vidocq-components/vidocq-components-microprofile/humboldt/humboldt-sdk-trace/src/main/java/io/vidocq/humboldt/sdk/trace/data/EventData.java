package io.vidocq.humboldt.sdk.trace.data;

import io.opentelemetry.api.common.Attributes;

/**
 * Event attached to a span, immutable.
 *
 * @param epochNanos timestamp in nanoseconds since the UTC epoch
 * @param name       event name (never {@code null})
 * @param attributes attributes (never {@code null}, may be empty)
 */
public record EventData(long epochNanos, String name, Attributes attributes) {

    public EventData {
        if (name == null) throw new NullPointerException("name");
        if (attributes == null) attributes = Attributes.empty();
    }
}
