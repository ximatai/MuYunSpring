package net.ximatai.muyun.spring.platform.content;

import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.FieldReadAbility;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.content.ContentFieldDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordDao;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicEntityOperations;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.ability.child.ChildrenAbility;
import net.ximatai.muyun.spring.ability.child.ChildAbility;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import org.springframework.aop.support.AopUtils;
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
    private final ObjectProvider<PlatformModuleActionService> moduleActions;
    public BusinessContentService(BusinessContentStore store, ActionExecutionPolicyService policies, DataScopeCriteriaService scopes) {
        this(store, policies, scopes, null);
    }
    public BusinessContentService(BusinessContentStore store, ActionExecutionPolicyService policies, DataScopeCriteriaService scopes,
                                  ObjectProvider<DynamicRecordService> dynamicRecords) {
        this(store, policies, scopes, dynamicRecords, null);
    }
    @Autowired
    public BusinessContentService(BusinessContentStore store, ActionExecutionPolicyService policies, DataScopeCriteriaService scopes,
                                  ObjectProvider<DynamicRecordService> dynamicRecords,
                                  ObjectProvider<PlatformModuleActionService> moduleActions) {
        this.dynamicRecords = dynamicRecords;
        this.moduleActions = moduleActions;
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
        readableFields(ability, record).forEach((field, content) -> {
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
        var field = readableFields(ability, record).get(fieldName);
        if (field == null) throw denied();
        var source = BusinessContentSaveLifecycleListener.source(ability, record, field.key());
        return store.history(source, page).stream().map(BusinessContentService::view).toList();
    }

    public Sources childSources(CrudAbility<?> parent, String parentId, String relationCode, String childId) {
        var child = child(parent, parentId, relationCode, childId);
        return sources(child.ability(), child.record());
    }

    /** Controlled selections require a bounded reader; legacy custom maintenance stays available. */
    public Sources childSourcesForSelection(CrudAbility<?> parent, String parentId, String relationCode, String childId) {
        var child = child(parent, parentId, relationCode, childId, true);
        return sources(child.ability(), child.record());
    }

    public List<SourceRevision> childHistory(CrudAbility<?> parent, String parentId, String relationCode,
                                            String childId, String field, PageRequest page) {
        var child = child(parent, parentId, relationCode, childId);
        return history(child.ability(), child.record(), field, page);
    }

    private record OwnedChild(CrudAbility<?> ability, EntityContract record) { }

    public record ChildSelection(List<String> ids, boolean more) {
        public ChildSelection { ids = List.copyOf(ids); }
    }

    /** Bounded identities through the normal aggregate read; field qualification is still checked for each text. */
    public ChildSelection childSelection(CrudAbility<?> parent, String parentId, String relationCode, int limit) {
        if (limit < 0 || limit > 200) throw new IllegalArgumentException("Invalid aggregate content selection limit");
        var rows = childRows(parent, parentId, relationCode, null, PageRequest.of(1, limit + 1), true).records();
        return new ChildSelection(rows.stream().limit(limit).map(EntityContract::getId).toList(), rows.size() > limit);
    }

    /** Child sources inherit the already established parent-owned aggregate read contract. */
    private OwnedChild child(CrudAbility<?> ability, String parentId, String relationCode, String childId) {
        return child(ability, parentId, relationCode, childId, false);
    }
    private OwnedChild child(CrudAbility<?> ability, String parentId, String relationCode, String childId, boolean bounded) {
        var rows = childRows(ability, parentId, relationCode, childId, PageRequest.of(1, 1), bounded);
        var child = rows.records().stream().filter(row -> childId.equals(row.getId()))
                .findFirst().orElseThrow(BusinessContentService::denied);
        return new OwnedChild(rows.ability(), child);
    }
    private record ChildRows(CrudAbility<?> ability, List<? extends EntityContract> records) { }
    private ChildRows childRows(CrudAbility<?> ability, String parentId, String relationCode,
                               String childId, PageRequest page, boolean requireBounded) {
        var parent = authorizedRecord(ability, parentId);
        var records = dynamicRecords == null ? null : dynamicRecords.getIfAvailable();
        if (!(ability instanceof ChildrenAbility<?>) && ability.getDao() instanceof DynamicRecordDao && records != null) {
            List<DynamicRecord> selected;
            try { selected = records.aggregateChildrenForView(ability.getModuleAlias(), parentId, relationCode, childId, page); }
            catch (UnsupportedOperationException legacy) {
                if (requireBounded) throw denied();
                selected = records.aggregateChildrenForView(ability.getModuleAlias(), parentId, relationCode);
            }
            var children = selected.stream()
                    .filter(row -> Objects.equals(parent.getTenantId(), row.getTenantId())).toList();
            return new ChildRows(children.isEmpty() ? null
                    : records.entity(ability.getModuleAlias(), children.getFirst().getEntity().alias()), children);
        }
        if (!(ability instanceof ChildrenAbility<?> aggregate)) throw denied();
        var relation = aggregate.childRelations().stream().filter(candidate -> relationCode.equals(candidate.relationCode()))
                .findFirst().orElseThrow(BusinessContentService::denied);
        boolean bounded = ChildAbility.supportsBoundedReads(
                AopUtils.getTargetClass(relation.childAbility()));
        if (requireBounded && !bounded) throw denied();
        List<? extends EntityContract> children;
        // Only tenant filtering changes after the exact parent is authorized. The actor stays intact.
        try (var ignored = parent.getTenantId() == null
                ? TenantContext.bypassTenantFilter("authorized platform aggregate content read")
                : TenantContext.use(parent.getTenantId())) {
            Criteria criteria = Criteria.of();
            if (parent.getTenantId() == null) criteria.isNull("tenantId");
            else criteria.eq("tenantId", parent.getTenantId());
            if (childId != null) criteria.eq("id", childId);
            children = bounded ? relation.selectChildren(parentId, criteria, page) : relation.selectChildren(parentId);
        }
        return new ChildRows(relation.childAbility(), children.stream()
                .filter(row -> Objects.equals(parent.getTenantId(), row.getTenantId())).toList());
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
        var definition = readableFields(owned.ability(), owned.record()).get(field);
        if (definition == null) throw denied();
        return new FieldTarget(owned.ability(), owned.record(), field, definition,
                BusinessContentSaveLifecycleListener.source(owned.ability(), owned.record(), definition.key()));
    }

    /** Every source/translation path keeps the producer's current VIEW field policy, including aggregate children. */
    Map<String, ContentFieldDefinition> readableFields(CrudAbility<?> ability, EntityContract record) {
        var fields = BusinessContentFields.definitions(ability, record);
        if (!(ability instanceof FieldReadAbility readable)) return fields;
        var policyContext = ActionExecutionContextHolder.current()
                .filter(current -> ability.getModuleAlias().equals(current.moduleAlias()) && PlatformAction.VIEW.matches(current.actionCode()))
                .map(ActionExecutionContext::actionPolicy).orElseGet(() -> viewPolicy(ability.getModuleAlias()));
        // An aggregate grant establishes ownership, not the child's field identity. Never
        // pass parent/batch IDs or a parent's authorization result to the producer callback.
        var action = ActionExecutionContext.ofPolicy(ability.getModuleAlias(), policyContext,
                Set.of(record.getId()), CurrentUserContext.currentUser());
        var policy = readable.fieldReadPolicy(action);
        if (policy == null || !policy.restricted()) return fields;
        var allowed = new LinkedHashMap<String, ContentFieldDefinition>();
        fields.forEach((name, definition) -> { if (policy.allows(name)) allowed.put(name, definition); });
        return Map.copyOf(allowed);
    }
    private ActionExecutionPolicy viewPolicy(String moduleAlias) {
        var actions = moduleActions == null ? null : moduleActions.getIfAvailable();
        return actions == null ? PlatformAction.VIEW.executionPolicy()
                : actions.findDeclaredExecutionPolicy(moduleAlias, PlatformAction.VIEW.code()).orElseGet(PlatformAction.VIEW::executionPolicy);
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
        if (ability instanceof DynamicEntityOperations dynamic && dynamic.selectRawForView(recordId) == null) throw denied();
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
