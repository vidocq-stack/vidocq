package io.vidocq.runtime.examples.mansart;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private double price;

    public Product() {}

    public Product(String name, double price) {
        this.name  = name;
        this.price = price;
    }

    public Long   getId()                 { return id;     }
    public void   setId(Long id)          { this.id = id;  }
    public String getName()               { return name;   }
    public void   setName(String n)       { this.name = n; }
    public double getPrice()              { return price;  }
    public void   setPrice(double p)      { this.price = p; }
}
