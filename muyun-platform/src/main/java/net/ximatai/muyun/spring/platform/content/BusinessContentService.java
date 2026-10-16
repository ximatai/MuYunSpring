package net.ximatai.muyun.spring.platform.content;

import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.content.ContentFieldDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordDao;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicEntityOperations;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.ability.child.ChildrenAbility;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ObjectProvider<DynamicRecordService> dynamicRecords;
    public BusinessContentService(BusinessContentStore store, ActionExecutionPolicyService policies, DataScopeCriteriaService scopes) {
        this(store, policies, scopes, null);
    }
    @Autowired
    public BusinessContentService(BusinessContentStore store, ActionExecutionPolicyService policies, DataScopeCriteriaService scopes,
                                  ObjectProvider<DynamicRecordService> dynamicRecords) {
        this.dynamicRecords = dynamicRecords;
        this.store = Objects.requireNonNull(store); this.policies = Objects.requireNonNull(policies);
        this.scopes = Objects.requireNonNull(scopes);
    }
    public record SourceRevision(String sourceKey, int revision, String text, String sourceLanguage,
                                 String authorId, Instant createdAt, Instant authoredAt,
                                 String revisionAuthorId, String revisionKind, String authorName, String revisionAuthorName, String revisionId) { }
    public record CurrentSource(ContentFieldDefinition content, String text, Integer expectedRevision,
                                SourceRevision source) { }
    public record Sources(Integer recordVersion, Map<String, CurrentSource> fields) {
        public Sources { fields = Map.copyOf(fields); }
    }

    /** Server-side owning row for entry adapters that must recheck menu and page-workspace constraints. */
    public EntityContract requireOwner(CrudAbility<?> parent, Location location) {
        return authorizedRecord(parent, location.recordId());
    }

    /** The text is always the authorized raw business value. A missing matching source is explicitly untracked. */
    public <T extends EntityContract> Sources sources(CrudAbility<T> ability, String recordId) {
        return sources(ability, authorizedRecord(ability, recordId));
    }

    private Sources sources(CrudAbility<?> ability, EntityContract record) {
        Map<String, CurrentSource> fields = new LinkedHashMap<>();
        BusinessContentFields.definitions(ability, record).forEach((field, content) -> {
            String text = BusinessContentFields.text(record, field);
            var latest = store.latest(BusinessContentSaveLifecycleListener.source(ability, record, content.key()));
            fields.put(field, new CurrentSource(content, text, latest == null ? null : latest.getRevision(),
                    latest != null && Objects.equals(latest.getOriginalText(), text) ? view(latest) : null));
        });
        return new Sources(record.getVersion(), fields);
    }

    public <T extends EntityContract> List<SourceRevision> history(CrudAbility<T> ability, String recordId,
                                                                 String fieldName, PageRequest page) {
        Objects.requireNonNull(page, "page");
        if (page.getLimit() > 200) throw new IllegalArgumentException("Content history page size must not exceed 200");
        return history(ability, authorizedRecord(ability, recordId), fieldName, page);
    }

    private List<SourceRevision> history(CrudAbility<?> ability, EntityContract record, String fieldName, PageRequest page) {
        if (page.getLimit() > 200) throw new IllegalArgumentException("Content history page size must not exceed 200");
        var field = BusinessContentFields.definitions(ability, record).get(fieldName);
        if (field == null) throw denied();
        var source = BusinessContentSaveLifecycleListener.source(ability, record, field.key());
        return store.history(source, page).stream().map(BusinessContentService::view).toList();
    }

    public Sources childSources(CrudAbility<?> parent, String parentId, String relationCode, String childId) {
        var child = child(parent, parentId, relationCode, childId);
        return sources(child.ability(), child.record());
    }

    public List<SourceRevision> childHistory(CrudAbility<?> parent, String parentId, String relationCode,
                                            String childId, String field, PageRequest page) {
        var child = child(parent, parentId, relationCode, childId);
        return history(child.ability(), child.record(), field, page);
    }

    private record OwnedChild(CrudAbility<?> ability, EntityContract record) { }

    /** Child sources inherit the already established parent-owned aggregate read contract. */
    private OwnedChild child(CrudAbility<?> ability, String parentId, String relationCode, String childId) {
        var parent = authorizedRecord(ability, parentId);
        var records = dynamicRecords == null ? null : dynamicRecords.getIfAvailable();
        if (!(ability instanceof ChildrenAbility<?>) && ability.getDao() instanceof DynamicRecordDao && records != null) {
            var child = records.aggregateChildrenForView(ability.getModuleAlias(), parentId, relationCode).stream()
                    .filter(row -> childId.equals(row.getId()) && Objects.equals(parent.getTenantId(), row.getTenantId()))
                    .findFirst().orElseThrow(BusinessContentService::denied);
            return new OwnedChild(records.entity(ability.getModuleAlias(), child.getEntity().alias()), child);
        }
        if (!(ability instanceof ChildrenAbility<?> aggregate)) throw denied();
        var relation = aggregate.childRelations().stream().filter(candidate -> relationCode.equals(candidate.relationCode()))
                .findFirst().orElseThrow(BusinessContentService::denied);
        List<? extends EntityContract> children;
        // Only tenant filtering changes after the exact parent is authorized. The actor stays intact.
        try (var ignored = parent.getTenantId() == null
                ? TenantContext.bypassTenantFilter("authorized platform aggregate content read")
                : TenantContext.use(parent.getTenantId())) {
            children = relation.selectChildren(parentId);
        }
        var child = children.stream().filter(row -> childId.equals(row.getId())
                        && Objects.equals(parent.getTenantId(), row.getTenantId()))
                .findFirst().orElseThrow(BusinessContentService::denied);
        return new OwnedChild(relation.childAbility(), child);
    }

    static SourceRevision view(BusinessContentRevision row) {
        return new SourceRevision(row.getSourceKey(), row.getRevision(), row.getOriginalText(), row.getSourceLanguage(),
                BusinessContentStore.originalAuthor(row), row.getCreatedAt(), BusinessContentStore.originalAuthoredAt(row),
                row.getCreatedBy(), row.getRevisionKind() == null ? "ORIGINAL_WRITE" : row.getRevisionKind(),
                row.getOriginalAuthorName(), row.getRevisionAuthorName(), row.getId());
    }

    /** A location is always resolved through its owning aggregate, never an independently supplied source hash. */
    public record Location(String recordId, String relationCode, String childId) {
        public Location {
            if (recordId == null || recordId.isBlank() || recordId.length() > 64)
                throw new IllegalArgumentException("A valid owning record is required");
            if ((relationCode == null) != (childId == null)
                    || relationCode != null && (relationCode.isBlank() || relationCode.length() > 128 || childId.isBlank() || childId.length() > 64))
                throw new IllegalArgumentException("An aggregate child requires its relation and identity");
        }
        public static Location record(String id) { return new Location(id, null, null); }
    }
    record OwnedRecord(CrudAbility<?> ability, EntityContract record) { }
    record FieldTarget(CrudAbility<?> ability, EntityContract record, String fieldName,
                       ContentFieldDefinition content, BusinessContentSource source) {
        String text() { return BusinessContentFields.text(record, fieldName); }
    }
    OwnedRecord owned(CrudAbility<?> parent, Location location, PlatformAction action) {
        var root = authorizedRecord(parent, location.recordId());
        if (action != PlatformAction.VIEW) authorizedRecord(parent, location.recordId(), action);
        if (location.childId() == null) return new OwnedRecord(parent, root);
        var child = child(parent, location.recordId(), location.relationCode(), location.childId());
        return new OwnedRecord(child.ability(), child.record());
    }
    FieldTarget target(CrudAbility<?> parent, Location location, String field, PlatformAction action) {
        var owned = owned(parent, location, action);
        var definition = BusinessContentFields.definitions(owned.ability(), owned.record()).get(field);
        if (definition == null) throw denied();
        return new FieldTarget(owned.ability(), owned.record(), field, definition,
                BusinessContentSaveLifecycleListener.source(owned.ability(), owned.record(), definition.key()));
    }
    private <T extends EntityContract> T authorizedRecord(CrudAbility<T> ability, String recordId) {
        return authorizedRecord(ability, recordId, PlatformAction.VIEW);
    }
    private <T extends EntityContract> T authorizedRecord(CrudAbility<T> ability, String recordId, PlatformAction action) {
        if (!TenantContext.hasContext() || CurrentUserContext.currentUser().isEmpty()) throw denied();
        var policy = ActionExecutionContextHolder.current()
                .filter(context -> ability.getModuleAlias().equals(context.moduleAlias()) && action.matches(context.actionCode()))
                .map(ActionExecutionContext::actionPolicy).orElseGet(action::executionPolicy);
        policies.requireRecordAction(ActionExecutionContext.ofPolicy(ability.getModuleAlias(), policy,
                Set.of(recordId), CurrentUserContext.currentUser()));
        // An embedded dynamic facade can own a stricter VIEW scope than the host policy service.
        // Preserve that normal read boundary without using its QUERY-scoped list operation.
        if (ability instanceof DynamicEntityOperations dynamic && dynamic.select(recordId) == null) throw denied();
        Criteria id = Criteria.of().eq("id", recordId);
        var scope = ability instanceof DataScopeAbility<?> scoped
                ? policy.equals(action.executionPolicy()) ? scoped.readScope(action, id) : scoped.readScopeByPolicy(policy, id)
                : ability.getDao() instanceof DynamicRecordDao
                    ? scopes.resolveReadScope(ability.getModuleAlias(), policy, id,
                        CurrentUserContext.currentUser())
                    : DataScopeCriteriaResult.unrestricted(id);
        // The requested action scope above is authoritative. Facade list() would add QUERY scope and
        // presentation hooks; original provenance must read only the scoped storage facts.
        T record;
        if (scope.crossTenant()) {
            try (var ignored = TenantContext.bypassTenantFilter("authorized business content source read")) {
                record = ability.listRaw(scope.criteria(), PageRequest.of(1, 1)).stream().findFirst().orElse(null);
            }
        } else record = ability.listRaw(scope.criteria(), PageRequest.of(1, 1)).stream().findFirst().orElse(null);
        if (record == null) throw denied();
        return record;
    }
    private static PlatformAccessDeniedException denied() {
        return new PlatformAccessDeniedException("Business content source is unavailable");
    }
}
