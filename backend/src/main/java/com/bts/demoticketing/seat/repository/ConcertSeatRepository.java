package com.bts.demoticketing.seat.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import com.bts.demoticketing.seat.entity.ConcertSeat;

public interface ConcertSeatRepository extends JpaRepository<ConcertSeat, Long> {
    List<ConcertSeat> findByConcertPublicIdOrderByRowAscNumberAsc(String publicId);
}
