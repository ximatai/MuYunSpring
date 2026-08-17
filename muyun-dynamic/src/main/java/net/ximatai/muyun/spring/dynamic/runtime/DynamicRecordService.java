package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.TransactionScopeSupport;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.ability.event.RuntimeMutationSource;
import net.ximatai.muyun.spring.ability.reference.ReferenceOption;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.id.Ids;
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
import java.util.Arrays;
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
    private final DynamicRecordMutationCoordinator mutationCoordinator;
    private final Clock mutationClock;

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
        this.mutationCoordinator = mutationCoordinator == null ? DynamicRecordMutationCoordinator.NONE : mutationCoordinator;
        this.mutationClock = mutationClock == null ? Clock.systemDefaultZone() : mutationClock;
    }

    public DynamicRecord newRecord(String moduleAlias, String entityAlias) {
        return runtime.newRecord(moduleAlias, entityAlias);
    }

    public DynamicModuleDescriptor describe(String moduleAlias) {
        return runtime.describe(moduleAlias);
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
        return entityService(moduleAlias, entityAlias).previewFormula(record);
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
        DynamicModuleDescriptor descriptor = describe(moduleAlias);
        findAction(descriptor, actionCode);
        String entityAlias = actionEntityAlias(moduleAlias, actionCode);
        return entityService(moduleAlias, entityAlias)
                .actionAvailability(actionCode, record);
    }

    public DynamicActionAvailability actionAuthorizationAvailability(String moduleAlias,
                                                                     String actionCode,
                                                                     Collection<String> recordIds) {
        DynamicModuleDescriptor module = describe(moduleAlias);
        DynamicActionDescriptor action = findAction(module, actionCode);
        String entityAlias = actionEntityAlias(moduleAlias, actionCode);
        return actionAuthorizationAvailability(moduleAlias, entityAlias, action, recordIds);
    }

    public DynamicActionExecutionResult executeAction(String moduleAlias,
                                                      String actionCode,
                                                      DynamicActionExecutionRequest request) {
        DynamicModuleDescriptor module = describe(moduleAlias);
        DynamicActionDescriptor action = findAction(module, actionCode);
        String entityAlias = actionEntityAlias(moduleAlias, actionCode);
        return executeAction(moduleAlias, entityAlias, action, request);
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
        findAction(moduleAlias, entityDescriptor(moduleAlias, entityAlias), actionCode);
        return entityService(moduleAlias, entityAlias).actionAvailability(actionCode, record);
    }

    public DynamicActionAvailability actionAuthorizationAvailability(String moduleAlias,
                                                                     String entityAlias,
                                                                     String actionCode,
                                                                     Collection<String> recordIds) {
        DynamicActionDescriptor action = findAction(moduleAlias, entityDescriptor(moduleAlias, entityAlias), actionCode);
        return actionAuthorizationAvailability(moduleAlias, entityAlias, action, recordIds);
    }

    public DynamicActionExecutionResult executeAction(String moduleAlias,
                                                      String entityAlias,
                                                      String actionCode,
                                                      DynamicActionExecutionRequest request) {
        DynamicActionDescriptor action = findAction(moduleAlias, entityDescriptor(moduleAlias, entityAlias), actionCode);
        return executeAction(moduleAlias, entityAlias, action, request);
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
        return create(moduleAlias, entityAlias, record, mutationMetadata(record));
    }

    @Transactional
    public String create(String moduleAlias,
                         String entityAlias,
                         DynamicRecord record,
                         Map<String, Object> mutationMetadata) {
        return create(moduleAlias, entityAlias, record, RuntimeMutationSource.BUSINESS, null, mutationMetadata);
    }

    String createFromAction(String moduleAlias, String entityAlias, DynamicRecord record, String traceId) {
        return create(moduleAlias, entityAlias, record, RuntimeMutationSource.ACTION, traceId, Map.of());
    }

    private String create(String moduleAlias,
                          String entityAlias,
                          DynamicRecord record,
                          RuntimeMutationSource mutationSource,
                          String traceId,
                          Map<String, Object> mutationMetadata) {
        try (DynamicMutationContext mutationContext = DynamicMutationContext.open(mutationClock, mutationSource,
                traceId, mutationMetadata)) {
            if (mutationSource == RuntimeMutationSource.BUSINESS) {
                actionExecutionPolicyService.requireAuthorized(ActionExecutionContext.ofPlatformAction(
                        moduleAlias,
                        PlatformAction.CREATE,
                        Set.of(),
                        CurrentUserContext.currentUser()
                ));
            }
            mutationCoordinator.beforeCreate(moduleAlias, entityAlias, record);
            List<RelationChildMutation> childMutations = prepareRelationChildrenForCreate(moduleAlias, entityAlias, record);
            childMutations.forEach(mutation -> mutationCoordinator.beforeRelationChildCreate(
                    moduleAlias,
                    entityAlias,
                    mutation.relation().code(),
                    mutation.relation().childEntityAlias(),
                    record,
                    mutation.incoming()
            ));
            String id = entityService(moduleAlias, entityAlias).insert(record);
            mutationCoordinator.afterCreate(moduleAlias, entityAlias, record, id);
            mutationCoordinator.afterMutation(mutationEvent(
                    DynamicRecordMutationEventType.AFTER_SAVE,
                    moduleAlias,
                    entityAlias,
                    id,
                    DynamicRecordSaveOperation.CREATE,
                    null,
                    record,
                    mutationContext
            ));
            childMutations.forEach(mutation -> mutationCoordinator.afterRelationChildCreate(
                    moduleAlias,
                    entityAlias,
                    mutation.relation().code(),
                    mutation.relation().childEntityAlias(),
                    record,
                    mutation.incoming(),
                    mutation.incoming().getId()
            ));
            eventPublisher.created(eventContext(moduleAlias, entityAlias, mutationSource, traceId), id);
            return id;
        }
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

    @Transactional
    public int update(String moduleAlias, String entityAlias, DynamicRecord record) {
        return update(moduleAlias, entityAlias, record, mutationMetadata(record));
    }

    @Transactional
    public int update(String moduleAlias,
                      String entityAlias,
                      DynamicRecord record,
                      Map<String, Object> mutationMetadata) {
        return update(moduleAlias, entityAlias, record, RuntimeMutationSource.BUSINESS, null, mutationMetadata);
    }

    @Transactional
    public int updateSystem(String moduleAlias, String entityAlias, DynamicRecord record, String systemReason) {
        try (TenantContext.Scope ignored = TenantContext.system(systemReason)) {
            return update(moduleAlias, entityAlias, record, RuntimeMutationSource.SYSTEM, null, Map.of());
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
        try (DynamicMutationContext ignored = DynamicMutationContext.openWriteBack(mutationClock,
                writeBackContext, mutationMetadata)) {
            DynamicMutationContext context = DynamicMutationContext.current().orElseThrow();
            return create(moduleAlias, entityAlias, record, RuntimeMutationSource.WRITE_BACK,
                    context.traceId(), mutationMetadata);
        }
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
        try (DynamicMutationContext ignored = DynamicMutationContext.openWriteBack(mutationClock,
                writeBackContext, mutationMetadata)) {
            DynamicMutationContext context = DynamicMutationContext.current().orElseThrow();
            return update(moduleAlias, entityAlias, record, RuntimeMutationSource.WRITE_BACK,
                    context.traceId(), mutationMetadata);
        }
    }

    int updateFromAction(String moduleAlias, String entityAlias, DynamicRecord record, String traceId) {
        return update(moduleAlias, entityAlias, record, RuntimeMutationSource.ACTION, traceId, Map.of());
    }

    void validateImportFromAction(String moduleAlias, String entityAlias, DynamicRecord record, DynamicRecord existing) {
        DynamicFormulaRuntime formulaRuntime = new DynamicFormulaRuntime(
                moduleAlias, record.getEntity(), runtime.registry().requireModule(moduleAlias));
        if (formulaRuntime.hasImportValidateRules()) {
            formulaRuntime.importValidate(record, existing);
        }
    }

    private int update(String moduleAlias,
                       String entityAlias,
                       DynamicRecord record,
                       RuntimeMutationSource mutationSource,
                       String traceId,
                       Map<String, Object> mutationMetadata) {
        try (DynamicMutationContext mutationContext = DynamicMutationContext.open(mutationClock, mutationSource,
                traceId, mutationMetadata)) {
            if (record == null) {
                throw new PlatformException("dynamic record must not be null");
            }
            DataScopeCriteriaResult mutationScope = DataScopeCriteriaResult.unrestricted(Criteria.of());
            if (mutationSource == RuntimeMutationSource.BUSINESS) {
                Set<String> recordIds = normalizeRecordId(record == null ? null : record.getId());
                mutationScope = requireBusinessRecordMutation(moduleAlias, entityAlias, PlatformAction.UPDATE, recordIds);
            }
            DynamicRecord before = withTenantScope(mutationScope,
                    () -> entityService(moduleAlias, entityAlias).selectActiveRaw(record.getId()));
            mutationCoordinator.beforeUpdate(moduleAlias, entityAlias, before, record);
            List<RelationChildMutation> childMutations = prepareRelationChildrenForUpdate(moduleAlias, entityAlias, before, record);
            childMutations.forEach(mutation -> {
                if (mutation.kind() == RelationChildMutationKind.CREATE) {
                    mutationCoordinator.beforeRelationChildCreate(moduleAlias, entityAlias, mutation.relation().code(),
                            mutation.relation().childEntityAlias(), before, mutation.incoming());
                } else if (mutation.kind() == RelationChildMutationKind.UPDATE) {
                    mutationCoordinator.beforeRelationChildUpdate(moduleAlias, entityAlias, mutation.relation().code(),
                            mutation.relation().childEntityAlias(), before, record, mutation.before(), mutation.incoming());
                } else if (mutation.kind() == RelationChildMutationKind.DELETE) {
                    mutationCoordinator.beforeRelationChildDelete(moduleAlias, entityAlias, mutation.relation().code(),
                            mutation.relation().childEntityAlias(), before, mutation.before());
                }
            });
            int updated = withTenantScope(mutationScope, () -> entityService(moduleAlias, entityAlias).update(record));
            if (updated > 0) {
                mutationCoordinator.afterUpdate(moduleAlias, entityAlias, before, record);
                mutationCoordinator.afterMutation(mutationEvent(
                        DynamicRecordMutationEventType.AFTER_SAVE,
                        moduleAlias,
                        entityAlias,
                        record.getId(),
                        DynamicRecordSaveOperation.UPDATE,
                        before,
                        record,
                        mutationContext
                ));
                childMutations.forEach(mutation -> {
                    if (mutation.kind() == RelationChildMutationKind.CREATE) {
                        mutationCoordinator.afterRelationChildCreate(moduleAlias, entityAlias, mutation.relation().code(),
                                mutation.relation().childEntityAlias(), record, mutation.incoming(), mutation.incoming().getId());
                    } else if (mutation.kind() == RelationChildMutationKind.UPDATE) {
                        mutationCoordinator.afterRelationChildUpdate(moduleAlias, entityAlias, mutation.relation().code(),
                                mutation.relation().childEntityAlias(), before, record, mutation.before(), mutation.incoming());
                    } else if (mutation.kind() == RelationChildMutationKind.DELETE) {
                        mutationCoordinator.afterRelationChildDelete(moduleAlias, entityAlias, mutation.relation().code(),
                                mutation.relation().childEntityAlias(), before, mutation.before());
                    }
                });
                eventPublisher.updated(eventContext(moduleAlias, entityAlias, mutationSource, traceId), record.getId());
            }
            return updated;
        }
    }

    private Map<String, Object> mutationMetadata(DynamicRecord record) {
        return record == null ? Map.of() : record.mutationMetadata();
    }

    private DynamicRecordMutationEvent mutationEvent(DynamicRecordMutationEventType eventType,
                                                     String moduleAlias,
                                                     String entityAlias,
                                                     String recordId,
                                                     DynamicRecordSaveOperation saveOperation,
                                                     DynamicRecord before,
                                                     DynamicRecord after,
                                                     DynamicMutationContext context) {
        DynamicMutationContext effectiveContext = context == null ? DynamicMutationContext.current().orElse(null) : context;
        return new DynamicRecordMutationEvent(
                null,
                eventType,
                moduleAlias,
                entityAlias,
                recordId,
                saveOperation,
                before == null ? null : before.copy(),
                after == null ? null : after.copy(),
                effectiveContext == null ? RuntimeMutationSource.BUSINESS : effectiveContext.mutationSource(),
                effectiveContext == null ? null : effectiveContext.traceId(),
                effectiveContext == null ? 0 : effectiveContext.depth(),
                effectiveContext == null ? null : effectiveContext.parentExecutionId(),
                effectiveContext == null || effectiveContext.cascadeAllowed(),
                effectiveContext == null ? Map.of() : effectiveContext.metadata()
        );
    }

    @Transactional
    public int delete(String moduleAlias, String entityAlias, String id) {
        return delete(moduleAlias, entityAlias, id, null, RuntimeMutationSource.BUSINESS, null);
    }

    @Transactional
    public int delete(String moduleAlias, String entityAlias, String id, Integer expectedVersion) {
        return delete(moduleAlias, entityAlias, id, expectedVersion, RuntimeMutationSource.BUSINESS, null);
    }

    int deleteFromAction(String moduleAlias, String entityAlias, String id, String traceId) {
        return delete(moduleAlias, entityAlias, id, null, RuntimeMutationSource.ACTION, traceId);
    }

    int deleteBatchFromAction(String moduleAlias, String entityAlias, Collection<String> ids, String traceId) {
        return deleteBatch(moduleAlias, entityAlias, ids, RuntimeMutationSource.ACTION, traceId);
    }

    private int delete(String moduleAlias, String entityAlias, String id, Integer expectedVersion,
                       RuntimeMutationSource mutationSource, String traceId) {
        try (DynamicMutationContext mutationContext = DynamicMutationContext.open(mutationClock, mutationSource,
                traceId, Map.of())) {
            DataScopeCriteriaResult mutationScope = DataScopeCriteriaResult.unrestricted(Criteria.of());
            if (mutationSource == RuntimeMutationSource.BUSINESS) {
                Set<String> recordIds = normalizeRecordId(id);
                mutationScope = requireBusinessRecordMutation(moduleAlias, entityAlias, PlatformAction.DELETE, recordIds);
            }
            DynamicRecord before = withTenantScope(mutationScope,
                    () -> entityService(moduleAlias, entityAlias).selectActiveRaw(id));
            mutationCoordinator.beforeDelete(moduleAlias, entityAlias, before);
            List<RelationChildMutation> childMutations = relationChildrenForCascadeDelete(moduleAlias, entityAlias, before);
            childMutations.forEach(mutation -> mutationCoordinator.beforeRelationChildDelete(
                    moduleAlias,
                    entityAlias,
                    mutation.relation().code(),
                    mutation.relation().childEntityAlias(),
                    before,
                    mutation.before()
            ));
            int deleted = withTenantScope(mutationScope,
                    () -> entityService(moduleAlias, entityAlias).delete(id, expectedVersion));
            if (deleted > 0) {
                mutationCoordinator.afterDelete(moduleAlias, entityAlias, before);
                mutationCoordinator.afterMutation(mutationEvent(
                        DynamicRecordMutationEventType.AFTER_DELETE,
                        moduleAlias,
                        entityAlias,
                        id,
                        null,
                        before,
                        null,
                        mutationContext
                ));
                childMutations.forEach(mutation -> mutationCoordinator.afterRelationChildDelete(
                        moduleAlias,
                        entityAlias,
                        mutation.relation().code(),
                        mutation.relation().childEntityAlias(),
                        before,
                        mutation.before()
                ));
                eventPublisher.deleted(eventContext(moduleAlias, entityAlias, mutationSource, traceId), id);
            }
            return deleted;
        }
    }

    @Transactional
    public int deleteBatch(String moduleAlias, String entityAlias, Collection<String> ids) {
        return deleteBatch(moduleAlias, entityAlias, ids, RuntimeMutationSource.BUSINESS, null);
    }

    private int deleteBatch(String moduleAlias, String entityAlias, Collection<String> ids, RuntimeMutationSource mutationSource, String traceId) {
        try (DynamicMutationContext mutationContext = DynamicMutationContext.open(mutationClock, mutationSource,
                traceId, Map.of())) {
            Set<String> normalizedIds = normalizeRecordIds(ids);
            if (normalizedIds.isEmpty()) {
                return 0;
            }
            DataScopeCriteriaResult mutationScope = DataScopeCriteriaResult.unrestricted(Criteria.of());
            if (mutationSource == RuntimeMutationSource.BUSINESS) {
                mutationScope = requireBusinessRecordMutation(moduleAlias, entityAlias, PlatformAction.DELETE, normalizedIds);
            }
            List<DynamicRecord> beforeRecords = withTenantScope(mutationScope, () -> entityService(moduleAlias, entityAlias)
                    .list(Criteria.of().in("id", List.copyOf(normalizedIds)), PageRequest.of(1, normalizedIds.size())));
            List<RelationChildMutation> childMutations = beforeRecords.stream()
                    .flatMap(record -> relationChildrenForCascadeDelete(moduleAlias, entityAlias, record).stream()
                            .map(mutation -> mutation.withParentBefore(record)))
                    .toList();
            beforeRecords.forEach(record -> mutationCoordinator.beforeDelete(moduleAlias, entityAlias, record));
            childMutations.forEach(mutation -> mutationCoordinator.beforeRelationChildDelete(
                    moduleAlias,
                    entityAlias,
                    mutation.relation().code(),
                    mutation.relation().childEntityAlias(),
                    mutation.parentBefore(),
                    mutation.before()
            ));
            int deleted = withTenantScope(mutationScope, () -> entityService(moduleAlias, entityAlias).deleteBatch(normalizedIds));
            if (deleted > 0) {
                beforeRecords.forEach(record -> {
                    mutationCoordinator.afterDelete(moduleAlias, entityAlias, record);
                    mutationCoordinator.afterMutation(mutationEvent(
                            DynamicRecordMutationEventType.AFTER_DELETE,
                            moduleAlias,
                            entityAlias,
                            record.getId(),
                            null,
                            record,
                            null,
                            mutationContext
                    ));
                });
                childMutations.forEach(mutation -> mutationCoordinator.afterRelationChildDelete(
                        moduleAlias,
                        entityAlias,
                        mutation.relation().code(),
                        mutation.relation().childEntityAlias(),
                        mutation.parentBefore(),
                        mutation.before()
                ));
                eventPublisher.deletedBatch(eventContext(moduleAlias, entityAlias, mutationSource, traceId),
                        List.copyOf(normalizedIds), deleted);
            }
            return deleted;
        }
    }

    public List<DynamicRecord> list(String moduleAlias, String entityAlias, Criteria criteria, PageRequest pageRequest, Sort... sorts) {
        DataScopeCriteriaResult scope = readScope(moduleAlias, PlatformAction.QUERY, criteria);
        return withTenantScope(scope, () -> entityService(moduleAlias, entityAlias).list(scope.criteria(),
                pageRequest, sorts));
    }

    public List<DynamicRecord> list(String moduleAlias, String entityAlias, Criteria criteria, Sort... sorts) {
        DataScopeCriteriaResult scope = readScope(moduleAlias, PlatformAction.QUERY, criteria);
        return withTenantScope(scope, () -> entityService(moduleAlias, entityAlias).list(scope.criteria(), sorts));
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
        DataScopeCriteriaResult scope = readScope(moduleAlias, action, criteria);
        return withTenantScope(scope, () -> entityService(moduleAlias, entityAlias).list(scope.criteria(),
                pageRequest, sorts));
    }

    List<DynamicRecord> listForAction(String moduleAlias,
                                      String entityAlias,
                                      PlatformAction action,
                                      Criteria criteria,
                                      Sort... sorts) {
        DataScopeCriteriaResult scope = readScope(moduleAlias, action, criteria);
        return withTenantScope(scope, () -> entityService(moduleAlias, entityAlias).list(scope.criteria(), sorts));
    }

    public PageResult<DynamicRecord> page(String moduleAlias, String entityAlias, Criteria criteria, PageRequest pageRequest, Sort... sorts) {
        DataScopeCriteriaResult scope = readScope(moduleAlias, PlatformAction.QUERY, criteria);
        return withTenantScope(scope, () -> entityService(moduleAlias, entityAlias).pageQuery(scope.criteria(),
                pageRequest, sorts));
    }

    public <R> R withQueryReadScope(String moduleAlias, Criteria criteria, Function<Criteria, R> action) {
        Objects.requireNonNull(action, "action must not be null");
        DataScopeCriteriaResult scope = readScope(moduleAlias, PlatformAction.QUERY, criteria);
        return withTenantScope(scope, () -> action.apply(scope.criteria()));
    }

    public PageResult<DynamicRecord> pageForAction(String moduleAlias,
                                                   String entityAlias,
                                                   String actionCode,
                                                   Criteria criteria,
                                                   PageRequest pageRequest,
                                                   Sort... sorts) {
        DynamicActionDescriptor action = findAction(moduleAlias, entityDescriptor(moduleAlias, entityAlias), actionCode);
        ActionExecutionPolicy policy = actionPolicy(action);
        actionExecutionPolicyService.authorize(ActionExecutionContext.ofPolicy(
                moduleAlias,
                policy,
                Set.of(),
                CurrentUserContext.currentUser()
        ));
        DataScopeCriteriaResult scope = readScope(moduleAlias, policy, criteria);
        return withTenantScope(scope, () -> entityService(moduleAlias, entityAlias).pageQuery(scope.criteria(),
                pageRequest, sorts));
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
        requireCapability(moduleAlias, entityAlias, EntityCapability.TREE);
        DynamicActionDescriptor action = findAction(moduleAlias, entityDescriptor(moduleAlias, entityAlias), actionCode);
        ActionExecutionPolicy policy = actionPolicy(action);
        actionExecutionPolicyService.authorize(ActionExecutionContext.ofPolicy(
                moduleAlias,
                policy,
                Set.of(),
                CurrentUserContext.currentUser()
        ));
        DataScopeCriteriaResult scope = readScope(moduleAlias, policy, criteria);
        return withTenantScope(scope, () -> entityService(moduleAlias, entityAlias)
                .children(scope.criteria(), parentId));
    }

    public long count(String moduleAlias, String entityAlias, Criteria criteria) {
        DataScopeCriteriaResult scope = readScope(moduleAlias, PlatformAction.QUERY, criteria);
        return withTenantScope(scope, () -> entityService(moduleAlias, entityAlias).count(scope.criteria()));
    }

    public List<DynamicRecord> sortedList(String moduleAlias, String entityAlias, Criteria criteria) {
        DataScopeCriteriaResult scope = readScope(moduleAlias, PlatformAction.QUERY, criteria);
        return withTenantScope(scope, () -> entityService(moduleAlias, entityAlias).sortedList(scope.criteria()));
    }

    public void reorder(String moduleAlias, String entityAlias, List<String> orderedIds) {
        reorder(moduleAlias, entityAlias, orderedIds, RuntimeMutationSource.BUSINESS, null);
    }

    void reorderFromAction(String moduleAlias, String entityAlias, List<String> orderedIds, String traceId) {
        reorder(moduleAlias, entityAlias, orderedIds, RuntimeMutationSource.ACTION, traceId);
    }

    private void reorder(String moduleAlias, String entityAlias, List<String> orderedIds, RuntimeMutationSource mutationSource, String traceId) {
        DataScopeCriteriaResult mutationScope = DataScopeCriteriaResult.unrestricted(Criteria.of());
        if (requiresStandardSortScopeCheck(mutationSource)) {
            mutationScope = sortMutationScope(moduleAlias, entityAlias, normalizeRecordIds(orderedIds),
                    ignored -> normalizeRecordIds(orderedIds));
        }
        withTenantScope(mutationScope, () -> {
            entityService(moduleAlias, entityAlias).reorder(orderedIds);
            return null;
        });
        eventPublisher.reordered(eventContext(moduleAlias, entityAlias, mutationSource, traceId), orderedIds);
    }

    public void moveBefore(String moduleAlias, String entityAlias, String id, String beforeId) {
        moveBefore(moduleAlias, entityAlias, id, beforeId, RuntimeMutationSource.BUSINESS, null);
    }

    void moveBeforeFromAction(String moduleAlias, String entityAlias, String id, String beforeId, String traceId) {
        moveBefore(moduleAlias, entityAlias, id, beforeId, RuntimeMutationSource.ACTION, traceId);
    }

    private void moveBefore(String moduleAlias, String entityAlias, String id, String beforeId, RuntimeMutationSource mutationSource, String traceId) {
        DataScopeCriteriaResult mutationScope = DataScopeCriteriaResult.unrestricted(Criteria.of());
        if (requiresStandardSortScopeCheck(mutationSource)) {
            mutationScope = sortMutationScope(moduleAlias, entityAlias, normalizeRecordIds(Arrays.asList(id, beforeId)),
                    scope -> sortScopeRecordIds(moduleAlias, entityAlias, id, beforeId));
        }
        withTenantScope(mutationScope, () -> {
            entityService(moduleAlias, entityAlias).moveBefore(id, beforeId);
            return null;
        });
        eventPublisher.movedBefore(eventContext(moduleAlias, entityAlias, mutationSource, traceId), id, beforeId);
    }

    public void moveAfter(String moduleAlias, String entityAlias, String id, String afterId) {
        moveAfter(moduleAlias, entityAlias, id, afterId, RuntimeMutationSource.BUSINESS, null);
    }

    void moveAfterFromAction(String moduleAlias, String entityAlias, String id, String afterId, String traceId) {
        moveAfter(moduleAlias, entityAlias, id, afterId, RuntimeMutationSource.ACTION, traceId);
    }

    private void moveAfter(String moduleAlias, String entityAlias, String id, String afterId, RuntimeMutationSource mutationSource, String traceId) {
        DataScopeCriteriaResult mutationScope = DataScopeCriteriaResult.unrestricted(Criteria.of());
        if (requiresStandardSortScopeCheck(mutationSource)) {
            mutationScope = sortMutationScope(moduleAlias, entityAlias, normalizeRecordIds(Arrays.asList(id, afterId)),
                    scope -> sortScopeRecordIds(moduleAlias, entityAlias, id, afterId));
        }
        withTenantScope(mutationScope, () -> {
            entityService(moduleAlias, entityAlias).moveAfter(id, afterId);
            return null;
        });
        eventPublisher.movedAfter(eventContext(moduleAlias, entityAlias, mutationSource, traceId), id, afterId);
    }

    public void moveInTree(String moduleAlias, String entityAlias, String id, String previousId, String nextId, String parentId) {
        moveInTree(moduleAlias, entityAlias, id, previousId, nextId, parentId, RuntimeMutationSource.BUSINESS, null);
    }

    private void moveInTree(String moduleAlias,
                            String entityAlias,
                            String id,
                            String previousId,
                            String nextId,
                            String parentId,
                            RuntimeMutationSource mutationSource,
                            String traceId) {
        if (mutationSource == RuntimeMutationSource.BUSINESS) {
            DataScopeCriteriaResult mutationScope = sortMutationScope(moduleAlias, entityAlias,
                    treeSortExplicitRecordIds(id, previousId, nextId, parentId),
                    scope -> treeSortScopeRecordIds(moduleAlias, entityAlias, id, previousId, nextId, parentId));
            withTenantScope(mutationScope, () -> {
                entityService(moduleAlias, entityAlias).moveInTree(id, previousId, nextId, parentId);
                return null;
            });
        } else {
            entityService(moduleAlias, entityAlias).moveInTree(id, previousId, nextId, parentId);
        }
        eventPublisher.movedInTree(eventContext(moduleAlias, entityAlias, mutationSource, traceId),
                id, previousId, nextId, parentId);
    }

    public List<DynamicRecord> children(String moduleAlias, String entityAlias, String parentId) {
        DataScopeCriteriaResult scope = readScope(moduleAlias, PlatformAction.TREE, Criteria.of());
        return withTenantScope(scope, () -> {
            if (!scope.restricted()) {
                return entityService(moduleAlias, entityAlias).children(parentId);
            }
            return entityService(moduleAlias, entityAlias).children(scope.criteria(), parentId);
        });
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

    private List<RelationChildMutation> prepareRelationChildrenForCreate(String moduleAlias,
                                                                         String entityAlias,
                                                                         DynamicRecord parent) {
        if (parent == null || parent.getChildren().isEmpty()) {
            return List.of();
        }
        ensureRecordId(parent);
        List<RelationChildMutation> mutations = new ArrayList<>();
        for (DynamicRelationDescriptor relation : childRelations(moduleAlias, entityAlias)) {
            List<DynamicRecord> children = parent.getChildren(relation.code());
            if (children == null || children.isEmpty()) {
                continue;
            }
            for (DynamicRecord child : children) {
                prepareRelationChild(parent, relation, child);
                mutations.add(RelationChildMutation.create(relation, child));
            }
        }
        return List.copyOf(mutations);
    }

    private List<RelationChildMutation> prepareRelationChildrenForUpdate(String moduleAlias,
                                                                         String entityAlias,
                                                                         DynamicRecord before,
                                                                         DynamicRecord incoming) {
        if (incoming == null || incoming.getChildren().isEmpty()) {
            return List.of();
        }
        List<RelationChildMutation> mutations = new ArrayList<>();
        for (DynamicRelationDescriptor relation : childRelations(moduleAlias, entityAlias)) {
            if (!incoming.getChildren().containsKey(relation.code())) {
                continue;
            }
            List<DynamicRecord> incomingChildren = incoming.getChildren(relation.code());
            if (incomingChildren == null) {
                continue;
            }
            Map<String, DynamicRecord> existingById = childrenById(moduleAlias, relation, incoming.getId());
            for (DynamicRecord child : incomingChildren) {
                prepareRelationChild(before == null ? incoming : before, relation, child);
                DynamicRecord existing = existingById.remove(child.getId());
                mutations.add(existing == null
                        ? RelationChildMutation.create(relation, child)
                        : RelationChildMutation.update(relation, existing, child));
            }
            if (!incoming.isPartialChildren(relation.code())) {
                existingById.values().forEach(child -> mutations.add(RelationChildMutation.delete(relation, child)));
            }
        }
        return List.copyOf(mutations);
    }

    private List<RelationChildMutation> relationChildrenForCascadeDelete(String moduleAlias,
                                                                         String entityAlias,
                                                                         DynamicRecord before) {
        if (before == null || before.getId() == null || before.getId().isBlank()) {
            return List.of();
        }
        List<RelationChildMutation> mutations = new ArrayList<>();
        for (DynamicRelationDescriptor relation : childRelations(moduleAlias, entityAlias).stream()
                .filter(DynamicRelationDescriptor::cascadeOnParentUnavailable)
                .toList()) {
            childrenById(moduleAlias, relation, before.getId()).values()
                    .forEach(child -> mutations.add(RelationChildMutation.delete(relation, child)));
        }
        return List.copyOf(mutations);
    }

    private List<DynamicRelationDescriptor> childRelations(String moduleAlias, String parentEntityAlias) {
        return relations(moduleAlias).stream()
                .filter(relation -> parentEntityAlias.equals(relation.parentEntityAlias()))
                .toList();
    }

    private Map<String, DynamicRecord> childrenById(String moduleAlias,
                                                    DynamicRelationDescriptor relation,
                                                    String parentId) {
        if (parentId == null || parentId.isBlank()) {
            return Map.of();
        }
        LinkedHashMap<String, DynamicRecord> children = new LinkedHashMap<>();
        entityService(moduleAlias, relation.childEntityAlias())
                .selectChildRows(Criteria.of().eq(relation.childForeignKeyField(), parentId))
                .forEach(child -> {
                    if (child.getId() != null && !child.getId().isBlank()) {
                        children.put(child.getId(), child);
                    }
                });
        return children;
    }

    private void prepareRelationChild(DynamicRecord parent,
                                      DynamicRelationDescriptor relation,
                                      DynamicRecord child) {
        ensureRecordId(child);
        child.putPlatformValue(relation.childForeignKeyField(), parent.getId());
        if (child.getTenantId() == null && parent.getTenantId() != null) {
            child.setTenantId(parent.getTenantId());
        }
    }

    private void ensureRecordId(DynamicRecord record) {
        if (record != null && (record.getId() == null || record.getId().isBlank())) {
            record.setId(Ids.newId());
        }
    }

    public int enable(String moduleAlias, String entityAlias, String id) {
        return enable(moduleAlias, entityAlias, id, null, RuntimeMutationSource.BUSINESS, null);
    }

    public int enable(String moduleAlias, String entityAlias, String id, Integer expectedVersion) {
        return enable(moduleAlias, entityAlias, id, expectedVersion, RuntimeMutationSource.BUSINESS, null);
    }

    int enableFromAction(String moduleAlias, String entityAlias, String id, String traceId) {
        return enable(moduleAlias, entityAlias, id, null, RuntimeMutationSource.ACTION, traceId);
    }

    private int enable(String moduleAlias, String entityAlias, String id, Integer expectedVersion,
                       RuntimeMutationSource mutationSource, String traceId) {
        DataScopeCriteriaResult mutationScope = DataScopeCriteriaResult.unrestricted(Criteria.of());
        if (mutationSource == RuntimeMutationSource.BUSINESS) {
            mutationScope = requireBusinessRecordMutation(moduleAlias, entityAlias, PlatformAction.ENABLE, normalizeRecordId(id));
        }
        int updated = withTenantScope(mutationScope,
                () -> entityService(moduleAlias, entityAlias).enable(id, expectedVersion));
        if (updated > 0) {
            eventPublisher.enabled(eventContext(moduleAlias, entityAlias, mutationSource, traceId), id);
        }
        return updated;
    }

    public int disable(String moduleAlias, String entityAlias, String id) {
        return disable(moduleAlias, entityAlias, id, null, RuntimeMutationSource.BUSINESS, null);
    }

    public int disable(String moduleAlias, String entityAlias, String id, Integer expectedVersion) {
        return disable(moduleAlias, entityAlias, id, expectedVersion, RuntimeMutationSource.BUSINESS, null);
    }

    int disableFromAction(String moduleAlias, String entityAlias, String id, String traceId) {
        return disable(moduleAlias, entityAlias, id, null, RuntimeMutationSource.ACTION, traceId);
    }

    private int disable(String moduleAlias, String entityAlias, String id, Integer expectedVersion,
                        RuntimeMutationSource mutationSource, String traceId) {
        DataScopeCriteriaResult mutationScope = DataScopeCriteriaResult.unrestricted(Criteria.of());
        if (mutationSource == RuntimeMutationSource.BUSINESS) {
            mutationScope = requireBusinessRecordMutation(moduleAlias, entityAlias, PlatformAction.DISABLE, normalizeRecordId(id));
        }
        int updated = withTenantScope(mutationScope,
                () -> entityService(moduleAlias, entityAlias).disable(id, expectedVersion));
        if (updated > 0) {
            eventPublisher.disabled(eventContext(moduleAlias, entityAlias, mutationSource, traceId), id);
        }
        return updated;
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

    private DataScopeCriteriaResult sortMutationScope(String moduleAlias,
                                                      String entityAlias,
                                                      Set<String> explicitRecordIds,
                                                      java.util.function.Function<DataScopeCriteriaResult, Set<String>> scopeCollector) {
        requireRecordAction(moduleAlias, PlatformAction.SORT, explicitRecordIds);
        DataScopeCriteriaResult explicitScope = requireRecordDataScope(moduleAlias, entityAlias,
                PlatformAction.SORT, explicitRecordIds);
        Set<String> scopedRecordIds = withTenantScope(explicitScope, () -> scopeCollector.apply(explicitScope));
        return requireRecordDataScope(moduleAlias, entityAlias, PlatformAction.SORT, scopedRecordIds);
    }

    private Set<String> sortScopeRecordIds(String moduleAlias, String entityAlias, String id, String targetId) {
        Set<String> recordIds = new LinkedHashSet<>(normalizeRecordIds(Arrays.asList(id, targetId)));
        DynamicEntityService service = entityService(moduleAlias, entityAlias);
        DynamicRecord moving = service.select(id);
        DynamicRecord target = targetId == null || targetId.isBlank() ? null : service.select(targetId);
        if (moving == null || target == null) {
            return recordIds;
        }
        service.sortPartition().requireSamePartition(moving, target);
        service.sortedList(service.sortPartition().criteriaFor(moving)).stream()
                .map(DynamicRecord::getId)
                .forEach(recordIds::add);
        return recordIds;
    }

    private Set<String> treeSortScopeRecordIds(String moduleAlias,
                                               String entityAlias,
                                               String id,
                                               String previousId,
                                               String nextId,
                                               String parentId) {
        Set<String> recordIds = new LinkedHashSet<>(normalizeRecordIds(Arrays.asList(id, previousId, nextId)));
        DynamicEntityService service = entityService(moduleAlias, entityAlias);
        DynamicRecord moving = service.select(id);
        if (moving == null) {
            return recordIds;
        }
        String targetParentId = normalizeTreeParentId(parentId);
        if (targetParentId == null) {
            targetParentId = neighborParentId(service, previousId);
        }
        if (targetParentId == null) {
            targetParentId = neighborParentId(service, nextId);
        }
        if (targetParentId == null) {
            targetParentId = normalizeTreeParentId(moving.parentId());
        }
        if (targetParentId == null) {
            targetParentId = TreeAbility.ROOT_ID;
        }
        if (!TreeAbility.ROOT_ID.equals(targetParentId)) {
            recordIds.add(targetParentId);
        }
        service.children(targetParentId).stream()
                .map(DynamicRecord::getId)
                .forEach(recordIds::add);
        return recordIds;
    }

    private String neighborParentId(DynamicEntityService service, String neighborId) {
        if (neighborId == null || neighborId.isBlank()) {
            return null;
        }
        DynamicRecord neighbor = service.select(neighborId);
        return neighbor == null ? null : normalizeTreeParentId(neighbor.parentId());
    }

    private String normalizeTreeParentId(String parentId) {
        return parentId == null || parentId.isBlank() ? null : parentId;
    }

    private boolean requiresStandardSortScopeCheck(RuntimeMutationSource mutationSource) {
        return mutationSource == RuntimeMutationSource.BUSINESS || mutationSource == RuntimeMutationSource.ACTION;
    }

    private Set<String> treeSortExplicitRecordIds(String id, String previousId, String nextId, String parentId) {
        LinkedHashSet<String> ids = new LinkedHashSet<>(normalizeRecordIds(Arrays.asList(id, previousId, nextId)));
        String normalizedParentId = normalizeTreeParentId(parentId);
        if (normalizedParentId != null && !TreeAbility.ROOT_ID.equals(normalizedParentId)) {
            ids.add(normalizedParentId);
        }
        return java.util.Collections.unmodifiableSet(ids);
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

    private DynamicActionAvailability actionAuthorizationAvailability(String moduleAlias,
                                                                      String entityAlias,
                                                                      DynamicActionDescriptor action,
                                                                      Collection<String> recordIds) {
        ActionExecutionPolicy policy = actionPolicy(action);
        Set<String> normalizedIds = normalizeRecordIds(recordIds);
        try {
            actionExecutionPolicyService.authorize(ActionExecutionContext.ofPolicy(
                    moduleAlias,
                    policy,
                    normalizedIds,
                    CurrentUserContext.currentUser()
            ));
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

    private DynamicActionExecutionResult executeAction(String moduleAlias,
                                                       String entityAlias,
                                                       DynamicActionDescriptor action,
                                                       DynamicActionExecutionRequest request) {
        DynamicActionExecutionRequest normalized = request == null ? DynamicActionExecutionRequest.empty() : request;
        ActionExecutionPolicy policy = actionPolicy(action);
        ActionAuthorizationResult authorization = actionExecutionPolicyService.authorize(ActionExecutionContext.ofPolicy(
                moduleAlias,
                policy,
                actionRecordIds(normalized),
                CurrentUserContext.currentUser()
        ));
        Set<String> recordIds = actionRecordIds(normalized);
        DataScopeCriteriaResult recordScope = requireActionRecordDataScope(moduleAlias, entityAlias, policy, recordIds);
        DataScopeCriteriaResult criteriaScope = actionCriteriaDataScope(moduleAlias, entityAlias, policy, normalized, recordIds);
        DataScopeCriteriaResult actionScope = criteriaScope == null ? recordScope : criteriaScope;
        DynamicActionExecutionRequest scopedRequest = criteriaScope == null
                ? normalized
                : normalized.withCriteria(criteriaScope.criteria());
        DynamicActionAvailability availability = withTenantScope(actionScope, () -> {
            DynamicRecord availabilityRecord = availabilityRecord(moduleAlias, entityAlias, scopedRequest);
            return actionAvailability(moduleAlias, entityAlias, action.code(), availabilityRecord);
        });
        String traceId = actionTraceId();
        DynamicActionExecutionContext context = executionContext(moduleAlias, entityAlias, action, scopedRequest,
                availability, null, traceId, authorization);
        if (!availability.available()) {
            eventPublisher.actionFailed(context, DynamicActionExecutionException.STAGE_AVAILABILITY, availability.message(), null);
            throw new DynamicActionExecutionException(availability.message(), context,
                    DynamicActionExecutionException.STAGE_AVAILABILITY, null);
        }
        DynamicActionExecutionResult result;
        try {
            result = withTenantScope(actionScope, () -> runtime.actionTransactionOperator().executeResult(context, () -> {
                if (!isInteractionOnlyAction(action)) {
                    validateBeforeActionExecute(moduleAlias, entityAlias, scopedRequest, context);
                }
                DynamicActionResultBody body = executeActionValue(moduleAlias, entityAlias, action, scopedRequest, context, traceId, policy);
                DynamicActionExecutionContext completed = executionContext(moduleAlias, entityAlias, action, scopedRequest,
                        availability, body.value(), traceId, authorization);
                return new DynamicActionExecutionResult(completed, body.value(), body);
            }));
        } catch (DynamicActionExecutionException e) {
            eventPublisher.actionFailed(context, e.failureStage(), e.getMessage(), failureError(e));
            throw e;
        } catch (RuntimeException e) {
            RuntimeException afterCommitFailure = afterCommitFailure(e);
            if (afterCommitFailure != null) {
                throw afterCommitFailure;
            }
            eventPublisher.actionFailed(context, DynamicActionExecutionException.STAGE_EXECUTE, e.getMessage(), e);
            throw e;
        }
        eventPublisher.actionExecuted(result.context(), result.body());
        return result;
    }

    private Set<String> actionRecordIds(DynamicActionExecutionRequest request) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        collectId(ids, request.recordId());
        if (request.record() != null) {
            collectId(ids, request.record().getId());
        }
        request.ids().forEach(id -> collectId(ids, id));
        request.orderedIds().forEach(id -> collectId(ids, id));
        collectId(ids, request.beforeId());
        collectId(ids, request.afterId());
        collectId(ids, request.parentId());
        return java.util.Collections.unmodifiableSet(ids);
    }

    private DataScopeCriteriaResult actionCriteriaDataScope(String moduleAlias,
                                                           String entityAlias,
                                                           ActionExecutionPolicy policy,
                                                           DynamicActionExecutionRequest request,
                                                           Collection<String> recordIds) {
        if (!supportsCapability(moduleAlias, entityAlias, EntityCapability.DATA_SCOPE)
                || !policy.requiresDataScope()
                || (request.criteria() == null && !normalizeRecordIds(recordIds).isEmpty())) {
            return null;
        }
        return readScope(moduleAlias, policy, actionExecutionCriteria(request.criteria(), recordIds));
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

    private ActionExecutionPolicy actionPolicy(DynamicActionDescriptor action) {
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

    private void collectId(Set<String> ids, String id) {
        if (id != null && !id.isBlank()) {
            ids.add(id.trim());
        }
    }

    private RuntimeException afterCommitFailure(RuntimeException error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof TransactionScopeSupport.AfterCommitActionException afterCommit) {
                return afterCommit.unwrap();
            }
            current = current.getCause();
        }
        return null;
    }

    private void validateBeforeActionExecute(String moduleAlias,
                                             String entityAlias,
                                             DynamicActionExecutionRequest request,
                                             DynamicActionExecutionContext context) {
        DynamicRecord record = availabilityRecord(moduleAlias, entityAlias, request);
        if (record != null && (record.getId() == null || record.getId().isBlank()) && request.recordId() != null) {
            record.setId(request.recordId());
        }
        if (record == null) {
            return;
        }
        DynamicFormulaRuntime formulaRuntime = new DynamicFormulaRuntime(
                moduleAlias, record.getEntity(), runtime.registry().requireModule(moduleAlias));
        if (!formulaRuntime.hasBeforeActionExecuteRules()) {
            return;
        }
        DynamicRecord existing = !shouldLoadExistingForActionRules(record)
                ? null
                : select(moduleAlias, entityAlias, record.getId());
        if (isActionRecordProbe(record) && existing != null) {
            record = existing;
            existing = null;
        }
        try {
            formulaRuntime.beforeActionExecute(record, existing);
        } catch (DynamicFormulaException e) {
            throw new DynamicActionExecutionException(e.getMessage(), context,
                    DynamicActionExecutionException.STAGE_BEFORE_EXECUTE_RULE, e);
        }
    }

    private boolean isActionRecordProbe(DynamicRecord record) {
        return record.explicitFieldCodes().isEmpty() && record.getChildren().isEmpty();
    }

    private boolean shouldLoadExistingForActionRules(DynamicRecord record) {
        return record.getId() != null
                && !record.getId().isBlank()
                && (isActionRecordProbe(record) || !record.getChildren().isEmpty());
    }

    private DynamicActionResultBody executeActionValue(String moduleAlias,
                                                       String entityAlias,
                                                       DynamicActionDescriptor action,
                                                       DynamicActionExecutionRequest request,
                                                       DynamicActionExecutionContext context,
                                                       String traceId,
                                                       ActionExecutionPolicy policy) {
        if (action.executorType() == EntityActionExecutorType.STANDARD) {
            return new DynamicStandardActionExecutor(this, moduleAlias, entityAlias, traceId)
                    .execute(action.code(), request);
        }
        if (action.executorType() == EntityActionExecutorType.SERVICE
                || action.executorType() == EntityActionExecutorType.GENERATE) {
            return executeRegisteredAction(moduleAlias, entityAlias, action, request, context, traceId, policy);
        }
        if (action.executorType() == EntityActionExecutorType.DIALOG) {
            return DynamicActionResultBody.dialog(dialog(moduleAlias, action, request));
        }
        throw new DynamicActionExecutionException(
                "dynamic action executor is not supported: " + action.executorType(),
                context
        );
    }

    private DynamicActionResultBody executeRegisteredAction(String moduleAlias,
                                                            String entityAlias,
                                                            DynamicActionDescriptor action,
                                                            DynamicActionExecutionRequest request,
                                                            DynamicActionExecutionContext context,
                                                            String traceId,
                                                            ActionExecutionPolicy policy) {
        DynamicActionExecutor executor;
        try {
            executor = runtime.actionExecutorRegistry().require(action.executorKey());
        } catch (IllegalArgumentException e) {
            throw new DynamicActionExecutionException(e.getMessage(), context, e);
        }
        try {
            return actionResultBody(executor.execute(context, request,
                    actionOperations(moduleAlias, entityAlias, traceId, policy)));
        } catch (DynamicActionExecutionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new DynamicActionExecutionException(e.getMessage(), context, e);
        }
    }

    private String dialogKey(DynamicActionDescriptor action) {
        String executorKey = requireText(action.executorKey(), "dialog executorKey");
        int separator = executorKey.indexOf('#');
        return separator < 0 ? executorKey : requireText(executorKey.substring(0, separator), "dialog key");
    }

    private DynamicActionDialog dialog(String moduleAlias,
                                       DynamicActionDescriptor action,
                                       DynamicActionExecutionRequest request) {
        String submitActionCode = dialogSubmitActionCode(action);
        DynamicActionDescriptor submitAction = submitActionCode == null ? null : action(moduleAlias, submitActionCode);
        if (submitAction != null && submitAction.executorType() == EntityActionExecutorType.DIALOG) {
            throw new PlatformException("dialog submit action must not be DIALOG: " + submitActionCode);
        }
        String recordId = request == null ? null : firstText(request.recordId(),
                request.record() == null ? null : request.record().getId());
        return new DynamicActionDialog(
                dialogKey(action),
                action.title(),
                action.code(),
                submitActionCode,
                submitActionPath(moduleAlias, submitAction, recordId),
                recordId,
                submitActionCode != null,
                null,
                submitActionCode == null ? DynamicActionRefreshStrategy.none()
                        : DynamicActionRefreshStrategy.listAndDetail()
        );
    }

    private String dialogSubmitActionCode(DynamicActionDescriptor action) {
        String executorKey = requireText(action.executorKey(), "dialog executorKey");
        int separator = executorKey.indexOf('#');
        if (separator < 0 || separator == executorKey.length() - 1) {
            return null;
        }
        return requireText(executorKey.substring(separator + 1), "dialog submit actionCode");
    }

    private String submitActionPath(String moduleAlias,
                                    DynamicActionDescriptor submitAction,
                                    String recordId) {
        if (submitAction == null) {
            return null;
        }
        if (submitAction.actionLevel() == net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel.RECORD) {
            return recordId == null
                    ? "/" + moduleAlias + "/" + submitAction.code() + "/{recordId}"
                    : "/" + moduleAlias + "/" + submitAction.code() + "/" + recordId;
        }
        if (submitAction.actionLevel() == net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel.BATCH) {
            return "/" + moduleAlias + "/" + submitAction.code() + "/batch";
        }
        return "/" + moduleAlias + "/" + submitAction.code();
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private boolean isInteractionOnlyAction(DynamicActionDescriptor action) {
        return action.executorType() == EntityActionExecutorType.DIALOG;
    }

    private DynamicActionResultBody actionResultBody(Object value) {
        if (value instanceof DynamicActionResultBody body) {
            return body;
        }
        return DynamicActionResultBody.of(value);
    }

    private DynamicActionOperations actionOperations(String moduleAlias,
                                                     String entityAlias,
                                                     String traceId,
                                                     ActionExecutionPolicy policy) {
        return new DynamicActionOperations() {
            @Override
            public DynamicRecord newRecord() {
                return DynamicRecordService.this.newRecord(moduleAlias, entityAlias);
            }

            @Override
            public DynamicRecord newRecord(String targetModuleAlias, String targetEntityAlias) {
                return DynamicRecordService.this.newRecord(targetModuleAlias, targetEntityAlias);
            }

            @Override
            public DynamicRecord select(String id) {
                return DynamicRecordService.this.select(moduleAlias, entityAlias, id);
            }

            @Override
            public DynamicRecord select(String targetModuleAlias, String targetEntityAlias, String id) {
                return DynamicRecordService.this.select(targetModuleAlias, targetEntityAlias, id);
            }

            @Override
            public void requireAction(String targetModuleAlias, PlatformAction action) {
                DynamicRecordService.this.requireAction(targetModuleAlias, action);
            }

            @Override
            public int update(DynamicRecord record) {
                DataScopeCriteriaResult scope = requireActionRecordDataScope(moduleAlias, entityAlias, policy,
                        normalizeRecordId(record == null ? null : record.getId()));
                return withTenantScope(scope, () -> DynamicRecordService.this.update(moduleAlias, entityAlias, record,
                        RuntimeMutationSource.ACTION, traceId, Map.of()));
            }

            @Override
            public int delete(String id) {
                DataScopeCriteriaResult scope = requireActionRecordDataScope(moduleAlias, entityAlias, policy, normalizeRecordId(id));
                return withTenantScope(scope, () -> DynamicRecordService.this.delete(moduleAlias, entityAlias, id,
                        null, RuntimeMutationSource.ACTION, traceId));
            }
        };
    }

    private DynamicRecord availabilityRecord(String moduleAlias, String entityAlias, DynamicActionExecutionRequest request) {
        if (request.record() != null) {
            return request.record();
        }
        if (request.recordId() == null || request.recordId().isBlank()) {
            return null;
        }
        DynamicRecord probe = newRecord(moduleAlias, entityAlias);
        probe.setId(request.recordId());
        return probe;
    }

    private DynamicActionExecutionContext executionContext(String moduleAlias,
                                                           String entityAlias,
                                                           DynamicActionDescriptor action,
                                                           DynamicActionExecutionRequest request,
                                                           DynamicActionAvailability availability) {
        return executionContext(moduleAlias, entityAlias, action, request, availability, null, actionTraceId(), null);
    }

    private String actionTraceId() {
        return RequestTraceContext.currentTraceId().orElseGet(() -> UUID.randomUUID().toString());
    }

    private DynamicActionExecutionContext executionContext(String moduleAlias,
                                                           String entityAlias,
                                                           DynamicActionDescriptor action,
                                                           DynamicActionExecutionRequest request,
                                                           DynamicActionAvailability availability,
                                                           Object value,
                                                           String traceId,
                                                           ActionAuthorizationResult authorization) {
        String recordId = request.recordId();
        if ((recordId == null || recordId.isBlank()) && request.record() != null) {
            recordId = request.record().getId();
        }
        if ((recordId == null || recordId.isBlank()) && PlatformAction.CREATE.matches(action.code()) && value instanceof String id) {
            recordId = id;
        }
        return new DynamicActionExecutionContext(
                moduleAlias,
                entityAlias,
                action.code(),
                action,
                recordId,
                traceId,
                TenantContext.currentTenantId().orElse(null),
                TenantContext.isSystem(),
                TenantContext.systemReason().orElse(null),
                authorization == null ? null : authorization.operatorId(),
                authorization == null ? null : authorization.operatorType(),
                authorization == null ? null : authorization.decision(),
                authorization == null ? null : authorization.permissionCode(),
                authorization == null ? null : authorization.permissionActionCode(),
                availability
        );
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("dynamic action requires " + fieldName);
        }
        return value;
    }

    DynamicEntityService entityService(String moduleAlias, String entityAlias) {
        return runtime.entityService(moduleAlias, entityAlias);
    }

    private Throwable failureError(DynamicActionExecutionException exception) {
        return exception.getCause() == null ? exception : exception.getCause();
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

    private enum RelationChildMutationKind {
        CREATE,
        UPDATE,
        DELETE
    }

    private record RelationChildMutation(
            RelationChildMutationKind kind,
            DynamicRelationDescriptor relation,
            DynamicRecord parentBefore,
            DynamicRecord before,
            DynamicRecord incoming
    ) {
        private static RelationChildMutation create(DynamicRelationDescriptor relation, DynamicRecord incoming) {
            return new RelationChildMutation(RelationChildMutationKind.CREATE, relation, null, null, incoming);
        }

        private static RelationChildMutation update(DynamicRelationDescriptor relation,
                                                    DynamicRecord before,
                                                    DynamicRecord incoming) {
            return new RelationChildMutation(RelationChildMutationKind.UPDATE, relation, null, before, incoming);
        }

        private static RelationChildMutation delete(DynamicRelationDescriptor relation, DynamicRecord before) {
            return new RelationChildMutation(RelationChildMutationKind.DELETE, relation, null, before, null);
        }

        private RelationChildMutation withParentBefore(DynamicRecord parentBefore) {
            return new RelationChildMutation(kind, relation, parentBefore, before, incoming);
        }
    }

}
