package com.bts.demoticketing.queue;

import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.bts.demoticketing.concert.service.ConcertService;
import com.bts.demoticketing.user.service.DemoAuthService;
import com.bts.demoticketing.user.entity.DemoUser;
import com.bts.demoticketing.common.exception.ApiException;
import com.bts.demoticketing.common.response.ResponseCode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("redis")
@SpringBootTest(properties={"demo.jobs.enabled=false","bts.enabled=false","demo.queue.capacity=1","demo.policy.mode=disabled","demo.captcha.mode=disabled"})
@AutoConfigureMockMvc
class QueueApiRedisTests {
    static final String PREFIX="demo-ticketing:queue:api-test:"+UUID.randomUUID();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){
        registry.add("demo.queue.key-prefix",()->PREFIX);
    }
    @Autowired MockMvc mvc;
    @Autowired StringRedisTemplate redis;
    @MockitoBean DemoAuthService auth;
    @MockitoBean ConcertService concerts;
    @BeforeEach void user(){when(auth.require(any())).thenReturn(new DemoUser(1,"queue-test","demo","queue-test",null));}
    @AfterEach void cleanup(){var keys=redis.keys(PREFIX+":*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);}

    @Test void springBootUsesDemoRedisAndApiRoundTrip() throws Exception {
        var factory=(LettuceConnectionFactory)redis.getConnectionFactory();
        assertEquals(Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT","6380")),factory.getPort());
        try(var connection=factory.getConnection()){assertEquals("PONG",connection.ping());}
        mvc.perform(post("/api/v1/concerts/c1/queue"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.code").value("COMMON_201"))
                .andExpect(jsonPath("$.data.status").value("ADMITTED"));
        mvc.perform(post("/api/v1/concerts/c1/queue"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("COMMON_200"));
        mvc.perform(get("/api/v1/concerts/c1/queue/me"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.position").value(0));
        mvc.perform(delete("/api/v1/concerts/c1/queue/me")).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/concerts/c1/queue/me")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/concerts/c1/queue/me"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("NOT_JOINED"));
    }
    @Test void unauthenticatedAndMissingConcertUseExistingErrors() throws Exception {
        when(auth.require(any())).thenThrow(new ApiException(ResponseCode.UNAUTHORIZED));
        mvc.perform(post("/api/v1/concerts/c1/queue")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(concerts);
        doReturn(new DemoUser(1,"queue-test","demo","queue-test",null)).when(auth).require(any());
        when(concerts.get("missing")).thenThrow(new ApiException(ResponseCode.CONCERT_NOT_FOUND));
        mvc.perform(get("/api/v1/concerts/missing/queue/me")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONCERT_NOT_FOUND"));
    }
}
