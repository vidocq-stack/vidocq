package io.vidocq.runtime.examples.petstore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Join entity materialising the many-to-many link between {@link Pet} and {@link Tag}.
 * Mansart Data has no association mapping, so the link is a first-class flat row managed
 * explicitly by {@code PetService} (delete-then-reinsert on update).
 */
@Entity
@Table(name = "pet_tags")
public class PetTag {

    @Id
    @GeneratedValue
    private Long id;

    @Column(name = "pet_id", nullable = false)
    private Long petId;

    @Column(name = "tag_id", nullable = false)
    private Long tagId;

    public PetTag() {}

    public PetTag(Long petId, Long tagId) {
        this.petId = petId;
        this.tagId = tagId;
    }

    public Long getId()              { return id;        }
    public void setId(Long id)       { this.id = id;     }
    public Long getPetId()           { return petId;     }
    public void setPetId(Long p)     { this.petId = p;   }
    public Long getTagId()           { return tagId;     }
    public void setTagId(Long t)     { this.tagId = t;   }
}
