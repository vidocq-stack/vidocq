package io.vidocq.mansart.data.tests;

import io.vidocq.mansart.data.Column;
import io.vidocq.mansart.data.Entity;
import io.vidocq.mansart.data.GeneratedValue;
import io.vidocq.mansart.data.Id;
import io.vidocq.mansart.data.Version;

@Entity
public class Article {

    @Id @GeneratedValue
    private Long id;

    @Column(nullable = false, length = 300)
    private String title;

    @Version
    private Integer version;

    public Long    getId()                  { return id; }
    public void    setId(Long id)           { this.id = id; }
    public String  getTitle()               { return title; }
    public void    setTitle(String title)   { this.title = title; }
    public Integer getVersion()             { return version; }
    public void    setVersion(Integer v)    { this.version = v; }
}
