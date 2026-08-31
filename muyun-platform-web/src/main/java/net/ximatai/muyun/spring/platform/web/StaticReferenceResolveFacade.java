package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.spring.ability.PageRequests;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceOption;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetResolver;
import net.ximatai.muyun.spring.ability.reference.ReferenceSelectionProjectionReader;
import net.ximatai.muyun.spring.ability.reference.ReferenceCandidateCriteria;
import net.ximatai.muyun.spring.ability.reference.StaticReferenceResolver;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.model.capability.TreeCapable;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.model.title.TitleFieldResolver;
import net.ximatai.muyun.spring.platform.reference.StaticAbilityCatalog;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.web.WebPageRequest;
import net.ximatai.muyun.spring.web.WebReferenceMatchMode;
import net.ximatai.muyun.spring.web.WebReferenceResolveItem;
import net.ximatai.muyun.spring.web.WebReferenceResolveMode;
import net.ximatai.muyun.spring.web.WebReferenceResolveRequest;
import net.ximatai.muyun.spring.web.WebReferenceResolveResponse;
import net.ximatai.muyun.spring.web.WebReferenceResolveResult;
import net.ximatai.muyun.spring.web.WebReferenceResolveStatus;
import net.ximatai.muyun.spring.web.WebReferenceTenantScope;
import net.ximatai.muyun.spring.web.WebTreeNode;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** Default candidate/translation delivery for a reference declared on a static model. */
@Service
public class StaticReferenceResolveFacade {
    private final StaticModuleDefinitionCatalog modules;
    private final StaticAbilityCatalog abilities;
    private final DynamicRecordService dynamicRecords;

    @Autowired
    public StaticReferenceResolveFacade(StaticModuleDefinitionCatalog modules, StaticAbilityCatalog abilities,
                                       ObjectProvider<DynamicRecordService> dynamicRecords) {
        this(modules, abilities, dynamicRecords.getIfAvailable());
    }

    StaticReferenceResolveFacade(StaticModuleDefinitionCatalog modules, StaticAbilityCatalog abilities) {
        this(modules, abilities, (DynamicRecordService) null);
    }

    StaticReferenceResolveFacade(StaticModuleDefinitionCatalog modules, StaticAbilityCatalog abilities,
                                 DynamicRecordService dynamicRecords) {
        this.modules = modules;
        this.abilities = abilities;
        this.dynamicRecords = dynamicRecords;
    }

    public WebReferenceResolveResponse resolve(String moduleAlias, String fieldName,
                                               WebReferenceResolveRequest request) {
        StaticModuleDefinition source = modules.find(moduleAlias)
                .orElseThrow(() -> new PlatformException("static module is not available: " + moduleAlias));
        ReferencePlan plan = sourceModels(source).stream()
                .flatMap(modelClass -> java.util.stream.Stream.concat(
                        StaticReferenceResolver.rules(modelClass).stream().map(StaticReferenceResolver.ReferenceRule::plan),
                        StaticReferenceResolver.discriminatedValuePlans(modelClass).stream()
                                .flatMap(value -> value.cases().stream())
                                .map(net.ximatai.muyun.spring.ability.discriminator.DiscriminatedValueCasePlan::reference)
                                .filter(java.util.Objects::nonNull)))
                .filter(candidate -> candidate.sourceField().equals(fieldName))
                .findFirst()
                .orElseThrow(() -> new PlatformException("static reference field is not available: " + moduleAlias + "." + fieldName));
        WebReferenceResolveRequest normalized = request == null ? WebReferenceResolveRequest.empty() : request;
        return WebReferenceTenantScope.within(normalized, plan.tenantScope(),
                sourceRecordId -> persistedSourceTenant(source, sourceRecordId),
                () -> switch (normalized.mode()) {
                    case TRANSLATE -> translate(plan, normalized);
                    case TREE -> tree(plan, normalized);
                    case QUERY -> query(plan, normalized);
                });
    }

    /**
     * A static module owns its aggregate child models as well as its root model. Reference
     * candidate resolution must therefore see the child field catalog, not only the root.
     */
    private static List<Class<?>> sourceModels(StaticModuleDefinition source) {
        java.util.LinkedHashSet<Class<?>> models = new java.util.LinkedHashSet<>();
        if (source.modelClass() != null) models.add(source.modelClass());
        models.addAll(source.entityModelClasses().values());
        return List.copyOf(models);
    }

