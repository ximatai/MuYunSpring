package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.AggregateQuery;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.child.ChildRelation;
import net.ximatai.muyun.spring.ability.event.RuntimeMutationSource;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceOption;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.permission.RecordPermissionAccess;
import net.ximatai.muyun.spring.ability.permission.RecordPermissionWrite;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaService;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicActionDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicAssociationRelationOverview;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicAssociationViewDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicReferenceDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicRelationDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicViewDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.EntityViewType;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;
import net.ximatai.muyun.spring.dynamic.openapi.DynamicOpenApiDocument;
import net.ximatai.muyun.spring.dynamic.openapi.DynamicOpenApiGenerator;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.Optional;

public class DynamicRecordService {
    private static final DynamicOpenApiGenerator OPEN_API_GENERATOR = new DynamicOpenApiGenerator();

    private final DynamicRecordRuntime runtime;
    private final DynamicRecordAccessContext access;
    private final DynamicRecordQueryRuntime queryRuntime;
    private final DynamicSchemaGovernanceFacts schemaGovernanceFacts;
    private final DynamicRecordMutationRuntime mutationRuntime;
    /** Owns relation/reference reads and association-view composition. */
    private final DynamicRecordRelationRuntime relationRuntime;
    /** Owns action availability and execution; this facade keeps the long-standing public API stable. */
    private final DynamicRecordActionRuntime actionRuntime;

    public DynamicRecordService(DynamicRecordRuntime runtime) {
        this(runtime, new AllowAllActionExecutionPolicyService());
    }

    public DynamicRecordService(DynamicRecordRuntime runtime,
                                ActionExecutionPolicyService actionExecutionPolicyService) {
        this(runtime, actionExecutionPolicyService, new AllowAllDataScopeCriteriaService());
    }

    public DynamicRecordService(DynamicRecordRuntime runtime,
                                ActionExecutionPolicyService actionExecutionPolicyService,
                                DataScopeCriteriaService dataScopeCriteriaService) {
        this(runtime, actionExecutionPolicyService, dataScopeCriteriaService, DynamicRecordMutationCoordinator.NONE);
    }

    public DynamicRecordService(DynamicRecordRuntime runtime,
                                ActionExecutionPolicyService actionExecutionPolicyService,
                                DataScopeCriteriaService dataScopeCriteriaService,
                                DynamicRecordMutationCoordinator mutationCoordinator) {
        this(runtime, actionExecutionPolicyService, dataScopeCriteriaService, mutationCoordinator,
                Clock.systemDefaultZone());
    }

    public DynamicRecordService(DynamicRecordRuntime runtime,
                                ActionExecutionPolicyService actionExecutionPolicyService,
                                DataScopeCriteriaService dataScopeCriteriaService,
                                DynamicRecordMutationCoordinator mutationCoordinator,
                                Clock mutationClock) {
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        DynamicRecordEventPublisher eventPublisher = new DynamicRecordEventPublisher(runtime.eventPublisher());
        this.access = new DynamicRecordAccessContext(runtime, actionExecutionPolicyService, dataScopeCriteriaService);
        this.queryRuntime = new DynamicRecordQueryRuntime(access);
        this.schemaGovernanceFacts = new DynamicSchemaGovernanceFacts(runtime);
        DynamicRecordMutationCoordinator effectiveMutationCoordinator = mutationCoordinator == null
                ? DynamicRecordMutationCoordinator.NONE
                : mutationCoordinator;
        Clock effectiveMutationClock = mutationClock == null ? Clock.systemDefaultZone() : mutationClock;
        this.mutationRuntime = new DynamicRecordMutationRuntime(eventPublisher, access, effectiveMutationCoordinator, effectiveMutationClock);
        this.relationRuntime = new DynamicRecordRelationRuntime(access, queryRuntime);
        this.actionRuntime = new DynamicRecordActionRuntime(runtime, access, queryRuntime, mutationRuntime, eventPublisher);
    }

    public DynamicRecord newRecord(String moduleAlias, String entityAlias) {
        return runtime.newRecord(moduleAlias, entityAlias);
    }

    public DynamicModuleDescriptor describe(String moduleAlias) {
        return runtime.describe(moduleAlias);
    }

    /** Version of the installed runtime definition, for consumers caching compiled runtime facts. */
    public long runtimeRevision(String moduleAlias) {
        return runtime.registry().revision(moduleAlias);
    }

    public List<ModuleDefinition> moduleDefinitions() {
        return runtime.registry().modules();
    }

    public DynamicOpenApiDocument openApi(String moduleAlias) {
        return OPEN_API_GENERATOR.generate(describe(moduleAlias));
    }

    public String mainEntityAlias(String moduleAlias) {
        return access.mainEntityAlias(moduleAlias);
    }

    public ModuleOperations module(String moduleAlias) {
        return new ModuleOperations(this, moduleAlias);
    }

    public DynamicEntityOperations entity(String moduleAlias, String entityAlias) {
        return new DynamicEntityOperations(this, moduleAlias, entityAlias);
    }

    /**
     * Internal physical-row facts for destructive metadata schema governance.
     * Normal record queries must continue to use this service's scoped read APIs.
     */
    public DynamicSchemaGovernanceFacts schemaGovernanceFacts() {
        return schemaGovernanceFacts;
    }

    /**
     * Distinguishes a metadata-owned target from a static target that shares the platform reference registry.
     * Consumers must keep static targets on their own delivery path instead of treating a reference identity
     * module segment as a dynamic module alias.
     */
    public boolean hasRegisteredDynamicEntity(String moduleAlias, String entityAlias) {
        return access.hasRegisteredDynamicEntity(moduleAlias, entityAlias);
    }

