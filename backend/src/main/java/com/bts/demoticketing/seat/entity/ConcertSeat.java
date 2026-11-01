package com.bts.demoticketing.seat.entity;

import jakarta.persistence.*;
import com.bts.demoticketing.concert.entity.Concert;

@Entity
@Table(name = "concert_seats", uniqueConstraints =
        @UniqueConstraint(columnNames = {"concert_id", "seat_code"}))
public class ConcertSeat {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "concert_id", nullable = false)
    private Concert concert;
    @Column(name = "seat_code", nullable = false, length = 20)
    private String seatCode;
    @Column(name = "row_label", nullable = false, length = 10)
    private String row;
    @Column(name = "seat_number", nullable = false)
    private int number;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10)
    private SeatGrade grade;
    @Column(nullable = false)
    private long price;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private SeatStatus state;
    @Version
    private long version;

    protected ConcertSeat() {}
    public String getSeatCode() { return seatCode; }
    public String getRow() { return row; }
    public int getNumber() { return number; }
    public SeatGrade getGrade() { return grade; }
    public long getPrice() { return price; }
    public SeatStatus getState() { return state; }
}
