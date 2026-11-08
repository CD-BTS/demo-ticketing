package com.bts.demoticketing.outbox;

import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {
    private final JdbcTemplate jdbc;
    public OutboxRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Message(long id,long userId,String serviceId,String externalId,String aggregateId,String eventType,
            LocalDateTime occurredAt,String metadata,String payload,String lease,int attempts){}
    private final RowMapper<Message> mapper=(r,n)->new Message(r.getLong("id"),r.getLong("user_id"),r.getString("service_id"),
            r.getString("external_event_id"),r.getString("aggregate_id"),r.getString("event_type"),
            r.getObject("occurred_at_utc",LocalDateTime.class),r.getString("metadata"),r.getString("payload"),
            r.getString("lease_token"),r.getInt("retries"));

    public void append(long user,String service,String booking,String type,LocalDateTime occurred,String metadata,LocalDateTime now){
        jdbc.update("""
                INSERT INTO outbox_events(user_id,service_id,external_event_id,business_transition_id,aggregate_id,
                event_type,occurred_at_utc,metadata,next_attempt_at,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """,user,service,UUID.randomUUID().toString(),booking+":"+type,booking,type,occurred,metadata,now,now);
    }

    public long appendAttempt(long user,String service,String attempt,long journal,LocalDateTime occurred,String metadata,LocalDateTime now){
        String transition="attempt-event:"+journal;
        jdbc.update("""
            INSERT INTO outbox_events(user_id,service_id,external_event_id,business_transition_id,aggregate_id,
            event_type,occurred_at_utc,metadata,next_attempt_at,created_at)
            VALUES(?,?,?,?,?,'BOOKING_ATTEMPT',?,?,?,?)
            """,user,service,UUID.randomUUID().toString(),transition,"attempt:"+attempt,occurred,metadata,now,now);
        return jdbc.queryForObject("SELECT id FROM outbox_events WHERE business_transition_id=? AND event_type='BOOKING_ATTEMPT'",Long.class,transition);
    }

    public Optional<Message> claim(LocalDateTime now,String serviceId){
        List<Message> rows=jdbc.query("""
                SELECT e.* FROM outbox_events e
                WHERE e.service_id=? AND ((e.delivery_status IN ('PENDING','RETRY') AND e.next_attempt_at<=?)
                    OR (e.delivery_status='IN_FLIGHT' AND e.locked_until<=?))
                AND (e.event_type='BOOKING_ATTEMPT' OR NOT EXISTS (SELECT 1 FROM outbox_events older WHERE older.service_id=e.service_id AND older.aggregate_id=e.aggregate_id
                    AND older.id<e.id AND older.delivery_status<>'SENT'))
                ORDER BY e.id LIMIT 1 FOR UPDATE SKIP LOCKED
                """,mapper,serviceId,now,now);
        if(rows.isEmpty())return Optional.empty();
        Message m=rows.get(0);
        String lease=UUID.randomUUID().toString();
        jdbc.update("UPDATE outbox_events SET delivery_status='IN_FLIGHT',lease_token=?,locked_until=?,retries=retries+1 WHERE id=?",lease,now.plusSeconds(60),m.id());
        return Optional.of(new Message(m.id(),m.userId(),m.serviceId(),m.externalId(),m.aggregateId(),m.eventType(),m.occurredAt(),m.metadata(),m.payload(),lease,m.attempts()+1));
    }

    public void freeze(Message m,String payload){
        if(jdbc.update("UPDATE outbox_events SET payload=? WHERE id=? AND lease_token=? AND payload IS NULL",payload,m.id(),m.lease())!=1)
            throw new IllegalStateException("Outbox lease lost before payload freeze");
    }
    public void sent(Message m,long eventId,LocalDateTime now){
        jdbc.update("""
                UPDATE outbox_events SET delivery_status='SENT',bts_event_id=?,sent_at=?,locked_until=NULL,
                lease_token=NULL,last_error_code=NULL WHERE id=? AND lease_token=?
                """,eventId,now,m.id(),m.lease());
    }
    public void failed(Message m,String code,boolean retry,LocalDateTime now){
        boolean again=retry&&m.attempts()<8;
        long delay=Math.min(300,1L<<Math.min(m.attempts(),8));
        jdbc.update("""
                UPDATE outbox_events SET delivery_status=?,last_error_code=?,next_attempt_at=?,
                locked_until=NULL,lease_token=NULL WHERE id=? AND lease_token=?
                """,again?"RETRY":"FAILED",code,now.plusSeconds(delay),m.id(),m.lease());
    }
}
