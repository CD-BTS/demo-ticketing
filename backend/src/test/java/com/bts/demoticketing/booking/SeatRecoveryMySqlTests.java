package com.bts.demoticketing.booking;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.bts.demoticketing.outbox.OutboxRepository;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("mysql")
@EnabledIfEnvironmentVariable(named="SEAT_RECOVERY_ISOLATED",matches="[a-f0-9]{12}")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "demo.jobs.enabled=false","bts.enabled=false","demo.hold-duration=PT5M","demo.queue.capacity=150",
        "demo.policy.mode=disabled","demo.captcha.mode=disabled"})
@Import(SeatRecoverySupport.TimeConfig.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class SeatRecoveryMySqlTests extends SeatRecoverySupport {
    @MockitoSpyBean OutboxRepository outbox;

    @Test void httpCancellationAndResalePreserveHistoryAndClearOccupancy()throws Exception{
        var a=actor();var b=actor();String original=hold(a,"A-01","A-02");
        assertEquals("HELD",state("A-01"));assertEquals("ACTIVE",holdStatus(original));
        String booking=book(a,original);
        assertEquals("BOOKED",state("A-01"));assertEquals("CONVERTED",holdStatus(original));
        ticketStatus(booking,"ISSUED",2);assertEquals(1,events(booking,"BOOKING"));
        expect(cancel(a,booking),200);
        assertEquals("CANCELLED",bookingStatus(booking));ticketStatus(booking,"CANCELLED",2);
        assertNotNull(jdbc.queryForObject("SELECT cancelled_at FROM bookings WHERE public_id=?",LocalDateTime.class,booking));
        available("A-01","A-02");assertEquals(1,events(booking,"CANCEL"));
        var mine=expect(api("GET","/bookings/me",a,null),200).list();
        assertTrue(mine.stream().anyMatch(row->booking.equals(row.get("id"))&&"CANCELLED".equals(row.get("status"))));
        var seats=expect(api("GET","/concerts/"+concert+"/seats",b,null),200).list();
        assertEquals(2,seats.stream().filter(row->List.of("A-01","A-02").contains(row.get("id"))&&"AVAILABLE".equals(row.get("status"))).count());
        String replacement=hold(b,"A-01","A-02");String resale=book(b,replacement);
        assertNotEquals(booking,resale);expect(cancel(a,booking),200);
        assertEquals("BOOKED",state("A-01"));assertEquals("CONFIRMED",bookingStatus(resale));
        ticketStatus(booking,"CANCELLED",2);ticketStatus(resale,"ISSUED",2);
        assertEquals(1,events(booking,"CANCEL"));assertEquals(1,events(resale,"BOOKING"));
        assertEquals(2,count("SELECT COUNT(*) FROM seat_holds WHERE concert_id=?"));
        assertEquals(4,count("SELECT COUNT(*) FROM seat_hold_items i JOIN seat_holds h ON h.id=i.hold_id WHERE h.concert_id=?"));
        assertEquals(2,count("SELECT COUNT(*) FROM bookings WHERE concert_id=?"));
        assertEquals(4,count("SELECT COUNT(*) FROM booking_seats i JOIN bookings b ON b.id=i.booking_id WHERE b.concert_id=?"));
    }
    @Test void foreignCancellationIsForbiddenAndConcurrentDuplicatesCreateOneCancelEvent()throws Exception{
        var a=actor();var b=actor();String booking=book(a,hold(a,"A-01"));
        assertEquals("FORBIDDEN",expect(cancel(b,booking),403).code());
        assertEquals("CONFIRMED",bookingStatus(booking));assertEquals(0,events(booking,"CANCEL"));
        List<Reply> results=race(List.of(()->cancel(a,booking),()->cancel(a,booking)));
        for(var result:results){expect(result,200);assertEquals("CANCELLED",result.object().get("status"));}
        available("A-01");ticketStatus(booking,"CANCELLED",1);assertEquals(1,events(booking,"CANCEL"));
    }
    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void fiveMinuteBoundaryUsesStoredMicrosecondTimeAndLogicalExpiryDoesNotReleaseDb(long microseconds)throws Exception{
        var a=actor();var b=actor();String original=hold(a,"A-01");
        clock.set(expiry(original).plusNanos(microseconds*1000));
        assertEquals("HELD",state("A-01"));
        var visible=expect(api("GET","/concerts/"+concert+"/seats",b,null),200).list().stream()
                .filter(row->"A-01".equals(row.get("id"))).findFirst().orElseThrow();
        if(microseconds<0){
            assertEquals("HELD",visible.get("status"));book(a,original);
            assertEquals("BOOKED",state("A-01"));
            expect(api("POST","/concerts/"+concert+"/seat-holds",b,Map.of("seatIds",List.of("A-01"))),409);
        }else{
            assertEquals("AVAILABLE",visible.get("status"));
            assertEquals("HOLD_EXPIRED",expect(confirm(a,original),410).code());
            // 조회상 AVAILABLE/확정 거부와 물리적 해제는 다르다. 이 컨텍스트는 스케줄러가 꺼져 있다.
            assertEquals("HELD",state("A-01"));assertEquals("ACTIVE",holdStatus(original));
            assertEquals(0,count("SELECT COUNT(*) FROM bookings WHERE concert_id=?"));
            String next=hold(b,"A-01");assertNotEquals(original,next);assertEquals("EXPIRED",holdStatus(original));
            book(b,next);
        }
        System.out.printf("5분 경계 검증: offsetMicros=%d, original=%s%n",microseconds,holdStatus(original));
    }
    @Test void expiredMultiSeatHoldKeepsItemsAndDoesNotCreateCancellationEvent()throws Exception{
        var a=actor();var b=actor();String original=hold(a,"A-01","A-02");
        clock.set(expiry(original));expect(confirm(a,original),410);
        hold(b,"A-01");available("A-02");assertEquals("EXPIRED",holdStatus(original));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM seat_hold_items i JOIN seat_holds h ON h.id=i.hold_id WHERE h.public_id=?",Long.class,original));
        assertEquals(0,count("SELECT COUNT(*) FROM bookings WHERE concert_id=?"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE user_id=?",Long.class,a.id()));
    }
    @Test void cancelEventFailureRollsBackBookingTicketsSeatsAndTimestamp()throws Exception{
        var a=actor();String booking=book(a,hold(a,"A-01","A-02"));
        var before=jdbc.queryForList("SELECT state,active_hold_id,active_booking_id,version FROM concert_seats WHERE concert_id=? ORDER BY id",concertId);
        doAnswer(invocation->{
            if("CANCEL".equals(invocation.getArgument(3)))throw new IllegalStateException("취소 Outbox 저장 실패 주입");
            return invocation.callRealMethod();
        }).when(outbox).append(anyLong(),anyString(),anyString(),anyString(),any(),anyString(),any());
        assertEquals("INTERNAL_SERVER_ERROR",expect(cancel(a,booking),500).code());
        assertEquals(before,jdbc.queryForList("SELECT state,active_hold_id,active_booking_id,version FROM concert_seats WHERE concert_id=? ORDER BY id",concertId));
        assertEquals("CONFIRMED",bookingStatus(booking));ticketStatus(booking,"ISSUED",2);
        assertNull(jdbc.queryForObject("SELECT cancelled_at FROM bookings WHERE public_id=?",LocalDateTime.class,booking));
        assertEquals(1,events(booking,"BOOKING"));assertEquals(0,events(booking,"CANCEL"));
        reset(outbox);expect(cancel(a,booking),200);available("A-01","A-02");
    }
    @RepeatedTest(3) void cancellationRacingAnotherUsersHoldKeepsResaleConsistent()throws Exception{
        var a=actor();var b=actor();String booking=book(a,hold(a,"A-01"));
        List<Reply> results=race(List.of(()->cancel(a,booking),()->api("POST","/concerts/"+concert+"/seat-holds",b,Map.of("seatIds",List.of("A-01")))));
        expect(results.get(0),200);var next=results.get(1);assertTrue(next.status()==201||next.status()==409);
        String nextHold=next.status()==201?next.object().get("holdId").toString():hold(b,"A-01");
        String resale=book(b,nextHold);expect(cancel(a,booking),200);
        assertEquals("CONFIRMED",bookingStatus(resale));assertEquals(1,events(booking,"CANCEL"));
    }
    @RepeatedTest(3) void expiredConfirmationRacingReclaimCannotBookTheOldHold()throws Exception{
        var a=actor();var b=actor();String original=hold(a,"A-01","A-02");clock.set(expiry(original));
        List<Reply> results=race(List.of(()->confirm(a,original),()->api("POST","/concerts/"+concert+"/seat-holds",b,Map.of("seatIds",List.of("A-01","A-02")))));
        expect(results.get(0),410);String next=expect(results.get(1),201).object().get("holdId").toString();
        assertEquals("EXPIRED",holdStatus(original));String resale=book(b,next);ticketStatus(resale,"ISSUED",2);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM bookings WHERE user_id=?",Long.class,a.id()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE user_id=?",Long.class,a.id()));
    }
}