    public DynamicEntityOperations mainEntity(String moduleAlias) {
        return entity(moduleAlias, mainEntityAlias(moduleAlias));
    }

    public boolean formActionSupported(String moduleAlias, String actionCode) {
        DynamicActionDescriptor action = access.actionDescriptor(moduleAlias, actionCode);
        return action != null && action.executorKey() != null
                && runtime.actionExecutorRegistry().definition(action.executorKey()).formSupported();
    }

    /**
     * A scoped adapter for the cross-source reference reader.  It deliberately delegates every
     * projection to this service, where REFERENCE data scope and tenant context are applied.
     */
    public Optional<ReferenceAbility<?>> referenceAbility(ReferenceTarget target) {
        if (target == null) {
            return Optional.empty();
        }
        try {
            access.requireCapability(target.moduleAlias(), target.entityAlias(), EntityCapability.REFERENCE);
            return Optional.of(new ReferenceAbility<DynamicTitledRecord>() {
                @Override
                public BaseDao<DynamicTitledRecord, String> getDao() {
                    @SuppressWarnings("unchecked")
                    BaseDao<DynamicTitledRecord, String> dao = (BaseDao<DynamicTitledRecord, String>)
                            (BaseDao<?, ?>) entityService(target.moduleAlias(), target.entityAlias()).getDao();
                    return dao;
                }

                @Override
                public String getModuleAlias() {
                    return target.qualifiedName();
                }

                @Override
                public ReferenceTarget referenceTarget() {
                    return target;
                }

                @Override
                public Map<String, Map<String, Object>> projections(Collection<String> ids,
                                                                       Collection<String> fieldNames) {
                    return DynamicRecordService.this.projections(target.moduleAlias(), target.entityAlias(),
                            ids, fieldNames);
                }

                @Override
                public PageResult<ReferenceOption> referenceOptions(Criteria criteria, PageRequest pageRequest) {
                    return DynamicRecordService.this.referenceOptions(target.moduleAlias(), target.entityAlias(),
                            criteria, pageRequest);
                }

                @Override
                public PageResult<ReferenceOption> referenceOptions(ReferencePlan plan, Criteria criteria,
                                                                      PageRequest pageRequest) {
                    return DynamicRecordService.this.referenceOptions(target.moduleAlias(), target.entityAlias(), plan,
                            criteria, pageRequest);
                }
            });
        } catch (ModuleDefinitionException ignored) {
            return Optional.empty();
        }
    }

    public DynamicRecordActionGateway recordsForAction(String moduleAlias, PlatformAction action, String traceId) {
        return new DynamicRecordActionGateway(this, moduleAlias, action, traceId);
    }

    public DynamicEntityDescriptor entityDescriptor(String moduleAlias, String entityAlias) {
        return access.entityDescriptor(moduleAlias, entityAlias);
    }

    public DynamicFormulaPreviewResult previewFormula(String moduleAlias, String entityAlias, DynamicRecord record) {
        if (record == null || record.getId() == null || record.getId().isBlank()) {
            access.requireAction(moduleAlias, PlatformAction.CREATE);
            return entityService(moduleAlias, entityAlias).previewFormula(record);
        }
        DataScopeCriteriaResult scope = access.requireBusinessRecordMutation(moduleAlias, entityAlias,
                PlatformAction.UPDATE, normalizeRecordId(record.getId()));
        return access.withTenantScope(scope, () -> entityService(moduleAlias, entityAlias).previewFormula(record));
    }

    public List<DynamicActionDescriptor> actions(String moduleAlias) {
        return describe(moduleAlias).actions();
    }

    public DynamicActionDescriptor action(String moduleAlias, String actionCode) {
        return access.actionDescriptor(moduleAlias, actionCode);
    }

    public String actionEntityAlias(String moduleAlias, String actionCode) {
        return access.actionEntityAlias(moduleAlias, actionCode);
    }

    public DynamicActionAvailability actionAvailability(String moduleAlias, String actionCode, DynamicRecord record) {
        return actionRuntime.actionAvailability(moduleAlias, actionCode, record);
    }

    /**
     * Resolves visible-record action availability with bounded reads: records are loaded once,
     * and each action performs at most one data-scope projection for the requested id set.
     */
    public List<DynamicRecordActionAvailability> recordActionAvailability(String moduleAlias,
                                                                            String entityAlias,
                                                                            Collection<String> actionCodes,
                                                                            Collection<String> recordIds) {
        return actionRuntime.recordActionAvailability(moduleAlias, entityAlias, actionCodes, recordIds);
    }

    public DynamicActionAvailability actionAuthorizationAvailability(String moduleAlias,
                                                                     String actionCode,
                                                                     Collection<String> recordIds) {
        return actionRuntime.actionAuthorizationAvailability(moduleAlias, actionCode, recordIds);
    }

    /** Authorization for capability endpoints intentionally excluded from the generic action directory. */
    public DynamicActionAvailability httpOnlyCapabilityAuthorizationAvailability(String moduleAlias,
                                                                                  PlatformAction action,
                                                                                  Collection<String> recordIds) {
        return actionRuntime.httpOnlyCapabilityAuthorizationAvailability(moduleAlias, action, recordIds);
    }

    public DynamicActionExecutionResult executeAction(String moduleAlias,
                                                      String actionCode,
                                                      DynamicActionExecutionRequest request) {
        return actionRuntime.executeAction(moduleAlias, actionCode, request);
    }

