package com.bts.demoticketing.queue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;
import com.bts.demoticketing.common.exception.ApiException;
import com.bts.demoticketing.common.response.ResponseCode;

@Repository
public class QueueStore {
    private final StringRedisTemplate redis;
    private final String prefix;
    private final int capacity;
    private final long waitTtl, admitTtl, retention;
    private final DefaultRedisScript<List> script = new DefaultRedisScript<>();

    public QueueStore(StringRedisTemplate redis,
            @Value("${demo.queue.key-prefix:demo-ticketing:queue}") String prefix,
            @Value("${demo.queue.capacity:100}") int capacity,
            @Value("${demo.queue.wait-ttl:PT30M}") Duration waitTtl,
            @Value("${demo.queue.admit-ttl:PT5M}") Duration admitTtl,
            @Value("${demo.queue.expired-retention:PT10M}") Duration retention) {
        if (prefix.isBlank() || prefix.contains("{") || prefix.contains("}") || capacity < 1
                || waitTtl.toMillis() < 1 || admitTtl.toMillis() < 1 || retention.toMillis() < 1)
            throw new IllegalArgumentException("Queue prefix, capacity and TTLs must be valid and positive");
        this.redis=redis; this.prefix=prefix; this.capacity=capacity;
        this.waitTtl=waitTtl.toMillis(); this.admitTtl=admitTtl.toMillis(); this.retention=retention.toMillis();
        script.setLocation(new ClassPathResource("queue.lua"));
        script.setResultType(List.class);
    }

    public record Snapshot(String status, Long position, Instant expiresAt) {}
    public record Result(Snapshot snapshot, boolean created) {}

    public Result execute(String concert, long user, String operation) {
        return run(concert,user,operation,"ALLOW",null);
    }
    public Result join(String concert,long user,String action,String captchaToken){
        return run(concert,user,"join",action,captchaToken);
    }
    public String base(String concert){
        String tag=Base64.getUrlEncoder().withoutPadding().encodeToString(concert.getBytes(StandardCharsets.UTF_8));
        return prefix+":{"+tag+"}:";
    }
    private Result run(String concert,long user,String operation,String action,String captchaToken){
        String base=base(concert);
        var policy=QueueJoinPolicy.forAction(com.bts.demoticketing.integration.bts.PolicyDecisionClient.Action.valueOf(action));
        boolean provided=captchaToken!=null&&!captchaToken.isBlank();
        String grant=base+"captcha-grant:"+(provided?com.bts.demoticketing.captcha.CaptchaService.digest(captchaToken):"none");
        try {
            List<?> result=redis.execute(script,
                    List.of(base+"waiting",base+"deadlines",base+"admitted",base+"expired",base+"sequence",base+"waiting-delayed",grant),
                    operation,Long.toString(user),Integer.toString(capacity),Long.toString(waitTtl),
                    Long.toString(admitTtl),Long.toString(retention),action,
                    policy.delayed()?"1":"0",policy.captchaRequired()?"1":"0",policy.restricted()?"1":"0",provided?"1":"0");
            if(result==null || result.size()!=4) throw new IllegalStateException("Invalid queue script response");
            String status=result.get(0).toString();
            if(status.equals("CAPTCHA_REQUIRED"))throw new ApiException(ResponseCode.CAPTCHA_REQUIRED);
            if(status.equals("CAPTCHA_INVALID"))throw new ApiException(ResponseCode.CAPTCHA_INVALID);
            if(status.equals("QUEUE_RESTRICTED"))throw new ApiException(ResponseCode.QUEUE_RESTRICTED);
            long position=Long.parseLong(result.get(1).toString());
            long expiry=Long.parseLong(result.get(2).toString());
            return new Result(new Snapshot(result.get(0).toString(),position<0?null:position,
                    expiry==0?null:Instant.ofEpochMilli(expiry)),"1".equals(result.get(3).toString()));
        } catch(DataAccessException e) { throw new ApiException(ResponseCode.SERVICE_UNAVAILABLE); }
    }
}
