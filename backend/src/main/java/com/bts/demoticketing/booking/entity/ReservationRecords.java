package com.bts.demoticketing.booking.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// Domain entities mapped explicitly by the JDBC repository; distinct from HTTP DTOs.
public final class ReservationRecords {
    private ReservationRecords(){}
    public record Concert(long id,String publicId,String title,String venue,LocalDateTime startAt,LocalDateTime endAt){}
    public record Hold(long id,String publicId,long userId,long concertId,String status,LocalDateTime expiresAt,LocalDateTime createdAt){}
    public record Seat(long id,String code,String grade,long price,String state,Long holdId,Long bookingId){}
    public record Booking(long id,String publicId,long userId,long concertId,long holdId,String status,long totalPrice,
            String title,String venue,LocalDateTime startAt,LocalDateTime endAt,LocalDateTime createdAt,
            LocalDateTime confirmedAt,BigDecimal duration,String paymentIdentifier,String ipAddress){}
}
