package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.TableMeta;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.ConditionalBatchUpdate;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.schema.PlatformEntityManagers;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

@Service
public class UserSessionRecordService extends AbstractAbilityService<UserSession> {
    public static final String MODULE_ALIAS = "iam.user_session";
    private static final PageRequest FIRST = new PageRequest(0, 1);
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private static final TableMeta TABLE =
            PlatformEntityManagers.entityMetaResolver().resolve(UserSession.class).asTableMeta();
    private final IDatabaseOperations<?> database;

    public UserSessionRecordService(UserSessionDao userSessionDao, IDatabaseOperations<?> database) {
        super(MODULE_ALIAS, UserSession.class, userSessionDao);
        this.database = Objects.requireNonNull(database, "database");
    }

    /** One statement owns the transition and returns the exact sessions for lifecycle publication. */
    public List<String> revokeActiveByUserId(String userId, Instant now, String reason) {
        var conditions = new LinkedHashMap<String, Object>();
        conditions.put("userId", userId);
        conditions.put("revokedAt", null);
        var patch = new LinkedHashMap<String, Object>();
        patch.put("revokedAt", now);
        patch.put("revokedReason", reason);
        return ConditionalBatchUpdate.updateReturningIds(database, TABLE, patch, conditions, now);
    }

    public String issue(UserSession session) {
        return insert(session);
    }

    public UserSession findByTokenHash(String tokenHash) {
        return list(Criteria.of().eq("tokenHash", tokenHash), FIRST)
                .stream()
                .findFirst()
                .orElse(null);
    }

    public UserSession findById(String id) {
        return select(id);
    }

    public List<UserSession> listByUserId(String userId) {
        return list(Criteria.of().eq("userId", userId), ALL);
    }

    public int updateSession(UserSession session, Integer expectedVersion, Instant now) {
        EntityLifecycle.prepareUpdate(session, now);
        return getDao().updateByIdAndVersion(session, expectedVersion);
    }
}
