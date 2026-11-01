package com.bts.demoticketing.outbox;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import com.bts.demoticketing.user.repository.DemoUserRepository;

@Service
public class OutboxTransactions {
    private final OutboxRepository outbox;
    private final DemoUserRepository users;
    private final Clock clock;
    private final com.bts.demoticketing.integration.bts.BtsSettings settings;
    public OutboxTransactions(OutboxRepository outbox,DemoUserRepository users,Clock clock,com.bts.demoticketing.integration.bts.BtsSettings settings){this.outbox=outbox;this.users=users;this.clock=clock;this.settings=settings;}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Optional<OutboxRepository.Message> claim(){return outbox.claim(now(),settings.serviceId);}
    @Transactional
    public void freeze(OutboxRepository.Message m,String payload){outbox.freeze(m,payload);}
    @Transactional
    public void mapUser(long user,String uuid){users.mapBtsUser(user,uuid);}
    @Transactional
    public void sent(OutboxRepository.Message m,long id){outbox.sent(m,id,now());}
    @Transactional
    public void failed(OutboxRepository.Message m,String code,boolean retry){outbox.failed(m,code,retry,now());}
}