    private WebReferenceResolveResponse query(ReferencePlan plan, WebReferenceResolveRequest request) {
        WebPageRequest page = request.page() == null ? WebPageRequest.DEFAULT : request.page();
        Criteria criteria = candidateCriteria(plan, request);
        if (request.fuzzy() != null && !request.fuzzy().isBlank()) {
            criteria.like(titleField(plan), request.fuzzy().trim());
        }
        PageRequest pageRequest = PageRequest.of(page.pageNum(), page.pageSize());
        PageResult<ReferenceOption> result = referenceOptions(plan, criteria, pageRequest);
        Map<String, Map<String, Object>> selectionProjections = selectionProjections(plan,
                result.getRecords().stream().map(ReferenceOption::id).toList());
        List<WebReferenceResolveItem> options = result.getRecords().stream()
                .map(option -> new WebReferenceResolveItem(option.id(), option.title(), null,
                        selectionProjections.get(option.id()), null)).toList();
        return new WebReferenceResolveResponse(options.isEmpty() ? WebReferenceResolveStatus.NOT_FOUND : WebReferenceResolveStatus.OK,
                WebReferenceResolveMode.QUERY, options, List.of(), pageRequest.getOffset(), page.pageSize(), result.getTotal());
    }

    private WebReferenceResolveResponse translate(ReferencePlan plan, WebReferenceResolveRequest request) {
        List<String> ids = request.values().stream().filter(java.util.Objects::nonNull).map(String::valueOf).distinct().toList();
        Criteria criteria = candidateCriteria(plan, request);
        if (ids.isEmpty()) {
            criteria.raw(net.ximatai.muyun.database.core.orm.SqlRawCondition.of("1 = 0", java.util.Map.of()));
        } else {
            criteria.in(plan.targetKeyField(), ids);
        }
        List<ReferenceOption> resolved = referenceOptions(plan, criteria, PageRequests.all()).getRecords();
        Map<String, ReferenceOption> optionsByMatchValue = optionsByMatchValue(plan, resolved);
        Map<String, Map<String, Object>> selectionProjections = selectionProjections(plan,
                optionsByMatchValue.values().stream().map(ReferenceOption::id).distinct().toList());
        List<WebReferenceResolveResult> results = request.values().stream().map(value -> {
            String matchValue = value == null ? null : String.valueOf(value);
            ReferenceOption option = matchValue == null ? null : optionsByMatchValue.get(matchValue);
            WebReferenceResolveItem item = option == null ? null
                    : new WebReferenceResolveItem(option.id(), option.title(), WebReferenceMatchMode.KEY,
                    selectionProjections.get(option.id()), null);
            return new WebReferenceResolveResult(value,
                    item == null ? WebReferenceResolveStatus.NOT_FOUND : WebReferenceResolveStatus.RESOLVED,
                    item == null ? null : WebReferenceMatchMode.KEY, item, List.of());
        }).toList();
        WebReferenceResolveStatus status = results.isEmpty() ? WebReferenceResolveStatus.NOT_FOUND
                : results.stream().allMatch(result -> result.status() == WebReferenceResolveStatus.RESOLVED)
                ? WebReferenceResolveStatus.RESOLVED : WebReferenceResolveStatus.PARTIAL;
        return new WebReferenceResolveResponse(status, WebReferenceResolveMode.TRANSLATE, List.of(), results,
                0, 0, results.size());
    }

