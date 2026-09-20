/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.examples.petstore;

import io.vidocq.runtime.examples.petstore.model.PetInput;
import io.vidocq.runtime.examples.petstore.model.PetView;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Application service that re-assembles the Pet ⇄ Category ⇄ Tag relations by hand. Mansart Data
 * has no association mapping, so every link is resolved here from flat foreign keys / the
 * {@link PetTag} join table. This keeps the repositories trivial and the wiring explicit.
 */
@ApplicationScoped
public class PetService {

    private static final System.Logger LOG = System.getLogger(PetService.class.getName());

    private static final String DEFAULT_STATUS = "available";

    @Inject PetRepository    pets;
    @Inject CategoryRepository categories;
    @Inject TagRepository    tags;
    @Inject PetTagRepository petTags;
    @Inject OperationAudit   audit;

    // ---- queries -------------------------------------------------------------------------------

    public List<PetView> list(String status) {
        List<Pet> found = (status != null && !status.isBlank())
                ? pets.findByStatus(status)
                : pets.findAll().toList();
        return found.stream().map(this::toView).toList();
    }

    public Optional<PetView> get(long id) {
        return pets.findById(id).map(this::toView);
    }

    public long count() {
        return pets.count();
    }

    // ---- commands ------------------------------------------------------------------------------

    @Transactional
    public PetView create(PetInput input) {
        Long categoryId = resolveCategory(input.category());
        String status = (input.status() == null || input.status().isBlank())
                ? DEFAULT_STATUS : input.status();
        Pet saved = pets.save(new Pet(input.name(), categoryId, status, input.price()));
        linkTags(saved.getId(), input.tags());
        audit.record("created pet id=" + saved.getId());
        LOG.log(System.Logger.Level.INFO, () -> "TX audit: " + audit.entries());
        return toView(saved);
    }

    @Transactional
    public Optional<PetView> update(long id, PetInput input) {
        Optional<Pet> existing = pets.findById(id);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        Pet pet = existing.get();
        pet.setName(input.name());
        pet.setCategoryId(resolveCategory(input.category()));
        pet.setStatus((input.status() == null || input.status().isBlank())
                ? DEFAULT_STATUS : input.status());
        pet.setPrice(input.price());
        Pet saved = pets.save(pet);
        // Resync the join table: drop the old links, recreate from the new tag list.
        unlinkTags(id);
        linkTags(id, input.tags());
        audit.record("updated pet id=" + id);
        return Optional.of(toView(saved));
    }

    @Transactional
    public boolean delete(long id) {
        if (pets.findById(id).isEmpty()) {
            return false;
        }
        unlinkTags(id);
        pets.deleteById(id);
        audit.record("deleted pet id=" + id);
        LOG.log(System.Logger.Level.INFO, () -> "TX audit: " + audit.entries());
        return true;
    }

    /**
     * Test-only unit of work for {@code TransactionalRollbackTest} (Vidocq/vidocq#97): saves a pet
     * and then always fails, so an in-process test can prove the write does not survive the
     * transaction rollback. Package-private with no JAX-RS annotation, so Cassini never exposes it
     * as an endpoint. Lives in main sources, not {@code src/test/java}, because this repository's
     * Vauban-generated bean registry is produced separately per Maven compilation round (main vs.
     * test), so a CDI bean declared only in the test round is never merged into the main round's
     * registry and would not be discoverable at runtime.
     */
    @Transactional
    void createThenFailForTests() {
        pets.save(new Pet("Rollback probe", null, DEFAULT_STATUS, 1.0));
        throw new IllegalStateException(
                "simulated failure after a write, for TransactionalRollbackTest (Vidocq/vidocq#97)");
    }

    // ---- relation plumbing ---------------------------------------------------------------------

    private PetView toView(Pet pet) {
        String categoryName = pet.getCategoryId() == null ? null
                : categories.findById(pet.getCategoryId()).map(Category::getName).orElse(null);
        List<String> tagNames = petTags.findByPetId(pet.getId()).stream()
                .map(PetTag::getTagId)
                .map(tags::findById)
                .flatMap(Optional::stream)
                .map(Tag::getName)
                .toList();
        return new PetView(pet.getId(), pet.getName(), categoryName,
                pet.getStatus(), pet.getPrice(), tagNames);
    }

    /** Resolves a category by name, creating it on first use. Returns {@code null} for a blank name. */
    private Long resolveCategory(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return categories.findByName(name).stream().findFirst()
                .map(Category::getId)
                .orElseGet(() -> categories.save(new Category(name)).getId());
    }

    /** Resolves a tag by name, creating it on first use. */
    private Long resolveTag(String name) {
        return tags.findByName(name).stream().findFirst()
                .map(Tag::getId)
                .orElseGet(() -> tags.save(new Tag(name)).getId());
    }

    private void linkTags(Long petId, List<String> tagNames) {
        if (tagNames == null) {
            return;
        }
        List<String> seen = new ArrayList<>();
        for (String name : tagNames) {
            if (name == null || name.isBlank() || seen.contains(name)) {
                continue;
            }
            seen.add(name);
            petTags.save(new PetTag(petId, resolveTag(name)));
        }
    }

    private void unlinkTags(Long petId) {
        petTags.findByPetId(petId).forEach(link -> petTags.deleteById(link.getId()));
    }
}
