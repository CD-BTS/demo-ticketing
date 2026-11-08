package com.bts.demoticketing.captcha;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.script.RedisScript;
import com.bts.demoticketing.queue.QueueStore;
import com.bts.demoticketing.common.exception.ApiException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CaptchaReplayTests {
    @Mock ObjectProvider<CaptchaVerifier> provider;
    @Mock HashOperations<String,Object,Object> hashes;
    @Test void returnsCachedTokenEvenAfterChallengeWasConsumed(){
        var redis=mock(StringRedisTemplate.class);
        var queues=mock(QueueStore.class);
        when(provider.getIfAvailable()).thenReturn(mock(CaptchaVerifier.class));
        when(queues.base("c1")).thenReturn("queue:{c1}:");
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.execute(ArgumentMatchers.<RedisScript<List<String>>>any(),anyList(),any(Object[].class)))
            .thenReturn(List.of("OK","2000000000000","original-token"));
        var service=new CaptchaService(redis,queues,provider,"mock",Duration.ofMinutes(2),Duration.ofMinutes(2),5);
        var body=new CaptchaService.Verification("a".repeat(64),"42");
        var first=service.verify("c1",7,body,"transition");
        var retry=service.verify("c1",7,body,"transition");
        assertEquals("original-token",first.captchaToken());
        assertEquals(first,retry);
        verify(redis,times(2)).execute(ArgumentMatchers.<RedisScript<List<String>>>any(),argThat(keys -> keys.get(2).equals("queue:{c1}:captcha-replay:7:transition")),any(Object[].class));
    }
    @Test void changedInputForReplayKeyIsAConflict(){
        var redis=mock(StringRedisTemplate.class);
        var queues=mock(QueueStore.class);
        when(provider.getIfAvailable()).thenReturn(mock(CaptchaVerifier.class));
        when(queues.base("c1")).thenReturn("queue:{c1}:");
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.execute(ArgumentMatchers.<RedisScript<List<String>>>any(),anyList(),any(Object[].class))).thenReturn(List.of("CONFLICT"));
        var service=new CaptchaService(redis,queues,provider,"mock",Duration.ofMinutes(2),Duration.ofMinutes(2),5);
        var error=assertThrows(ApiException.class,()->service.verify("c1",7,new CaptchaService.Verification("a".repeat(64),"changed"),"transition"));
        assertEquals("DATA_CONFLICT",error.getResponseCode().getCode());
    }
}
