package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.ability.*;
import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real transaction locks for code contract tests, matching the starter's mutation host. */
final class CodeTestMutationHost {
    static void install(PlatformTransactionManager manager, DataSource source) {
        var tx = new TransactionTemplate(manager);
        PlatformAbilityRuntime.configureMutationTransactionOperator(new MutationTransactionOperator() {
            public <T> T execute(java.util.function.Supplier<T> work) { return tx.execute(status -> work.get()); }
            public void lock(String scope, String key) {
                new org.springframework.jdbc.core.JdbcTemplate(source).queryForObject(
                        "select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, scope.length() + ":" + scope + ":" + key);
            }
        });
    }
}
