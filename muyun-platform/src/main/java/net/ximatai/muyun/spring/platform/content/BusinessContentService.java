package net.ximatai.muyun.spring.platform.content;

import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordDao;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.platform.*;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

/** Source history can only be read through the owning record's live authorization and data scope. */
@Service
public final class BusinessContentService {
    private final BusinessContentStore store;
    private final ActionExecutionPolicyService policies;
    private final DataScopeCriteriaService scopes;
    public BusinessContentService(BusinessContentStore store, ActionExecutionPolicyService policies, DataScopeCriteriaService scopes) {
        this.store = Objects.requireNonNull(store); this.policies = Objects.requireNonNull(policies);
        this.scopes = Objects.requireNonNull(scopes);
    }
    public record SourceRevision(String sourceKey, int revision, String text, String sourceLanguage,
                                 String authorId, Instant createdAt) { }

    public <T extends EntityContract> List<SourceRevision> history(CrudAbility<T> ability, String recordId,
                                                                 String fieldName, PageRequest page) {
        Objects.requireNonNull(page, "page");
        if (page.getLimit() > 200) throw new IllegalArgumentException("Content history page size must not exceed 200");
        if (!TenantContext.hasContext() || CurrentUserContext.currentUser().isEmpty()) throw denied();
        policies.requireRecordAction(ActionExecutionContext.ofPlatformAction(ability.getModuleAlias(), PlatformAction.VIEW,
                Set.of(recordId), CurrentUserContext.currentUser()));
        Criteria id = Criteria.of().eq("id", recordId);
        var scope = ability instanceof DataScopeAbility<?> scoped ? scoped.readScope(PlatformAction.VIEW, id)
                : ability.getDao() instanceof DynamicRecordDao
                    ? scopes.resolveReadScope(ability.getModuleAlias(), PlatformAction.VIEW.executionPolicy(), id,
                        CurrentUserContext.currentUser())
                    : DataScopeCriteriaResult.unrestricted(id);
        T record;
        if (scope.crossTenant()) {
            try (var ignored = TenantContext.bypassTenantFilter("authorized business content source read")) {
                record = ability.list(scope.criteria(), PageRequest.of(1, 1)).stream().findFirst().orElse(null);
            }
        } else record = ability.list(scope.criteria(), PageRequest.of(1, 1)).stream().findFirst().orElse(null);
        if (record == null) throw denied();
        var field = BusinessContentFields.definitions(ability, record).get(fieldName);
        if (field == null) throw denied();
        var source = BusinessContentSaveLifecycleListener.source(ability, record, field.key());
        return store.history(source, page).stream().map(row -> new SourceRevision(row.getSourceKey(), row.getRevision(),
                row.getOriginalText(), row.getSourceLanguage(), row.getCreatedBy(), row.getCreatedAt())).toList();
    }
    private static PlatformAccessDeniedException denied() {
        return new PlatformAccessDeniedException("Business content source is unavailable");
    }
}
