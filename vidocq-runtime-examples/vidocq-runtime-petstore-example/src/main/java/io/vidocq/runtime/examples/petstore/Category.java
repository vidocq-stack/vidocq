package io.vidocq.runtime.examples.petstore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A pet category (e.g. Dog, Cat). Referenced from {@link Pet#getCategoryId()}. */
@Entity
@Table(name = "categories")
public class Category {

    @Id
    @GeneratedValue
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    public Category() {}

    public Category(String name) {
        this.name = name;
    }

    public Long   getId()             { return id;       }
    public void   setId(Long id)      { this.id = id;    }
    public String getName()           { return name;     }
    public void   setName(String n)   { this.name = n;   }
}
