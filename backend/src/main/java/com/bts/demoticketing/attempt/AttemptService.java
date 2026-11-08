package com.bts.demoticketing.attempt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import com.bts.demoticketing.user.entity.DemoUser;
import com.bts.demoticketing.common.exception.ApiException;
import com.bts.demoticketing.common.response.ResponseCode;
import com.bts.demoticketing.outbox.OutboxRepository;

/** Local durable journal. No remote I/O occurs on the reservation path. */
@Service
public class AttemptService {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private final OutboxRepository outbox;
    private final TransactionTemplate tx;
    private final boolean enabled;
    public AttemptService(JdbcTemplate jdbc,JsonMapper json,Clock clock,OutboxRepository outbox,
            PlatformTransactionManager manager,@Value("${demo.attempts.enabled:false}") boolean enabled) {
        this.jdbc=jdbc;this.json=json;this.clock=clock;this.outbox=outbox;this.enabled=enabled;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    private static ApiException error(ResponseCode code){return new ApiException(code);}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);}
    public static void uuid(String value){
        try {if(!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException();}
        catch(RuntimeException e){throw error(ResponseCode.INVALID_REQUEST);}
    }
    public boolean enabled(){return enabled;}
    private void requireEnabled(){if(!enabled)throw error(ResponseCode.SERVICE_UNAVAILABLE);}
    public record Snapshot(String attemptId,String concertId,String stage,String status,long sequence,String holdId,String bookingId){}
    public Snapshot start(String concert,String id,DemoUser user,String clickedAt){
        requireEnabled();uuid(id);
        if(clickedAt!=null){try{OffsetDateTime.parse(clickedAt);}catch(RuntimeException e){throw error(ResponseCode.INVALID_REQUEST);}}
        return tx.execute(s->{
            Long c=jdbc.query("SELECT id FROM concerts WHERE public_id=?",(r,n)->r.getLong(1),concert).stream()
                .findFirst().orElseThrow(()->error(ResponseCode.CONCERT_NOT_FOUND));
            jdbc.update("""
                INSERT INTO booking_attempts(attempt_id,user_id,concert_id,service_id,started_at)
                VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE attempt_id=attempt_id
                """,id,user.id(),c,user.serviceId(),now());
            check(id,user,concert,true);
            Map<String,Object> extra=new LinkedHashMap<>();if(clickedAt!=null)extra.put("clientOccurredAt",clickedAt);
            append(id,"start","STARTED","ENTERED","CLIENT_REPORT",extra);
            return snapshot(id,user);
        });
    }
    public Snapshot get(String id,DemoUser user){
        requireEnabled();uuid(id);
        return tx.execute(s->{
            check(id,user,null,true);reconcile(id);return snapshot(id,user);
        });
    }
    private void reconcile(String id){
        var a=jdbc.queryForMap("SELECT * FROM booking_attempts WHERE attempt_id=? FOR UPDATE",id);
        if(terminal((String)a.get("stage"),(String)a.get("status"))||a.get("hold_id")==null)return;
        var bookings=jdbc.queryForList("SELECT id,public_id FROM bookings WHERE hold_id=?",a.get("hold_id"));
        if(!bookings.isEmpty()){
            var b=bookings.get(0);completed(id,((Number)b.get("id")).longValue(),b.get("public_id").toString());return;
        }
        String state=jdbc.queryForObject("SELECT status FROM seat_holds WHERE id=?",String.class,a.get("hold_id"));
        if("EXPIRED".equals(state))append(id,"expired:"+a.get("hold_id"),"SEAT_HOLD","EXPIRED","SERVER",Map.of("reasonCode","HOLD_EXPIRED"));
    }
    public void check(String id,DemoUser user,String concert,boolean lock){
        requireEnabled();uuid(id);
        var rows=jdbc.queryForList("""
            SELECT a.*,c.public_id AS concert FROM booking_attempts a JOIN concerts c ON c.id=a.concert_id
            WHERE a.attempt_id=?
            """+(lock?" FOR UPDATE":""),id);
        if(rows.isEmpty())throw error(ResponseCode.RESOURCE_NOT_FOUND);
        var a=rows.get(0);
        if(((Number)a.get("user_id")).longValue()!=user.id()||!user.serviceId().equals(a.get("service_id")))throw error(ResponseCode.FORBIDDEN);
        if(concert!=null&&!concert.equals(a.get("concert")))throw error(ResponseCode.DATA_CONFLICT);
    }
    private Snapshot snapshot(String id,DemoUser user){
        check(id,user,null,false);
        return jdbc.query("""
            SELECT a.*,c.public_id AS concert,h.public_id AS hold,b.public_id AS booking
            FROM booking_attempts a JOIN concerts c ON c.id=a.concert_id
            LEFT JOIN seat_holds h ON h.id=a.hold_id LEFT JOIN bookings b ON b.id=a.booking_id WHERE a.attempt_id=?
            """,(r,n)->new Snapshot(id,r.getString("concert"),r.getString("stage"),r.getString("status"),r.getLong("sequence_no"),r.getString("hold"),r.getString("booking")),id).get(0);
    }
    public Snapshot client(String id,DemoUser user,String transition,String stage,String status){
        requireEnabled();uuid(transition);
        return tx.execute(s->{
            check(id,user,null,true);
            if(replayed(id,transition,stage,status,"CLIENT_REPORT",Map.of()))return snapshot(id,user);
            var current=snapshot(id,user);
            if(!AttemptRules.clientAllowed(current.stage(),current.status(),current.holdId()!=null,stage,status))throw error(ResponseCode.DATA_CONFLICT);
            if("ABANDONED".equals(status)&&current.holdId()!=null){
                String holdState=jdbc.queryForObject("SELECT status FROM seat_holds WHERE public_id=?",String.class,current.holdId());
                if("ACTIVE".equals(holdState)||"CONVERTED".equals(holdState))throw error(ResponseCode.DATA_CONFLICT);
            }
            append(id,transition,stage,status,"CLIENT_REPORT",Map.of());return snapshot(id,user);
        });
    }
    /** Used before business work; the caller already owns a reservation transaction. */
    public void active(String id,DemoUser user,String concert){
        if(id==null)return;check(id,user,concert,true);
        var a=snapshot(id,user);
        if(terminal(a.stage(),a.status()))throw error(ResponseCode.DATA_CONFLICT);
    }
    public static boolean terminal(String stage,String status){return stage.equals("COMPLETED")||Set.of("FAILED","ABANDONED","EXPIRED").contains(status);}
    public void linkHold(String id,DemoUser user,String concert,long holdId,String hold){
        if(id==null)return;active(id,user,concert);
        var a=snapshot(id,user);
        if(a.holdId()!=null&&!a.holdId().equals(hold))throw error(ResponseCode.DATA_CONFLICT);
        jdbc.update("UPDATE booking_attempts SET hold_id=? WHERE attempt_id=?",holdId,id);
        append(id,"hold:"+hold,"SEAT_HOLD","SUCCEEDED","SERVER",Map.of("holdId",hold));
    }
    public String forHold(long hold){
        if(!enabled)return null;
        return jdbc.query("SELECT attempt_id FROM booking_attempts WHERE hold_id=?",(r,n)->r.getString(1),hold).stream().findFirst().orElse(null);
    }
    public String forBooking(long booking){
        if(!enabled)return null;
        return jdbc.query("SELECT attempt_id FROM booking_attempts WHERE booking_id=?",(r,n)->r.getString(1),booking).stream().findFirst().orElse(null);
    }
    public void completed(String id,long bookingId,String booking){
        if(id==null)return;
        jdbc.update("UPDATE booking_attempts SET booking_id=? WHERE attempt_id=?",bookingId,id);
        append(id,"completed:"+booking,"COMPLETED","SUCCEEDED","SERVER",Map.of("bookingId",booking));
    }
    public void expired(long hold){
        String id=forHold(hold);if(id!=null)append(id,"expired:"+hold,"SEAT_HOLD","EXPIRED","SERVER",Map.of("reasonCode","HOLD_EXPIRED"));
    }
    /** Redis succeeded independently: observation failure must not undo queue admission. */
    public void observe(String id,DemoUser user,String concert,String transition,String stage,String status){
        observe(id,user,concert,transition,stage,status,null);
    }
    public void observe(String id,DemoUser user,String concert,String transition,String stage,String status,String reason){
        if(id==null)return;
        try{tx.execute(s->{check(id,user,concert,true);append(id,transition,stage,status,"SERVER",reason==null?Map.of():Map.of("reasonCode",reason));return null;});}
        catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(AttemptService.class).warn("Attempt observation not persisted; retry from authoritative state");}
    }
    private void append(String id,String transition,String stage,String status,String source,Map<String,Object> extra){
        var a=jdbc.queryForMap("SELECT * FROM booking_attempts WHERE attempt_id=? FOR UPDATE",id);
        String signature=fingerprint(stage+"|"+status+"|"+source+"|"+json.writeValueAsString(new TreeMap<>(extra)));
        if(replayed(id,transition,stage,status,source,extra))return;
        if(terminal((String)a.get("stage"),(String)a.get("status")))throw error(ResponseCode.DATA_CONFLICT);
        long sequence=((Number)a.get("sequence_no")).longValue()+1;
        LocalDateTime at=now();
        String concert=jdbc.queryForObject("SELECT public_id FROM concerts WHERE id=?",String.class,a.get("concert_id"));
        Map<String,Object> m=new LinkedHashMap<>(extra);
        if(a.get("hold_id")!=null)m.putIfAbsent("holdId",jdbc.queryForObject("SELECT public_id FROM seat_holds WHERE id=?",String.class,a.get("hold_id")));
        if(a.get("booking_id")!=null)m.putIfAbsent("bookingId",jdbc.queryForObject("SELECT public_id FROM bookings WHERE id=?",String.class,a.get("booking_id")));
        if(status.equals("ABANDONED"))m.putIfAbsent("reasonCode","USER_CANCELLED");
        m.put("attemptId",id);m.put("transitionId",transition);m.put("concertId",concert);m.put("stage",stage);
        m.put("status",status);m.put("sequence",sequence);m.put("source",source);m.put("recordedAt",at.toInstant(ZoneOffset.UTC).toString());
        String metadata=json.writeValueAsString(m);
        jdbc.update("""
            INSERT INTO booking_attempt_events(attempt_id,transition_id,sequence_no,request_fingerprint,metadata,occurred_at)
            VALUES(?,?,?,?,?,?)
            """,id,transition,sequence,signature,metadata,at.truncatedTo(ChronoUnit.SECONDS));
        jdbc.update("UPDATE booking_attempts SET stage=?,status=?,sequence_no=? WHERE attempt_id=?",stage,status,sequence,id);
    }
    private boolean replayed(String id,String transition,String stage,String status,String source,Map<String,Object> extra){
        String signature=fingerprint(stage+"|"+status+"|"+source+"|"+json.writeValueAsString(new TreeMap<>(extra)));
        var old=jdbc.queryForList("SELECT request_fingerprint FROM booking_attempt_events WHERE attempt_id=? AND transition_id=?",id,transition);
        if(!old.isEmpty()){
            if(!signature.equals(old.get(0).get("request_fingerprint")))throw error(ResponseCode.DATA_CONFLICT);
            return true;
        }
        return false;
    }
    public static String fingerprint(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    /** Materialization is separate from business transactions and can be retried. */
    public void materialize(){
        if(!enabled)return;
        tx.execute(s->{
            var changed=jdbc.queryForList("""
                SELECT a.attempt_id FROM booking_attempts a JOIN seat_holds h ON h.id=a.hold_id
                WHERE a.stage<>'COMPLETED' AND a.status NOT IN ('FAILED','ABANDONED','EXPIRED')
                  AND (h.status='EXPIRED' OR EXISTS(SELECT 1 FROM bookings b WHERE b.hold_id=h.id))
                ORDER BY a.attempt_id LIMIT 50
                """,String.class);
            changed.forEach(this::reconcile);
            var pending=jdbc.queryForList("""
                SELECT e.*,a.user_id,a.service_id FROM booking_attempt_events e JOIN booking_attempts a ON a.attempt_id=e.attempt_id
                WHERE e.outbox_id IS NULL ORDER BY e.id LIMIT 50 FOR UPDATE SKIP LOCKED
                """);
            for(var e:pending){
                long outboxId=outbox.appendAttempt(((Number)e.get("user_id")).longValue(),(String)e.get("service_id"),
                    (String)e.get("attempt_id"),((Number)e.get("id")).longValue(),
                    sqlTime(e.get("occurred_at")),e.get("metadata").toString(),now());
                jdbc.update("UPDATE booking_attempt_events SET outbox_id=? WHERE id=?",outboxId,e.get("id"));
            }
            return null;
        });
    }
    private static LocalDateTime sqlTime(Object value){return value instanceof LocalDateTime t?t:((java.sql.Timestamp)value).toLocalDateTime();}
}
