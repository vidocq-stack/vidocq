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
package io.vidocq.mansart.data.tests;

import jakarta.data.Order;
import jakarta.data.page.CursoredPage;
import jakarta.data.page.Page;
import jakarta.data.page.PageRequest;
import jakarta.data.repository.By;
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Delete;
import jakarta.data.repository.Find;
import jakarta.data.repository.Insert;
import jakarta.data.repository.OrderBy;
import jakarta.data.repository.Param;
import jakarta.data.repository.Query;
import jakarta.data.repository.Repository;
import jakarta.data.repository.Save;
import jakarta.data.repository.Update;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Fixture repository exercising the query shapes of Jakarta Data 1.0 that Mansart implements: derived queries
 * (every comparator, {@code IgnoreCase}, {@code Not}, {@code Or}, collection {@code In}, first-N, paging),
 * {@code @Query} JDQL (comparisons, {@code BETWEEN}, {@code IS [NOT] NULL}, {@code IN}, {@code NOT}, {@code OR},
 * scalar functions, projections, aggregates, updates and deletes), {@code @Find} with {@code @By} and
 * {@code @OrderBy}, and the lifecycle annotations. {@code GadgetQueriesTest} runs it through both the
 * annotation-processor implementation and the runtime proxy.
 */
@Repository
public interface GadgetRepository extends CrudRepository<Gadget, Long> {

    /* ---- derived queries ---- */

    List<Gadget> findByPriceBetween(double low, double high);

    List<Gadget> findByCategoryIsNull();

    List<Gadget> findByCategoryIsNotNull();

    List<Gadget> findByActiveTrue();

    List<Gadget> findByActiveFalse();

    List<Gadget> findByNameContains(String part);

    List<Gadget> findByNameStartsWith(String prefix);

    List<Gadget> findByNameEndsWith(String suffix);

    List<Gadget> findByNameIgnoreCase(String name);

    List<Gadget> findByNameNot(String name);

    List<Gadget> findByStockLessThan(int stock);

    List<Gadget> findByStockLessThanEqual(int stock);

    List<Gadget> findByStockGreaterThan(int stock);

    List<Gadget> findByStockGreaterThanEqual(int stock);

    List<Gadget> findByStockLessThanOrStockGreaterThan(int low, int high);

    List<Gadget> findByNameIn(List<String> names);

    Stream<Gadget> findByCategoryIn(Collection<String> categories);

    Optional<Gadget> findOneByNameIn(Set<String> names);

    long countByNameIn(List<String> names);

    boolean existsByNameIn(List<String> names);

    long deleteByNameIn(List<String> names);

    long countByActiveTrue();

    boolean existsByNameIgnoreCase(String name);

    int deleteByCategoryIsNull();

    void removeByName(String name);

    Gadget findByName(String name);

    Stream<Gadget> findByCategory(String category);

    Collection<Gadget> findByActive(boolean active);

    Iterable<Gadget> findByStock(int stock);

    Optional<Gadget> findFirstByOrderByPriceDesc();

    List<Gadget> findFirst2ByActiveTrueOrderByPriceAsc();

    Page<Gadget> findByActiveOrderByNameAsc(boolean active, PageRequest pageRequest);

    CursoredPage<Gadget> findByStockGreaterThanOrderByStockAsc(int stock, PageRequest pageRequest);

    /* ---- @Query JDQL ---- */

    @Query("WHERE price BETWEEN ?1 AND ?2 ORDER BY price DESC")
    List<Gadget> pricedBetween(double low, double high);

    @Query("WHERE category IS NULL ORDER BY name")
    List<Gadget> uncategorised();

    @Query("WHERE category IS NOT NULL AND NOT active = ?1 ORDER BY name")
    List<Gadget> categorisedAndNot(boolean active);

    @Query("WHERE stock < ?1 OR stock >= ?2 ORDER BY stock")
    List<Gadget> stockOutside(int low, int high);

    @Query("WHERE stock <= :max AND stock > :min AND name <> :excluded AND name != :other ORDER BY stock ASC")
    List<Gadget> stockWindow(@Param("min") int min, @Param("max") int max,
                             @Param("excluded") String excluded, @Param("other") String other);

    @Query("WHERE name IN (?1, ?2) ORDER BY name")
    List<Gadget> namedEither(String first, String second);

    @Query("WHERE name IN ?1 ORDER BY name")
    List<Gadget> namedAnyOf(List<String> names);

    @Query("WHERE UPPER(name) = ?1")
    Optional<Gadget> byUpperName(String upperName);

