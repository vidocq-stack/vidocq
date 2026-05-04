package io.vidocq.mansart.data.tests;

import io.vidocq.mansart.data.Column;
import io.vidocq.mansart.data.Entity;
import io.vidocq.mansart.data.GeneratedValue;
import io.vidocq.mansart.data.Id;

@Entity
public class Author {

    @Id @GeneratedValue
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    public Long getId()           { return id; }
    public void setId(Long id)    { this.id = id; }
    public String getName()       { return name; }
    public void setName(String n) { this.name = n; }
}
