package com.bts.demoticketing.concert.controller;

import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import com.bts.demoticketing.common.response.ApiResponse;
import com.bts.demoticketing.concert.dto.ConcertResponse;
import com.bts.demoticketing.concert.service.ConcertService;
import com.bts.demoticketing.seat.dto.SeatResponse;
import com.bts.demoticketing.seat.service.SeatQueryService;

@RestController
@RequestMapping("/api/v1/concerts")
public class ConcertController {
    private final ConcertService concerts;
    private final SeatQueryService seats;
    private final com.bts.demoticketing.user.service.DemoAuthService auth;

    public ConcertController(ConcertService concerts, SeatQueryService seats,com.bts.demoticketing.user.service.DemoAuthService auth) {
        this.concerts = concerts;
        this.seats = seats;
        this.auth=auth;
    }
    @GetMapping
    public ApiResponse<List<ConcertResponse>> list() {
        return ApiResponse.success(concerts.list());
    }
    @GetMapping("/{concertId}")
    public ApiResponse<ConcertResponse> get(
            @PathVariable @NotBlank @Size(max = 50) String concertId) {
        return ApiResponse.success(concerts.get(concertId));
    }
    @GetMapping("/{concertId}/seats")
    public ApiResponse<List<SeatResponse>> seats(
            @PathVariable @NotBlank @Size(max = 50) String concertId,HttpServletRequest request) {
        var user=auth.optional(request);
        return ApiResponse.success(user==null?seats.list(concertId):seats.list(concertId,user.id()));
    }
}
