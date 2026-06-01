package io.vidocq.mansart.data.tests;

import jakarta.data.repository.BasicRepository;
import jakarta.data.repository.Repository;

import java.util.List;

/**
 * Regression repository for MANSART-002. The 4-condition {@code And} chain below must parse into four
 * predicates (it previously failed: only the first {@code And} was split, the rest bundled into an
 * unresolvable token → silent {@code UnsupportedOperationException} stub). The 2-condition method is
 * the control that always worked.
 */
@Repository
public interface LabSeatRepository extends BasicRepository<LabSeat, Long> {

    /** Control: two-condition And (always supported). */
    List<LabSeat> findByRoomIdAndReleased(String roomId, boolean released);

    /** The regression: four-condition And chain (String, int, int, boolean). */
    List<LabSeat> findByRoomIdAndSeatRowAndSeatBlockIndexAndReleased(
            String roomId, int seatRow, int seatBlockIndex, boolean released);
}
