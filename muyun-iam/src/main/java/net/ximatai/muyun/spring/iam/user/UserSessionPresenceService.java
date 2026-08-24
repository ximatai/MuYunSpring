package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.SqlSubQuery;
import org.springframework.stereotype.Service;

/** IAM read capability for constraining accounts to users with an effective login session. */
@Service
public class UserSessionPresenceService {
    private static final String ACTIVE_USER_IDS_SQL = """
            SELECT DISTINCT user_id
            FROM iam_user_session
            WHERE revoked_at IS NULL
              AND expires_at > CURRENT_TIMESTAMP
              AND COALESCE(max_expires_at, expires_at) > CURRENT_TIMESTAMP
            """;

    public Criteria activeAccountCriteria() {
        return Criteria.of().inSubQuery("id", SqlSubQuery.of(ACTIVE_USER_IDS_SQL, null));
    }
}
