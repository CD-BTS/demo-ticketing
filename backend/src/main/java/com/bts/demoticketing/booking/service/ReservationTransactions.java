package com.bts.demoticketing.booking.service;

import java.util.function.Supplier;
import org.springframework.dao.TransientDataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.bts.demoticketing.common.exception.ApiException;
import com.bts.demoticketing.common.response.ResponseCode;

/** 각 재시도는 이전 시도의 잠금과 변경을 모두 롤백한 새 트랜잭션이다. */
@Component
public class ReservationTransactions {
    private static final Logger log=LoggerFactory.getLogger(ReservationTransactions.class);
    public static class Retry extends RuntimeException {}
    private final TransactionTemplate transaction;
    public ReservationTransactions(PlatformTransactionManager manager) {
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public <T> T run(Supplier<T> work) {
        for (int attempt = 0; attempt < 5; attempt++) {
            try { return transaction.execute(status -> work.get()); }
            catch (Retry | TransientDataAccessException failure) {
                if (attempt == 4) {
                    log.warn("좌석 트랜잭션 재시도 한도 초과: {}",failure.getClass().getSimpleName());
                    throw new ApiException(ResponseCode.SERVICE_UNAVAILABLE);
                }
            }
        }
        throw new IllegalStateException("Unreachable reservation retry");
    }
}
