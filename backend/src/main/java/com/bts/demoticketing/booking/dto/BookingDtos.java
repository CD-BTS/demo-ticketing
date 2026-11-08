package com.bts.demoticketing.booking.dto;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import jakarta.validation.constraints.*;
import com.bts.demoticketing.booking.entity.ReservationRecords.Booking;

public final class BookingDtos {
    private BookingDtos(){}
    public record HoldRequest(@NotNull @Size(min=1,max=4) List<@NotBlank @Size(max=20) String> seatIds,
            @Size(max=36) String attemptId,@Size(max=36) String transitionId){}
    public record ConfirmRequest(@NotBlank @Size(max=50) String holdId,
            @NotBlank @Size(max=100) String paymentIdentifier){}
    public record HoldResponse(String holdId,String concertId,List<String> seatIds,OffsetDateTime expiresAt){}
    public record BookingResponse(String id,String concertId,String concertTitle,String venue,OffsetDateTime startAt,
            List<String> seatIds,long totalPrice,String status,OffsetDateTime createdAt){
        public static BookingResponse from(Booking b,String concertId,List<String> seats){
            return new BookingResponse(b.publicId(),concertId,b.title(),b.venue(),b.startAt().atOffset(ZoneOffset.UTC),
                    seats,b.totalPrice(),b.status(),b.createdAt().atOffset(ZoneOffset.UTC));
        }
    }
    public record Confirmation(BookingResponse booking,boolean created){}
}
