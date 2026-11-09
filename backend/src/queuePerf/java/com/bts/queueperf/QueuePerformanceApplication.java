package com.bts.queueperf;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.boot.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;
import com.bts.demoticketing.DemoTicketingApplication;
import com.bts.demoticketing.queue.*;
import com.bts.demoticketing.concert.service.ConcertService;
import com.bts.demoticketing.integration.bts.PolicyDecisionClient;
import com.bts.demoticketing.user.repository.DemoUserRepository;

/** 운영 JAR 밖의 별도 source set. 입장 검사만 A/B로 분리한다. */
public class QueuePerformanceApplication {
    static String required(String key){return Objects.requireNonNull(System.getenv(key),key+" 필요");}
    public static void main(String[] args){
        String run=required("PERF_RUN"),mode=required("PERF_MODE");
        if(!run.matches("[a-f0-9]{12}")||!Set.of("no-queue","redis-queue").contains(mode)
                ||!("perf_"+run).equals(required("DB_USERNAME")))throw new IllegalStateException("격리 성능 실행기만 사용하세요.");
        new SpringApplicationBuilder(DemoTicketingApplication.class,PerfConfiguration.class).run(args);
    }
    @Configuration(proxyBeanMethods=false)
    static class PerfConfiguration {
        @Bean @Primary QueueService performanceQueueService(ConcertService concerts,QueueStore store,PolicyDecisionClient policy,DemoUserRepository users){
            boolean enabled=required("PERF_MODE").equals("redis-queue");
            return new QueueService(concerts,store,policy,users){
                @Override public void requireAdmitted(String concert,long user){if(enabled)super.requireAdmitted(concert,user);}
            };
        }
        @Bean ApplicationRunner prepare(JdbcTemplate jdbc,DataSource source,StringRedisTemplate redis,JsonMapper json,Environment env){
            return arguments->{
                String run=required("PERF_RUN");
                if(!"127.0.0.1".equals(env.getProperty("server.address")))throw new IllegalStateException("loopback 전용 서버가 아닙니다.");
                try(var connection=source.getConnection()){
                    String url=connection.getMetaData().getURL();
                    if(!url.startsWith("jdbc:mysql://127.0.0.1:"+required("DB_PORT")+"/")||"3308".equals(required("DB_PORT"))
                            ||!jdbc.queryForObject("SELECT CURRENT_USER()",String.class).startsWith("perf_"+run+"@"))
                        throw new IllegalStateException("전용 DB가 아닙니다.");
                }
                if(!required("PERF_REDIS_RUN_ID").equals(redis.execute((org.springframework.data.redis.core.RedisCallback<Properties>) c->c.serverCommands().info("server")).getProperty("run_id")))
                    throw new IllegalStateException("전용 Redis가 아닙니다.");
                int vus=Integer.parseInt(required("PERF_VUS"));
                int seconds=Integer.parseInt(required("PERF_SECONDS"));
                if(!Set.of(1,10,50,100).contains(vus)||seconds<8||seconds>120)throw new IllegalArgumentException("허용 범위는 1/10/50/100 VU, 8~120초입니다.");
                Map<String,String> concerts=new LinkedHashMap<>();
                for(String phase:List.of("verify","warmup","measure")){
                    String concert="p18-"+run+"-"+phase;concerts.put(phase,concert);
                    jdbc.update("INSERT INTO concerts(public_id,title,artist,venue,start_at,end_at,price_from,tagline,hue) VALUES(?,'대기열 비교','test','test',UTC_TIMESTAMP()+INTERVAL 1 DAY,UTC_TIMESTAMP()+INTERVAL 2 DAY,100,'test',0)",concert);
                    long id=jdbc.queryForObject("SELECT id FROM concerts WHERE public_id=?",Long.class,concert);
                    int count=phase.equals("verify")?4:phase.equals("warmup")?vus*5:vus*(seconds+20);
                    for(int start=1;start<=count;start+=500){
                        List<Object[]> batch=new ArrayList<>();
                        for(int index=start;index<=Math.min(count,start+499);index++)batch.add(new Object[]{id,String.format("S%06d",index),index});
                        jdbc.batchUpdate("INSERT INTO concert_seats(concert_id,seat_code,row_label,seat_number,grade,price,state) VALUES(?,?,'A',?,'VIP',100,'AVAILABLE')",batch);
                    }
                }
                Path output=Path.of(required("PERF_OUTPUT"));Files.createDirectories(output);
                var metrics=new PerformanceMetrics(source,redis,json,output,concerts.get("measure"),env.getProperty("demo.queue.key-prefix"));
                metrics.start();
                Runtime.getRuntime().addShutdownHook(new Thread(metrics::close));
                Files.writeString(output.resolve("server-ready.json"),json.writeValueAsString(Map.of("at",Instant.now().toString(),"mode",required("PERF_MODE"),"concerts",concerts)),StandardCharsets.UTF_8);
            };
        }
    }
}
