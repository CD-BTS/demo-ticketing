package com.bts.demoticketing.booking;

import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.bts.demoticketing.booking.service.BookingJobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockingDetails;

@Tag("mysql")
@EnabledIfEnvironmentVariable(named="SEAT_RECOVERY_ISOLATED",matches="[a-f0-9]{12}")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "demo.jobs.enabled=true","demo.expiry-poll-ms=5000","bts.enabled=false","demo.hold-duration=PT5M",
        "demo.queue.capacity=150","demo.policy.mode=disabled","demo.captcha.mode=disabled"})
@Import(SeatRecoverySupport.TimeConfig.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class SeatRecoverySchedulerMySqlTests extends SeatRecoverySupport {
    // 실제 메서드를 실행하는 Spy이며, expire()를 테스트에서 직접 호출하거나 대체하지 않는다.
    @MockitoSpyBean BookingJobs jobs;
    long scheduledScans(){return mockingDetails(jobs).getInvocations().stream().filter(i->i.getMethod().getName().equals("expire")).count();}

    @Test void realSchedulerReleasesFiveMinuteHoldWithoutAnyFurtherUserRequest()throws Exception{
        var a=actor();String original=hold(a,"A-01","A-02");Instant deadline=expiry(original);
        clock.set(started.plus(Duration.ofMinutes(2)));var control=actor();String future=hold(control,"A-03");
        clock.set(deadline.minusNanos(1000));
        long scansBefore=scheduledScans();int requestsBefore=httpCalls.get();
        // 5분 직전에 실제 스케줄러가 실행되어도 아직 유효한 선점은 유지되어야 한다.
        awaitSql(()->scheduledScans()>scansBefore);
        assertEquals("ACTIVE",holdStatus(original));assertEquals("HELD",state("A-01"));
        clock.set(deadline);long measuredFrom=System.nanoTime();
        // 여기부터 물리적 해제 확인까지는 JDBC SELECT만 수행한다.
        awaitSql(()->"EXPIRED".equals(holdStatus(original))&&"AVAILABLE".equals(state("A-01"))&&"AVAILABLE".equals(state("A-02")));
        assertEquals(requestsBefore,httpCalls.get());available("A-01","A-02");
        assertEquals("ACTIVE",holdStatus(future));assertEquals("HELD",state("A-03"));
        assertEquals(0,count("SELECT COUNT(*) FROM bookings WHERE concert_id=?"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE user_id=?",Long.class,a.id()));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM seat_hold_items i JOIN seat_holds h ON h.id=i.hold_id WHERE h.public_id=?",Long.class,original));
        System.out.printf("자동 물리적 해제 검증: 추가 HTTP=0, 직접 expire 호출=0, 스케줄러 주기=5000ms, 관측 지연=%.2fms, scans=%d%n",
                (System.nanoTime()-measuredFrom)/1_000_000.0,scheduledScans()-scansBefore);
        // 자동 복구를 SQL로 확인한 다음에만 사용자 요청을 다시 보낸다.
        assertEquals("HOLD_EXPIRED",expect(confirm(a,original),410).code());
        var b=actor();String resale=book(b,hold(b,"A-01","A-02"));ticketStatus(resale,"ISSUED",2);
        expect(api("DELETE","/seat-holds/"+future,control,null),200);available("A-03");
    }
    @Test void schedulerExpiresOnlyActiveHoldAndDoesNotReleaseConfirmedBooking()throws Exception{
        var a=actor();var b=actor();String converted=hold(a,"A-01");String booking=book(a,converted);
        String expiring=hold(b,"A-02");int requestsBefore=httpCalls.get();clock.set(expiry(expiring));
        awaitSql(()->"EXPIRED".equals(holdStatus(expiring))&&"AVAILABLE".equals(state("A-02")));
        assertEquals(requestsBefore,httpCalls.get());available("A-02");
        assertEquals("CONVERTED",holdStatus(converted));assertEquals("CONFIRMED",bookingStatus(booking));
        assertEquals("BOOKED",state("A-01"));ticketStatus(booking,"ISSUED",1);assertEquals(0,events(booking,"CANCEL"));
        expect(cancel(a,booking),200);available("A-01","A-02");
    }
}
