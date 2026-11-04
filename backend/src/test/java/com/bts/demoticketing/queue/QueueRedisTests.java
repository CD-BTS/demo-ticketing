package com.bts.demoticketing.queue;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.*;

@Tag("redis")
class QueueRedisTests {
    LettuceConnectionFactory factory;
    StringRedisTemplate redis;
    String prefix;
    QueueStore store;
    @BeforeEach void setup(){
        var config=new RedisStandaloneConfiguration(env("REDIS_HOST","localhost"),Integer.parseInt(env("REDIS_PORT","6380")));
        config.setDatabase(Integer.parseInt(env("REDIS_DATABASE","0")));
        if(!env("REDIS_PASSWORD","").isEmpty()) config.setPassword(env("REDIS_PASSWORD",""));
        if(!env("REDIS_USERNAME","").isEmpty()) config.setUsername(env("REDIS_USERNAME",""));
        factory=new LettuceConnectionFactory(config);factory.afterPropertiesSet();
        redis=new StringRedisTemplate(factory);
        prefix="demo-ticketing:queue:test:"+UUID.randomUUID();
        store=new QueueStore(redis,prefix,1,Duration.ofSeconds(10),Duration.ofSeconds(10),Duration.ofSeconds(10));
    }
    static String env(String key,String fallback){return System.getenv().getOrDefault(key,fallback);}
    @AfterEach void cleanup(){
        try { var keys=redis.keys(prefix+":*");if(keys!=null&&!keys.isEmpty())redis.delete(keys); }
        finally { factory.destroy(); }
    }
    QueueStore.Result call(String concert,long user,String operation){return store.execute(concert,user,operation);}
    @Test void firstDuplicateFifoRankCapacityLeaveAndIndependentConcerts(){
        var first=call("c1",1,"join");assertTrue(first.created());assertEquals("ADMITTED",first.snapshot().status());
        var duplicate=call("c1",1,"join");assertFalse(duplicate.created());assertEquals(first.snapshot(),duplicate.snapshot());
        assertEquals(1L,call("c1",2,"join").snapshot().position());
        assertEquals(2L,call("c1",3,"join").snapshot().position());
        assertEquals("ADMITTED",call("c2",2,"join").snapshot().status());
        call("c1",1,"leave");assertEquals("ADMITTED",call("c1",2,"status").snapshot().status());
        assertEquals(1L,call("c1",3,"status").snapshot().position());
        call("c1",3,"leave");call("c1",3,"leave");
        assertEquals("NOT_JOINED",call("c1",3,"status").snapshot().status());
        assertTrue(call("c1",3,"join").created());
    }
    @Test void admissionExpiryReturnsSlotAndDoesNotRenewOnPolling() throws Exception {
        store=new QueueStore(redis,prefix,1,Duration.ofSeconds(10),Duration.ofMillis(250),Duration.ofSeconds(10));
        var first=call("c",1,"join");call("c",2,"join");
        assertEquals(first.snapshot().expiresAt(),call("c",1,"status").snapshot().expiresAt());
        Thread.sleep(300);
        assertEquals("ADMITTED",call("c",2,"status").snapshot().status());
        assertEquals("EXPIRED",call("c",1,"status").snapshot().status());
        assertTrue(call("c",1,"join").created());
        assertEquals(1L,call("c",1,"status").snapshot().position());
    }
    @Test void waitingExpiryAndReentry() throws Exception {
        store=new QueueStore(redis,prefix,1,Duration.ofMillis(100),Duration.ofSeconds(10),Duration.ofSeconds(10));
        call("c",1,"join");call("c",2,"join");Thread.sleep(150);
        assertEquals("EXPIRED",call("c",2,"status").snapshot().status());
        assertTrue(call("c",2,"join").created());
        assertEquals("WAITING",call("c",2,"status").snapshot().status());
    }
    @Test void concurrentDuplicateAndDistinctAdmissions() throws Exception {
        var executor=Executors.newFixedThreadPool(12);
        try {
            List<Callable<QueueStore.Result>> same=new ArrayList<>();
            for(int i=0;i<40;i++)same.add(()->call("same",1,"join"));
            var results=executor.invokeAll(same);
            int created=0;for(var result:results)if(result.get().created())created++;
            assertEquals(1,created);
            List<Callable<QueueStore.Result>> distinct=new ArrayList<>();
            for(long i=1;i<=40;i++){long id=i;distinct.add(()->call("distinct",id,"join"));}
            var joined=executor.invokeAll(distinct);
            int admitted=0;Set<Long> ranks=new HashSet<>();
            for(var result:joined){var snapshot=result.get().snapshot();if(snapshot.status().equals("ADMITTED"))admitted++;else ranks.add(snapshot.position());}
            assertEquals(1,admitted);assertEquals(39,ranks.size());
            for(long rank=1;rank<=39;rank++)assertTrue(ranks.contains(rank));
        } finally {executor.shutdownNow();}
    }
}
