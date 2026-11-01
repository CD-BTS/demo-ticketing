package com.bts.demoticketing.concert.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import com.bts.demoticketing.concert.entity.Concert;

public interface ConcertRepository extends JpaRepository<Concert, Long> {
    List<Concert> findAllByOrderByStartAtAscPublicIdAsc();
    Optional<Concert> findByPublicId(String publicId);
    boolean existsByPublicId(String publicId);
}
