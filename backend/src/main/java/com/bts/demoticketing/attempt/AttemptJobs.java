package com.bts.demoticketing.attempt;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="demo.jobs.enabled",havingValue="true",matchIfMissing=true)
public class AttemptJobs {
    private final AttemptService attempts;
    public AttemptJobs(AttemptService attempts){this.attempts=attempts;}
    @Scheduled(fixedDelayString="${bts.poll-ms:5000}")
    public void materialize(){
        try{attempts.materialize();}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(AttemptJobs.class).warn("Attempt outbox materialization will retry");}
    }
}
