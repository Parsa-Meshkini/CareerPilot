package com.careerpilot.common;

import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;

/**
 * The agent runs inside the transaction of the import that triggered it, and its tools call transactional services whose
 * failures (for example an LLM token budget being reached) the agent deliberately handles. With Spring's default, such an
 * exception marks the shared transaction rollback-only even though it was caught, and the import's commit then fails.
 * With this setting only the outermost caller decides: if it handles the failure and finishes normally, the commit succeeds.
 */
@Configuration
public class TransactionConfig {
    @Bean
    public TransactionManagerCustomizer<JpaTransactionManager> participationFailureCustomizer() {
        return tm -> tm.setGlobalRollbackOnParticipationFailure(false);
    }
}
