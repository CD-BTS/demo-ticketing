package com.bts.demoticketing.outbox;

import java.time.ZoneOffset;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import com.bts.demoticketing.integration.bts.*;
import com.bts.demoticketing.user.entity.DemoUser;
import com.bts.demoticketing.user.repository.DemoUserRepository;

@Service
public class OutboxDeliveryService {
    private final OutboxTransactions tx;
    private final DemoUserRepository users;
    private final BtsClient bts;
    private final BtsSettings settings;
    private final JsonMapper json;
    public OutboxDeliveryService(OutboxTransactions tx,DemoUserRepository users,BtsClient bts,BtsSettings settings,JsonMapper json){
        this.tx=tx;this.users=users;this.bts=bts;this.settings=settings;this.json=json;
    }
    // Network I/O is deliberately outside every local DB transaction/booking lock.
    public boolean deliverNext(){
        if(!settings.enabled)return false;
        var claimed=tx.claim();
        if(claimed.isEmpty())return false;
        var m=claimed.get();
        try {
            String payload=m.payload();
            if(payload==null){
                DemoUser user=users.find(m.userId()).orElseThrow();
                if(!m.serviceId().equals(settings.serviceId)||!m.serviceId().equals(user.serviceId()))
                    throw new BtsClient.DeliveryFailure("SERVICE_MAPPING_MISMATCH",false);
                String uuid=user.btsUserId();
                if(uuid==null){uuid=bts.register(user);tx.mapUser(user.id(),uuid);}
                Map<String,Object> body=new LinkedHashMap<>();
                body.put("serviceId",m.serviceId());body.put("externalEventId",m.externalId());
                body.put("userId",uuid);body.put("eventType",m.eventType());body.put("bookingId",m.aggregateId());
                body.put("occurredAt",m.occurredAt().toInstant(ZoneOffset.UTC).toString());
                body.put("metadata",json.readValue(m.metadata(),Map.class));
                payload=json.writeValueAsString(body);
                tx.freeze(m,payload);
            }
            long eventId=bts.send(payload);
            tx.sent(m,eventId);
        }catch(BtsClient.DeliveryFailure e){tx.failed(m,e.code,e.retryable);}
        catch(RuntimeException e){tx.failed(m,"LOCAL_DELIVERY_ERROR",true);}
        return true;
    }
}
