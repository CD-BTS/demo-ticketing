package com.bts.demoticketing.booking.service;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import com.bts.demoticketing.booking.dto.BookingDtos.*;
import com.bts.demoticketing.booking.entity.ReservationRecords.*;
import com.bts.demoticketing.booking.repository.ReservationRepository;
import com.bts.demoticketing.common.exception.ApiException;
import com.bts.demoticketing.common.response.ResponseCode;
import com.bts.demoticketing.outbox.OutboxRepository;
import com.bts.demoticketing.user.entity.DemoUser;

@Service
@Transactional(isolation=Isolation.READ_COMMITTED)
public class ReservationService {
    private final ReservationRepository repo;
    private final OutboxRepository outbox;
    private final JsonMapper json;
    private final Clock clock;
    private final Duration holdDuration;
    private final Set<String> paymentIds;
    public ReservationService(ReservationRepository repo,OutboxRepository outbox,JsonMapper json,Clock clock,
            @Value("${demo.hold-duration:PT5M}") Duration holdDuration,
            @Value("${demo.payment-identifiers:test-pay-shared-01,test-pay-shared-02,test-pay-shared-03}") String payments){
        if(holdDuration.isNegative()||holdDuration.isZero())throw new IllegalArgumentException("Hold duration must be positive");
        this.repo=repo;this.outbox=outbox;this.json=json;this.clock=clock;this.holdDuration=holdDuration;
        this.paymentIds=Set.of(payments.split(","));
    }
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);}
    private static ApiException fail(ResponseCode code){return new ApiException(code);}
    private static void owner(long expected,DemoUser user){if(expected!=user.id())throw fail(ResponseCode.FORBIDDEN);}
    private void expireLocked(Concert concert,LocalDateTime now){for(Hold h:repo.expired(concert.id(),now))repo.endHold(h.id(),"EXPIRED");}
    private HoldResponse response(Hold h,Concert c){return new HoldResponse(h.publicId(),c.publicId(),repo.holdCodes(h.id()),h.expiresAt().atOffset(ZoneOffset.UTC));}
    private BookingResponse response(Booking b){return BookingResponse.from(b,repo.concert(b.concertId()).publicId(),repo.bookingCodes(b.id()));}

    public HoldResponse hold(String concertId,DemoUser user,List<String> requested){
        if(requested==null||requested.isEmpty()||requested.size()>4||new HashSet<>(requested).size()!=requested.size())
            throw fail(ResponseCode.INVALID_SEAT_SELECTION);
        Concert c=repo.lockConcert(concertId).orElseThrow(()->fail(ResponseCode.CONCERT_NOT_FOUND));
        LocalDateTime now=now();
        expireLocked(c,now);
        List<String> codes=requested.stream().sorted().toList();
        List<Seat> seats=repo.seats(c.id(),codes);
        if(seats.size()!=codes.size())throw fail(ResponseCode.SEAT_NOT_FOUND);
        var old=repo.activeHold(user.id(),c.id());
        for(Seat s:seats){
            if(!s.state().equals("AVAILABLE") && !(s.state().equals("HELD")&&old.isPresent()&&Objects.equals(s.holdId(),old.get().id())))
                throw fail(ResponseCode.SEAT_ALREADY_TAKEN);
        }
        // Exact same selection is a retry, not a way to extend the five-minute deadline.
        if(old.isPresent()&&repo.holdCodes(old.get().id()).equals(codes))return response(old.get(),c);
        old.ifPresent(h->repo.endHold(h.id(),"RELEASED"));
        Hold h=repo.createHold("h_"+UUID.randomUUID(),user.id(),c.id(),now,now.plus(holdDuration),seats);
        return response(h,c);
    }

    public void release(String holdId,DemoUser user){
        var found=repo.hold(holdId);
        if(found.isEmpty())return;
        owner(found.get().userId(),user);
        repo.lockConcert(found.get().concertId());
        Hold current=repo.hold(holdId).orElseThrow();
        if(current.status().equals("ACTIVE"))repo.endHold(current.id(),current.expiresAt().isAfter(now())?"RELEASED":"EXPIRED");
    }

    public Confirmation confirm(String holdId,String paymentIdentifier,String observedIp,DemoUser user){
        Hold original=repo.hold(holdId).orElseThrow(()->fail(ResponseCode.HOLD_EXPIRED));
        owner(original.userId(),user);
        Concert c=repo.lockConcert(original.concertId());
        Hold h=repo.hold(holdId).orElseThrow();
        var existing=repo.byHold(h.id());
        if(existing.isPresent())return new Confirmation(response(existing.get()),false);
        LocalDateTime now=now();
        if(!h.status().equals("ACTIVE")||!h.expiresAt().isAfter(now))throw fail(ResponseCode.HOLD_EXPIRED);
        if(!paymentIds.contains(paymentIdentifier))throw fail(ResponseCode.INVALID_REQUEST);
        List<Seat> seats=repo.heldSeats(h.id());
        if(seats.isEmpty()||seats.stream().anyMatch(s->!s.state().equals("HELD")||!Objects.equals(s.holdId(),h.id())))
            throw fail(ResponseCode.BOOKING_CONFLICT);
        Duration elapsed=Duration.between(h.createdAt(),now);
        if(elapsed.isNegative())throw fail(ResponseCode.INTERNAL_SERVER_ERROR);
        BigDecimal seconds=BigDecimal.valueOf(elapsed.toNanos(),9).setScale(6,java.math.RoundingMode.DOWN);
        Booking b=repo.createBooking("b_"+UUID.randomUUID(),h,c,now,seconds,paymentIdentifier,observedIp,seats);
        Map<String,Object> metadata=metadata(b,c.publicId());
        metadata.put("bookingDurationSeconds",seconds);
        metadata.put("paymentIdentifier",paymentIdentifier);metadata.put("ipAddress",observedIp);
        enqueue(user,b,"BOOKING",metadata,now);
        return new Confirmation(response(b),true);
    }

    public BookingResponse cancel(String bookingId,DemoUser user){
        Booking original=repo.booking(bookingId).orElseThrow(()->fail(ResponseCode.BOOKING_NOT_FOUND));
        owner(original.userId(),user);
        Concert c=repo.lockConcert(original.concertId());
        Booking b=repo.booking(bookingId).orElseThrow();
        if(b.status().equals("CANCELLED"))return response(b);
        if(repo.checkedIn(b.id()))throw fail(ResponseCode.BOOKING_NOT_CANCELLABLE);
        LocalDateTime now=now();
        if(now.isBefore(b.confirmedAt()))throw fail(ResponseCode.INTERNAL_SERVER_ERROR);
        repo.cancel(b,now);
        enqueue(user,b,"CANCEL",metadata(b,c.publicId()),now);
        return response(repo.booking(bookingId).orElseThrow());
    }

    @Transactional(readOnly=true)
    public List<BookingResponse> mine(DemoUser user){return repo.bookings(user.id()).stream().map(this::response).toList();}
    public void expire(long concertId){Concert c=repo.lockConcert(concertId);expireLocked(c,now());}
    @Transactional(readOnly=true)
    public List<Long> expiredConcerts(){return repo.expiredConcerts(now());}

    private Map<String,Object> metadata(Booking b,String concert){
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("concertId",concert);m.put("performanceStartsAt",b.startAt().toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).toString());
        m.put("performanceEndsAt",b.endAt().toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).toString());m.put("ticketIds",repo.ticketIds(b.id()));
        return m;
    }
    private void enqueue(DemoUser user,Booking b,String type,Map<String,Object> metadata,LocalDateTime now){
        outbox.append(user.id(),user.serviceId(),b.publicId(),type,now.truncatedTo(ChronoUnit.SECONDS),json.writeValueAsString(metadata),now);
    }
}
