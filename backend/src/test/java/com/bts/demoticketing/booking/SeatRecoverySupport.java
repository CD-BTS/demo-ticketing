package com.bts.demoticketing.booking;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 실제 HTTP와 SQL만 사용한다. 운영 서비스의 expire()를 직접 호출하지 않는다. */
abstract class SeatRecoverySupport {
    static final String RUN=System.getenv("SEAT_RECOVERY_ISOLATED");
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry properties){
        if(RUN==null||!RUN.matches("[a-f0-9]{12}")||!("recovery_"+RUN).equals(System.getenv("DB_USERNAME")))
            throw new IllegalStateException("scripts/Test-SeatRecovery.ps1의 격리 환경이 필요합니다.");
        if("3308".equals(System.getenv("DB_PORT"))||"6380".equals(System.getenv("REDIS_PORT")))
            throw new IllegalStateException("공용 Demo 포트에서는 복구 테스트를 실행할 수 없습니다.");
        properties.add("demo.queue.key-prefix",()->"demo-ticketing:recovery:"+RUN);
        properties.add("spring.datasource.url",()->"jdbc:mysql://127.0.0.1:"+System.getenv("DB_PORT")+
                "/demo_ticketing_db?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8");
        properties.add("spring.datasource.username",()->"recovery_"+RUN);
        properties.add("spring.datasource.password",()->System.getenv("DB_PASSWORD"));
    }
    @TestConfiguration static class TimeConfig {
        @Bean @Primary RecoveryClock recoveryClock(){return new RecoveryClock();}
    }
    static class RecoveryClock extends Clock {
        final AtomicReference<Instant> time=new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.MICROS));
        void set(Instant value){time.set(value);}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return time.get();}
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired Environment environment;
    @Autowired RecoveryClock clock;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    final AtomicInteger httpCalls=new AtomicInteger();
    final List<Long> actorIds=new ArrayList<>();
    String concert;
    long concertId;
    Instant started;
    record Actor(String token,long id){}
    record Reply(int status,String code,Object data){
        @SuppressWarnings("unchecked") Map<String,Object> object(){return (Map<String,Object>)data;}
        @SuppressWarnings("unchecked") List<Map<String,Object>> list(){return (List<Map<String,Object>>)data;}
    }
    @BeforeEach void fixture(){
        assertEquals("demo_ticketing_db",jdbc.queryForObject("SELECT DATABASE()",String.class));
        assertTrue(jdbc.queryForObject("SELECT CURRENT_USER()",String.class).startsWith("recovery_"+RUN+"@"));
        started=Instant.now().truncatedTo(ChronoUnit.MICROS);clock.set(started);
        concert="r25_"+RUN+"_"+UUID.randomUUID().toString().substring(0,8);
        jdbc.update("""
                INSERT INTO concerts(public_id,title,artist,venue,start_at,end_at,price_from,tagline,hue)
                VALUES(?,'좌석 복구 검증','test','test','2027-12-12 09:00:00','2027-12-12 12:00:00',100,'test',0)
                """,concert);
        concertId=jdbc.queryForObject("SELECT id FROM concerts WHERE public_id=?",Long.class,concert);
        for(int i=1;i<=5;i++)jdbc.update("INSERT INTO concert_seats(concert_id,seat_code,row_label,seat_number,grade,price,state) VALUES(?,?,'A',?,'VIP',100,'AVAILABLE')",concertId,"A-0"+i,i);
    }
    Reply api(String method,String path,Actor actor,Object body)throws Exception{
        httpCalls.incrementAndGet();
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+environment.getProperty("local.server.port")+"/api/v1"+path))
                .timeout(Duration.ofSeconds(20)).header("Content-Type","application/json");
        if(actor!=null)request.header("Authorization","Bearer "+actor.token());
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
        var envelope=json.readValue(response.body(),Map.class);
        assertEquals(response.statusCode()<400,envelope.get("success"),path);
        return new Reply(response.statusCode(),envelope.get("code").toString(),envelope.get("data"));
    }
    Reply expect(Reply reply,int status){assertEquals(status,reply.status(),reply.code());return reply;}
    Actor actor()throws Exception{
        String publicId="r25_"+RUN+"_"+UUID.randomUUID().toString().substring(0,8);
        var login=expect(api("POST","/users",null,Map.of("userId",publicId)),200).object();
        long id=jdbc.queryForObject("SELECT id FROM demo_users WHERE public_user_id=?",Long.class,publicId);
        actorIds.add(id);var actor=new Actor(login.get("token").toString(),id);
        assertEquals("ADMITTED",expect(api("POST","/concerts/"+concert+"/queue",actor,null),201).object().get("status"));
        return actor;
    }
    String hold(Actor actor,String... seats)throws Exception{
        var data=expect(api("POST","/concerts/"+concert+"/seat-holds",actor,Map.of("seatIds",List.of(seats))),201).object();
        String id=data.get("holdId").toString();
        var created=jdbc.queryForObject("SELECT created_at FROM seat_holds WHERE public_id=?",LocalDateTime.class,id);
        var expiry=jdbc.queryForObject("SELECT expires_at FROM seat_holds WHERE public_id=?",LocalDateTime.class,id);
        assertEquals(Duration.ofMinutes(5),Duration.between(created,expiry));
        assertEquals(expiry.toInstant(ZoneOffset.UTC),OffsetDateTime.parse(data.get("expiresAt").toString()).toInstant());
        return id;
    }
    Reply confirm(Actor actor,String hold)throws Exception{return api("POST","/bookings",actor,Map.of("holdId",hold,"paymentIdentifier","test-pay-shared-01"));}
    String book(Actor actor,String hold)throws Exception{return expect(confirm(actor,hold),201).object().get("id").toString();}
    Reply cancel(Actor actor,String booking)throws Exception{return api("POST","/bookings/"+booking+"/cancel",actor,null);}
    Instant expiry(String hold){return jdbc.queryForObject("SELECT expires_at FROM seat_holds WHERE public_id=?",LocalDateTime.class,hold).toInstant(ZoneOffset.UTC);}
    String state(String seat){return jdbc.queryForObject("SELECT state FROM concert_seats WHERE concert_id=? AND seat_code=?",String.class,concertId,seat);}
    String holdStatus(String hold){return jdbc.queryForObject("SELECT status FROM seat_holds WHERE public_id=?",String.class,hold);}
    String bookingStatus(String booking){return jdbc.queryForObject("SELECT status FROM bookings WHERE public_id=?",String.class,booking);}
    long count(String sql){return jdbc.queryForObject(sql,Long.class,concertId);}
    long events(String booking,String type){return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id=? AND event_type=?",Long.class,booking,type);}
    void available(String... seats){
        for(String seat:seats){
            var row=jdbc.queryForMap("SELECT state,active_hold_id,active_booking_id FROM concert_seats WHERE concert_id=? AND seat_code=?",concertId,seat);
            assertEquals("AVAILABLE",row.get("state"));assertNull(row.get("active_hold_id"));assertNull(row.get("active_booking_id"));
        }
    }
    void ticketStatus(String booking,String status,int expected){
        assertEquals(expected,jdbc.queryForObject("SELECT COUNT(*) FROM admission_tickets t JOIN bookings b ON b.id=t.booking_id WHERE b.public_id=? AND t.status=?",Long.class,booking,status));
    }
    <T> List<T> race(List<Callable<T>> work)throws Exception{
        var gate=new CountDownLatch(1);var ready=new CountDownLatch(work.size());var pool=Executors.newFixedThreadPool(work.size());
        try{
            List<Future<T>> pending=new ArrayList<>();
            for(var job:work)pending.add(pool.submit(()->{ready.countDown();assertTrue(gate.await(10,TimeUnit.SECONDS));return job.call();}));
            assertTrue(ready.await(10,TimeUnit.SECONDS));gate.countDown();
            List<T> results=new ArrayList<>();for(var future:pending)results.add(future.get(30,TimeUnit.SECONDS));return results;
        }finally{gate.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
    void awaitSql(BooleanSupplier condition)throws Exception{
        long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        while(System.nanoTime()<deadline){if(condition.getAsBoolean())return;Thread.sleep(50);}
        fail("실제 스케줄러가 15초 이내에 DB 상태를 복구하지 못했습니다.");
    }
    @AfterEach void consistent(){
        assertEquals(0,count("""
                SELECT COUNT(*) FROM concert_seats s LEFT JOIN seat_holds h ON h.id=s.active_hold_id LEFT JOIN bookings b ON b.id=s.active_booking_id
                WHERE s.concert_id=? AND ((s.state='AVAILABLE' AND (s.active_hold_id IS NOT NULL OR s.active_booking_id IS NOT NULL))
                OR (s.state='HELD' AND (h.id IS NULL OR h.status<>'ACTIVE' OR s.active_booking_id IS NOT NULL OR NOT EXISTS
                (SELECT 1 FROM seat_hold_items i WHERE i.hold_id=h.id AND i.concert_seat_id=s.id)))
                OR (s.state='BOOKED' AND (b.id IS NULL OR b.status<>'CONFIRMED' OR s.active_hold_id IS NOT NULL OR NOT EXISTS
                (SELECT 1 FROM booking_seats i WHERE i.booking_id=b.id AND i.concert_seat_id=s.id))))
                """));
        assertEquals(0,count("""
                SELECT COUNT(*) FROM seat_hold_items i JOIN seat_holds h ON h.id=i.hold_id JOIN concert_seats s ON s.id=i.concert_seat_id
                WHERE h.concert_id=? AND ((h.status='ACTIVE' AND (s.state<>'HELD' OR NOT(s.active_hold_id <=> h.id)))
                OR (h.status<>'ACTIVE' AND s.active_hold_id=h.id))
                """));
        assertEquals(0,count("SELECT COUNT(*) FROM (SELECT user_id FROM seat_holds WHERE concert_id=? AND status='ACTIVE' GROUP BY user_id HAVING COUNT(*)>1) x"));
        assertEquals(0,count("SELECT COUNT(*) FROM seat_holds h WHERE h.concert_id=? AND NOT EXISTS (SELECT 1 FROM seat_hold_items i WHERE i.hold_id=h.id)"));
        assertEquals(0,count("SELECT COUNT(*) FROM (SELECT i.concert_seat_id FROM booking_seats i JOIN bookings b ON b.id=i.booking_id WHERE b.concert_id=? AND b.status='CONFIRMED' GROUP BY i.concert_seat_id HAVING COUNT(*)>1) x"));
        assertEquals(0,count("""
                SELECT COUNT(*) FROM booking_seats i JOIN bookings b ON b.id=i.booking_id JOIN concert_seats s ON s.id=i.concert_seat_id
                WHERE b.concert_id=? AND ((b.status='CONFIRMED' AND (s.state<>'BOOKED' OR NOT(s.active_booking_id <=> b.id)))
                OR (SELECT COUNT(*) FROM admission_tickets t WHERE t.booking_id=b.id AND t.concert_seat_id=s.id
                    AND t.status=IF(b.status='CANCELLED','CANCELLED','ISSUED'))<>1)
                """));
        assertEquals(0,count("""
                SELECT COUNT(*) FROM bookings b JOIN seat_holds h ON h.id=b.hold_id WHERE b.concert_id=? AND
                (h.status<>'CONVERTED' OR
                (SELECT COUNT(*) FROM booking_seats i WHERE i.booking_id=b.id)<>(SELECT COUNT(*) FROM seat_hold_items i WHERE i.hold_id=h.id) OR
                (SELECT COUNT(*) FROM outbox_events e WHERE e.aggregate_id=b.public_id AND e.event_type='BOOKING')<>1
                OR (SELECT COUNT(*) FROM outbox_events e WHERE e.aggregate_id=b.public_id AND e.event_type='CANCEL')<>IF(b.status='CANCELLED',1,0))
                """));
        for(long actor:actorIds)assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events e WHERE e.user_id=? AND NOT EXISTS (SELECT 1 FROM bookings b WHERE b.public_id=e.aggregate_id)",Long.class,actor));
        System.out.printf("DB 정합성 통과: concert=%s, holds=%d, bookings=%d, 점유 불일치=0%n",concert,
                count("SELECT COUNT(*) FROM seat_holds WHERE concert_id=?"),count("SELECT COUNT(*) FROM bookings WHERE concert_id=?"));
        // 이력은 삭제하지 않고 실행기에서 SQL 스냅샷을 저장한 후 격리 컨테이너를 제거한다.
    }
}
