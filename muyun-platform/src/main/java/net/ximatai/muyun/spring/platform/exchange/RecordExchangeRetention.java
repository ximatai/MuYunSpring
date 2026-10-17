package net.ximatai.muyun.spring.platform.exchange;

import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/**
 * Bounded physical cleanup of expired file identities; it performs no business reads or model work.
 */
@Component
public class RecordExchangeRetention {
    private final RecordExchangeReceipts receipts;
    private final TransactionTemplate transactions;

    public RecordExchangeRetention(
            RecordExchangeReceipts receipts, PlatformTransactionManager transactions) {
        this.receipts = java.util.Objects.requireNonNull(receipts);
        this.transactions = new TransactionTemplate(java.util.Objects.requireNonNull(transactions));
    }

    @Scheduled(initialDelay = 60000, fixedDelay = 3600000)
    public void cleanup() {
        try {
            for (int batch = 0; batch < 10; batch++) {
                int removed = transactions.execute(status -> receipts.purgeExpired(Instant.now()));
                if (removed < 200) break;
            }
        } catch (RuntimeException unavailable) {
            LoggerFactory.getLogger(RecordExchangeRetention.class)
                    .warn(
                            "Expired exchange identities could not be removed ({})",
                            unavailable.getClass().getSimpleName());
        }
    }
}
