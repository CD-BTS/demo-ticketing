package com.bts.demoticketing.booking;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.type.TypeReference;
import com.bts.demoticketing.booking.dto.BookingDtos.*;
import com.bts.demoticketing.booking.service.ReservationService;
import com.bts.demoticketing.common.exception.ApiException;
import com.bts.demoticketing.outbox.OutboxDeliveryService;
import com.bts.demoticketing.seat.service.SeatQueryService;
import com.bts.demoticketing.user.entity.DemoUser;
import com.bts.demoticketing.user.repository.DemoUserRepository;
import com.bts.demoticketing.user.service.DemoAuthService;
import com.bts.demoticketing.queue.QueueService;
import com.bts.demoticketing.queue.QueueStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("mysql")
@SpringBootTest(properties={"demo.jobs.enabled=false","bts.enabled=true","bts.service-id=it-reservations","bts.api-key=integration-only-key","demo.queue.capacity=2","demo.policy.mode=disabled","demo.captcha.mode=disabled"})
@AutoConfigureMockMvc
@Import(ReservationMySqlTests.TimeConfig.class)
class ReservationMySqlTests {
    static final Remote BTS=new Remote();
    // Avoid creating already-expired fixtures for any other demo server's expiry worker.
    static final Instant TEST_NOW=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    static final String QUEUE_PREFIX="demo-ticketing:queue:reservation-test:"+UUID.randomUUID();
    @DynamicPropertySource static void remote(DynamicPropertyRegistry r){
        r.add("bts.base-url",()->BTS.url());
        r.add("demo.queue.key-prefix",()->QUEUE_PREFIX);
    }
    @Autowired ReservationService reservations;
    @Autowired DemoAuthService auth;
    @Autowired DemoUserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired SeatQueryService seats;
    @Autowired OutboxDeliveryService delivery;
    @Autowired MutableClock clock;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired QueueService queue;
    @Autowired StringRedisTemplate redis;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean QueueStore queueStore;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.bts.demoticketing.outbox.OutboxRepository outboxRepository;
    long concertId;
    String concert;
    DemoUser alice,bob;
    String aliceToken,bobToken;
    final List<Long> userIds=new ArrayList<>();

