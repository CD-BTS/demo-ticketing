package com.bts.demoticketing.seat.service;

import java.util.List;
import java.time.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.bts.demoticketing.common.exception.ApiException;
import com.bts.demoticketing.common.response.ResponseCode;
import com.bts.demoticketing.concert.repository.ConcertRepository;
import com.bts.demoticketing.seat.dto.SeatResponse;
import com.bts.demoticketing.seat.repository.ConcertSeatRepository;

@Service
@Transactional(readOnly = true)
public class SeatQueryService {
    private final ConcertRepository concerts;
    private final com.bts.demoticketing.booking.repository.ReservationRepository reservations;
    private final Clock clock;
    public SeatQueryService(ConcertRepository concerts, com.bts.demoticketing.booking.repository.ReservationRepository reservations,Clock clock) {
        this.concerts = concerts;
        this.reservations = reservations;
        this.clock=clock;
    }
    public List<SeatResponse> list(String concertId) {
        return list(concertId,null);
    }
    public List<SeatResponse> list(String concertId,Long userId) {
        if (!concerts.existsByPublicId(concertId)) {
            throw new ApiException(ResponseCode.CONCERT_NOT_FOUND);
        }
        return reservations.visibleSeats(concertId,userId,LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));
    }
}
