package com.bts.demoticketing.captcha;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.dao.DataAccessException;
import jakarta.validation.constraints.*;
import com.bts.demoticketing.common.exception.ApiException;
import com.bts.demoticketing.common.response.ResponseCode;
import com.bts.demoticketing.queue.QueueStore;
import com.bts.demoticketing.integration.bts.PolicyDecisionClient;

@Service
public class CaptchaService {
    public record Verification(@NotBlank @Pattern(regexp="[a-f0-9]{64}") String challengeId,
            @NotBlank @Size(max=100) String answer) {}
    public record Challenge(String challengeId,String question,Instant expiresAt) {}
    public record Grant(String captchaToken,Instant expiresAt) {}
    private final StringRedisTemplate redis;
    private final QueueStore queues;
    private final CaptchaVerifier verifier;
    private final long challengeTtl,grantTtl;
    private final int attempts;
    private final DefaultRedisScript<List> script=new DefaultRedisScript<>();
    public CaptchaService(StringRedisTemplate redis,QueueStore queues,org.springframework.beans.factory.ObjectProvider<CaptchaVerifier> providers,
            @Value("${demo.captcha.mode:disabled}") String mode,
            @Value("${demo.captcha.challenge-ttl:PT2M}") Duration challengeTtl,
            @Value("${demo.captcha.grant-ttl:PT2M}") Duration grantTtl,
            @Value("${demo.captcha.max-attempts:5}") int attempts){
        if(!Set.of("disabled","mock").contains(mode)||challengeTtl.toMillis()<1||grantTtl.toMillis()<1||attempts<1)
            throw new IllegalArgumentException("Invalid CAPTCHA configuration; real provider is not implemented");
        this.redis=redis;this.queues=queues;this.verifier=providers.getIfAvailable();
        this.challengeTtl=challengeTtl.toMillis();this.grantTtl=grantTtl.toMillis();this.attempts=attempts;
        script.setLocation(new ClassPathResource("captcha.lua"));script.setResultType(List.class);
    }
    public static String digest(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static String nonce(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return HexFormat.of().formatHex(bytes);}
    private void enabled(){if(verifier==null)throw new ApiException(ResponseCode.SERVICE_UNAVAILABLE);}
    private List<?> call(List<String> keys,String... args){
        try{var result=redis.execute(script,keys,(Object[])args);if(result==null)throw new IllegalStateException("Invalid CAPTCHA response");return result;}
        catch(DataAccessException e){throw new ApiException(ResponseCode.SERVICE_UNAVAILABLE);}
    }
    public Challenge challenge(String concert,long user,PolicyDecisionClient.Action action){
        enabled();String id=nonce();var problem=verifier.create();
        var result=call(List.of(queues.base(concert)+"captcha-challenge:"+id),"create",Long.toString(user),action.name(),
                digest(problem.answer()),Long.toString(challengeTtl),Integer.toString(attempts));
        return new Challenge(id,problem.question(),Instant.ofEpochMilli(Long.parseLong(result.get(0).toString())));
    }
    public Grant verify(String concert,long user,Verification body){
        enabled();String token=nonce();String key=queues.base(concert)+"captcha-challenge:"+body.challengeId();
        // The provider comparison is separate; attempts, expiry and single successful
        // verification are checked again atomically in Lua against the expected digest.
        String expected;
        try{expected=(String)redis.opsForHash().get(key,"answer");}
        catch(DataAccessException e){throw new ApiException(ResponseCode.SERVICE_UNAVAILABLE);}
        boolean valid=expected!=null&&verifier.verify(expected,body.answer());
        var result=call(List.of(key,queues.base(concert)+"captcha-grant:"+digest(token)),"verify",Long.toString(user),
                valid?expected:"invalid",Long.toString(grantTtl));
        if(!result.get(0).toString().equals("OK"))throw new ApiException(ResponseCode.CAPTCHA_INVALID);
        return new Grant(token,Instant.ofEpochMilli(Long.parseLong(result.get(1).toString())));
    }
}
