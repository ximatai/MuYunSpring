package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.TransactionScopeSupport;
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
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicAssociationRelationItem;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicAssociationRelationOverview;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicAssociationViewDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicReferenceDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicReferenceFilterDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicRelationDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicViewDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.AssociationViewQueryMappingGroupOperator;
import net.ximatai.muyun.spring.dynamic.metadata.AssociationViewQueryMappingSourceType;
import net.ximatai.muyun.spring.dynamic.metadata.AssociationViewRootQueryMapping;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType;
import net.ximatai.muyun.spring.dynamic.metadata.EntityViewType;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicQueryOperator;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;
import net.ximatai.muyun.spring.dynamic.openapi.DynamicOpenApiDocument;
import net.ximatai.muyun.spring.dynamic.openapi.DynamicOpenApiGenerator;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
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
        return describe(moduleAlias).associationViews();
    }

    public List<DynamicAssociationViewDescriptor> associationViews(String moduleAlias, String entityAlias) {
        return entityDescriptor(moduleAlias, entityAlias).associationViews();
    }

    public DynamicAssociationViewDescriptor associationView(String moduleAlias, String entityAlias, String viewCode) {
        return findAssociationView(moduleAlias, entityDescriptor(moduleAlias, entityAlias), viewCode);
    }

    public PageResult<DynamicRecord> associationViewPage(String moduleAlias,
                                                         String entityAlias,
                                                         String sourceRecordId,
                                                         String viewCode,
                                                         Criteria criteria,
                                                         PageRequest pageRequest,
                                                         Sort... sorts) {
        DynamicAssociationViewDescriptor view = associationView(moduleAlias, entityAlias, viewCode);
        if (!view.queryable()) {
            throw new PlatformException("dynamic association view is not queryable: " + moduleAlias + "." + viewCode);
        }
        DynamicRecord source = select(moduleAlias, entityAlias, sourceRecordId);
        if (source == null) {
            throw new PlatformException("dynamic association source record does not exist: " + sourceRecordId);
        }
        Criteria associationCriteria = associationCriteria(moduleAlias, entityAlias, source, view);
        Criteria targetCriteria = associationTargetCriteria(source, view, associationCriteria, criteria);
        return page(view.targetModuleAlias(), view.targetEntityAlias(), targetCriteria, pageRequest, sorts);
    }

    public DynamicAssociationRelationOverview associationRelationOverview(String moduleAlias) {
        DynamicModuleDescriptor descriptor = describe(moduleAlias);
        Map<String, String> viewByRelation = new LinkedHashMap<>();
        Map<String, String> viewByReference = new LinkedHashMap<>();
        for (DynamicAssociationViewDescriptor view : descriptor.associationViews()) {
            if (view.relationCode() != null && !view.relationCode().isBlank()) {
                viewByRelation.put(view.sourceEntityAlias() + "." + view.relationCode(), view.code());
            }
            if (view.referenceField() != null && !view.referenceField().isBlank()) {
                viewByReference.put(view.sourceEntityAlias() + "." + view.referenceField(), view.code());
            }
        }
        List<DynamicAssociationRelationItem> downstream = new ArrayList<>();
        List<DynamicAssociationRelationItem> upstream = new ArrayList<>();
        for (DynamicRelationDescriptor relation : descriptor.relations()) {
            String viewCode = viewByRelation.get(relation.parentEntityAlias() + "." + relation.code());
            downstream.add(new DynamicAssociationRelationItem("RELATION", relation.code(), moduleAlias,
                    relation.parentEntityAlias(), moduleAlias, relation.childEntityAlias(), viewCode));
            upstream.add(new DynamicAssociationRelationItem("RELATION", relation.code(), moduleAlias,
                    relation.childEntityAlias(), moduleAlias, relation.parentEntityAlias(), viewCode));
        }
        for (DynamicReferenceDescriptor reference : descriptor.references()) {
            String viewCode = viewByReference.get(reference.sourceEntityAlias() + "." + reference.sourceField());
            downstream.add(new DynamicAssociationRelationItem("REFERENCE", reference.sourceField(), moduleAlias,
                    reference.sourceEntityAlias(), reference.targetModuleAlias(), reference.targetEntityAlias(), viewCode));
            if (moduleAlias.equals(reference.targetModuleAlias())) {
                upstream.add(new DynamicAssociationRelationItem("REFERENCE", reference.sourceField(), moduleAlias,
                        reference.targetEntityAlias(), moduleAlias, reference.sourceEntityAlias(), viewCode));
            }
        }
        return new DynamicAssociationRelationOverview(moduleAlias, upstream, downstream);
    }

    public List<DynamicAssociationViewDescriptor> associationViewDesignDescriptors(String moduleAlias) {
        return associationViews(moduleAlias);
    }

    public DynamicAssociationViewDiagnosis diagnoseAssociationView(String moduleAlias,
                                                                   String entityAlias,
                                                                   String sourceRecordId,
                                                                   String viewCode,
                                                                   Criteria criteria) {
        DynamicAssociationViewDescriptor view = associationView(moduleAlias, entityAlias, viewCode);
        if (!view.queryable()) {
            throw new PlatformException("dynamic association view is not queryable: " + moduleAlias + "." + viewCode);
        }
        DynamicRecord source = select(moduleAlias, entityAlias, sourceRecordId);
        if (source == null) {
            throw new PlatformException("dynamic association source record does not exist: " + sourceRecordId);
        }
        Criteria associationCriteria = associationCriteria(moduleAlias, entityAlias, source, view);
        Criteria targetCriteria = associationTargetCriteria(source, view, associationCriteria, criteria);
        long targetCount = count(view.targetModuleAlias(), view.targetEntityAlias(), targetCriteria);
        DynamicAssociationViewDiagnosisStatus status = diagnosisStatus(view, targetCount);
        return new DynamicAssociationViewDiagnosis(view, associationCriteria, criteria == null ? Criteria.of() : criteria,
                targetCriteria, targetCount, status, diagnosisMessage(view, status, targetCount));
    }

    public List<DynamicRelationDescriptor> relations(String moduleAlias) {
        return describe(moduleAlias).relations();
    }

    public List<DynamicReferenceDescriptor> references(String moduleAlias) {
        return describe(moduleAlias).references();
    }

    private Criteria associationCriteria(String moduleAlias,
                                         String entityAlias,
                                         DynamicRecord source,
                                         DynamicAssociationViewDescriptor view) {
        if (view.relationCode() != null && !view.relationCode().isBlank()) {
            DynamicRelationDescriptor relation = relations(moduleAlias).stream()
                    .filter(item -> item.code().equals(view.relationCode())
                            && item.parentEntityAlias().equals(entityAlias)
                            && item.childEntityAlias().equals(view.targetEntityAlias()))
                    .findFirst()
                    .orElseThrow(() -> new ModuleDefinitionException("unknown dynamic association relation: "
                            + moduleAlias + "." + view.code()));
            return Criteria.of().eq(relation.childForeignKeyField(), source.getId());
        }
        DynamicReferenceDescriptor reference = reference(moduleAlias, entityAlias, view.referenceField());
        String keyField = reference.keyField() == null || reference.keyField().isBlank()
                ? "id"
                : reference.keyField();
        Object value = source.getValue(reference.sourceField());
        if (value == null) {
            return falseCriteria();
        }
        if (value instanceof Collection<?> collection) {
            List<?> values = collection.stream()
                    .filter(item -> item != null && !String.valueOf(item).isBlank())
                    .toList();
            return values.isEmpty() ? falseCriteria() : Criteria.of().in(keyField, values);
        }
        if (String.valueOf(value).isBlank()) {
            return falseCriteria();
        }
        return Criteria.of().eq(keyField, value);
    }

    private Criteria rootQueryMappingCriteria(DynamicRecord source, DynamicAssociationViewDescriptor view) {
        AssociationViewRootQueryMapping mapping = view.rootQueryMapping();
        if (mapping == null) {
            return Criteria.of();
        }
        return mappingCriteria(source, view.targetModuleAlias(), view.targetEntityAlias(), mapping);
    }

    private Criteria associationTargetCriteria(DynamicRecord source,
                                               DynamicAssociationViewDescriptor view,
                                               Criteria associationCriteria,
                                               Criteria requestCriteria) {
        Criteria targetCriteria = andCriteria(associationCriteria, rootQueryMappingCriteria(source, view));
        return andCriteria(targetCriteria, requestCriteria);
    }

    private Criteria mappingCriteria(DynamicRecord source,
                                     String targetModuleAlias,
                                     String targetEntityAlias,
                                     AssociationViewRootQueryMapping mapping) {
        if (mapping == null) {
            return Criteria.of();
        }
        if (mapping.leaf()) {
            Object value = mappingValue(source, mapping);
            if (value == null && mapping.operator() != DynamicQueryOperator.NULL
                    && mapping.operator() != DynamicQueryOperator.NOT_NULL) {
                return falseCriteria();
            }
            return queryCriteria(targetModuleAlias, targetEntityAlias, List.of(
                    new DynamicQueryCondition(mapping.targetField(), mapping.operator(), mappingValues(mapping, value))));
        }
        Criteria criteria = Criteria.of();
        for (AssociationViewRootQueryMapping child : mapping.children()) {
            Criteria childCriteria = mappingCriteria(source, targetModuleAlias, targetEntityAlias, child);
            if (childCriteria.isEmpty()) {
                continue;
            }
            if (mapping.groupOperator() == AssociationViewQueryMappingGroupOperator.OR) {
                criteria.orGroup(childCriteria.getRoot());
            } else {
                criteria.andGroup(childCriteria.getRoot());
            }
        }
        return criteria;
    }

    private List<?> mappingValues(AssociationViewRootQueryMapping mapping, Object value) {
        return switch (mapping.operator()) {
            case NULL, NOT_NULL -> List.of();
            case IN, NOT_IN -> value instanceof Collection<?> collection ? List.copyOf(collection) : List.of(value);
            case BETWEEN -> value instanceof Collection<?> collection ? List.copyOf(collection) : List.of(value);
            default -> List.of(value);
        };
    }

    private Object mappingValue(DynamicRecord source, AssociationViewRootQueryMapping mapping) {
        AssociationViewQueryMappingSourceType sourceType = mapping.sourceType();
        if (sourceType == null) {
            throw new ModuleDefinitionException("association rootQueryMapping source type is required");
        }
        return switch (sourceType) {
            case SOURCE_FIELD -> source.getValue(mapping.sourceField());
            case SYSTEM_VARIABLE -> systemVariableValue(source, mapping.systemVariable());
            case CONSTANT -> mapping.constantValue();
        };
    }

    private Object systemVariableValue(DynamicRecord source, String systemVariable) {
        if (systemVariable == null || systemVariable.isBlank()) {
            throw new ModuleDefinitionException("association rootQueryMapping system variable is required");
        }
        return switch (systemVariable.trim()) {
            case "source.id", "sourceId" -> source.getId();
            default -> throw new ModuleDefinitionException("unsupported association rootQueryMapping system variable: "
                    + systemVariable);
        };
    }

    private DynamicAssociationViewDiagnosisStatus diagnosisStatus(DynamicAssociationViewDescriptor view, long targetCount) {
        if (view.viewType() == EntityViewType.FORM) {
            if (targetCount == 0) {
                return DynamicAssociationViewDiagnosisStatus.FORM_NOT_FOUND;
            }
            if (targetCount > 1) {
                return DynamicAssociationViewDiagnosisStatus.FORM_NOT_UNIQUE;
            }
            return DynamicAssociationViewDiagnosisStatus.OK;
        }
        return targetCount == 0 ? DynamicAssociationViewDiagnosisStatus.EMPTY : DynamicAssociationViewDiagnosisStatus.OK;
    }

    private String diagnosisMessage(DynamicAssociationViewDescriptor view,
                                    DynamicAssociationViewDiagnosisStatus status,
                                    long targetCount) {
        return switch (status) {
            case OK -> "association view target matched";
            case EMPTY -> "association view target is empty";
            case FORM_NOT_FOUND -> "association view FORM target not found";
            case FORM_NOT_UNIQUE -> "association view FORM target must be unique, but matched " + targetCount;
        };
    }

    private Criteria andCriteria(Criteria left, Criteria right) {
        if (left == null || left.isEmpty()) {
            return right == null ? Criteria.of() : right;
        }
        if (right == null || right.isEmpty()) {
            return left;
        }
        Criteria criteria = Criteria.of();
        criteria.andGroup(left.getRoot());
        criteria.andGroup(right.getRoot());
        return criteria;
    }

    private Criteria falseCriteria() {
        return Criteria.of().raw(net.ximatai.muyun.database.core.orm.SqlRawCondition.of("1 = 0", Map.of()));
    }

    public List<DynamicReferenceDescriptor> references(String moduleAlias, String entityAlias) {
        return describe(moduleAlias).references().stream()
                .filter(reference -> reference.sourceEntityAlias().equals(entityAlias))
                .toList();
    }

    public DynamicReferenceDescriptor reference(String moduleAlias, String entityAlias, String sourceField) {
        return references(moduleAlias, entityAlias).stream()
                .filter(reference -> reference.sourceField().equals(sourceField))
                .findFirst()
                .orElseThrow(() -> new ModuleDefinitionException("unknown dynamic reference: "
                        + moduleAlias + "." + entityAlias + "." + sourceField));
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
        requireCapability(moduleAlias, entityAlias, EntityCapability.REFERENCE);
        DataScopeCriteriaResult scope = readScope(moduleAlias, PlatformAction.VIEW, Criteria.of().eq("id", id));
        if (!recordVisible(moduleAlias, entityAlias, scope, id)) {
            return null;
        }
        return withTenantScope(scope, () -> entityService(moduleAlias, entityAlias).title(id));
    }

    public Map<String, String> titles(String moduleAlias, String entityAlias, Collection<String> ids) {
        requireCapability(moduleAlias, entityAlias, EntityCapability.REFERENCE);
        DataScopeCriteriaResult scope = readScope(moduleAlias, PlatformAction.VIEW, idsCriteria(ids));
        Set<String> visibleIds = visibleRecordIds(moduleAlias, entityAlias, scope, ids);
        return withTenantScope(scope, () -> entityService(moduleAlias, entityAlias).titles(visibleIds));
    }

    public Map<String, Map<String, Object>> projections(String moduleAlias,
                                                        String entityAlias,
                                                        Collection<String> ids,
                                                        Collection<String> fieldNames) {
        requireCapability(moduleAlias, entityAlias, EntityCapability.REFERENCE);
        DataScopeCriteriaResult scope = readScope(moduleAlias, PlatformAction.VIEW, idsCriteria(ids));
        Set<String> visibleIds = visibleRecordIds(moduleAlias, entityAlias, scope, ids);
        return withTenantScope(scope, () -> entityService(moduleAlias, entityAlias).projections(visibleIds, fieldNames));
    }

    public PageResult<ReferenceOption> referenceOptions(String moduleAlias,
                                                        String entityAlias,
                                                        Criteria criteria,
                                                        PageRequest pageRequest) {
        DataScopeCriteriaResult scope = readScope(moduleAlias, PlatformAction.REFERENCE, criteria);
        return withTenantScope(scope, () -> entityService(moduleAlias, entityAlias)
                .referenceOptions(scope.criteria(), pageRequest));
    }

    private DataScopeCriteriaResult readScope(String moduleAlias, PlatformAction action, Criteria criteria) {
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

    private Criteria idsCriteria(Collection<String> ids) {
        Set<String> normalized = normalizeRecordIds(ids);
        if (normalized.isEmpty()) {
            return Criteria.of().raw(net.ximatai.muyun.database.core.orm.SqlRawCondition.of("1 = 0", Map.of()));
        }
        return normalized.size() == 1
                ? Criteria.of().eq("id", normalized.iterator().next())
                : Criteria.of().in("id", List.copyOf(normalized));
    }

    private boolean recordVisible(String moduleAlias, String entityAlias, DataScopeCriteriaResult scope, String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return !withTenantScope(scope, () -> entityService(moduleAlias, entityAlias)
                .list(scope.criteria(), new PageRequest(0, 1))).isEmpty();
    }

    private Set<String> visibleRecordIds(String moduleAlias,
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

    private <R> R withTenantScope(DataScopeCriteriaResult scope, Supplier<R> supplier) {
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
        DynamicReferenceDescriptor reference = reference(moduleAlias, entityAlias, sourceField);
        DynamicReferenceResolveRequest normalized = request == null
                ? DynamicReferenceResolveRequest.query(null)
                : request;
        Criteria criteria = referenceCriteria(normalized.criteria(), reference, normalized.formValues());
        DynamicReferenceResolveRequest effective = normalized.withCriteria(criteria);
        DataScopeCriteriaResult scope = readScope(reference.targetModuleAlias(), PlatformAction.REFERENCE, criteria);
        return withTenantScope(scope, () -> entityService(moduleAlias, entityAlias)
                .resolveReference(sourceField, effective.withCriteria(scope.criteria())));
    }

    public DynamicReferenceResolveResponse resolveFieldReference(String moduleAlias,
                                                                 String entityAlias,
                                                                 String fieldName,
                                                                 DynamicReferenceResolveRequest request) {
        return resolveReference(moduleAlias, entityAlias, fieldName, request);
    }

    private Criteria referenceCriteria(Criteria base,
                                       DynamicReferenceDescriptor reference,
                                       Map<String, Object> formValues) {
        Criteria criteria = Criteria.of();
        if (base != null && !base.isEmpty()) {
            criteria.andGroup(base.getRoot());
        }
        if (reference.filters().isEmpty() || formValues == null || formValues.isEmpty()) {
            return criteria;
        }
        for (DynamicReferenceFilterDescriptor filter : reference.filters()) {
            Object value = formValues.get(filter.formField());
            if (isBlankReferenceFilterValue(value)) {
                continue;
            }
            appendReferenceFilter(criteria, filter, value);
        }
        return criteria;
    }

    private boolean isBlankReferenceFilterValue(Object value) {
        return value == null || (value instanceof String text && text.isBlank());
    }

    private void appendReferenceFilter(Criteria criteria,
                                       DynamicReferenceFilterDescriptor filter,
                                       Object value) {
        String fieldName = filter.referenceField();
        DynamicQueryOperator operator = filter.operator() == null ? DynamicQueryOperator.EQ : filter.operator();
        switch (operator) {
            case EQ -> criteria.eq(fieldName, value);
            case LIKE -> criteria.like(fieldName, String.valueOf(value));
            case IN -> criteria.in(fieldName, referenceFilterValues(value));
            case BETWEEN -> {
                List<?> values = referenceFilterValues(value);
                if (values.size() != 2) {
                    throw new ModuleDefinitionException("reference filter BETWEEN requires exactly two values: "
                            + filter.formField() + " -> " + fieldName);
                }
                criteria.between(fieldName, values.get(0), values.get(1));
            }
            case GT -> criteria.gt(fieldName, value);
            case GTE -> criteria.gte(fieldName, value);
            case LT -> criteria.lt(fieldName, value);
            case LTE -> criteria.lte(fieldName, value);
        }
    }

    private List<?> referenceFilterValues(Object value) {
        if (value instanceof Collection<?> collection) {
            return List.copyOf(collection);
        }
        if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> values = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                values.add(java.lang.reflect.Array.get(value, i));
            }
            return values;
        }
        return List.of(value);
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

    private void requireCapability(String moduleAlias, String entityAlias, EntityCapability capability) {
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

    private DynamicAssociationViewDescriptor findAssociationView(String moduleAlias,
                                                                DynamicEntityDescriptor entity,
                                                                String viewCode) {
        return entity.associationViews().stream()
                .filter(view -> view.code().equals(viewCode))
                .findFirst()
                .orElseThrow(() -> new ModuleDefinitionException("unknown dynamic association view: "
                        + moduleAlias + "." + entity.entityAlias() + "." + viewCode));
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
