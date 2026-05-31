package io.vidocq.mansart.data.tests;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

import java.time.Instant;

/**
 * Test entity for MANSART-002: an {@link Instant} field mapped to a {@code TIMESTAMPTZ} column.
 * The PostgreSQL driver rejects {@code setObject(Instant, TIMESTAMP_WITH_TIMEZONE)}, so the dialect
 * must bind an {@code OffsetDateTime}; this entity exercises that write/read round-trip.
 */
@Entity
public class Event {

    @Id @GeneratedValue
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public Long getId()                       { return id; }
    public void setId(Long id)                { this.id = id; }
    public Instant getOccurredAt()            { return occurredAt; }
    public void setOccurredAt(Instant when)   { this.occurredAt = when; }
}
