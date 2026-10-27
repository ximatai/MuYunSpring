package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.ConditionalBatchUpdate;
import net.ximatai.muyun.spring.ability.VersionedRecordMutation;
import net.ximatai.muyun.spring.common.schema.PlatformEntityManagers;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Internal credential storage. No generic HTTP CRUD projection is exposed. */
@Service
public class PersonalAccessTokenStore extends AbstractAbilityService<PersonalAccessToken> {
    private final IDatabaseOperations<?> database;
    public PersonalAccessTokenStore(PersonalAccessTokenDao dao, IDatabaseOperations<?> database) {
        super(PersonalAccessTokenService.MODULE_ALIAS, PersonalAccessToken.class, dao);
        this.database = Objects.requireNonNull(database, "database");
    }
    public PersonalAccessToken findByHash(String hash) {
        try (var ignored = TenantContext.system("resolve personal access token")) {
            return getDao().query(Criteria.of().eq("tokenHash", hash).isNull("tenantId")
                    .eq("deleted", false), new PageRequest(0, 1)).stream().findFirst().orElse(null);
        }
    }
    public List<PersonalAccessToken> ownedBy(String userId) {
        try (var ignored = TenantContext.system("list own personal access tokens")) {
            return getDao().query(Criteria.of().eq("userId", userId).isNull("tenantId")
                    .eq("deleted", false), new PageRequest(0, Integer.MAX_VALUE));
        }
    }
    public void issue(PersonalAccessToken token) {
        try (var ignored = TenantContext.system("issue own personal access token")) {
            token.setTenantId(null);
            insert(token);
        }
    }
    public boolean revoke(String userId, String id, Instant now) {
        try (var ignored = TenantContext.system("revoke own personal access token")) {
            return VersionedRecordMutation.update(getDao(), () -> {
                var token = getDao().findById(id);
                return token != null && token.getTenantId() == null && !Boolean.TRUE.equals(token.getDeleted())
                        && userId.equals(token.getUserId()) && token.getRevokedAt() == null ? token : null;
            }, token -> { token.setRevokedAt(now); token.setRevokedReason("owner revoked"); }) != null;
        }
    }
    public boolean deleteRevoked(String userId, String id, Instant now) {
        try (var ignored = TenantContext.system("delete revoked personal access token")) {
            // Credential retention is internal state; reuse standard soft-delete fields and CAS lifecycle.
            return VersionedRecordMutation.update(getDao(), () -> {
                var token = getDao().findById(id);
                if (token == null || token.getTenantId() != null || Boolean.TRUE.equals(token.getDeleted())
                        || !userId.equals(token.getUserId())) return null;
                if (token.getRevokedAt() == null) throw net.ximatai.muyun.spring.common.exception.PlatformErrors.badRequestText(
                        net.ximatai.muyun.spring.common.i18n.TextReference.code("iam.access-token.revoke_before_delete", "请先撤销访问密钥，再删除"));
                return token;
            }, token -> net.ximatai.muyun.spring.common.model.EntityLifecycle.prepareDelete(token, now)) != null;
        }
    }
    public void revokeForUser(String userId, String reason) {
        try (var ignored = TenantContext.system("revoke account personal access tokens")) {
            var conditions = new LinkedHashMap<String, Object>();
            conditions.put("userId", userId);
            conditions.put("tenantId", null);
            conditions.put("revokedAt", null);
            var patch = new LinkedHashMap<String, Object>();
            Instant now = Instant.now();
            patch.put("revokedAt", now);
            patch.put("revokedReason", reason);
            ConditionalBatchUpdate.updateReturningIds(database,
                    PlatformEntityManagers.entityMetaResolver().resolve(PersonalAccessToken.class).asTableMeta(),
                    patch, conditions, now);
        }
    }
    public void touch(PersonalAccessToken token, Instant now) {
        if (token.getLastUsedAt() != null && token.getLastUsedAt().plusSeconds(60).isAfter(now)) return;
        try (var ignored = TenantContext.system("track personal access token usage")) {
            // CAS never overwrites a concurrent revocation; a lost usage timestamp is harmless.
            Integer version = token.getVersion();
            token.setLastUsedAt(now);
            net.ximatai.muyun.spring.common.model.EntityLifecycle.prepareUpdate(token, now);
            getDao().updateByIdAndVersion(token, version);
        }
    }
}
