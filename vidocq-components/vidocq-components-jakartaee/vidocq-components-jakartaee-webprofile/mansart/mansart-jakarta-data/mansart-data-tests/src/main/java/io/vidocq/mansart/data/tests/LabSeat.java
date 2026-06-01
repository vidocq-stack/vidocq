package io.vidocq.mansart.data.tests;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Regression fixture for MANSART-002: an entity with enough comparable fields to exercise a derived
 * query with a long {@code And} chain ({@code findByRoomIdAndSeatRowAndSeatBlockIndexAndReleased}).
 * Mirrors the Arago LAB seat that first surfaced the bug (3+ {@code And} conditions were silently
 * mis-parsed into an unsupported method stub).
 */
@Entity
@Table(name = "lab_seats")
public class LabSeat {

    @Id @GeneratedValue
    private Long id;

    @Column(nullable = false)
    private String roomId;

    @Column(nullable = false)
    private int seatRow;

    @Column(nullable = false)
    private int seatBlockIndex;

    @Column(nullable = false)
    private boolean released;

    public Long    getId()                       { return id; }
    public void    setId(Long id)                { this.id = id; }
    public String  getRoomId()                   { return roomId; }
    public void    setRoomId(String roomId)      { this.roomId = roomId; }
    public int     getSeatRow()                  { return seatRow; }
    public void    setSeatRow(int seatRow)       { this.seatRow = seatRow; }
    public int     getSeatBlockIndex()           { return seatBlockIndex; }
    public void    setSeatBlockIndex(int i)      { this.seatBlockIndex = i; }
    public boolean isReleased()                  { return released; }
    public void    setReleased(boolean released) { this.released = released; }
}
