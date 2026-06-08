package io.vidocq.runtime.examples.petstore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A pet for sale. The {@code categoryId} is a plain foreign-key column — the {@link Category}
 * relation is resolved in {@code PetService}, not by JPA. Tags are linked through the
 * {@link PetTag} join entity, also assembled in the service layer.
 */
@Entity
@Table(name = "pets")
public class Pet {

    @Id
    @GeneratedValue
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "category_id")
    private Long categoryId;

    /** One of {@code available}, {@code pending}, {@code sold}. */
    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private double price;

    public Pet() {}

    public Pet(String name, Long categoryId, String status, double price) {
        this.name       = name;
        this.categoryId = categoryId;
        this.status     = status;
        this.price      = price;
    }

    public Long   getId()                    { return id;             }
    public void   setId(Long id)             { this.id = id;          }
    public String getName()                  { return name;           }
    public void   setName(String n)          { this.name = n;         }
    public Long   getCategoryId()            { return categoryId;     }
    public void   setCategoryId(Long c)      { this.categoryId = c;   }
    public String getStatus()                { return status;         }
    public void   setStatus(String s)        { this.status = s;       }
    public double getPrice()                 { return price;          }
    public void   setPrice(double p)         { this.price = p;        }
}
