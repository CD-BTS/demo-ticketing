package com.bts.queueperf;

import java.io.*;
import java.lang.management.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.data.redis.core.*;
import tools.jackson.databind.json.JsonMapper;

/** 1초 표본. 별도 모니터 연결 1개는 Hikari 풀 밖에서 유지한다. */
final class PerformanceMetrics {
    private final HikariDataSource pool;
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final Path output;
    private final String base;
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"perf-metrics");t.setDaemon(true);return t;});
    private Connection monitor;
    PerformanceMetrics(DataSource source,StringRedisTemplate redis,JsonMapper json,Path output,String concert,String prefix)throws Exception{
        pool=source.unwrap(HikariDataSource.class);this.redis=redis;this.json=json;this.output=output;
        base=prefix+":{"+Base64.getUrlEncoder().withoutPadding().encodeToString(concert.getBytes(StandardCharsets.UTF_8))+"}:";
        monitor=DriverManager.getConnection(pool.getJdbcUrl(),pool.getUsername(),pool.getPassword());
    }
    void start(){timer.scheduleWithFixedDelay(this::sample,0,1,TimeUnit.SECONDS);}
    private void sample(){
        Map<String,Object> row=new LinkedHashMap<>();row.put("time",Instant.now().toString());
        var p=pool.getHikariPoolMXBean();row.put("poolActive",p.getActiveConnections());row.put("poolTotal",p.getTotalConnections());row.put("poolWaiters",p.getThreadsAwaitingConnection());row.put("poolMax",pool.getMaximumPoolSize());
        row.put("heapBytes",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        try(var statement=monitor.createStatement();var result=statement.executeQuery("SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected','Threads_running','Max_used_connections','Innodb_row_lock_waits','Innodb_row_lock_time','Questions')")){
            while(result.next())row.put(result.getString(1),Long.parseLong(result.getString(2)));
        }catch(Exception failure){row.put("dbError",failure.getClass().getSimpleName());}
        try(var statement=monitor.createStatement();var result=statement.executeQuery("SELECT COUNT(*) FROM performance_schema.data_lock_waits")){
            result.next();row.put("dataLockWaits",result.getLong(1));
        }catch(Exception failure){row.put("lockMetricError",failure.getClass().getSimpleName());}
        try{
            row.put("waiting",redis.opsForZSet().zCard(base+"waiting")+redis.opsForZSet().zCard(base+"waiting-delayed"));
            row.put("admitted",redis.opsForZSet().zCard(base+"admitted"));
            for(String section:List.of("cpu","memory")){
                Properties info=redis.execute((RedisCallback<Properties>) c->c.serverCommands().info(section));
                for(String key:List.of("used_cpu_sys","used_cpu_user","used_memory","used_memory_rss"))if(info.getProperty(key)!=null)row.put("redis_"+key,Double.parseDouble(info.getProperty(key)));
            }
        }catch(Exception failure){row.put("redisError",failure.getClass().getSimpleName());}
        try{Files.writeString(output.resolve("server-metrics.jsonl"),json.writeValueAsString(row)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception ignored){System.err.println("성능 표본 저장 실패");}
    }
    void close(){timer.shutdownNow();try{monitor.close();}catch(Exception ignored){}}
}
