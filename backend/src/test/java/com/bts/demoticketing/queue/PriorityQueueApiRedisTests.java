package com.bts.demoticketing.queue;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.json.JsonMapper;
import com.bts.demoticketing.concert.service.ConcertService;
import com.bts.demoticketing.user.service.DemoAuthService;
import com.bts.demoticketing.user.entity.DemoUser;
import com.bts.demoticketing.integration.bts.PolicyDecisionClient;
import com.bts.demoticketing.integration.bts.PolicyDecisionClient.Action;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("redis")
@SpringBootTest(properties={"demo.jobs.enabled=false","bts.enabled=false","demo.queue.capacity=1",
        "demo.policy.mode=mock","demo.captcha.mode=mock",
        "demo.policy.mock-actions={\"u2\":\"REQUIRE_CAPTCHA\",\"u3\":\"DELAY\",\"u4\":\"RESTRICT\",\"u5\":\"DELAY\",\"u6\":\"REQUIRE_CAPTCHA\"}"})
@AutoConfigureMockMvc
class PriorityQueueApiRedisTests {
    static final String PREFIX="demo-ticketing:queue:priority-test:"+UUID.randomUUID();
    static final String PATH="/api/v1/concerts/c/queue";
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("demo.queue.key-prefix",()->PREFIX);}
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired QueueStore store;
    @Autowired StringRedisTemplate redis;
    @MockitoBean DemoAuthService auth;
    @MockitoBean ConcertService concerts;
    @MockitoSpyBean PolicyDecisionClient policies;
    static DemoUser user(long id){return new DemoUser(id,"u"+id,"demo","u"+id,null);}
    @BeforeEach void setup(){when(auth.require(any())).thenAnswer(i->user(Long.parseLong(((jakarta.servlet.http.HttpServletRequest)i.getArgument(0)).getHeader("Test-User"))));}
    @AfterEach void cleanup(){var keys=redis.keys(PREFIX+":*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);}
    Map<String,Object> data(MvcResult result)throws java.io.UnsupportedEncodingException{return json.convertValue(json.readTree(result.getResponse().getContentAsString()).get("data"),new tools.jackson.core.type.TypeReference<Map<String,Object>>() {});}
    Map<String,Object> join(long id,String token,int expected)throws Exception{
        var request=post(PATH).header("Test-User",id);
        if(token!=null)request.header("X-Captcha-Token",token);
        return data(mvc.perform(request).andExpect(status().is(expected)).andReturn());
    }
    void error(long id,String token,String code)throws Exception{
        var request=post(PATH).header("Test-User",id);
        if(token!=null)request.header("X-Captcha-Token",token);
        mvc.perform(request).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(code));
    }
    Map<String,Object> challenge(long id)throws Exception{
        return data(mvc.perform(post(PATH+"/captcha-challenges").header("Test-User",id)).andExpect(status().isOk()).andReturn());
    }
    String answer(Map<String,Object> challenge){
        String[] numbers=challenge.get("question").toString().split(" ");
        return Integer.toString(Integer.parseInt(numbers[0])+Integer.parseInt(numbers[2]));
    }
    ResultActions verify(long id,Map<String,Object> c,String answer)throws Exception{
        return mvc.perform(post(PATH+"/captcha-verifications").header("Test-User",id).contentType("application/json")
                .content(json.writeValueAsString(Map.of("challengeId",c.get("challengeId"),"answer",answer))));
    }
    String token(long id)throws Exception{var c=challenge(id);return data(verify(id,c,answer(c)).andExpect(status().isOk()).andReturn()).get("captchaToken").toString();}
    void leave(long id)throws Exception{mvc.perform(delete(PATH+"/me").header("Test-User",id)).andExpect(status().isOk());}
    void assertNoRegistration(long id){
        assertEquals("NOT_JOINED",store.execute("c",id,"status").snapshot().status());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(store.base("c")+"sequence")));
        assertEquals(0,redis.opsForZSet().zCard(store.base("c")+"admitted"));
    }
    @Test void allowNeedsNoBodyAndUnknownUserDefaultsToAllow()throws Exception{
        assertEquals("ADMITTED",join(1,null,201).get("status"));
        assertEquals("WAITING",join(9,null,201).get("status"));
    }
    @Test void captchaAndRestrictCannotAllocateSequenceOrAdmissionBeforeVerification()throws Exception{
        for(long id:List.of(2L,3L)){
            error(id,null,"CAPTCHA_REQUIRED");assertNoRegistration(id);
            mvc.perform(post(PATH).header("Test-User",id).contentType("application/json").content("{\"captchaVerified\":true,\"action\":\"ALLOW\"}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CAPTCHA_REQUIRED"));
            assertNoRegistration(id);
        }
        error(4,null,"QUEUE_RESTRICTED");assertNoRegistration(4);
        assertEquals("ADMITTED",join(2,token(2),201).get("status"));
    }
    @Test void delayCanEnterImmediatelyWithoutAMinimumWaitingTime()throws Exception{
        assertEquals("ADMITTED",join(3,token(3),201).get("status"));
    }
    @Test void normalPriorityAndFifoWithinEachGroup()throws Exception{
        join(1,null,201);
        join(3,token(3),201);join(5,token(5),201);
        join(2,token(2),201);join(6,token(6),201);
        assertEquals(1L,store.execute("c",2,"status").snapshot().position());
        assertEquals(2L,store.execute("c",6,"status").snapshot().position());
        assertEquals(3L,store.execute("c",3,"status").snapshot().position());
        for(long next:List.of(2L,6L,3L,5L)){
            leave(next==2?1:next==6?2:next==3?6:3);
            assertEquals("ADMITTED",store.execute("c",next,"status").snapshot().status());
        }
    }
    @Test void activeRegistrationsIgnoreLaterPolicyChangesAndDoNotRenewExpiry()throws Exception{
        join(1,null,201);var waiting=join(3,token(3),201);
        doReturn(Action.RESTRICT).when(policies).decide(user(3));
        assertEquals(waiting,join(3,null,200));
        assertFalse(store.join("c",3,"RESTRICT",null).created());
        assertEquals("WAITING",store.join("c",3,"REQUIRE_CAPTCHA",null).snapshot().status());
        doThrow(new IllegalStateException("policy unavailable")).when(policies).decide(user(1));
        assertEquals("ADMITTED",join(1,null,200).get("status"));
        leave(3);error(3,null,"QUEUE_RESTRICTED");
        mvc.perform(post(PATH).header("Test-User",8)).andExpect(status().isCreated());
        doThrow(new IllegalStateException("policy unavailable")).when(policies).decide(user(7));
        mvc.perform(post(PATH).header("Test-User",7)).andExpect(status().isServiceUnavailable());
        assertEquals("NOT_JOINED",store.execute("c",7,"status").snapshot().status());
    }
    @Test void expiredAdmissionRequiresFreshPolicyAndCaptcha()throws Exception{
        String grant=token(3);join(3,grant,201);
        redis.opsForZSet().add(store.base("c")+"admitted","3",1);
        assertEquals("EXPIRED",store.execute("c",3,"status").snapshot().status());
        doReturn(Action.RESTRICT).when(policies).decide(user(3));
        error(3,null,"QUEUE_RESTRICTED");
        doReturn(Action.DELAY).when(policies).decide(user(3));
        error(3,grant,"CAPTCHA_INVALID");
        assertEquals("ADMITTED",join(3,token(3),201).get("status"));
    }
    @Test void wrongAnswersHaveBoundedAttemptsAndChallengeAndGrantAreBoundToUserAndConcert()throws Exception{
        var c=challenge(2);
        verify(6,c,answer(c)).andExpect(status().isForbidden());
        for(int i=0;i<5;i++)verify(2,c,"wrong").andExpect(status().isForbidden());
        verify(2,c,answer(c)).andExpect(status().isForbidden());
        String grant=token(2);
        error(6,grant,"CAPTCHA_INVALID");
        mvc.perform(post("/api/v1/concerts/other/queue").header("Test-User",2).header("X-Captcha-Token",grant))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CAPTCHA_INVALID"));
        join(2,grant,201);leave(2);error(2,grant,"CAPTCHA_INVALID");
    }
    @Test void expiredChallengeAndGrantAreRejectedAndUnrelatedRequestsDoNotExtendGrantTtl()throws Exception{
        var c=challenge(2);
        redis.opsForHash().put(store.base("c")+"captcha-challenge:"+c.get("challengeId"),"expiry","1");
        verify(2,c,answer(c)).andExpect(status().isForbidden());
        String grant=token(2);String key=store.base("c")+"captcha-grant:"+com.bts.demoticketing.captcha.CaptchaService.digest(grant);
        redis.expire(key,java.time.Duration.ofSeconds(3));
        store.execute("c",1,"status");
        assertTrue(redis.getExpire(key,TimeUnit.MILLISECONDS)<=3000);
        redis.opsForHash().put(key,"expiry","1");
        error(2,grant,"CAPTCHA_INVALID");assertNoRegistration(2);
    }
    @Test void concurrentChallengeVerificationIssuesOnlyOneGrant()throws Exception{
        var c=challenge(2);var pool=Executors.newFixedThreadPool(8);
        try{
            List<Callable<Integer>> work=new ArrayList<>();
            for(int i=0;i<16;i++)work.add(()->verify(2,c,answer(c)).andReturn().getResponse().getStatus());
            int success=0;for(var r:pool.invokeAll(work)){int status=r.get();assertTrue(status==200||status==403);if(status==200)success++;}
            assertEquals(1,success);
            assertNoRegistration(2);
        }finally{pool.shutdownNow();}
    }
    @Test void concurrentJoinConsumesGrantOnceAndCreatesOnlyOneMembership()throws Exception{
        join(1,null,201);String grant=token(3);var pool=Executors.newFixedThreadPool(8);
        try{
            List<Callable<Integer>> work=new ArrayList<>();
            for(int i=0;i<24;i++)work.add(()->mvc.perform(post(PATH).header("Test-User",3).header("X-Captcha-Token",grant)).andReturn().getResponse().getStatus());
            int created=0;for(var r:pool.invokeAll(work)){int status=r.get();assertTrue(status==201||status==200);if(status==201)created++;}
            assertEquals(1,created);
            assertEquals("2",redis.opsForValue().get(store.base("c")+"sequence"));
            assertEquals(1,redis.opsForZSet().zCard(store.base("c")+"waiting-delayed"));
            assertFalse(Boolean.TRUE.equals(redis.hasKey(store.base("c")+"captcha-grant:"+com.bts.demoticketing.captcha.CaptchaService.digest(grant))));
            leave(3);error(3,grant,"CAPTCHA_INVALID");
        }finally{pool.shutdownNow();}
    }
    @Test void strictPriorityCanStarveDelayedUsersUntilTheirWaitingTtlExpires()throws Exception{
        join(1,null,201);join(3,token(3),201);
        long previous=1;
        for(long normal=10;normal<15;normal++){
            join(normal,null,201);leave(previous);
            assertEquals("ADMITTED",store.execute("c",normal,"status").snapshot().status());
            assertEquals("WAITING",store.execute("c",3,"status").snapshot().status());
            previous=normal;
        }
        redis.opsForZSet().add(store.base("c")+"deadlines","3",1);
        assertEquals("EXPIRED",store.execute("c",3,"status").snapshot().status());
    }
    @Test void concurrentMixedGroupsHaveUniqueContiguousRanksAndRespectCapacity()throws Exception{
        join(1,null,201);
        Map<Long,String> grants=new HashMap<>();
        for(long id:List.of(2L,3L,5L,6L))grants.put(id,token(id));
        var pool=Executors.newFixedThreadPool(8);
        try{
            List<Callable<Integer>> work=new ArrayList<>();
            for(int i=0;i<8;i++)for(long id:grants.keySet())work.add(()->mvc.perform(post(PATH).header("Test-User",id)
                    .header("X-Captcha-Token",grants.get(id))).andReturn().getResponse().getStatus());
            int created=0;for(var result:pool.invokeAll(work)){int status=result.get();assertTrue(status==200||status==201);if(status==201)created++;}
            assertEquals(4,created);
            Set<Long> ranks=new HashSet<>();
            for(long id:grants.keySet()){
                var s=store.execute("c",id,"status").snapshot();assertEquals("WAITING",s.status());ranks.add(s.position());
                if(id==2||id==6)assertTrue(s.position()<=2);else assertTrue(s.position()>2);
            }
            assertEquals(Set.of(1L,2L,3L,4L),ranks);
            assertEquals("5",redis.opsForValue().get(store.base("c")+"sequence"));
            assertEquals(1,redis.opsForZSet().zCard(store.base("c")+"admitted"));
        }finally{pool.shutdownNow();}
    }
}
