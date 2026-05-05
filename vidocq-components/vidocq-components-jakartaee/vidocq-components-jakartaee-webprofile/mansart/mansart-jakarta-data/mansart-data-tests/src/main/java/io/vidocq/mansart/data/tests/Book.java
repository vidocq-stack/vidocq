package io.vidocq.mansart.data.tests;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Version;

import java.time.LocalDate;

@Entity
public class Book {

    @Id @GeneratedValue
    private Long id;

    @Column(nullable = false, length = 300)
    private String title;

    @ManyToOne
    @JoinColumn(name = "author_id", nullable = false)
    private Author author;

    @Column
    private LocalDate publishedOn;

    @Version
    private Integer version;

    public Long getId()                         { return id; }
    public void setId(Long id)                  { this.id = id; }
    public String getTitle()                    { return title; }
    public void setTitle(String t)              { this.title = t; }
    public Author getAuthor()                   { return author; }
    public void setAuthor(Author a)             { this.author = a; }
    public LocalDate getPublishedOn()           { return publishedOn; }
    public void setPublishedOn(LocalDate p)     { this.publishedOn = p; }
    public Integer getVersion()                 { return version; }
    public void setVersion(Integer v)           { this.version = v; }
}
