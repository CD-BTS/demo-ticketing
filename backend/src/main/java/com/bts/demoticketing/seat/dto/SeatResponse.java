package com.bts.demoticketing.seat.dto;

import com.bts.demoticketing.seat.entity.*;

public record SeatResponse(String id, String row, int number, SeatGrade grade,
        long price, SeatStatus status, boolean heldByMe) {
    public static SeatResponse from(ConcertSeat seat) {
        // No user authentication/hold ownership is implemented in phase 1.
        return new SeatResponse(seat.getSeatCode(), seat.getRow(), seat.getNumber(),
                seat.getGrade(), seat.getPrice(), seat.getState(), false);
    }
}
