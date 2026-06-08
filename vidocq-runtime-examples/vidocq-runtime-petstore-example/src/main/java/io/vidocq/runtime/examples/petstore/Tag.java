package io.vidocq.runtime.examples.petstore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A free-form pet tag (e.g. friendly, trained). Linked to pets via {@link PetTag}. */
@Entity
@Table(name = "tags")
public class Tag {

    @Id
    @GeneratedValue
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    public Tag() {}

    public Tag(String name) {
        this.name = name;
    }

    public Long   getId()             { return id;       }
    public void   setId(Long id)      { this.id = id;    }
    public String getName()           { return name;     }
    public void   setName(String n)   { this.name = n;   }
}
