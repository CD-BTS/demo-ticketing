package com.bts.demoticketing.booking;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.context.annotation.Import;
import com.bts.demoticketing.booking.dto.BookingDtos.*;
import com.bts.demoticketing.booking.repository.ReservationRepository;
import com.bts.demoticketing.booking.service.ReservationService;
import com.bts.demoticketing.common.exception.ApiException;
import com.bts.demoticketing.queue.QueueService;
import com.bts.demoticketing.user.entity.DemoUser;
import com.bts.demoticketing.user.repository.DemoUserRepository;
import com.bts.demoticketing.user.service.DemoAuthService;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("mysql")
@SpringBootTest(properties={"demo.jobs.enabled=false","bts.enabled=false","demo.queue.capacity=150",
        "demo.policy.mode=disabled","demo.captcha.mode=disabled"})
@Import(ReservationMySqlTests.TimeConfig.class)
class SeatConcurrencyMySqlTests {
    static final String PREFIX="demo-ticketing:queue:seat-test:"+UUID.randomUUID();
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("demo.queue.key-prefix",()->PREFIX);}
    @Autowired ReservationService service;
    @MockitoSpyBean ReservationRepository repo;
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoAuthService auth;
    @Autowired DemoUserRepository users;
    @Autowired QueueService queue;
    @Autowired StringRedisTemplate redis;
    @Autowired ReservationMySqlTests.MutableClock clock;
    long concertId;
    String concert;
    final List<DemoUser> actors=new ArrayList<>();

    @BeforeEach void setup(){
        clock.time.set(Instant.now());
        concert="sc_"+UUID.randomUUID();
        jdbc.update("""
                INSERT INTO concerts(public_id,title,artist,venue,start_at,end_at,price_from,tagline,hue)
                VALUES(?,'concurrency','test','test','2027-12-12 09:00:00','2027-12-12 12:00:00',100,'test',0)
                """,concert);
        concertId=jdbc.queryForObject("SELECT id FROM concerts WHERE public_id=?",Long.class,concert);
        for(int i=1;i<=100;i++)jdbc.update("INSERT INTO concert_seats(concert_id,seat_code,row_label,seat_number,grade,price,state) VALUES(?,?,'A',?,'VIP',100,'AVAILABLE')",concertId,"S"+i,i);
    }
    DemoUser actor(){
        var login=auth.login("sc_"+UUID.randomUUID());
        var user=users.find(jdbc.queryForObject("SELECT id FROM demo_users WHERE public_user_id=?",Long.class,login.userId())).orElseThrow();
        actors.add(user);
        assertEquals("ADMITTED",queue.execute(concert,user.id(),"join").snapshot().status());
        return user;
    }
    HoldResponse hold(DemoUser user,String... seats){return service.hold(concert,user,List.of(seats));}
    Confirmation confirm(DemoUser user,HoldResponse hold){return service.confirm(hold.holdId(),"test-pay-shared-01","127.0.0.1",user);}
    String outcome(Supplier<?> work){try{work.get();return "OK";}catch(ApiException e){return e.getResponseCode().getCode();}}
    <T> List<T> race(List<Callable<T>> jobs)throws Exception{
        var start=new CountDownLatch(1);
        var ready=new CountDownLatch(jobs.size());
        var pool=Executors.newFixedThreadPool(jobs.size());
        try{
            List<Future<T>> tasks=new ArrayList<>();
            for(var job:jobs)tasks.add(pool.submit(()->{ready.countDown();assertTrue(start.await(20,TimeUnit.SECONDS));return job.call();}));
            assertTrue(ready.await(20,TimeUnit.SECONDS));start.countDown();
            List<T> result=new ArrayList<>();for(var task:tasks)result.add(task.get(60,TimeUnit.SECONDS));return result;
        }finally{start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
    }
    long count(String sql){return jdbc.queryForObject(sql,Long.class,concertId);}
    void consistent(){
        assertEquals(0,count("""
                SELECT COUNT(*) FROM concert_seats s LEFT JOIN seat_holds h ON h.id=s.active_hold_id
                LEFT JOIN bookings b ON b.id=s.active_booking_id WHERE s.concert_id=? AND (
                  (s.state='AVAILABLE' AND (s.active_hold_id IS NOT NULL OR s.active_booking_id IS NOT NULL)) OR
                  (s.state='HELD' AND (h.id IS NULL OR h.status<>'ACTIVE' OR s.active_booking_id IS NOT NULL OR NOT EXISTS
                    (SELECT 1 FROM seat_hold_items i WHERE i.hold_id=h.id AND i.concert_seat_id=s.id))) OR
                  (s.state='BOOKED' AND (b.id IS NULL OR b.status<>'CONFIRMED' OR s.active_hold_id IS NOT NULL OR NOT EXISTS
                    (SELECT 1 FROM booking_seats i WHERE i.booking_id=b.id AND i.concert_seat_id=s.id))))
                """));
        assertEquals(0,count("""
                SELECT COUNT(*) FROM seat_hold_items i JOIN seat_holds h ON h.id=i.hold_id
                JOIN concert_seats s ON s.id=i.concert_seat_id WHERE h.concert_id=? AND h.status='ACTIVE'
                AND (s.state<>'HELD' OR NOT (s.active_hold_id <=> h.id))
                """));
        assertEquals(0,count("SELECT COUNT(*) FROM (SELECT user_id FROM seat_holds WHERE concert_id=? AND status='ACTIVE' GROUP BY user_id HAVING COUNT(*)>1) x"));
        assertEquals(0,count("SELECT COUNT(*) FROM (SELECT i.concert_seat_id FROM booking_seats i JOIN bookings b ON b.id=i.booking_id WHERE b.concert_id=? AND b.status='CONFIRMED' GROUP BY i.concert_seat_id HAVING COUNT(*)>1) x"));
        assertEquals(0,count("""
                SELECT COUNT(*) FROM booking_seats i JOIN bookings b ON b.id=i.booking_id
                JOIN concert_seats s ON s.id=i.concert_seat_id WHERE b.concert_id=? AND (
                  (b.status='CONFIRMED' AND (s.state<>'BOOKED' OR NOT(s.active_booking_id <=> b.id))) OR
                  (SELECT COUNT(*) FROM admission_tickets t WHERE t.booking_id=b.id AND t.concert_seat_id=s.id
                    AND t.status=IF(b.status='CANCELLED','CANCELLED','ISSUED'))<>1)
                """));
        assertEquals(0,count("""
                SELECT COUNT(*) FROM bookings b JOIN seat_holds h ON h.id=b.hold_id WHERE b.concert_id=? AND
                (h.status<>'CONVERTED' OR (SELECT COUNT(*) FROM outbox_events e WHERE e.aggregate_id=b.public_id AND e.event_type='BOOKING')<>1
                OR (SELECT COUNT(*) FROM outbox_events e WHERE e.aggregate_id=b.public_id AND e.event_type='CANCEL')<>IF(b.status='CANCELLED',1,0))
                """));
    }
    @AfterEach void cleanup(){
        try{consistent();}finally{
            reset(repo);
            jdbc.update("DELETE FROM outbox_events WHERE aggregate_id IN (SELECT public_id FROM bookings WHERE concert_id=?)",concertId);
            jdbc.update("UPDATE concert_seats SET state='AVAILABLE',active_hold_id=NULL,active_booking_id=NULL WHERE concert_id=?",concertId);
            jdbc.update("DELETE t FROM admission_tickets t JOIN bookings b ON b.id=t.booking_id WHERE b.concert_id=?",concertId);
            jdbc.update("DELETE i FROM booking_seats i JOIN bookings b ON b.id=i.booking_id WHERE b.concert_id=?",concertId);
            jdbc.update("DELETE FROM bookings WHERE concert_id=?",concertId);
            jdbc.update("DELETE i FROM seat_hold_items i JOIN seat_holds h ON h.id=i.hold_id WHERE h.concert_id=?",concertId);
            jdbc.update("DELETE FROM seat_holds WHERE concert_id=?",concertId);
            jdbc.update("DELETE FROM concert_seats WHERE concert_id=?",concertId);
            jdbc.update("DELETE FROM concerts WHERE id=?",concertId);
            for(var user:actors){jdbc.update("DELETE FROM demo_sessions WHERE user_id=?",user.id());jdbc.update("DELETE FROM demo_users WHERE id=?",user.id());}
            var keys=redis.keys(PREFIX+":*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);
        }
    }
    @ParameterizedTest @ValueSource(ints={2,10,100})
    void sameSeatHasExactlyOneWinner(int n)throws Exception{
        List<Callable<String>> work=new ArrayList<>();
        for(int i=0;i<n;i++){var user=actor();work.add(()->outcome(()->hold(user,"S1")));}
        var results=race(work);
        assertEquals(1,Collections.frequency(results,"OK"));assertEquals(n-1,Collections.frequency(results,"SEAT_ALREADY_TAKEN"));
        assertEquals(1,count("SELECT COUNT(*) FROM seat_holds WHERE concert_id=?"));
        assertEquals(1,count("SELECT COUNT(*) FROM concert_seats WHERE concert_id=? AND state='HELD'"));
    }
    @ParameterizedTest @ValueSource(ints={2,10,100})
    void differentSeatsAllSucceed(int n)throws Exception{
        List<Callable<String>> work=new ArrayList<>();
        for(int i=1;i<=n;i++){var user=actor();String seat="S"+i;work.add(()->outcome(()->hold(user,seat)));}
        assertEquals(n,Collections.frequency(race(work),"OK"));
        assertEquals(n,count("SELECT COUNT(*) FROM concert_seats WHERE concert_id=? AND state='HELD'"));
    }
    @Test void differentSeatCommitsWhileFirstTransactionIsStillLocked()throws Exception{
        var a=actor();var b=actor();var c=actor();
        long firstSeat=jdbc.queryForObject("SELECT id FROM concert_seats WHERE concert_id=? AND seat_code='S1'",Long.class,concertId);
        var acquired=new CountDownLatch(1);var release=new CountDownLatch(1);var paused=new AtomicBoolean();
        doAnswer(invocation->{
            long start=System.nanoTime();Object result=invocation.callRealMethod();
            Collection<?> ids=invocation.getArgument(0,Collection.class);
            if(ids.contains(firstSeat)&&paused.compareAndSet(false,true)){
                acquired.countDown();assertTrue(release.await(15,TimeUnit.SECONDS));
            }
            System.out.printf("좌석 잠금 호출: ids=%s, elapsedMs=%.2f%n",ids,(System.nanoTime()-start)/1_000_000.0);
            return result;
        }).when(repo).lockSeats(anyCollection());
        var pool=Executors.newFixedThreadPool(3);
        try{
            var first=pool.submit(()->hold(a,"S1"));assertTrue(acquired.await(10,TimeUnit.SECONDS));
            long start=System.nanoTime();var independent=pool.submit(()->hold(b,"S2"));
            assertNotNull(independent.get(5,TimeUnit.SECONDS));assertFalse(first.isDone());
            System.out.printf("병렬 처리 확인: 첫 트랜잭션 미커밋 중 다른 좌석 완료, elapsedMs=%.2f%n",(System.nanoTime()-start)/1_000_000.0);
            var competitor=pool.submit(()->outcome(()->hold(c,"S1")));
            assertThrows(TimeoutException.class,()->competitor.get(250,TimeUnit.MILLISECONDS));
            release.countDown();first.get(10,TimeUnit.SECONDS);assertEquals("SEAT_ALREADY_TAKEN",competitor.get(10,TimeUnit.SECONDS));
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
    }
    @Test void overlappingSelectionsAreAllOrNothing()throws Exception{
        var a=actor();var b=actor();
        var r=race(List.of(()->outcome(()->hold(a,"S1","S2")),()->outcome(()->hold(b,"S2","S3"))));
        assertEquals(1,Collections.frequency(r,"OK"));assertEquals(1,Collections.frequency(r,"SEAT_ALREADY_TAKEN"));
        assertEquals(2,count("SELECT COUNT(*) FROM concert_seats WHERE concert_id=? AND state='HELD'"));
    }
    @Test void reversedSeatOrderDoesNotDeadlock()throws Exception{
        var a=actor();var b=actor();
        var r=race(List.of(()->outcome(()->hold(a,"S1","S2")),()->outcome(()->hold(b,"S2","S1"))));
        assertEquals(1,Collections.frequency(r,"OK"));assertEquals(1,Collections.frequency(r,"SEAT_ALREADY_TAKEN"));
    }
    @Test void concurrentFirstSelectionsOfSameUserLeaveOneActiveHold()throws Exception{
        var a=actor();assertEquals(List.of("OK","OK"),race(List.of(()->outcome(()->hold(a,"S1")),()->outcome(()->hold(a,"S2")))));
        assertEquals(1,count("SELECT COUNT(*) FROM seat_holds WHERE concert_id=? AND status='ACTIVE'"));
        assertEquals(1,count("SELECT COUNT(*) FROM concert_seats WHERE concert_id=? AND state='HELD'"));
    }
    @Test void concurrentReplacementAndConfirmationAreConsistent()throws Exception{
        var a=actor();var h=hold(a,"S1","S2");
        var r=race(List.of(()->outcome(()->hold(a,"S2","S3")),()->outcome(()->confirm(a,h))));
        assertTrue(r.equals(List.of("OK","HOLD_EXPIRED"))||r.equals(List.of("SEAT_ALREADY_TAKEN","OK")),r.toString());
    }
    @Test void simultaneousReplacementsSerializeWithoutLeavingPartialOccupancy()throws Exception{
        var a=actor();hold(a,"S1","S2");
        assertEquals(List.of("OK","OK"),race(List.of(()->outcome(()->hold(a,"S2","S3")),()->outcome(()->hold(a,"S3","S4")))));
        assertEquals(1,count("SELECT COUNT(*) FROM seat_holds WHERE concert_id=? AND status='ACTIVE'"));
        assertEquals(2,count("SELECT COUNT(*) FROM concert_seats WHERE concert_id=? AND state='HELD'"));
    }
    @Test void crossReplacementsUseOneSeatOrderAndPreserveBothOriginalHolds()throws Exception{
        var a=actor();var b=actor();var first=hold(a,"S1");var second=hold(b,"S2");
        assertEquals(List.of("SEAT_ALREADY_TAKEN","SEAT_ALREADY_TAKEN"),
                race(List.of(()->outcome(()->hold(a,"S2")),()->outcome(()->hold(b,"S1")))));
        assertEquals(first.holdId(),hold(a,"S1").holdId());assertEquals(second.holdId(),hold(b,"S2").holdId());
    }
    @Test void duplicateConfirmCreatesOneBookingTicketAndOutbox()throws Exception{
        var a=actor();var h=hold(a,"S1");List<Callable<Confirmation>> work=new ArrayList<>();
        for(int i=0;i<10;i++)work.add(()->confirm(a,h));var r=race(work);
        assertEquals(1,r.stream().filter(Confirmation::created).count());
        assertEquals(1,r.stream().map(x->x.booking().id()).distinct().count());
        assertEquals(1,count("SELECT COUNT(*) FROM bookings WHERE concert_id=?"));
    }
    @Test void releaseRacingConfirmCannotFreeABookedSeat()throws Exception{
        var a=actor();var h=hold(a,"S1");
        var r=race(List.of(()->outcome(()->{service.release(h.holdId(),a);return null;}),()->outcome(()->confirm(a,h))));
        assertEquals("OK",r.get(0));assertTrue(Set.of("OK","HOLD_EXPIRED").contains(r.get(1)));
    }
    @Test void expiryRacingConfirmAtDeadlineCannotBook()throws Exception{
        var a=actor();var h=hold(a,"S1","S2");clock.advance(Duration.ofMinutes(5));
        var r=race(List.of(()->outcome(()->{service.expire(concertId);return null;}),()->outcome(()->confirm(a,h))));
        assertEquals(List.of("OK","HOLD_EXPIRED"),r);
        assertEquals(0,count("SELECT COUNT(*) FROM bookings WHERE concert_id=?"));
    }
    @Test void expiredMultiSeatHoldReclaimedAtomicallyDuringCompetingJoins()throws Exception{
        var a=actor();var b=actor();var c=actor();hold(a,"S1","S2");clock.advance(Duration.ofMinutes(5));
        assertEquals(List.of("OK","OK"),race(List.of(()->outcome(()->hold(b,"S1")),()->outcome(()->hold(c,"S2")))));
        assertEquals(1,count("SELECT COUNT(*) FROM seat_holds WHERE concert_id=? AND status='EXPIRED'"));
    }
    @Test void cancelRacingHoldAndOldCancelCannotReleaseResale()throws Exception{
        var a=actor();var b=actor();var booking=confirm(a,hold(a,"S1")).booking();
        var r=race(List.of(()->outcome(()->service.cancel(booking.id(),a)),()->outcome(()->hold(b,"S1"))));
        assertEquals("OK",r.get(0));assertTrue(Set.of("OK","SEAT_ALREADY_TAKEN").contains(r.get(1)));
        var resale=confirm(b,hold(b,"S1"));service.cancel(booking.id(),a);
        assertEquals("CONFIRMED",resale.booking().status());
    }
    @Test void cancellationRacingDuplicateConfirmationDoesNotCreateAnotherBooking()throws Exception{
        var a=actor();var h=hold(a,"S1");var initial=confirm(a,h);
        var results=race(List.of(()->outcome(()->service.cancel(initial.booking().id(),a)),()->outcome(()->{
            var retry=confirm(a,h);assertFalse(retry.created());assertEquals(initial.booking().id(),retry.booking().id());return retry;
        })));
        assertEquals(List.of("OK","OK"),results);
        assertEquals(1,count("SELECT COUNT(*) FROM bookings WHERE concert_id=? AND status='CANCELLED'"));
        assertEquals(0,count("SELECT COUNT(*) FROM concert_seats WHERE concert_id=? AND state<>'AVAILABLE'"));
    }
    @Test void duplicateExpiryAndNewHoldDoNotReleaseNewOwner()throws Exception{
        var a=actor();var b=actor();hold(a,"S1");clock.advance(Duration.ofMinutes(5));
        var r=race(List.of(()->outcome(()->{service.expire(concertId);return null;}),()->outcome(()->{service.expire(concertId);return null;}),()->outcome(()->hold(b,"S1"))));
        assertEquals(List.of("OK","OK","OK"),r);
        assertEquals(1,count("SELECT COUNT(*) FROM concert_seats WHERE concert_id=? AND state='HELD'"));
    }
    @Test void newlyExpiredHoldRestartsBeforeExpandingSeatLocks(){
        var a=actor();var b=actor();hold(a,"S1","S2");
        clearInvocations(repo);var advanced=new AtomicBoolean();
        doAnswer(invocation->{
            Object result=invocation.callRealMethod();
            if(advanced.compareAndSet(false,true))clock.advance(Duration.ofMinutes(5));
            return result;
        }).when(repo).lockSeats(anyCollection());
        hold(b,"S1");
        verify(repo,times(2)).lockSeats(anyCollection());
        assertEquals(1,count("SELECT COUNT(*) FROM seat_holds WHERE concert_id=? AND status='EXPIRED'"));
        assertEquals(1,count("SELECT COUNT(*) FROM concert_seats WHERE concert_id=? AND state='AVAILABLE' AND seat_code='S2'"));
    }
    @Test void transientFailureAfterWritesRollsBackBeforeRetry(){
        var a=actor();var injected=new AtomicBoolean();
        doAnswer(invocation->{
            Object result=invocation.callRealMethod();
            if(injected.compareAndSet(false,true))throw new org.springframework.dao.CannotAcquireLockException("테스트 잠금 실패");
            return result;
        }).when(repo).createHold(anyString(),anyLong(),anyLong(),any(),any(),anyList());
        hold(a,"S1","S2");
        verify(repo,times(2)).createHold(anyString(),anyLong(),anyLong(),any(),any(),anyList());
        assertEquals(1,count("SELECT COUNT(*) FROM seat_holds WHERE concert_id=?"));
        assertEquals(2,count("SELECT COUNT(*) FROM seat_hold_items i JOIN seat_holds h ON h.id=i.hold_id WHERE h.concert_id=?"));
    }
    @Test void exhaustedRetriesReturn503WithoutPartialWrites(){
        var a=actor();var original=hold(a,"S3");clearInvocations(repo);
        doAnswer(invocation->{invocation.callRealMethod();throw new org.springframework.dao.CannotAcquireLockException("테스트 잠금 실패");})
            .when(repo).createHold(anyString(),anyLong(),anyLong(),any(),any(),anyList());
        assertEquals("SERVICE_UNAVAILABLE",outcome(()->hold(a,"S1","S2")));
        verify(repo,times(5)).createHold(anyString(),anyLong(),anyLong(),any(),any(),anyList());
        assertEquals(1,count("SELECT COUNT(*) FROM seat_holds WHERE concert_id=?"));
        assertEquals(1,count("SELECT COUNT(*) FROM concert_seats WHERE concert_id=? AND state='HELD' AND seat_code='S3'"));
        assertEquals(original.expiresAt().toLocalDateTime(),jdbc.queryForObject("SELECT expires_at FROM seat_holds WHERE public_id=?",java.time.LocalDateTime.class,original.holdId()));
    }
}