    private Map<String, ReferenceOption> optionsByMatchValue(ReferencePlan plan,
                                                              List<ReferenceOption> options) {
        if ("id".equals(plan.targetKeyField())) {
            return options.stream().collect(java.util.stream.Collectors.toMap(
                    ReferenceOption::id, java.util.function.Function.identity(), (left, ignored) -> left));
        }
        Map<String, Map<String, Object>> keys = referenceAbility(plan.target())
                .projections(options.stream().map(ReferenceOption::id).toList(), List.of(plan.targetKeyField()));
        Map<String, ReferenceOption> result = new LinkedHashMap<>();
        for (ReferenceOption option : options) {
            Object key = keys.getOrDefault(option.id(), Map.of()).get(plan.targetKeyField());
            if (key != null) result.put(String.valueOf(key), option);
        }
        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private WebReferenceResolveResponse tree(ReferencePlan plan, WebReferenceResolveRequest request) {
        ReferenceTarget target = plan.target();
        ReferenceAbility<?> referenceAbility = abilities.findReference(target)
                .orElseThrow(() -> new PlatformException("tree reference target is not available: " + target.qualifiedName()));
        requireTreeTarget(referenceAbility);
        Criteria criteria = candidateCriteria(plan, request);
        Map<String, List<TreeCapable>> children = treeChildrenByParent((ReferenceAbility) referenceAbility, criteria);
        Map<String, ReferenceOption> optionsByRecordId = plan.targetLabelField() == null ? Map.of()
                : referenceOptions(plan, criteria, PageRequests.all()).getRecords().stream()
                .collect(java.util.stream.Collectors.toMap(ReferenceOption::id, option -> option,
                        (left, right) -> left, LinkedHashMap::new));
        Map<String, Map<String, Object>> selectionProjections = selectionProjections(plan,
                children.values().stream()
                        .flatMap(List::stream).map(TreeCapable::getId).toList());
        return targetTree(referenceAbility, children, selectionProjections, optionsByRecordId);
    }

    /**
     * Delivers a static tree target for metadata-driven sources. The source may be dynamic, but the
     * target's tree traversal and REFERENCE data scope remain owned by the static ability.
     */
    public WebReferenceResolveResponse resolveTargetTree(ReferenceTarget target, Criteria criteria) {
        ReferenceAbility<?> referenceAbility = abilities.findReference(target)
                .orElseThrow(() -> new PlatformException("tree reference target is not available: " + target.qualifiedName()));
        requireTreeTarget(referenceAbility);
        return targetTree(referenceAbility, treeChildrenByParent((ReferenceAbility) referenceAbility, criteria), Map.of(), Map.of());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private WebReferenceResolveResponse targetTree(ReferenceAbility<?> referenceAbility,
                                                   Map<String, List<TreeCapable>> children,
                                                   Map<String, Map<String, Object>> selectionProjections,
                                                   Map<String, ReferenceOption> optionsByRecordId) {
        List<WebTreeNode<WebReferenceResolveItem>> nodes = children.getOrDefault(TreeAbility.ROOT_ID, List.of()).stream()
                .map(record -> treeNode((ReferenceAbility) referenceAbility, children, record, selectionProjections,
                        optionsByRecordId))
                .toList();
        return new WebReferenceResolveResponse(nodes.isEmpty() ? WebReferenceResolveStatus.NOT_FOUND : WebReferenceResolveStatus.OK,
                WebReferenceResolveMode.TREE, List.of(), List.of(), 0, 0, nodes.size(), nodes);
    }

    private static void requireTreeTarget(ReferenceAbility<?> referenceAbility) {
        if (!(referenceAbility instanceof TreeAbility<?>)) {
            throw new PlatformException("tree reference target does not support tree delivery: "
                    + referenceAbility.referenceTarget().qualifiedName());
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Map<String, List<TreeCapable>> treeChildrenByParent(ReferenceAbility referenceAbility, Criteria criteria) {
        List<? extends TreeCapable> records;
        if (referenceAbility instanceof DataScopeAbility<?> scoped) {
            records = (List<? extends TreeCapable>) (List<?>) DataScopeAbility.cast(scoped)
                    .listForAction(PlatformAction.REFERENCE, criteria, PageRequests.all());
        } else {
            records = (List<? extends TreeCapable>) (List<?>) referenceAbility.list(criteria, PageRequests.all());
        }
        Map<String, List<TreeCapable>> children = new LinkedHashMap<>();
        records.stream()
                .sorted(java.util.Comparator.comparing(TreeCapable::getSortOrder,
                        java.util.Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(TreeCapable::getId))
                .forEach(record -> {
            String parentId = record.getParentId();
            String key = parentId == null || parentId.isBlank() ? TreeAbility.ROOT_ID : parentId;
            children.computeIfAbsent(key, ignored -> new java.util.ArrayList<>()).add(record);
                });
        return children;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private WebTreeNode<WebReferenceResolveItem> treeNode(ReferenceAbility referenceAbility,
                                                           Map<String, List<TreeCapable>> children,
                                                           TreeCapable record,
                                                           Map<String, Map<String, Object>> selectionProjections,
                                                           Map<String, ReferenceOption> optionsByRecordId) {
        ReferenceOption option = optionsByRecordId.get(record.getId());
        WebReferenceResolveItem item = new WebReferenceResolveItem(option == null ? record.getId() : option.id(),
                option == null ? referenceAbility.referenceTitle(record) : option.title(), null,
                selectionProjections.get(record.getId()), null);
        return new WebTreeNode<>(item, children.getOrDefault(record.getId(), List.of()).stream()
                .map(child -> treeNode(referenceAbility, children, child, selectionProjections, optionsByRecordId))
                .toList());
    }

    private Map<String, Map<String, Object>> selectionProjections(ReferencePlan plan, List<String> ids) {
        if (plan.selectionProjections().isEmpty() || ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return ReferenceSelectionProjectionReader.read(plan.target(), ids, plan.selectionProjections(),
                selectionProjectionResolver());
    }

    private ReferenceTargetResolver selectionProjectionResolver() {
        ReferenceTargetResolver platform = PlatformAbilityRuntime.referenceTargetResolver();
        return new ReferenceTargetResolver() {
            @Override
            public java.util.Optional<ReferenceAbility<?>> resolve(ReferenceTarget target) {
                java.util.Optional<ReferenceAbility<?>> staticAbility = abilities.findReference(target);
                return staticAbility.isPresent() ? staticAbility : platform.resolve(target);
            }

            @Override
            public java.util.Optional<ReferencePlan> referencePlan(ReferenceTarget sourceTarget, String sourceField) {
                java.util.Optional<ReferencePlan> staticPlan = abilities.findReference(sourceTarget)
                        .flatMap(ability -> StaticReferenceResolver.plans(ability.modelClass()).stream()
                                .filter(plan -> sourceField.equals(plan.sourceField())).findFirst());
                return staticPlan.isPresent() ? staticPlan : platform.referencePlan(sourceTarget, sourceField);
            }
        };
    }

    private Criteria candidateCriteria(ReferencePlan plan, WebReferenceResolveRequest request) {
        return ReferenceCandidateCriteria.from(plan.candidateDependencies(), request.formValues());
    }

    private PageResult<ReferenceOption> referenceOptions(ReferencePlan plan, Criteria criteria, PageRequest pageRequest) {
        ReferenceTarget target = plan.target();
        ReferenceAbility<?> staticTarget = abilities.findReference(target).orElse(null);
        if (staticTarget != null) {
            PageResult<ReferenceOption> options = staticTarget.referenceOptions(plan, criteria, pageRequest);
            // Existing adapters that predate the plan-aware option method may return null;
            // retain their id/title behavior only for an unchanged plan.
            if (options != null || !plan.usesDefaultTargetFields()) return options;
            return staticTarget.referenceOptions(criteria, pageRequest);
        }
        if (dynamicRecords != null) {
            if (plan.usesDefaultTargetFields()) {
                return dynamicRecords.referenceOptions(target.moduleAlias(), target.entityAlias(), criteria, pageRequest);
            }
            return dynamicRecords.referenceAbility(target)
                    .orElseThrow(() -> new PlatformException("reference target is not available: " + target.qualifiedName()))
                    .referenceOptions(plan, criteria, pageRequest);
        }
        throw new PlatformException("reference target is not available: " + target.qualifiedName());
    }

    private ReferenceAbility<?> referenceAbility(ReferenceTarget target) {
        ReferenceAbility<?> staticTarget = abilities.findReference(target).orElse(null);
        if (staticTarget != null) return staticTarget;
        if (dynamicRecords != null) {
            return dynamicRecords.referenceAbility(target).orElseThrow(() ->
                    new PlatformException("reference target is not available: " + target.qualifiedName()));
        }
        throw new PlatformException("reference target is not available: " + target.qualifiedName());
    }

    private String titleField(ReferencePlan plan) {
        if (plan.targetLabelField() != null) {
            return plan.targetLabelField();
        }
        return abilities.findReference(plan.target())
                .flatMap(ability -> TitleFieldResolver.resolveFieldName(ability.modelClass()))
                .orElse("title");
    }

    private String persistedSourceTenant(StaticModuleDefinition source, String sourceRecordId) {
        if (sourceRecordId == null || sourceRecordId.isBlank() || source.modelClass() == null) {
            return null;
        }
        EntityContract sourceRecord = abilities.findByModel(source.modelClass())
                .map(ability -> selectReferenceVisibleSource(ability, sourceRecordId))
                .orElse(null);
        String tenantId = sourceRecord == null ? null : sourceRecord.getTenantId();
        return tenantId == null || tenantId.isBlank() ? null : tenantId;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static EntityContract selectReferenceVisibleSource(net.ximatai.muyun.spring.ability.CrudAbility<?> ability,
                                                               String sourceRecordId) {
        if (ability instanceof DataScopeAbility<?> scoped) {
            return (EntityContract) ((DataScopeAbility) scoped).selectForAction(PlatformAction.REFERENCE, sourceRecordId);
        }
        return (EntityContract) ability.select(sourceRecordId);
    }
}