    public List<DynamicActionDescriptor> actions(String moduleAlias, String entityAlias) {
        return entityDescriptor(moduleAlias, entityAlias).actions();
    }

    public DynamicActionDescriptor action(String moduleAlias, String entityAlias, String actionCode) {
        return access.entityActionDescriptor(moduleAlias, entityAlias, actionCode);
    }

    public DynamicActionAvailability actionAvailability(String moduleAlias,
                                                        String entityAlias,
                                                        String actionCode,
                                                        DynamicRecord record) {
        return actionRuntime.actionAvailability(moduleAlias, entityAlias, actionCode, record);
    }

    public DynamicActionAvailability actionAuthorizationAvailability(String moduleAlias,
                                                                     String entityAlias,
                                                                     String actionCode,
                                                                     Collection<String> recordIds) {
        return actionRuntime.actionAuthorizationAvailability(moduleAlias, entityAlias, actionCode, recordIds);
    }

    public DynamicActionExecutionResult executeAction(String moduleAlias,
                                                      String entityAlias,
                                                      String actionCode,
                                                      DynamicActionExecutionRequest request) {
        return actionRuntime.executeAction(moduleAlias, entityAlias, actionCode, request);
    }

    public List<DynamicViewDescriptor> views(String moduleAlias, String entityAlias) {
        return entityDescriptor(moduleAlias, entityAlias).views();
    }

    public DynamicViewDescriptor view(String moduleAlias, String entityAlias, EntityViewType viewType) {
        return findView(moduleAlias, entityDescriptor(moduleAlias, entityAlias), viewType);
    }

    public List<DynamicAssociationViewDescriptor> associationViews(String moduleAlias) {
        return relationRuntime.associationViews(moduleAlias);
    }

    public List<DynamicAssociationViewDescriptor> associationViews(String moduleAlias, String entityAlias) {
        return relationRuntime.associationViews(moduleAlias, entityAlias);
    }

    public DynamicAssociationViewDescriptor associationView(String moduleAlias, String entityAlias, String viewCode) {
        return relationRuntime.associationView(moduleAlias, entityAlias, viewCode);
    }

    public PageResult<DynamicRecord> associationViewPage(String moduleAlias,
                                                         String entityAlias,
                                                         String sourceRecordId,
                                                         String viewCode,
                                                         Criteria criteria,
                                                         PageRequest pageRequest,
                                                         Sort... sorts) {
        return relationRuntime.associationViewPage(moduleAlias, entityAlias, sourceRecordId, viewCode, criteria,
                pageRequest, sorts);
    }

    public DynamicAssociationRelationOverview associationRelationOverview(String moduleAlias) {
        return relationRuntime.associationRelationOverview(moduleAlias);
    }

    public List<DynamicAssociationViewDescriptor> associationViewDesignDescriptors(String moduleAlias) {
        return associationViews(moduleAlias);
    }

    public DynamicAssociationViewDiagnosis diagnoseAssociationView(String moduleAlias,
                                                                   String entityAlias,
                                                                   String sourceRecordId,
                                                                   String viewCode,
                                                                   Criteria criteria) {
        return relationRuntime.diagnoseAssociationView(moduleAlias, entityAlias, sourceRecordId, viewCode, criteria);
    }

    public List<DynamicRelationDescriptor> relations(String moduleAlias) {
        return relationRuntime.relations(moduleAlias);
    }

    public List<DynamicReferenceDescriptor> references(String moduleAlias) {
        return relationRuntime.references(moduleAlias);
    }

    public List<DynamicReferenceDescriptor> references(String moduleAlias, String entityAlias) {
        return relationRuntime.references(moduleAlias, entityAlias);
    }

    public DynamicReferenceDescriptor reference(String moduleAlias, String entityAlias, String sourceField) {
        return relationRuntime.reference(moduleAlias, entityAlias, sourceField);
    }

    @Transactional
    public String create(String moduleAlias, String entityAlias, DynamicRecord record) {
        return mutationRuntime.create(moduleAlias, entityAlias, record, RuntimeMutationSource.BUSINESS, null,
                mutationMetadata(record));
    }

    @Transactional
    public String create(String moduleAlias,
                         String entityAlias,
                         DynamicRecord record,
                         Map<String, Object> mutationMetadata) {
        return mutationRuntime.create(moduleAlias, entityAlias, record, RuntimeMutationSource.BUSINESS, null, mutationMetadata);
    }

    String createFromAction(String moduleAlias, String entityAlias, DynamicRecord record, String traceId) {
        return mutationRuntime.create(moduleAlias, entityAlias, record, RuntimeMutationSource.ACTION, traceId, Map.of());
    }

    public DynamicRecord select(String moduleAlias, String entityAlias, String id) {
        return queryRuntime.select(moduleAlias, entityAlias, id);
    }

    RecordPermissionAccess<DynamicRecord> readForPermissionAction(String moduleAlias, String entityAlias, String id) {
        if (id == null || id.isBlank()) {
            return new RecordPermissionAccess<>(null, false);
        }
        DataScopeCriteriaResult scope = access.readScope(moduleAlias, PlatformAction.MANAGE_PERMISSIONS,
                Criteria.of().eq("id", id));
        DynamicRecord record = access.withTenantScope(scope, () -> {
            boolean visible = !entityService(moduleAlias, entityAlias)
                    .list(scope.criteria(), new PageRequest(0, 1)).isEmpty();
            return visible ? entityService(moduleAlias, entityAlias).select(id) : null;
        });
        return new RecordPermissionAccess<>(record, scope.crossTenant());
    }