    @TestConfiguration static class TimeConfig {
        @Bean @Primary MutableClock testClock(){return new MutableClock();}
    }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> time=new AtomicReference<>(TEST_NOW);
        void advance(Duration d){time.updateAndGet(t->t.plus(d));}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId z){return this;}
        @Override public Instant instant(){return time.get();}
    }
    @BeforeEach void setup(){
        assertEquals("demo_ticketing_db",jdbc.queryForObject("SELECT DATABASE()",String.class));
        clock.time.set(TEST_NOW);
        BTS.reset();
        concert="it_"+UUID.randomUUID();
        jdbc.update("""
                INSERT INTO concerts(public_id,title,artist,venue,start_at,end_at,price_from,tagline,hue)
                VALUES(?,'test','test','test','2026-12-12 09:00:00','2026-12-12 12:00:00',99000,'test',10)
                """,concert);
        concertId=jdbc.queryForObject("SELECT id FROM concerts WHERE public_id=?",Long.class,concert);
        for(int i=1;i<=4;i++)jdbc.update("""
                INSERT INTO concert_seats(concert_id,seat_code,row_label,seat_number,grade,price,state)
                VALUES(?,?,'A',?,'VIP',154000,'AVAILABLE')
                """,concertId,"A-0"+i,i);
        var a=auth.login("it_"+UUID.randomUUID().toString().substring(0,20));
        aliceToken=a.token(); alice=findUser(a.userId());userIds.add(alice.id());
        var b=auth.login("it_"+UUID.randomUUID().toString().substring(0,20));
        bobToken=b.token();bob=findUser(b.userId());userIds.add(bob.id());
        assertEquals("ADMITTED",queue.execute(concert,alice.id(),"join").snapshot().status());
        assertEquals("ADMITTED",queue.execute(concert,bob.id(),"join").snapshot().status());
    }
    private DemoUser findUser(String publicId){return users.find(jdbc.queryForObject("SELECT id FROM demo_users WHERE public_user_id=?",Long.class,publicId)).orElseThrow();}

    @AfterEach void cleanup(){
        // Only this test's random concert/users; no global resets or seed mutations.
        var keys=redis.keys(QUEUE_PREFIX+":*");
        if(keys!=null&&!keys.isEmpty())redis.delete(keys);
        jdbc.update("UPDATE concert_seats SET state='AVAILABLE',active_hold_id=NULL,active_booking_id=NULL WHERE concert_id=?",concertId);
        jdbc.update("DELETE FROM admission_tickets WHERE booking_id IN (SELECT id FROM bookings WHERE concert_id=?)",concertId);
        jdbc.update("DELETE FROM booking_seats WHERE booking_id IN (SELECT id FROM bookings WHERE concert_id=?)",concertId);
        jdbc.update("DELETE FROM bookings WHERE concert_id=?",concertId);
        jdbc.update("DELETE FROM seat_hold_items WHERE hold_id IN (SELECT id FROM seat_holds WHERE concert_id=?)",concertId);
        jdbc.update("DELETE FROM seat_holds WHERE concert_id=?",concertId);
        jdbc.update("DELETE FROM concert_seats WHERE concert_id=?",concertId);
        jdbc.update("DELETE FROM concerts WHERE id=?",concertId);
        for(long user:userIds){jdbc.update("DELETE FROM outbox_events WHERE user_id=?",user);jdbc.update("DELETE FROM demo_sessions WHERE user_id=?",user);jdbc.update("DELETE FROM demo_users WHERE id=?",user);}
    }
    @AfterAll static void stop(){BTS.server.stop(0);}
    private HoldResponse hold(DemoUser user,String... codes){return reservations.hold(concert,user,List.of(codes));}
    private Confirmation book(HoldResponse hold,DemoUser user){return reservations.confirm(hold.holdId(),"test-pay-shared-01","192.0.2.10",user);}
    private long outboxes(){return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE user_id IN (?,?)",Long.class,alice.id(),bob.id());}
    private String code(Runnable work){return assertThrows(ApiException.class,work::run).getResponseCode().getCode();}
    private long bookings(){return jdbc.queryForObject("SELECT COUNT(*) FROM bookings WHERE concert_id=?",Long.class,concertId);}
    private String state(String code){return jdbc.queryForObject("SELECT state FROM concert_seats WHERE concert_id=? AND seat_code=?",String.class,concertId,code);}

    private String admissionKey(){
        return QUEUE_PREFIX+":{"+Base64.getUrlEncoder().withoutPadding().encodeToString(concert.getBytes(StandardCharsets.UTF_8))+"}:admitted";
    }
    private void expireAdmission(DemoUser user){
        // Keep the ADMITTED member present with an elapsed absolute deadline: Lua must
        // detect expiry itself, not merely trust membership. No wall-clock sleep needed.
        redis.opsForZSet().add(admissionKey(),Long.toString(user.id()),1);
    }
    private void assertHoldDenied(String token,String error,int statusCode)throws Exception{
        long before=jdbc.queryForObject("SELECT COUNT(*) FROM seat_holds WHERE concert_id=?",Long.class,concertId);
        mvc.perform(post("/api/v1/concerts/"+concert+"/seat-holds").header("Authorization","Bearer "+token)
                .contentType("application/json").content("{\"seatIds\":[\"A-01\"]}"))
                .andExpect(status().is(statusCode)).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(error)).andExpect(jsonPath("$.data").isEmpty());
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM seat_holds WHERE concert_id=?",Long.class,concertId));
        assertEquals("AVAILABLE",state("A-01"));
    }

    @Test void unregisteredAndOtherConcertAdmissionCannotHold()throws Exception{
        queue.execute(concert,alice.id(),"leave");
        assertHoldDenied(aliceToken,"QUEUE_NOT_ADMITTED",403);
        // Admission for the real concert cannot authorize another existing performance.
        queue.execute(concert,alice.id(),"join");
        String other="it_"+UUID.randomUUID();
        jdbc.update("INSERT INTO concerts(public_id,title,artist,venue,start_at,end_at,price_from,tagline,hue) SELECT ?,title,artist,venue,start_at,end_at,price_from,tagline,hue FROM concerts WHERE id=?",other,concertId);
        try{
            mvc.perform(post("/api/v1/concerts/"+other+"/seat-holds").header("Authorization","Bearer "+aliceToken)
                    .contentType("application/json").content("{\"seatIds\":[\"A-01\"]}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("QUEUE_NOT_ADMITTED"));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM seat_holds h JOIN concerts c ON c.id=h.concert_id WHERE c.public_id=?",Long.class,other));
        }finally{jdbc.update("DELETE FROM concerts WHERE public_id=?",other);}
    }
    @Test void waitingUserCannotHold()throws Exception{
        var login=auth.login("it_"+UUID.randomUUID().toString().substring(0,20));
        var third=findUser(login.userId());userIds.add(third.id());
        assertEquals("WAITING",queue.execute(concert,third.id(),"join").snapshot().status());
        assertHoldDenied(login.token(),"QUEUE_NOT_ADMITTED",403);
    }
    @Test void staleAdmissionCannotCreateReplaceOrRetryHold()throws Exception{
        expireAdmission(alice);
        assertNotNull(redis.opsForZSet().score(admissionKey(),Long.toString(alice.id())));
        assertHoldDenied(aliceToken,"QUEUE_NOT_ADMITTED",403);
        assertEquals("EXPIRED",queue.execute(concert,alice.id(),"status").snapshot().status());
        queue.execute(concert,alice.id(),"join");
        var original=hold(alice,"A-01");
        expireAdmission(alice);
        assertEquals("QUEUE_NOT_ADMITTED",code(()->hold(alice,"A-01")));
        assertEquals("QUEUE_NOT_ADMITTED",code(()->hold(alice,"A-02")));
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM seat_holds WHERE public_id=?",String.class,original.holdId()));
        assertEquals("HELD",state("A-01"));assertEquals("AVAILABLE",state("A-02"));
    }
    @Test void validOwnedHoldConfirmsAfterAdmissionExpiryAndRetriesRemainIdempotent()throws Exception{
        var h=hold(alice,"A-01");
        assertEquals(h.holdId(),hold(alice,"A-01").holdId());
        expireAdmission(alice);
        assertEquals("EXPIRED",queue.execute(concert,alice.id(),"status").snapshot().status());
        String body=json.writeValueAsString(Map.of("holdId",h.holdId(),"paymentIdentifier","test-pay-shared-01"));
        mvc.perform(post("/api/v1/bookings").header("Authorization","Bearer "+bobToken).contentType("application/json").content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        for(int expected:List.of(201,200))mvc.perform(post("/api/v1/bookings").header("Authorization","Bearer "+aliceToken).contentType("application/json").content(body))
                .andExpect(status().is(expected)).andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        assertEquals(1,bookings());assertEquals(1,outboxes());
    }
    @Test void expiredOrMissingHoldStillReturnsGoneWithoutAdmission()throws Exception{
        var h=hold(alice,"A-01");
        expireAdmission(alice);clock.advance(Duration.ofMinutes(5));
        for(String id:List.of(h.holdId(),"h_missing"))mvc.perform(post("/api/v1/bookings").header("Authorization","Bearer "+aliceToken)
                .contentType("application/json").content(json.writeValueAsString(Map.of("holdId",id,"paymentIdentifier","test-pay-shared-01"))))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));
        assertEquals(0,bookings());assertEquals(0,outboxes());
    }
    @Test void redisConnectionFailureFailsClosedButDoesNotBlockExistingHoldConfirmation()throws Exception{
        var h=hold(bob,"A-02");
        var config=new org.springframework.data.redis.connection.RedisStandaloneConfiguration("127.0.0.1",1);
        var client=org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(200)).build();
        var unavailable=new LettuceConnectionFactory(config,client);unavailable.afterPropertiesSet();unavailable.start();
        try{
            var failing=new QueueStore(new StringRedisTemplate(unavailable),QUEUE_PREFIX,2,Duration.ofMinutes(5),Duration.ofMinutes(5),Duration.ofMinutes(5));
            org.mockito.Mockito.doAnswer(invocation->failing.execute(invocation.getArgument(0),invocation.getArgument(1),invocation.getArgument(2)))
                    .when(queueStore).execute(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString());
            assertHoldDenied(aliceToken,"SERVICE_UNAVAILABLE",503);
            assertTrue(book(h,bob).created());
            reservations.cancel(reservations.mine(bob).get(0).id(),bob);
            reservations.release(h.holdId(),bob);
            assertEquals("CANCELLED",reservations.mine(bob).get(0).status());
        }finally{unavailable.destroy();}
    }

    @Test void normalBookingCancellationResaleAndIdempotency(){
        var h=hold(alice,"A-01","A-02");
        assertEquals(TEST_NOW.plus(Duration.ofMinutes(5)),h.expiresAt().toInstant());
        assertEquals(h.holdId(),hold(alice,"A-02","A-01").holdId());
        assertTrue(seats.list(concert,alice.id()).get(0).heldByMe());
        assertFalse(seats.list(concert,bob.id()).get(0).heldByMe());
        clock.advance(Duration.ofMillis(12500));
        var first=book(h,alice);
        assertTrue(first.created());assertEquals(308000,first.booking().totalPrice());
        assertEquals("BOOKED",state("A-01"));
        var retry=reservations.confirm(h.holdId(),"test-pay-shared-02","192.0.2.99",alice);
        assertFalse(retry.created());assertEquals(first.booking().id(),retry.booking().id());
        assertEquals(1,bookings());assertEquals(1,outboxes());
        assertEquals("FORBIDDEN",code(()->reservations.cancel(first.booking().id(),bob)));
        assertEquals("CANCELLED",reservations.cancel(first.booking().id(),alice).status());
        assertEquals("AVAILABLE",state("A-01"));
        assertEquals(2,outboxes());
        reservations.cancel(first.booking().id(),alice);assertEquals(2,outboxes());
        var resale=book(hold(bob,"A-01"),bob);
        reservations.cancel(first.booking().id(),alice);assertEquals("BOOKED",state("A-01"));
        assertEquals("CONFIRMED",resale.booking().status());
    }

    @Test void allOrNothingAndReplacementPreserveExistingHold(){
        var a=hold(alice,"A-01");
        hold(bob,"A-02");
        assertEquals("SEAT_ALREADY_TAKEN",code(()->hold(alice,"A-02","A-03")));
        assertEquals("HELD",state("A-01"));assertEquals("AVAILABLE",state("A-03"));
        assertEquals("INVALID_SEAT_SELECTION",code(()->hold(alice,"A-03","A-03")));
        assertEquals("SEAT_NOT_FOUND",code(()->hold(alice,"missing")));
        assertEquals("FORBIDDEN",code(()->book(a,bob)));
        var replacement=hold(alice,"A-03");
        assertNotEquals(a.holdId(),replacement.holdId());assertEquals("AVAILABLE",state("A-01"));
        assertEquals("HOLD_EXPIRED",code(()->book(a,alice)));assertEquals(0,outboxes());
    }

    @Test void expirationBoundaryAndReleaseNeverProduceCancel(){
        var h=hold(alice,"A-01");
        clock.advance(Duration.ofMinutes(5));
        assertEquals("HOLD_EXPIRED",code(()->book(h,alice)));
        assertEquals("AVAILABLE",seats.list(concert).get(0).status().name());
        var second=hold(bob,"A-01");assertNotEquals(h.holdId(),second.holdId());
        assertEquals("EXPIRED",jdbc.queryForObject("SELECT status FROM seat_holds WHERE public_id=?",String.class,h.holdId()));
        reservations.release(second.holdId(),bob);reservations.release(second.holdId(),bob);
        assertEquals("AVAILABLE",state("A-01"));assertEquals(0,outboxes());
        var third=hold(alice,"A-03");
        clock.advance(Duration.ofMinutes(6));reservations.expire(concertId);
        assertEquals("AVAILABLE",state("A-03"));
        assertEquals("EXPIRED",jdbc.queryForObject("SELECT status FROM seat_holds WHERE public_id=?",String.class,third.holdId()));
    }

    @Test void concurrentUsersCannotBookSameSeat()throws Exception{
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{
            List<Future<String>> tasks=new ArrayList<>();
            for(var user:List.of(alice,bob))tasks.add(pool.submit(()->{
                gate.await();
                try{return book(hold(user,"A-01"),user).booking().id();}
                catch(ApiException e){return e.getResponseCode().getCode();}
            }));
            gate.countDown();
            List<String> results=new ArrayList<>();for(var t:tasks)results.add(t.get(15,TimeUnit.SECONDS));
            assertEquals(1,results.stream().filter(r->r.startsWith("b_")).count());
            assertTrue(results.contains("SEAT_ALREADY_TAKEN"));assertEquals(1,bookings());assertEquals(1,outboxes());
        }finally{pool.shutdownNow();}
    }

    @Test void concurrentDuplicateConfirmAndCancelConverge()throws Exception{
        var h=hold(alice,"A-01");var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{
            var a=pool.submit(()->{gate.await();return book(h,alice);});
            var b=pool.submit(()->{gate.await();return book(h,alice);});gate.countDown();
            var first=a.get(15,TimeUnit.SECONDS);var second=b.get(15,TimeUnit.SECONDS);
            assertEquals(first.booking().id(),second.booking().id());assertNotEquals(first.created(),second.created());
            var cancelGate=new CountDownLatch(1);
            var c=pool.submit(()->{cancelGate.await();return reservations.cancel(first.booking().id(),alice);});
            var d=pool.submit(()->{cancelGate.await();return reservations.cancel(first.booking().id(),alice);});
            cancelGate.countDown();assertEquals("CANCELLED",c.get(15,TimeUnit.SECONDS).status());assertEquals("CANCELLED",d.get(15,TimeUnit.SECONDS).status());
            assertEquals(1,bookings());assertEquals(2,outboxes());assertEquals("AVAILABLE",state("A-01"));
        }finally{pool.shutdownNow();}
    }

    @Test void failedConfirmationRollsBackEverything(){
        var h=hold(alice,"A-01");
        assertEquals("INVALID_REQUEST",code(()->reservations.confirm(h.holdId(),"not-a-test-payment","192.0.2.1",alice)));
        assertEquals(0,bookings());assertEquals(0,outboxes());assertEquals("HELD",state("A-01"));
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM seat_holds WHERE public_id=?",String.class,h.holdId()));
    }

    @Test void btsRetryFreezesPayloadAndKeepsBookingBeforeCancel(){
        var b=book(hold(alice,"A-01"),alice).booking();
        reservations.cancel(b.id(),alice);
        BTS.mode.set("STORE_THEN_503");
        assertTrue(delivery.deliverNext());
        assertEquals("RETRY",jdbc.queryForObject("SELECT delivery_status FROM outbox_events WHERE aggregate_id=? AND event_type='BOOKING'",String.class,b.id()));
        assertFalse(delivery.deliverNext()); // CANCEL waits for BOOKING; BOOKING is not due yet.
        clock.advance(Duration.ofSeconds(10));
        assertTrue(delivery.deliverNext());assertTrue(delivery.deliverNext());
        assertEquals(3,BTS.bodies.size());assertEquals(BTS.bodies.get(0),BTS.bodies.get(1));
        assertEquals(2,BTS.events.size()); // First event was stored, then response was lost/failed.
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id=? AND delivery_status='SENT'",Integer.class,b.id()));
        assertNotNull(users.find(alice.id()).orElseThrow().btsUserId());
        var request=json.readValue(BTS.bodies.get(0),Map.class);
        assertTrue(request.get("occurredAt").toString().matches(".*T\\d{2}:\\d{2}:\\d{2}Z"));
        Map<?,?> meta=(Map<?,?>)request.get("metadata");
        assertEquals("test-pay-shared-01",meta.get("paymentIdentifier"));assertEquals("192.0.2.10",meta.get("ipAddress"));
        assertEquals(1,((List<?>)meta.get("ticketIds")).size());
    }

    @Test void outboxFailureRollsBackBookingSeatsAndTickets(){
        var h=hold(alice,"A-01");
        org.mockito.Mockito.doThrow(new IllegalStateException("injected local persistence failure"))
            .when(outboxRepository).append(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class));
        assertThrows(org.springframework.dao.InvalidDataAccessApiUsageException.class,()->book(h,alice));
        assertEquals(0,bookings());assertEquals(0,outboxes());assertEquals("HELD",state("A-01"));
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM seat_holds WHERE public_id=?",String.class,h.holdId()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM admission_tickets WHERE booking_id IN (SELECT id FROM bookings WHERE concert_id=?)",Integer.class,concertId));
    }

    @Test void bts409IsIsolatedWithoutReissuingEventId(){
        var b=book(hold(alice,"A-01"),alice).booking();BTS.mode.set("CONFLICT");
        assertTrue(delivery.deliverNext());
        String id=jdbc.queryForObject("SELECT external_event_id FROM outbox_events WHERE aggregate_id=?",String.class,b.id());
        assertEquals("FAILED",jdbc.queryForObject("SELECT delivery_status FROM outbox_events WHERE aggregate_id=?",String.class,b.id()));
        assertEquals("DATA_CONFLICT",jdbc.queryForObject("SELECT last_error_code FROM outbox_events WHERE aggregate_id=?",String.class,b.id()));
        clock.advance(Duration.ofHours(1));assertFalse(delivery.deliverNext());
        assertEquals(id,jdbc.queryForObject("SELECT external_event_id FROM outbox_events WHERE aggregate_id=?",String.class,b.id()));
        assertEquals("BOOKED",state("A-01"));assertEquals("CONFIRMED",b.status());
    }

    @Test void httpOwnershipValidationAndPeerIp()throws Exception{
        mvc.perform(post("/api/v1/concerts/"+concert+"/seat-holds").contentType("application/json").content("{\"seatIds\":[\"A-01\"]}"))
                .andExpect(status().isUnauthorized());
        var result=mvc.perform(post("/api/v1/concerts/"+concert+"/seat-holds").header("Authorization","Bearer "+aliceToken)
                .contentType("application/json").content("{\"seatIds\":[\"A-01\"]}")).andExpect(status().isCreated()).andReturn();
        String holdId=((Map<?,?>)json.readValue(result.getResponse().getContentAsString(),Map.class).get("data")).get("holdId").toString();
        String body=json.writeValueAsString(Map.of("holdId",holdId,"paymentIdentifier","test-pay-shared-01"));
        mvc.perform(post("/api/v1/bookings").header("Authorization","Bearer "+bobToken).contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/bookings").header("Authorization","Bearer "+aliceToken).header("X-Forwarded-For","203.0.113.99")
                .contentType("application/json").content(body)).andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        assertEquals("127.0.0.1",jdbc.queryForObject("SELECT ip_address FROM bookings WHERE concert_id=?",String.class,concertId));
        mvc.perform(get("/api/v1/bookings/me").header("Authorization","Bearer "+aliceToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get("/api/v1/bookings/me").header("Authorization","Bearer "+bobToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test void expiredAndAmbiguousBearerSessionsAreRejected()throws Exception{
        mvc.perform(get("/api/v1/bookings/me").header("Authorization","Bearer "+aliceToken,"Bearer "+bobToken))
                .andExpect(status().isUnauthorized());
        clock.advance(Duration.ofHours(24));
        mvc.perform(get("/api/v1/bookings/me").header("Authorization","Bearer "+aliceToken))
                .andExpect(status().isUnauthorized());
    }

    // Explicit contract server, never the real BTS DB/container or a fabricated production key.
    static class Remote {
        final JsonMapper json=JsonMapper.builder().build();
        final HttpServer server;
        final AtomicReference<String> mode=new AtomicReference<>("NORMAL");
        final Map<String,String> users=new ConcurrentHashMap<>();
        final Map<String,Map<String,Object>> events=new ConcurrentHashMap<>();
        final List<String> bodies=new CopyOnWriteArrayList<>();
        final AtomicLong sequence=new AtomicLong();
        Remote(){
            try{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/api/v1/",this::handle);server.start();}
            catch(IOException e){throw new IllegalStateException(e);}
        }
        String url(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        void reset(){mode.set("NORMAL");bodies.clear();events.clear();users.clear();sequence.set(0);}
        void handle(HttpExchange exchange)throws IOException{
            try{
                if(!"integration-only-key".equals(exchange.getRequestHeaders().getFirst("X-API-Key"))){reply(exchange,401,"UNAUTHORIZED",null);return;}
                String raw=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
                Map<String,Object> body=json.readValue(raw,new TypeReference<Map<String,Object>>() {});
                if(!"it-reservations".equals(body.get("serviceId"))){reply(exchange,403,"FORBIDDEN",null);return;}
                if(exchange.getRequestURI().getPath().endsWith("/users")){
                    String external=body.get("externalUserId").toString();
                    boolean exists=users.containsKey(external);String id=users.computeIfAbsent(external,k->UUID.randomUUID().toString());
                    reply(exchange,exists?200:201,exists?"COMMON_200":"COMMON_201",Map.of("userId",id,"serviceId","it-reservations","externalUserId",external));return;
                }
                bodies.add(raw);
                if(!users.containsValue(body.get("userId"))){reply(exchange,404,"USER_NOT_FOUND",null);return;}
                if(!List.of("BOOKING","CANCEL","CHECK_IN").contains(body.get("eventType"))||!body.get("occurredAt").toString().matches(".*T\\d{2}:\\d{2}:\\d{2}Z")){
                    reply(exchange,400,"INVALID_REQUEST",null);return;
                }
                if(mode.get().equals("CONFLICT")){reply(exchange,409,"DATA_CONFLICT",null);return;}
                String id=body.get("externalEventId").toString();
                var old=events.get(id);
                if(old==null){body.put("eventId",sequence.incrementAndGet());events.put(id,body);}
                if(mode.compareAndSet("STORE_THEN_503","NORMAL")){reply(exchange,503,"SERVICE_UNAVAILABLE",null);return;}
                reply(exchange,old==null?201:200,old==null?"COMMON_201":"COMMON_200",events.get(id));
            }catch(RuntimeException e){reply(exchange,500,"INTERNAL_SERVER_ERROR",null);}
        }
        void reply(HttpExchange e,int status,String code,Object data)throws IOException{
            Map<String,Object> body=new LinkedHashMap<>();body.put("success",status<400);body.put("code",code);body.put("data",data);body.put("message","contract test");
            byte[] bytes=json.writeValueAsBytes(body);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);e.close();
        }
    }
}
