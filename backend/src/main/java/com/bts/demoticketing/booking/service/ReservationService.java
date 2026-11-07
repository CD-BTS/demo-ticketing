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
import com.bts.demoticketing.queue.QueueService;

@Service
public class ReservationService {
    private final ReservationRepository repo;
    private final OutboxRepository outbox;
    private final JsonMapper json;
    private final Clock clock;
    private final Duration holdDuration;
    private final Set<String> paymentIds;
    private final QueueService queue;
    private final ReservationTransactions transactions;
    public ReservationService(ReservationRepository repo,OutboxRepository outbox,JsonMapper json,Clock clock,QueueService queue,ReservationTransactions transactions,
            @Value("${demo.hold-duration:PT5M}") Duration holdDuration,
            @Value("${demo.payment-identifiers:test-pay-shared-01,test-pay-shared-02,test-pay-shared-03}") String payments){
        if(holdDuration.isNegative()||holdDuration.isZero())throw new IllegalArgumentException("Hold duration must be positive");
        this.repo=repo;this.outbox=outbox;this.json=json;this.clock=clock;this.holdDuration=holdDuration;
        this.paymentIds=Set.of(payments.split(","));
        this.queue=queue;
        this.transactions=transactions;
    }
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);}
    private static ApiException fail(ResponseCode code){return new ApiException(code);}
    private static void owner(long expected,DemoUser user){if(expected!=user.id())throw fail(ResponseCode.FORBIDDEN);}
    private HoldResponse response(Hold h,Concert c){return new HoldResponse(h.publicId(),c.publicId(),repo.holdCodes(h.id()),h.expiresAt().atOffset(ZoneOffset.UTC));}
    private BookingResponse response(Booking b){return BookingResponse.from(b,repo.concert(b.concertId()).publicId(),repo.bookingCodes(b.id()));}

    public HoldResponse hold(String concertId,DemoUser user,List<String> requested){
        if(requested==null||requested.isEmpty()||requested.size()>4||new HashSet<>(requested).size()!=requested.size())
            throw fail(ResponseCode.INVALID_SEAT_SELECTION);
        return transactions.run(()->holdOnce(concertId,user,requested));
    }
    private HoldResponse holdOnce(String concertId,DemoUser user,List<String> requested){
        Concert c=repo.concert(concertId).orElseThrow(()->fail(ResponseCode.CONCERT_NOT_FOUND));
        queue.requireAdmitted(concertId,user.id());
        repo.lockUserConcert(user.id(),c.id());
        List<String> codes=requested.stream().sorted().toList();
        List<Seat> observed=repo.seats(c.id(),codes);
        if(observed.size()!=codes.size())throw fail(ResponseCode.SEAT_NOT_FOUND);
        var previous=repo.activeHold(user.id(),c.id());
        Set<Long> ids=new TreeSet<>();
        observed.forEach(s->ids.add(s.id()));
        Set<Long> holds=new TreeSet<>();
        previous.ifPresent(h->{holds.add(h.id());ids.addAll(repo.holdSeatIds(h.id()));});
        LocalDateTime observedAt=now();
        // 만료 선점은 일부 좌석만 해제하지 않는다. 관련 좌석 전체를 먼저 수집한다.
        for(Seat seat:observed)if(seat.holdId()!=null){
            var h=repo.hold(seat.holdId()).orElseThrow();
            if(h.status().equals("ACTIVE")&&!h.expiresAt().isAfter(observedAt)){
                holds.add(h.id());ids.addAll(repo.holdSeatIds(h.id()));
            }
        }
        List<Seat> locked=repo.lockSeats(ids);
        LocalDateTime lockedAt=now();
        for(Seat seat:locked)if(codes.contains(seat.code())&&seat.holdId()!=null&&!holds.contains(seat.holdId())){
            var h=repo.hold(seat.holdId()).orElseThrow();
            if(h.status().equals("ACTIVE")&&!h.expiresAt().isAfter(lockedAt))throw new ReservationTransactions.Retry();
        }
        Map<Long,Hold> current=new TreeMap<>();
        for(long id:holds)current.put(id,repo.lockHold(id));
        // 사용자/좌석 잠금 대기 중 입장권이나 선점이 만료될 수 있으므로 다시 검사한다.
        queue.requireAdmitted(concertId,user.id());
        LocalDateTime now=now();
        for(Hold h:current.values())if(h.status().equals("ACTIVE")&&!h.expiresAt().isAfter(now))repo.endHold(h.id(),"EXPIRED");
        var old=previous.flatMap(h->repo.hold(h.id())).filter(h->h.status().equals("ACTIVE"));
        List<Seat> seats=repo.seats(c.id(),codes);
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
        transactions.run(()->{releaseOnce(holdId,user);return null;});
    }
    private void releaseOnce(String holdId,DemoUser user){
        var found=repo.hold(holdId);
        if(found.isEmpty())return;
        owner(found.get().userId(),user);
        repo.lockSeats(repo.holdSeatIds(found.get().id()));
        Hold current=repo.lockHold(found.get().id());
        if(current.status().equals("ACTIVE"))repo.endHold(current.id(),current.expiresAt().isAfter(now())?"RELEASED":"EXPIRED");
    }

    public Confirmation confirm(String holdId,String paymentIdentifier,String observedIp,DemoUser user){
        return transactions.run(()->confirmOnce(holdId,paymentIdentifier,observedIp,user));
    }
    private Confirmation confirmOnce(String holdId,String paymentIdentifier,String observedIp,DemoUser user){
        // A valid owned hold is the authority here; queue admission was enforced at hold().
        // Its later expiry (or Redis unavailability) must not invalidate that hold.
        Hold original=repo.hold(holdId).orElseThrow(()->fail(ResponseCode.HOLD_EXPIRED));
        owner(original.userId(),user);
        Concert c=repo.concert(original.concertId());
        List<Seat> seats=repo.lockSeats(repo.holdSeatIds(original.id()));
        Hold h=repo.lockHold(original.id());
        var existing=repo.byHold(h.id());
        if(existing.isPresent())return new Confirmation(response(existing.get()),false);
        LocalDateTime now=now();
        if(!h.status().equals("ACTIVE")||!h.expiresAt().isAfter(now))throw fail(ResponseCode.HOLD_EXPIRED);
        if(!paymentIds.contains(paymentIdentifier))throw fail(ResponseCode.INVALID_REQUEST);
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
        return transactions.run(()->cancelOnce(bookingId,user));
    }
    private BookingResponse cancelOnce(String bookingId,DemoUser user){
        Booking original=repo.booking(bookingId).orElseThrow(()->fail(ResponseCode.BOOKING_NOT_FOUND));
        owner(original.userId(),user);
        Concert c=repo.concert(original.concertId());
        repo.lockSeats(repo.bookingSeatIds(original.id()));
        Booking b=repo.lockBooking(original.id());
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
    public void expire(long concertId){
        for(Hold candidate:repo.expired(concertId,now()))expireHold(candidate.id());
    }
    public void expireHold(long holdId){
        transactions.run(()->{
            repo.lockSeats(repo.holdSeatIds(holdId));
            Hold h=repo.lockHold(holdId);
            if(h.status().equals("ACTIVE")&&!h.expiresAt().isAfter(now()))repo.endHold(h.id(),"EXPIRED");
            return null;
        });
    }
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
