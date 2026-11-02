package com.bts.demoticketing.booking.service;

import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.bts.demoticketing.outbox.OutboxDeliveryService;

@Component
@ConditionalOnProperty(name="demo.jobs.enabled",havingValue="true",matchIfMissing=true)
public class BookingJobs {
    private static final Logger log=LoggerFactory.getLogger(BookingJobs.class);
    private final ReservationService reservations;
    private final OutboxDeliveryService delivery;
    public BookingJobs(ReservationService reservations,OutboxDeliveryService delivery){this.reservations=reservations;this.delivery=delivery;}
    @Scheduled(fixedDelayString="${demo.expiry-poll-ms:5000}")
    public void expire(){
        for(long concert:reservations.expiredConcerts()){
            try{reservations.expire(concert);}catch(RuntimeException e){log.warn("Hold expiry will retry; concert={}",concert);}
        }
    }
    @Scheduled(fixedDelayString="${bts.poll-ms:5000}")
    public void deliver(){
        try{for(int i=0;i<10&&delivery.deliverNext();i++){/* bounded batch */}}
        catch(RuntimeException e){log.warn("BTS delivery job will retry; details withheld");}
    }
}
