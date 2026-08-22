package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.TransactionScopeSupport;
import net.ximatai.muyun.spring.ability.child.ChildRelation;
import net.ximatai.muyun.spring.ability.event.RuntimeMutationSource;
import net.ximatai.muyun.spring.ability.reference.ReferenceOption;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.ActionAccessMode;
import net.ximatai.muyun.spring.common.platform.ActionAuthorizationResult;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaService;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.web.RequestTraceContext;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicActionDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicAssociationRelationOverview;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicAssociationViewDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicReferenceDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicRelationDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicViewDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType;
import net.ximatai.muyun.spring.dynamic.metadata.EntityViewType;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;
import net.ximatai.muyun.spring.dynamic.openapi.DynamicOpenApiDocument;
import net.ximatai.muyun.spring.dynamic.openapi.DynamicOpenApiGenerator;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.Optional;

public class DynamicRecordService {
    private static final DynamicOpenApiGenerator OPEN_API_GENERATOR = new DynamicOpenApiGenerator();

    private final DynamicRecordRuntime runtime;
    private final DynamicRecordEventPublisher eventPublisher;
    private final ActionExecutionPolicyService actionExecutionPolicyService;
    private final DataScopeCriteriaService dataScopeCriteriaService;
    private final DynamicRecordQueryRuntime queryRuntime;
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
        this.eventPublisher = new DynamicRecordEventPublisher(runtime.eventPublisher());
        this.actionExecutionPolicyService = Objects.requireNonNull(actionExecutionPolicyService,
                "actionExecutionPolicyService must not be null");
        this.dataScopeCriteriaService = Objects.requireNonNull(dataScopeCriteriaService,
                "dataScopeCriteriaService must not be null");
        this.queryRuntime = new DynamicRecordQueryRuntime(runtime, this.actionExecutionPolicyService,
                this.dataScopeCriteriaService);
        DynamicRecordMutationCoordinator effectiveMutationCoordinator = mutationCoordinator == null
                ? DynamicRecordMutationCoordinator.NONE
                : mutationCoordinator;
        Clock effectiveMutationClock = mutationClock == null ? Clock.systemDefaultZone() : mutationClock;
        this.mutationRuntime = new DynamicRecordMutationRuntime(runtime, eventPublisher, this.actionExecutionPolicyService,
                this.dataScopeCriteriaService, effectiveMutationCoordinator, effectiveMutationClock);
        this.relationRuntime = new DynamicRecordRelationRuntime(this);
        this.actionRuntime = new DynamicRecordActionRuntime(this, runtime, eventPublisher,
                this.actionExecutionPolicyService);
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
        return runtime.registry().requireModule(moduleAlias).mainEntityAlias();
    }

    public ModuleOperations module(String moduleAlias) {
        return new ModuleOperations(this, moduleAlias);
    }

    public DynamicEntityOperations entity(String moduleAlias, String entityAlias) {
        return new DynamicEntityOperations(this, moduleAlias, entityAlias);
    }

    public DynamicEntityOperations mainEntity(String moduleAlias) {
        return entity(moduleAlias, mainEntityAlias(moduleAlias));
    }

    public DynamicRecordActionGateway recordsForAction(String moduleAlias, PlatformAction action, String traceId) {
        return new DynamicRecordActionGateway(this, moduleAlias, action, traceId);
    }

    public DynamicEntityDescriptor entityDescriptor(String moduleAlias, String entityAlias) {
        return findEntity(describe(moduleAlias), entityAlias);
    }

    public DynamicFormulaPreviewResult previewFormula(String moduleAlias, String entityAlias, DynamicRecord record) {
        if (record == null || record.getId() == null || record.getId().isBlank()) {
            requireAction(moduleAlias, PlatformAction.CREATE);
            return entityService(moduleAlias, entityAlias).previewFormula(record);
        }
        DataScopeCriteriaResult scope = requireBusinessRecordMutation(moduleAlias, entityAlias,
                PlatformAction.UPDATE, normalizeRecordId(record.getId()));
        return withTenantScope(scope, () -> entityService(moduleAlias, entityAlias).previewFormula(record));
    }

    public List<DynamicActionDescriptor> actions(String moduleAlias) {
        return describe(moduleAlias).actions();
    }

    public DynamicActionDescriptor action(String moduleAlias, String actionCode) {
        return findAction(describe(moduleAlias), actionCode);
    }

    public String actionEntityAlias(String moduleAlias, String actionCode) {
        DynamicModuleDescriptor descriptor = describe(moduleAlias);
        findAction(descriptor, actionCode);
        DynamicEntityDescriptor mainEntity = findEntity(descriptor, descriptor.mainEntityAlias());
        if (hasAction(mainEntity, actionCode)) {
            return mainEntity.entityAlias();
        }
        return descriptor.entities().stream()
                .filter(entity -> !entity.entityAlias().equals(descriptor.mainEntityAlias()))
                .filter(entity -> entity.actions().stream().anyMatch(action -> action.code().equals(actionCode)))
                .map(DynamicEntityDescriptor::entityAlias)
                .findFirst()
                .orElseThrow(() -> new ModuleDefinitionException("unknown dynamic action entity: "
                        + moduleAlias + "." + actionCode));
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
        return findAction(moduleAlias, entityDescriptor(moduleAlias, entityAlias), actionCode);
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
        Criteria base = Criteria.of().eq("id", id);
        DataScopeCriteriaResult scope = readScope(moduleAlias, PlatformAction.VIEW.code(), base);
        return withTenantScope(scope, () -> {
            if (!scope.restricted()) {
                return entityService(moduleAlias, entityAlias).select(id);
            }
            boolean visible = !entityService(moduleAlias, entityAlias).list(scope.criteria(), new PageRequest(0, 1)).isEmpty();
            return visible ? entityService(moduleAlias, entityAlias).select(id) : null;
        });
    }

    /**
     * Reads one declared aggregate relation only after the parent has passed the normal VIEW
     * scope.  It is the dynamic counterpart of the shared {@code ChildRelation} read path used
     * by static modules; web delivery decides which child fields are exposed.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public List<DynamicRecord> aggregateChildrenForView(String moduleAlias, String parentId,
                                                        String relationCode) {
        String mainEntityAlias = mainEntityAlias(moduleAlias);
        DynamicRecord parent = select(moduleAlias, mainEntityAlias, parentId);
        if (parent == null) {
            throw new IllegalArgumentException("aggregate relation expansion parent is not visible: " + parentId);
        }
        ChildRelation relation = requireAggregateChildRelation(moduleAlias, relationCode);
        return (List<DynamicRecord>) relation.selectChildren(parent.getId());
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

    public DataScopeCriteriaResult requireRecordActionScope(String moduleAlias,
                                                            String entityAlias,
                                                            ActionExecutionPolicy policy,
                                                            Collection<String> recordIds,
                                                            Optional<CurrentUser> currentUser) {
        Set<String> normalized = normalizeRecordIds(recordIds);
        actionExecutionPolicyService.requireRecordAction(ActionExecutionContext.ofPolicy(
                moduleAlias,
                policy,
                normalized,
                currentUser
        ));
        return requireActionRecordDataScope(moduleAlias, entityAlias, policy, normalized);
    }

    // Package-private action-runtime support. These preserve one data-scope/tenant implementation while
    // keeping action orchestration out of this facade.
    DynamicActionDescriptor actionDescriptor(String moduleAlias, String actionCode) {
        return findAction(describe(moduleAlias), actionCode);
    }

    DynamicActionDescriptor entityActionDescriptor(String moduleAlias, String entityAlias, String actionCode) {
        return findAction(moduleAlias, entityDescriptor(moduleAlias, entityAlias), actionCode);
    }

    Set<String> normalizeActionRecordIds(Collection<String> recordIds) {
        return normalizeRecordIds(recordIds);
    }

    Criteria actionIdsCriteria(Collection<String> recordIds) {
        return idsCriteria(recordIds);
    }

    boolean supportsActionCapability(String moduleAlias, String entityAlias, EntityCapability capability) {
        return supportsCapability(moduleAlias, entityAlias, capability);
    }

    DataScopeCriteriaResult actionCriteriaScope(String moduleAlias,
                                                String entityAlias,
                                                ActionExecutionPolicy policy,
                                                Criteria criteria,
                                                Collection<String> recordIds) {
        if (!supportsCapability(moduleAlias, entityAlias, EntityCapability.DATA_SCOPE)
                || !policy.requiresDataScope()
                || (criteria == null && !normalizeRecordIds(recordIds).isEmpty())) {
            return null;
        }
        return readScope(moduleAlias, policy, actionExecutionCriteria(criteria, recordIds));
    }

    DataScopeCriteriaResult actionRecordDataScope(String moduleAlias,
                                                  String entityAlias,
                                                  ActionExecutionPolicy policy,
                                                  Collection<String> recordIds) {
        return requireActionRecordDataScope(moduleAlias, entityAlias, policy, recordIds);
    }

    <R> R withActionScope(DataScopeCriteriaResult scope, Supplier<R> supplier) {
        return withTenantScope(scope, supplier);
    }

    Set<String> visibleActionRecordIds(String moduleAlias,
                                       String entityAlias,
                                       ActionExecutionPolicy policy,
                                       Set<String> recordIds) {
        return visibleActionRecordIdsInternal(moduleAlias, entityAlias, policy, recordIds);
    }

    ActionExecutionPolicy actionPolicy(DynamicActionDescriptor action) {
        return new ActionExecutionPolicy(
                action.code(),
                toPlatformLevel(action.actionLevel()),
                toAccessMode(action.accessMode()),
                action.actionAuth(),
                action.dataAuth(),
                action.defaultGrantPolicy(),
                action.authInheritActionCode()
        );
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
        requireCapability(moduleAlias, entityAlias, EntityCapability.RECYCLE_BIN);
        DataScopeCriteriaResult scope = readScope(moduleAlias, PlatformAction.RECYCLE_BIN_QUERY, criteria);
        Criteria retained = retainedCriteria(scope.criteria());
        return withTenantScope(scope, () -> entityService(moduleAlias, entityAlias).getDao()
                .pageQuery(retained, pageRequest == null ? PageRequest.of(1, 20) : pageRequest, sorts));
    }

    /** Restore and purge validate the retained root through the same action data-range before coordinators mutate it. */
    boolean canAccessRecycleBinSourceForAction(String moduleAlias, String entityAlias, String id) {
        requireCapability(moduleAlias, entityAlias, EntityCapability.RECYCLE_BIN);
        if (id == null || id.isBlank()) return false;
        DataScopeCriteriaResult scope = readScope(moduleAlias, PlatformAction.RECYCLE_BIN_QUERY,
                Criteria.of().eq("id", id));
        return withTenantScope(scope, () -> !entityService(moduleAlias, entityAlias).getDao()
                .query(scope.criteria(), PageRequest.of(1, 1)).isEmpty());
    }

    boolean canAccessRecycleBinRecordForAction(String moduleAlias, String entityAlias, String id) {
        requireCapability(moduleAlias, entityAlias, EntityCapability.RECYCLE_BIN);
        if (id == null || id.isBlank()) return false;
        DataScopeCriteriaResult scope = readScope(moduleAlias, PlatformAction.RECYCLE_BIN_QUERY,
                Criteria.of().eq("id", id));
        return withTenantScope(scope, () -> !entityService(moduleAlias, entityAlias).getDao()
                .query(retainedCriteria(scope.criteria()), PageRequest.of(1, 1)).isEmpty());
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

    public List<DynamicRecord> children(String moduleAlias, String entityAlias, String parentId) {
        return queryRuntime.children(moduleAlias, entityAlias, parentId);
    }

    public List<String> ancestorIds(String moduleAlias, String entityAlias, String id) {
        requireCapability(moduleAlias, entityAlias, EntityCapability.TREE);
        DataScopeCriteriaResult scope = readScope(moduleAlias, PlatformAction.TREE, Criteria.of().eq("id", id));
        if (!recordVisible(moduleAlias, entityAlias, scope, id)) {
            return List.of();
        }
        List<String> ids = withTenantScope(scope, () -> entityService(moduleAlias, entityAlias).ancestorIds(id));
        return visibleTreeIds(moduleAlias, entityAlias, ids);
    }

    public List<String> ancestorIdsAndSelf(String moduleAlias, String entityAlias, String id) {
        requireCapability(moduleAlias, entityAlias, EntityCapability.TREE);
        DataScopeCriteriaResult scope = readScope(moduleAlias, PlatformAction.TREE, Criteria.of().eq("id", id));
        if (!recordVisible(moduleAlias, entityAlias, scope, id)) {
            return List.of();
        }
        List<String> ids = withTenantScope(scope, () -> entityService(moduleAlias, entityAlias).ancestorIdsAndSelf(id));
        return visibleTreeIds(moduleAlias, entityAlias, ids);
    }

    public List<String> descendantIds(String moduleAlias, String entityAlias, String id) {
        requireCapability(moduleAlias, entityAlias, EntityCapability.TREE);
        DataScopeCriteriaResult scope = readScope(moduleAlias, PlatformAction.TREE, Criteria.of().eq("id", id));
        if (!recordVisible(moduleAlias, entityAlias, scope, id)) {
            return List.of();
        }
        List<String> ids = withTenantScope(scope, () -> entityService(moduleAlias, entityAlias).descendantIds(id));
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
        requireCapability(moduleAlias, entityAlias, EntityCapability.ENABLE);
        DataScopeCriteriaResult scope = readScope(moduleAlias, PlatformAction.VIEW, Criteria.of().eq("id", id));
        if (!recordVisible(moduleAlias, entityAlias, scope, id)) {
            return false;
        }
        return withTenantScope(scope, () -> entityService(moduleAlias, entityAlias).isEnabled(id));
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

    DataScopeCriteriaResult readScope(String moduleAlias, PlatformAction action, Criteria criteria) {
        return readScope(moduleAlias, action.executionPolicy(), criteria);
    }

    private Criteria retainedCriteria(Criteria criteria) {
        Criteria result = Criteria.of();
        if (criteria != null && !criteria.isEmpty()) {
            result.andGroup(criteria.getRoot());
        }
        return result.eq(net.ximatai.muyun.spring.common.schema.StandardEntitySchema.DELETED_FIELD, Boolean.TRUE);
    }

    private DataScopeCriteriaResult readScope(String moduleAlias, String actionCode, Criteria criteria) {
        return readScope(moduleAlias, ActionExecutionContext.ofActionCode(
                moduleAlias, actionCode, Set.of(), CurrentUserContext.currentUser()).actionPolicy(), criteria);
    }

    private DataScopeCriteriaResult readScope(String moduleAlias, ActionExecutionPolicy policy, Criteria criteria) {
        return dataScopeCriteriaService.resolveReadScope(moduleAlias, policy,
                criteria == null ? Criteria.of() : criteria,
                CurrentUserContext.currentUser());
    }

    Criteria idsCriteria(Collection<String> ids) {
        Set<String> normalized = normalizeRecordIds(ids);
        if (normalized.isEmpty()) {
            return Criteria.of().raw(net.ximatai.muyun.database.core.orm.SqlRawCondition.of("1 = 0", Map.of()));
        }
        return normalized.size() == 1
                ? Criteria.of().eq("id", normalized.iterator().next())
                : Criteria.of().in("id", List.copyOf(normalized));
    }

    boolean recordVisible(String moduleAlias, String entityAlias, DataScopeCriteriaResult scope, String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return !withTenantScope(scope, () -> entityService(moduleAlias, entityAlias)
                .list(scope.criteria(), new PageRequest(0, 1))).isEmpty();
    }

    Set<String> visibleRecordIds(String moduleAlias,
                                         String entityAlias,
                                         DataScopeCriteriaResult scope,
                                         Collection<String> ids) {
        Set<String> normalized = normalizeRecordIds(ids);
        if (normalized.isEmpty()) {
            return Set.of();
        }
        Set<String> loaded = withTenantScope(scope, () -> entityService(moduleAlias, entityAlias)
                .list(scope.criteria(), new PageRequest(0, normalized.size()))
                .stream()
                .map(DynamicRecord::getId)
                .filter(normalized::contains)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        normalized.stream()
                .filter(loaded::contains)
                .forEach(ordered::add);
        return ordered;
    }

    <R> R withTenantScope(DataScopeCriteriaResult scope, Supplier<R> supplier) {
        if (scope.crossTenant()) {
            try (TenantContext.Scope ignored = TenantContext.bypassTenantFilter("data scope allows cross-tenant read")) {
                return supplier.get();
            }
        }
        return supplier.get();
    }

    private List<String> visibleTreeIds(String moduleAlias, String entityAlias, Collection<String> ids) {
        DataScopeCriteriaResult scope = readScope(moduleAlias, PlatformAction.TREE, idsCriteria(ids));
        return List.copyOf(visibleRecordIds(moduleAlias, entityAlias, scope, ids));
    }


    private DataScopeCriteriaResult requireBusinessRecordMutation(String moduleAlias,
                                                                  String entityAlias,
                                                                  PlatformAction action,
                                                                  Set<String> recordIds) {
        requireRecordAction(moduleAlias, action, recordIds);
        return requireRecordDataScope(moduleAlias, entityAlias, action, recordIds);
    }

    private void requireRecordAction(String moduleAlias, PlatformAction action, Set<String> recordIds) {
        actionExecutionPolicyService.requireRecordAction(ActionExecutionContext.ofPlatformAction(
                moduleAlias,
                action,
                recordIds,
                CurrentUserContext.currentUser()
        ));
    }

    void requireAction(String moduleAlias, PlatformAction action) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        actionExecutionPolicyService.requireAuthorized(ActionExecutionContext.ofPlatformAction(
                moduleAlias,
                action,
                Set.of(),
                CurrentUserContext.currentUser()
        ));
    }

    private DataScopeCriteriaResult requireRecordDataScope(String moduleAlias,
                                                           String entityAlias,
                                                           PlatformAction action,
                                                           Set<String> recordIds) {
        if (!supportsCapability(moduleAlias, entityAlias, EntityCapability.DATA_SCOPE)) {
            return DataScopeCriteriaResult.unrestricted(Criteria.of());
        }
        Set<String> normalized = normalizeRecordIds(recordIds);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("record action requires record ids: " + moduleAlias + "." + action.code());
        }
        ActionExecutionContext context = ActionExecutionContext.ofPlatformAction(
                moduleAlias,
                action,
                normalized,
                CurrentUserContext.currentUser()
        );
        if (!context.actionPolicy().requiresDataScope()) {
            return DataScopeCriteriaResult.unrestricted(Criteria.of());
        }
        return requireActionRecordDataScope(moduleAlias, entityAlias, context.actionPolicy(), normalized);
    }

    private DataScopeCriteriaResult requireActionRecordDataScope(String moduleAlias,
                                                                 String entityAlias,
                                                                 ActionExecutionPolicy policy,
                                                                 Collection<String> recordIds) {
        if (!supportsCapability(moduleAlias, entityAlias, EntityCapability.DATA_SCOPE)) {
            return DataScopeCriteriaResult.unrestricted(Criteria.of());
        }
        Set<String> normalized = normalizeRecordIds(recordIds);
        if (!policy.requiresDataScope() || normalized.isEmpty()) {
            return DataScopeCriteriaResult.unrestricted(Criteria.of());
        }
        Criteria idCriteria = normalized.size() == 1
                ? Criteria.of().eq("id", normalized.iterator().next())
                : Criteria.of().in("id", List.copyOf(normalized));
        DataScopeCriteriaResult scope = readScope(moduleAlias, policy, idCriteria);
        long visible = withTenantScope(scope, () -> entityService(moduleAlias, entityAlias)
                .list(scope.criteria(), new PageRequest(0, normalized.size()))
                .stream()
                .map(DynamicRecord::getId)
                .filter(normalized::contains)
                .distinct()
                .count());
        if (visible != normalized.size()) {
            throw new PlatformException("record data permission denied: " + moduleAlias + "." + policy.actionCode());
        }
        return scope;
    }

    private String actionAuthorizationFailure(String moduleAlias, ActionExecutionPolicy policy) {
        try {
            actionExecutionPolicyService.authorizeAction(moduleAlias, policy, CurrentUserContext.currentUser());
            return null;
        } catch (PlatformException exception) {
            return exception.getMessage() == null || exception.getMessage().isBlank()
                    ? "no action auth"
                    : exception.getMessage();
        }
    }

    private Set<String> visibleActionRecordIdsInternal(String moduleAlias,
                                                       String entityAlias,
                                                       ActionExecutionPolicy policy,
                                                       Set<String> recordIds) {
        if (!policy.requiresDataScope() || !supportsCapability(moduleAlias, entityAlias, EntityCapability.DATA_SCOPE)) {
            return recordIds;
        }
        try {
            DataScopeCriteriaResult scope = readScope(moduleAlias, policy, idsCriteria(recordIds));
            return visibleRecordIds(moduleAlias, entityAlias, scope, recordIds);
        } catch (PlatformException | IllegalArgumentException ignored) {
            return Set.of();
        }
    }

    private DynamicActionAvailability actionAuthorizationAvailability(String moduleAlias,
                                                                      String entityAlias,
                                                                      DynamicActionDescriptor action,
                                                                      Collection<String> recordIds) {
        ActionExecutionPolicy policy = actionPolicy(action);
        Set<String> normalizedIds = normalizeRecordIds(recordIds);
        try {
            actionExecutionPolicyService.authorizeAction(moduleAlias, policy, CurrentUserContext.currentUser());
            requireActionRecordDataScope(moduleAlias, entityAlias, policy, normalizedIds);
            return DynamicActionAvailability.available(action.code());
        } catch (PlatformException e) {
            return DynamicActionAvailability.unavailable(action.code(), e.getMessage());
        }
    }

    private Set<String> normalizeRecordIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        ids.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .forEach(normalized::add);
        return java.util.Collections.unmodifiableSet(normalized);
    }

    private Set<String> normalizeRecordId(String id) {
        return normalizeRecordIds(id == null ? null : java.util.Collections.singletonList(id));
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

    private Criteria actionExecutionCriteria(Criteria criteria, Collection<String> recordIds) {
        Criteria scoped = Criteria.of();
        if (criteria != null && !criteria.isEmpty()) {
            scoped.andGroup(criteria.getRoot());
        }
        Set<String> normalized = normalizeRecordIds(recordIds);
        if (normalized.isEmpty()) {
            return scoped;
        }
        if (normalized.size() == 1) {
            return scoped.eq("id", normalized.iterator().next());
        }
        return scoped.in("id", List.copyOf(normalized));
    }

    private PlatformActionLevel toPlatformLevel(net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel level) {
        if (level == null) {
            return PlatformActionLevel.DEFAULT;
        }
        return switch (level) {
            case LIST -> PlatformActionLevel.LIST;
            case RECORD -> PlatformActionLevel.RECORD;
            case BATCH -> PlatformActionLevel.BATCH;
            case ANY -> PlatformActionLevel.ANY;
        };
    }

    private ActionAccessMode toAccessMode(net.ximatai.muyun.spring.dynamic.metadata.EntityActionAccessMode accessMode) {
        if (accessMode == null) {
            return ActionAccessMode.AUTH_REQUIRED;
        }
        return switch (accessMode) {
            case AUTH_REQUIRED -> ActionAccessMode.AUTH_REQUIRED;
            case LOGIN_REQUIRED -> ActionAccessMode.LOGIN_REQUIRED;
            case ANONYMOUS_ALLOWED -> ActionAccessMode.ANONYMOUS_ALLOWED;
        };
    }

    DynamicEntityService entityService(String moduleAlias, String entityAlias) {
        return runtime.entityService(moduleAlias, entityAlias);
    }

    private boolean supportsCapability(String moduleAlias, String entityAlias, EntityCapability capability) {
        return findEntity(describe(moduleAlias), entityAlias).capabilities().contains(capability.name());
    }

    void requireCapability(String moduleAlias, String entityAlias, EntityCapability capability) {
        if (!supportsCapability(moduleAlias, entityAlias, capability)) {
            throw new PlatformException("dynamic entity does not support capability: " + capability);
        }
    }

    private DynamicRecordEventPublisher.DynamicRecordEventContext eventContext(String moduleAlias,
                                                                               String entityAlias,
                                                                               RuntimeMutationSource mutationSource,
                                                                               String traceId) {
        DynamicMutationContext mutationContext = DynamicMutationContext.current().orElse(null);
        boolean writeBack = mutationSource == RuntimeMutationSource.WRITE_BACK && mutationContext != null;
        return new DynamicRecordEventPublisher.DynamicRecordEventContext(
                moduleAlias,
                entityAlias,
                writeBack ? mutationContext.traceId() : traceId,
                TenantContext.currentTenantId().orElse(null),
                TenantContext.isSystem(),
                TenantContext.systemReason().orElse(null),
                mutationSource,
                writeBack ? mutationContext.depth() : 0,
                writeBack ? mutationContext.parentExecutionId() : null,
                !writeBack || mutationContext.cascadeAllowed()
        );
    }

    private DynamicEntityDescriptor findEntity(DynamicModuleDescriptor descriptor, String entityAlias) {
        return descriptor.entities().stream()
                .filter(entity -> entity.entityAlias().equals(entityAlias))
                .findFirst()
                .orElseThrow(() -> new ModuleDefinitionException("unknown dynamic entity: "
                        + descriptor.moduleAlias() + "." + entityAlias));
    }

    private DynamicActionDescriptor findAction(DynamicModuleDescriptor module, String actionCode) {
        return module.actions().stream()
                .filter(action -> action.code().equals(actionCode))
                .findFirst()
                .orElseThrow(() -> new ModuleDefinitionException("unknown dynamic action: "
                        + module.moduleAlias() + "." + actionCode));
    }

    private boolean hasAction(DynamicEntityDescriptor entity, String actionCode) {
        return entity.actions().stream().anyMatch(action -> action.code().equals(actionCode));
    }

    private DynamicActionDescriptor findAction(String moduleAlias, DynamicEntityDescriptor entity, String actionCode) {
        return entity.actions().stream()
                .filter(action -> action.code().equals(actionCode))
                .findFirst()
                .orElseThrow(() -> new ModuleDefinitionException("unknown dynamic action: "
                        + moduleAlias + "." + entity.entityAlias() + "." + actionCode));
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
