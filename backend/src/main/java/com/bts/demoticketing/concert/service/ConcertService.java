package com.bts.demoticketing.concert.service;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.bts.demoticketing.common.exception.ApiException;
import com.bts.demoticketing.common.response.ResponseCode;
import com.bts.demoticketing.concert.dto.ConcertResponse;
import com.bts.demoticketing.concert.repository.ConcertRepository;

@Service
@Transactional(readOnly = true)
public class ConcertService {
    private final ConcertRepository concerts;
    public ConcertService(ConcertRepository concerts) { this.concerts = concerts; }

    public List<ConcertResponse> list() {
        return concerts.findAllByOrderByStartAtAscPublicIdAsc().stream().map(ConcertResponse::from).toList();
    }
    public ConcertResponse get(String publicId) {
        return ConcertResponse.from(concerts.findByPublicId(publicId)
                .orElseThrow(() -> new ApiException(ResponseCode.CONCERT_NOT_FOUND)));
    }
}
