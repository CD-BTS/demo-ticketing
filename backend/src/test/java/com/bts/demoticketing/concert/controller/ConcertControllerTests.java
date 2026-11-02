package com.bts.demoticketing.concert.controller;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.bts.demoticketing.common.exception.*;
import com.bts.demoticketing.common.response.ResponseCode;
import com.bts.demoticketing.concert.dto.ConcertResponse;
import com.bts.demoticketing.concert.service.ConcertService;
import com.bts.demoticketing.seat.dto.SeatResponse;
import com.bts.demoticketing.seat.entity.*;
import com.bts.demoticketing.seat.service.SeatQueryService;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ConcertController.class)
@Import(GlobalExceptionHandler.class)
class ConcertControllerTests {
    @Autowired private MockMvc mvc;
    @MockitoBean private ConcertService concerts;
    @MockitoBean private SeatQueryService seats;
    @MockitoBean private com.bts.demoticketing.user.service.DemoAuthService auth;
    private static final ConcertResponse CONCERT = new ConcertResponse(
            "c1", "공연", "Artist", "서울",
            OffsetDateTime.parse("2026-12-12T09:00:00Z"), 99000, "tagline", 265);

    @Test
    void listAndDetailPreserveFrontendContract() throws Exception {
        when(concerts.list()).thenReturn(List.of(CONCERT));
        when(concerts.get("c1")).thenReturn(CONCERT);
        mvc.perform(get("/api/v1/concerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("COMMON_200"))
                .andExpect(jsonPath("$.message").value("요청이 성공했습니다."))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value("c1"))
                .andExpect(jsonPath("$.data[0].startAt").value("2026-12-12T09:00:00Z"))
                .andExpect(jsonPath("$.data[0].priceFrom").value(99000))
                .andExpect(jsonPath("$.data[0].start_at").doesNotExist());
        mvc.perform(get("/api/v1/concerts/c1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", aMapWithSize(8)))
                .andExpect(jsonPath("$.data.venue").value("서울"))
                .andExpect(jsonPath("$.data.endAt").doesNotExist());
    }

    @Test
    void seatListReturnsPublicSeatCodeAndEnums() throws Exception {
        when(seats.list("c1")).thenReturn(List.of(
                new SeatResponse("A-05", "A", 5, SeatGrade.VIP, 154000, SeatStatus.AVAILABLE, false)));
        mvc.perform(get("/api/v1/concerts/c1/seats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]", aMapWithSize(7)))
                .andExpect(jsonPath("$.data[0].id").value("A-05"))
                .andExpect(jsonPath("$.data[0].grade").value("VIP"))
                .andExpect(jsonPath("$.data[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data[0].heldByMe").value(false))
                .andExpect(jsonPath("$.data[0].concert").doesNotExist());
    }

    @Test
    void emptyCollectionsAreArrays() throws Exception {
        when(concerts.list()).thenReturn(List.of());
        when(seats.list("empty")).thenReturn(List.of());
        mvc.perform(get("/api/v1/concerts")).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/api/v1/concerts/empty/seats")).andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void missingConcertAlsoFailsSeatQuery() throws Exception {
        when(concerts.get("missing")).thenThrow(new ApiException(ResponseCode.CONCERT_NOT_FOUND));
        when(seats.list("missing")).thenThrow(new ApiException(ResponseCode.CONCERT_NOT_FOUND));
        for (String path : List.of("/missing", "/missing/seats")) {
            mvc.perform(get("/api/v1/concerts" + path))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("CONCERT_NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("공연을 찾을 수 없습니다."))
                    .andExpect(jsonPath("$.data").value(nullValue()));
        }
    }

    @Test
    void invalidPathNeverReachesService() throws Exception {
        for (String id : List.of("x".repeat(51), "   ")) {
            mvc.perform(get("/api/v1/concerts/{concertId}", id))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.data.errors").isArray());
        }
        verifyNoInteractions(concerts, seats);
    }

    @Test
    void mvcErrorsPreserveStatusAndAllowHeader() throws Exception {
        mvc.perform(post("/api/v1/concerts"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(get("/api/v1/no-such-route"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void internalErrorsDoNotExposeExceptionDetails() throws Exception {
        when(concerts.list()).thenThrow(new IllegalStateException("private database credential"));
        mvc.perform(get("/api/v1/concerts"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."))
                .andExpect(content().string(not(containsString("private database credential"))));
    }
}