    /**
     * Reads one declared aggregate relation only after the parent has passed the normal VIEW
     * scope.  It is the dynamic counterpart of the shared {@code ChildRelation} read path used
     * by static modules; web delivery decides which child fields are exposed.
     */
    public List<DynamicRecord> aggregateChildrenForView(String moduleAlias, String parentId,
                                                        String relationCode) {
        return aggregateChildrenForRead(moduleAlias, parentId, relationCode, false);
    }

    public List<DynamicRecord> aggregateChildrenForRecycleBin(String moduleAlias, String parentId,
                                                              String relationCode) {
        access.requireCapability(moduleAlias, mainEntityAlias(moduleAlias), EntityCapability.RECYCLE_BIN);
        return aggregateChildrenForRead(moduleAlias, parentId, relationCode, true);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<DynamicRecord> aggregateChildrenForRead(String moduleAlias, String parentId,
                                                         String relationCode, boolean retained) {
        String parentAlias = mainEntityAlias(moduleAlias);
        DataScopeCriteriaResult scope = access.readScope(moduleAlias,
                retained ? PlatformAction.RECYCLE_BIN_QUERY : PlatformAction.VIEW, Criteria.of().eq("id", parentId));
        return access.withTenantScope(scope, () -> {
            DynamicEntityService parentService = entityService(moduleAlias, parentAlias);
            Criteria criteria = retained ? parentService.recycleBinReadCriteria(scope.criteria())
                    : parentService.activeCriteria(scope.criteria());
            if (parentService.getDao().query(criteria, PageRequest.of(1, 1)).isEmpty()) {
                throw new IllegalArgumentException("aggregate relation expansion parent is not visible: " + parentId);
            }
            ChildRelation relation = requireAggregateChildRelation(moduleAlias, relationCode);
            if (!(relation.childAbility() instanceof DynamicEntityService childService)) {
                throw new IllegalStateException("dynamic aggregate child relation must use a dynamic child service: " + relationCode);
            }
            return childService.enrichAggregateViewChildren((List<DynamicRecord>) (retained
                    ? relation.selectDeletedChildren(parentId) : relation.selectChildren(parentId)));
        });
    }

    /**
     * Reads persisted aggregate children while validating a parent UPDATE.  This deliberately
     * follows the update action and tenant scope instead of the VIEW-only aggregate expansion
     * path, because the rows are a mutation baseline rather than response data.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public List<DynamicRecord> aggregateChildrenForUpdate(String moduleAlias, String parentId,
                                                          String relationCode) {
        String mainEntityAlias = mainEntityAlias(moduleAlias);
        DataScopeCriteriaResult scope = access.requireBusinessRecordMutation(moduleAlias, mainEntityAlias,
                PlatformAction.UPDATE, Set.of(parentId));
        ChildRelation relation = requireAggregateChildRelation(moduleAlias, relationCode);
        return access.withTenantScope(scope, () -> (List<DynamicRecord>) relation.selectChildren(parentId));
    }

    /** Presentation companions (for example, reference titles) travel with an aggregate expansion column. */
    public List<String> aggregateExpansionOutputFields(String moduleAlias, String relationCode,
                                                        List<String> requestedFields) {
        ChildRelation relation = requireAggregateChildRelation(moduleAlias, relationCode);
        if (!(relation.childAbility() instanceof DynamicEntityService childService)) {
            throw new IllegalStateException("dynamic aggregate child relation must use a dynamic child service: "
                    + relationCode);
        }
        return childService.expansionOutputFields(requestedFields);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ChildRelation requireAggregateChildRelation(String moduleAlias, String relationCode) {
        return entityService(moduleAlias, mainEntityAlias(moduleAlias)).childRelations().stream()
                .filter(candidate -> relationCode.equals(candidate.relationCode()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "unknown aggregate child relation: " + relationCode));
    }

    public DynamicRecord selectIgnoreSoftDelete(String moduleAlias, String entityAlias, String id) {
        return entityService(moduleAlias, entityAlias).selectIgnoreSoftDelete(id);
    }

    public DynamicRecord selectSystem(String moduleAlias, String entityAlias, String id) {
        return entityService(moduleAlias, entityAlias).select(id);
    }

    void requireAction(String moduleAlias, PlatformAction action) {
        access.requireAction(moduleAlias, action);
    }

    public DataScopeCriteriaResult requireRecordActionScope(String moduleAlias,
                                                            String entityAlias,
                                                            ActionExecutionPolicy policy,
                                                            Collection<String> recordIds,
                                                            Optional<CurrentUser> currentUser) {
        return access.requireRecordActionScope(moduleAlias, entityAlias, policy, recordIds, currentUser);
    }

    @Transactional
    public int update(String moduleAlias, String entityAlias, DynamicRecord record) {
        return mutationRuntime.update(moduleAlias, entityAlias, record, RuntimeMutationSource.BUSINESS, null,
                mutationMetadata(record));
    }

    @Transactional
    public int update(String moduleAlias,
                      String entityAlias,
                      DynamicRecord record,
                      Map<String, Object> mutationMetadata) {
        return mutationRuntime.update(moduleAlias, entityAlias, record, RuntimeMutationSource.BUSINESS, null, mutationMetadata);
    }

    @Transactional
    public int updateSystem(String moduleAlias, String entityAlias, DynamicRecord record, String systemReason) {
        try (TenantContext.Scope ignored = TenantContext.system(systemReason)) {
            return mutationRuntime.update(moduleAlias, entityAlias, record, RuntimeMutationSource.SYSTEM, null, Map.of());
        }
    }

    @Transactional
    public String createWriteBack(String moduleAlias,
                                  String entityAlias,
                                  DynamicRecord record,
                                  DynamicWriteBackContext writeBackContext) {
        return createWriteBack(moduleAlias, entityAlias, record, writeBackContext, Map.of());
    }

    @Transactional
    public String createWriteBack(String moduleAlias,
                                  String entityAlias,
                                  DynamicRecord record,
                                  DynamicWriteBackContext writeBackContext,
                                  Map<String, Object> mutationMetadata) {
        return mutationRuntime.createWriteBack(moduleAlias, entityAlias, record, writeBackContext, mutationMetadata);
    }

    @Transactional
    public int updateWriteBack(String moduleAlias,
                               String entityAlias,
                               DynamicRecord record,
                               DynamicWriteBackContext writeBackContext) {
        return updateWriteBack(moduleAlias, entityAlias, record, writeBackContext, Map.of());
    }

    @Transactional
    public int updateWriteBack(String moduleAlias,
                               String entityAlias,
                               DynamicRecord record,
                               DynamicWriteBackContext writeBackContext,
                               Map<String, Object> mutationMetadata) {
        return mutationRuntime.updateWriteBack(moduleAlias, entityAlias, record, writeBackContext, mutationMetadata);
    }

    @Transactional
    public int updatePermissions(String moduleAlias, String entityAlias, RecordPermissionWrite write) {
        return mutationRuntime.updatePermissions(moduleAlias, entityAlias, write);
    }

    int updateFromAction(String moduleAlias, String entityAlias, DynamicRecord record, String traceId) {
        return mutationRuntime.update(moduleAlias, entityAlias, record, RuntimeMutationSource.ACTION, traceId, Map.of());
    }

    void validateImportFromAction(String moduleAlias, String entityAlias, DynamicRecord record, DynamicRecord existing) {
        DynamicFormulaRuntime formulaRuntime = new DynamicFormulaRuntime(
                moduleAlias, record.getEntity(), runtime.registry().requireModule(moduleAlias));
        if (formulaRuntime.hasImportValidateRules()) {
            formulaRuntime.importValidate(record, existing);
        }
    }

    private Map<String, Object> mutationMetadata(DynamicRecord record) {
        return record == null ? Map.of() : record.mutationMetadata();
    }

    @Transactional
    public int delete(String moduleAlias, String entityAlias, String id) {
        return mutationRuntime.delete(moduleAlias, entityAlias, id, null, RuntimeMutationSource.BUSINESS, null);
    }

    @Transactional
    public int delete(String moduleAlias, String entityAlias, String id, Integer expectedVersion) {
        return mutationRuntime.delete(moduleAlias, entityAlias, id, expectedVersion, RuntimeMutationSource.BUSINESS, null);
    }

    int deleteFromAction(String moduleAlias, String entityAlias, String id, String traceId) {
        return mutationRuntime.delete(moduleAlias, entityAlias, id, null, RuntimeMutationSource.ACTION, traceId);
    }

    int deleteBatchFromAction(String moduleAlias, String entityAlias, Collection<String> ids, String traceId) {
        return mutationRuntime.deleteBatch(moduleAlias, entityAlias, ids, RuntimeMutationSource.ACTION, traceId);
    }

    @Transactional
    public int deleteBatch(String moduleAlias, String entityAlias, Collection<String> ids) {
        return mutationRuntime.deleteBatch(moduleAlias, entityAlias, ids, RuntimeMutationSource.BUSINESS, null);
    }

    public List<DynamicRecord> list(String moduleAlias, String entityAlias, Criteria criteria, PageRequest pageRequest, Sort... sorts) {
        return queryRuntime.list(moduleAlias, entityAlias, criteria, pageRequest, sorts);
    }

    public List<DynamicRecord> list(String moduleAlias, String entityAlias, Criteria criteria, Sort... sorts) {
        return queryRuntime.list(moduleAlias, entityAlias, criteria, sorts);
    }

    public List<DynamicRecord> listSystem(String moduleAlias,
                                          String entityAlias,
                                          Criteria criteria,
                                          PageRequest pageRequest,
                                          Sort... sorts) {
        return entityService(moduleAlias, entityAlias).list(criteria, pageRequest, sorts);
    }

    public List<DynamicRecord> listSystem(String moduleAlias,
                                          String entityAlias,
                                          Criteria criteria,
                                          Sort... sorts) {
        return entityService(moduleAlias, entityAlias).list(criteria, sorts);
    }

    List<DynamicRecord> listForAction(String moduleAlias,
                                      String entityAlias,
                                      PlatformAction action,
                                      Criteria criteria,
                                      PageRequest pageRequest,
                                      Sort... sorts) {
        return queryRuntime.listForAction(moduleAlias, entityAlias, action, criteria, pageRequest, sorts);
    }

    List<DynamicRecord> listForAction(String moduleAlias,
                                      String entityAlias,
                                      PlatformAction action,
                                      Criteria criteria,
                                      Sort... sorts) {
        return queryRuntime.listForAction(moduleAlias, entityAlias, action, criteria, sorts);
    }

    public PageResult<DynamicRecord> page(String moduleAlias, String entityAlias, Criteria criteria, PageRequest pageRequest, Sort... sorts) {
        return queryRuntime.page(moduleAlias, entityAlias, criteria, pageRequest, sorts);
    }

    /** Retained-record read uses the same dynamic data-scope kernel, but the RECYCLE_BIN action policy. */
    PageResult<DynamicRecord> pageRecycleBinForAction(String moduleAlias,
                                                      String entityAlias,
                                                      Criteria criteria,
                                                      PageRequest pageRequest,
                                                      Sort... sorts) {
        access.requireCapability(moduleAlias, entityAlias, EntityCapability.RECYCLE_BIN);
        DataScopeCriteriaResult scope = access.readScope(moduleAlias, PlatformAction.RECYCLE_BIN_QUERY, criteria);
        return access.withTenantScope(scope, () -> entityService(moduleAlias, entityAlias)
                .pageRecycleBin(scope.criteria(), pageRequest, sorts));
    }

    /** Restore and purge validate the retained root through the same action data-range before coordinators mutate it. */
    boolean canAccessRecycleBinSourceForAction(String moduleAlias, String entityAlias, String id) {
        access.requireCapability(moduleAlias, entityAlias, EntityCapability.RECYCLE_BIN);
        if (id == null || id.isBlank()) return false;
        DataScopeCriteriaResult scope = access.readScope(moduleAlias, PlatformAction.RECYCLE_BIN_QUERY,
                Criteria.of().eq("id", id));
        return access.withTenantScope(scope, () -> !entityService(moduleAlias, entityAlias).getDao()
                .query(entityService(moduleAlias, entityAlias).tenantCriteria(scope.criteria()), PageRequest.of(1, 1)).isEmpty());
    }

    boolean canAccessRecycleBinRecordForAction(String moduleAlias, String entityAlias, String id) {
        access.requireCapability(moduleAlias, entityAlias, EntityCapability.RECYCLE_BIN);
        if (id == null || id.isBlank()) return false;
        DataScopeCriteriaResult scope = access.readScope(moduleAlias, PlatformAction.RECYCLE_BIN_QUERY,
                Criteria.of().eq("id", id));
        return access.withTenantScope(scope, () -> !entityService(moduleAlias, entityAlias).getDao()
                .query(entityService(moduleAlias, entityAlias).tenantCriteria(retainedCriteria(scope.criteria())), PageRequest.of(1, 1)).isEmpty());
    }

    /** SQL projections retain the recycle-bin capability, data-range and tenant boundaries. */
    public <R> R withRecycleBinReadScope(String moduleAlias, Criteria criteria, Function<Criteria, R> reader) {
        String entityAlias = runtime.describe(moduleAlias).mainEntityAlias();
        access.requireCapability(moduleAlias, entityAlias, EntityCapability.RECYCLE_BIN);
        DataScopeCriteriaResult scope = access.readScope(moduleAlias, PlatformAction.RECYCLE_BIN_QUERY, criteria);
        return access.withTenantScope(scope, () -> reader.apply(entityService(moduleAlias, entityAlias)
                .recycleBinReadCriteria(scope.criteria())));
    }

    public <R> R withQueryReadScope(String moduleAlias, Criteria criteria, Function<Criteria, R> action) {
        return queryRuntime.withQueryReadScope(moduleAlias, criteria, action);
    }

    public PageResult<DynamicRecord> pageForAction(String moduleAlias,
                                                   String entityAlias,
                                                   String actionCode,
                                                   Criteria criteria,
                                                   PageRequest pageRequest,
                                                   Sort... sorts) {
        return queryRuntime.pageForAction(moduleAlias, entityAlias, actionCode, criteria, pageRequest, sorts);
    }

    /**
     * Reads one tree level through a declared action policy.
     *
     * <p>This is intentionally separate from {@link #children(String, String, String)}: a page
     * navigator consumes a module's reference surface, not its ordinary tree surface.</p>
     */
    public List<DynamicRecord> childrenForAction(String moduleAlias,
                                                  String entityAlias,
                                                  String actionCode,
                                                  Criteria criteria,
                                                  String parentId) {
        return queryRuntime.childrenForAction(moduleAlias, entityAlias, actionCode, criteria, parentId);
    }

    public long count(String moduleAlias, String entityAlias, Criteria criteria) {
        return queryRuntime.count(moduleAlias, entityAlias, criteria);
    }

    /** Aggregate through the same QUERY authorization, tenant and data-range scope as list reads. */
    public List<Map<String, Object>> aggregate(String moduleAlias, String entityAlias, Criteria criteria,
                                               AggregateQuery query) {
        return queryRuntime.aggregate(moduleAlias, entityAlias, criteria, query);
    }

    public List<DynamicRecord> sortedList(String moduleAlias, String entityAlias, Criteria criteria) {
        return queryRuntime.sortedList(moduleAlias, entityAlias, criteria);
    }

    public void reorder(String moduleAlias, String entityAlias, List<String> orderedIds) {
        mutationRuntime.reorder(moduleAlias, entityAlias, orderedIds, RuntimeMutationSource.BUSINESS, null);
    }

    /** Stable capability-runtime facade for the registered SORT action handler. */
    public void reorderFromAction(String moduleAlias, String entityAlias, List<String> orderedIds, String traceId) {
        mutationRuntime.reorder(moduleAlias, entityAlias, orderedIds, RuntimeMutationSource.ACTION, traceId);
    }

    public void moveBefore(String moduleAlias, String entityAlias, String id, String beforeId) {
        mutationRuntime.moveBefore(moduleAlias, entityAlias, id, beforeId, RuntimeMutationSource.BUSINESS, null);
    }

    /** Stable capability-runtime facade for the registered SORT action handler. */
    public void moveBeforeFromAction(String moduleAlias, String entityAlias, String id, String beforeId, String traceId) {
        mutationRuntime.moveBefore(moduleAlias, entityAlias, id, beforeId, RuntimeMutationSource.ACTION, traceId);
    }

    public void moveAfter(String moduleAlias, String entityAlias, String id, String afterId) {
        mutationRuntime.moveAfter(moduleAlias, entityAlias, id, afterId, RuntimeMutationSource.BUSINESS, null);
    }

    /** Stable capability-runtime facade for the registered SORT action handler. */
    public void moveAfterFromAction(String moduleAlias, String entityAlias, String id, String afterId, String traceId) {
        mutationRuntime.moveAfter(moduleAlias, entityAlias, id, afterId, RuntimeMutationSource.ACTION, traceId);
    }

    public void moveInTree(String moduleAlias, String entityAlias, String id, String previousId, String nextId, String parentId) {
        mutationRuntime.moveInTree(moduleAlias, entityAlias, id, previousId, nextId, parentId,
                RuntimeMutationSource.BUSINESS, null);
    }

    /** Stable capability-runtime facade for a TREE move action. */
    public void moveInTreeFromAction(String moduleAlias, String entityAlias, String id,
                                     String previousId, String nextId, String parentId, String traceId) {
        mutationRuntime.moveInTree(moduleAlias, entityAlias, id, previousId, nextId, parentId,
                RuntimeMutationSource.ACTION, traceId);
    }

    public void moveInTreeFromAction(String moduleAlias, String entityAlias, String id,
                                     String previousId, String nextId, String parentId,
                                     Criteria sortScope, String traceId) {
        mutationRuntime.moveInTree(moduleAlias, entityAlias, id, previousId, nextId, parentId,
                sortScope, RuntimeMutationSource.ACTION, traceId);
    }

    public List<DynamicRecord> children(String moduleAlias, String entityAlias, String parentId) {
        return queryRuntime.children(moduleAlias, entityAlias, parentId);
    }

    public List<String> ancestorIds(String moduleAlias, String entityAlias, String id) {
        access.requireCapability(moduleAlias, entityAlias, EntityCapability.TREE);
        DataScopeCriteriaResult scope = access.readScope(moduleAlias, PlatformAction.TREE, Criteria.of().eq("id", id));
        if (!access.recordVisible(moduleAlias, entityAlias, scope, id)) {
            return List.of();
        }
        List<String> ids = access.withTenantScope(scope, () -> entityService(moduleAlias, entityAlias).ancestorIds(id));
        return visibleTreeIds(moduleAlias, entityAlias, ids);
    }

    public List<String> ancestorIdsAndSelf(String moduleAlias, String entityAlias, String id) {
        access.requireCapability(moduleAlias, entityAlias, EntityCapability.TREE);
        DataScopeCriteriaResult scope = access.readScope(moduleAlias, PlatformAction.TREE, Criteria.of().eq("id", id));
        if (!access.recordVisible(moduleAlias, entityAlias, scope, id)) {
            return List.of();
        }
        List<String> ids = access.withTenantScope(scope, () -> entityService(moduleAlias, entityAlias).ancestorIdsAndSelf(id));
        return visibleTreeIds(moduleAlias, entityAlias, ids);
    }

    public List<String> descendantIds(String moduleAlias, String entityAlias, String id) {
        access.requireCapability(moduleAlias, entityAlias, EntityCapability.TREE);
        DataScopeCriteriaResult scope = access.readScope(moduleAlias, PlatformAction.TREE, Criteria.of().eq("id", id));
        if (!access.recordVisible(moduleAlias, entityAlias, scope, id)) {
            return List.of();
        }
        List<String> ids = access.withTenantScope(scope, () -> entityService(moduleAlias, entityAlias).descendantIds(id));
        return visibleTreeIds(moduleAlias, entityAlias, ids);
    }

    public int enable(String moduleAlias, String entityAlias, String id) {
        return mutationRuntime.enable(moduleAlias, entityAlias, id, null, RuntimeMutationSource.BUSINESS, null);
    }

    public int enable(String moduleAlias, String entityAlias, String id, Integer expectedVersion) {
        return mutationRuntime.enable(moduleAlias, entityAlias, id, expectedVersion, RuntimeMutationSource.BUSINESS, null);
    }

    /** Stable capability-runtime facade for the registered ENABLE action handler. */
    public int enableFromAction(String moduleAlias, String entityAlias, String id, String traceId) {
        return mutationRuntime.enable(moduleAlias, entityAlias, id, null, RuntimeMutationSource.ACTION, traceId);
    }

    public int disable(String moduleAlias, String entityAlias, String id) {
        return mutationRuntime.disable(moduleAlias, entityAlias, id, null, RuntimeMutationSource.BUSINESS, null);
    }

    public int disable(String moduleAlias, String entityAlias, String id, Integer expectedVersion) {
        return mutationRuntime.disable(moduleAlias, entityAlias, id, expectedVersion, RuntimeMutationSource.BUSINESS, null);
    }

    /** Stable capability-runtime facade for the registered ENABLE action handler. */
    public int disableFromAction(String moduleAlias, String entityAlias, String id, String traceId) {
        return mutationRuntime.disable(moduleAlias, entityAlias, id, null, RuntimeMutationSource.ACTION, traceId);
    }

    public boolean isEnabled(String moduleAlias, String entityAlias, String id) {
        access.requireCapability(moduleAlias, entityAlias, EntityCapability.ENABLE);
        DataScopeCriteriaResult scope = access.readScope(moduleAlias, PlatformAction.VIEW, Criteria.of().eq("id", id));
        if (!access.recordVisible(moduleAlias, entityAlias, scope, id)) {
            return false;
        }
        return access.withTenantScope(scope, () -> entityService(moduleAlias, entityAlias).isEnabled(id));
    }

    public Criteria enabledCriteria(String moduleAlias, String entityAlias, Criteria criteria) {
        return entityService(moduleAlias, entityAlias).enabledCriteria(criteria);
    }

    public Criteria queryCriteria(String moduleAlias, String entityAlias, Collection<DynamicQueryCondition> conditions) {
        return entityService(moduleAlias, entityAlias).queryCriteria(conditions);
    }

    public String title(String moduleAlias, String entityAlias, String id) {
        return relationRuntime.title(moduleAlias, entityAlias, id);
    }

    public Map<String, String> titles(String moduleAlias, String entityAlias, Collection<String> ids) {
        return relationRuntime.titles(moduleAlias, entityAlias, ids);
    }

    public Map<String, Map<String, Object>> projections(String moduleAlias,
                                                        String entityAlias,
                                                        Collection<String> ids,
                                                        Collection<String> fieldNames) {
        return relationRuntime.projections(moduleAlias, entityAlias, ids, fieldNames);
    }

    public PageResult<ReferenceOption> referenceOptions(String moduleAlias,
                                                        String entityAlias,
                                                        Criteria criteria,
                                                        PageRequest pageRequest) {
        return relationRuntime.referenceOptions(moduleAlias, entityAlias, criteria, pageRequest);
    }

    public PageResult<ReferenceOption> referenceOptions(String moduleAlias, String entityAlias, ReferencePlan plan,
                                                         Criteria criteria, PageRequest pageRequest) {
        return relationRuntime.referenceOptions(moduleAlias, entityAlias, plan, criteria, pageRequest);
    }

    private Criteria retainedCriteria(Criteria criteria) {
        Criteria result = Criteria.of();
        if (criteria != null && !criteria.isEmpty()) {
            result.andGroup(criteria.getRoot());
        }
        return result.eq(net.ximatai.muyun.spring.common.schema.StandardEntitySchema.DELETED_FIELD, Boolean.TRUE);
    }

    private List<String> visibleTreeIds(String moduleAlias, String entityAlias, Collection<String> ids) {
        DataScopeCriteriaResult scope = access.readScope(moduleAlias, PlatformAction.TREE, access.idsCriteria(ids));
        return List.copyOf(access.visibleRecordIds(moduleAlias, entityAlias, scope, ids));
    }

    private Set<String> normalizeRecordId(String id) {
        return access.normalizeRecordIds(id == null ? null : java.util.Collections.singletonList(id));
    }

    public DynamicReferenceResolveResponse resolveReference(String moduleAlias,
                                                            String entityAlias,
                                                            String sourceField,
                                                            DynamicReferenceResolveRequest request) {
        return relationRuntime.resolveReference(moduleAlias, entityAlias, sourceField, request);
    }

    public DynamicReferenceResolveResponse resolveFieldReference(String moduleAlias,
                                                                 String entityAlias,
                                                                 String fieldName,
                                                                 DynamicReferenceResolveRequest request) {
        return resolveReference(moduleAlias, entityAlias, fieldName, request);
    }

    DynamicEntityService entityService(String moduleAlias, String entityAlias) {
        return access.entityService(moduleAlias, entityAlias);
    }

    private DynamicViewDescriptor findView(String moduleAlias, DynamicEntityDescriptor entity, EntityViewType viewType) {
        return entity.views().stream()
                .filter(view -> view.viewType() == viewType)
                .findFirst()
                .orElseThrow(() -> new ModuleDefinitionException("unknown dynamic view: "
                        + moduleAlias + "." + entity.entityAlias() + "." + viewType));
    }

    public static final class ModuleOperations {
        private final DynamicRecordService service;
        private final String moduleAlias;

        private ModuleOperations(DynamicRecordService service, String moduleAlias) {
            this.service = service;
            this.moduleAlias = moduleAlias;
        }

        public DynamicModuleDescriptor describe() {
            return service.describe(moduleAlias);
        }

        public List<DynamicActionDescriptor> actions() {
            return service.actions(moduleAlias);
        }

        public DynamicActionDescriptor action(String actionCode) {
            return service.action(moduleAlias, actionCode);
        }

        public DynamicActionAvailability actionAvailability(String actionCode, DynamicRecord record) {
            return service.actionAvailability(moduleAlias, actionCode, record);
        }

        public DynamicActionExecutionResult executeAction(String actionCode, DynamicActionExecutionRequest request) {
            return service.executeAction(moduleAlias, actionCode, request);
        }

        public List<DynamicEntityDescriptor> entities() {
            return describe().entities();
        }

        public List<DynamicRelationDescriptor> relations() {
            return service.relations(moduleAlias);
        }

        public List<DynamicReferenceDescriptor> references() {
            return service.references(moduleAlias);
        }

        public List<DynamicAssociationViewDescriptor> associationViews() {
            return service.associationViews(moduleAlias);
        }

        public DynamicEntityOperations entity(String entityAlias) {
            return service.entity(moduleAlias, entityAlias);
        }
    }

}
