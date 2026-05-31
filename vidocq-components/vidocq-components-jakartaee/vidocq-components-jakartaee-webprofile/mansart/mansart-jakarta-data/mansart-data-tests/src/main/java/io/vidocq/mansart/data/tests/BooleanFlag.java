package io.vidocq.mansart.data.tests;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

/**
 * Test entity for MANSART-001: covers both a primitive {@code boolean} and a boxed {@link Boolean}
 * field, which must be typed as {@code BooleanAttribute} in the generated metamodel (previously they
 * fell through to {@code NumericAttribute} and failed to compile).
 */
@Entity
public class BooleanFlag {

    @Id @GeneratedValue
    private Long id;

    @Column(nullable = false)
    private boolean active;

    @Column
    private Boolean archived;

    public Long    getId()                  { return id; }
    public void    setId(Long id)           { this.id = id; }
    public boolean isActive()               { return active; }
    public void    setActive(boolean a)     { this.active = a; }
    public Boolean getArchived()            { return archived; }
    public void    setArchived(Boolean a)   { this.archived = a; }
}