    @Query("WHERE LOWER(name) LIKE ?1 ORDER BY name")
    Stream<Gadget> byLowerNameLike(String pattern);

    @Query("WHERE LENGTH(name) BETWEEN ?1 AND ?2 ORDER BY name")
    Collection<Gadget> byNameLength(int shortest, int longest);

    @Query("WHERE UPPER(category) IS NULL ORDER BY name")
    Iterable<Gadget> byUpperCategoryNull();

    @Query("WHERE UPPER(name) IN (?1, ?2) ORDER BY name")
    List<Gadget> byUpperNameEither(String first, String second);

    @Query("WHERE LOWER(name) IN ?1 ORDER BY name")
    List<Gadget> byLowerNameAnyOf(List<String> names);

    @Query("WHERE name IN ?1 AND UPPER(name) <> ?2 AND stock BETWEEN ?3 AND ?4 AND LENGTH(name) BETWEEN ?5 AND ?6"
            + " AND price IS NOT NULL AND UPPER(category) IS NOT NULL AND LOWER(category) IN ?7 ORDER BY name")
    List<Gadget> namedAmongInWindows(List<String> names, String notUpperName, int minStock, int maxStock,
                                     int minLength, int maxLength, List<String> lowerCategories);

    @Query("WHERE ABS(stock) > ?1 OR (category = ?2 AND price < ?3) ORDER BY name")
    List<Gadget> busyOrCheap(int stock, String category, double price);

    @Query("WHERE name = ?1")
    Gadget exactlyNamed(String name);

    @Query("WHERE active = ?1")
    long countByFlag(boolean active);

    @Query("WHERE name = ?1")
    boolean nameTaken(String name);

    @Query("SELECT COUNT(this) WHERE active = ?1")
    long countActive(boolean active);

    @Query("WHERE active = ?1 ORDER BY name")
    Page<Gadget> activePage(boolean active, PageRequest pageRequest);

    @Query("WHERE active = ?1 ORDER BY name")
    CursoredPage<Gadget> activeCursor(boolean active, PageRequest pageRequest);

    @Query("SELECT name WHERE active = ?1 ORDER BY name")
    List<String> activeNames(boolean active);

    @Query("SELECT name WHERE stock > ?1 ORDER BY name")
    Stream<String> namesInStockAbove(int stock);

    @Query("SELECT category WHERE name = ?1")
    Optional<String> categoryOf(String name);

    @Query("SELECT stock WHERE name = ?1")
    int stockOf(String name);

    @Query("SELECT price WHERE name = ?1")
    double priceOf(String name);

    @Query("SELECT name, stock WHERE active = ?1 ORDER BY name")
    List<Object[]> nameAndStock(boolean active);

    @Query("SELECT SUM(stock) WHERE active = ?1")
    int totalStock(boolean active);

    @Query("SELECT AVG(price) WHERE active = ?1")
    double averagePrice(boolean active);

    @Query("SELECT MAX(price) WHERE category = ?1")
    Double highestPrice(String category);

    @Query("SELECT MIN(name) WHERE active = ?1")
    String firstName(boolean active);

    @Query("UPDATE Gadget SET stock = ?1 WHERE name = ?2")
    int restock(int stock, String name);

    @Query("UPDATE Gadget SET active = ?1 WHERE category = ?2")
    void activateCategory(boolean active, String category);

    @Query("UPDATE Gadget SET stock = stock + ?1\n\tWHERE name = ?2")
    long addStock(int delta, String name);

    @Query("UPDATE Gadget SET price = price * ?1 WHERE category = ?2")
    void scalePrices(double factor, String category);

    @Query("DELETE FROM Gadget WHERE stock = ?1")
    int deleteWithStock(int stock);

    @Query("DELETE FROM Gadget WHERE category = ?1")
    void deleteCategory(String category);

    /* ---- @Find ---- */

    @Find
    @OrderBy("price")
    List<Gadget> inCategory(@By("category") String wanted);

    @Find
    @OrderBy("active")
    @OrderBy(value = "price", descending = true)
    List<Gadget> allByActivityThenPrice();

    @Find
    Optional<Gadget> named(String name);

    /* ---- lifecycle ---- */

    @Insert
    Gadget add(Gadget gadget);

    @Insert
    List<Gadget> addAll(List<Gadget> gadgets);

    @Save
    Gadget store(Gadget gadget);

    @Update
    Gadget change(Gadget gadget);

    @Delete
    void drop(Gadget gadget);
}
