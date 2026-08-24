package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.CriteriaClause;
import net.ximatai.muyun.database.core.orm.SqlSubQuery;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserSessionPresenceServiceTest {
    @Test
    void shouldConstrainAccountsWithDatabaseLevelEffectiveSessionSubQuery() {
        UserSessionPresenceService service = new UserSessionPresenceService();

        Criteria criteria = service.activeAccountCriteria();

        CriteriaClause clause = (CriteriaClause) criteria.getRoot().getEntries().getFirst().getNode();
        SqlSubQuery subQuery = (SqlSubQuery) clause.getValues().getFirst();
        assertThat(clause.getField()).isEqualTo("id");
        assertThat(subQuery.getSql())
                .contains("SELECT DISTINCT user_id", "revoked_at IS NULL", "expires_at > CURRENT_TIMESTAMP")
                .contains("COALESCE(max_expires_at, expires_at) > CURRENT_TIMESTAMP");
        assertThat(subQuery.getParams()).isEmpty();
    }
}
