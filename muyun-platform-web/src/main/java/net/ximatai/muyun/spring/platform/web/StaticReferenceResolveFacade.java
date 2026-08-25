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
import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.ability.reference.ReferenceLoadPath;
import net.ximatai.muyun.spring.ability.reference.ReferenceLoadReader;
import net.ximatai.muyun.spring.ability.reference.ReferenceSelectionProjection;
import net.ximatai.muyun.spring.ability.reference.ReferenceCandidateCriteria;
import net.ximatai.muyun.spring.ability.reference.StaticReferenceResolver;
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
                .flatMap(modelClass -> StaticReferenceResolver.rules(modelClass).stream())
                .filter(rule -> rule.plan().sourceField().equals(fieldName))
                .findFirst()
                .map(rule -> rule.plan())
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
            criteria.like(titleField(plan.target()), request.fuzzy().trim());
        }
        PageRequest pageRequest = PageRequest.of(page.pageNum(), page.pageSize());
        PageResult<ReferenceOption> result = referenceOptions(plan.target(), criteria, pageRequest);
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
            criteria.in("id", ids);
        }
        Map<String, String> titles = referenceOptions(plan.target(), criteria, PageRequests.all()).getRecords().stream()
                .collect(java.util.stream.Collectors.toMap(ReferenceOption::id, ReferenceOption::title, (left, right) -> left));
        Map<String, Map<String, Object>> selectionProjections = selectionProjections(plan, ids);
        List<WebReferenceResolveResult> results = request.values().stream().map(value -> {
            String id = value == null ? null : String.valueOf(value);
            String title = id == null ? null : titles.get(id);
            WebReferenceResolveItem item = title == null ? null
                    : new WebReferenceResolveItem(id, title, WebReferenceMatchMode.KEY,
                    selectionProjections.get(id), null);
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private WebReferenceResolveResponse tree(ReferencePlan plan, WebReferenceResolveRequest request) {
        ReferenceTarget target = plan.target();
        ReferenceAbility<?> referenceAbility = abilities.findReference(target)
                .orElseThrow(() -> new PlatformException("tree reference target is not available: " + target.qualifiedName()));
        if (!(referenceAbility instanceof TreeAbility<?>)) {
            throw new PlatformException("tree reference target does not support tree delivery: " + target.qualifiedName());
        }
        Map<String, List<TreeCapable>> children = treeChildrenByParent((ReferenceAbility) referenceAbility,
                candidateCriteria(plan, request));
        Map<String, Map<String, Object>> selectionProjections = selectionProjections(plan,
                children.values().stream().flatMap(List::stream).map(TreeCapable::getId).toList());
        List<WebTreeNode<WebReferenceResolveItem>> nodes = children.getOrDefault(TreeAbility.ROOT_ID, List.of()).stream()
                .map(record -> treeNode((ReferenceAbility) referenceAbility, children, record, selectionProjections))
                .toList();
        return new WebReferenceResolveResponse(nodes.isEmpty() ? WebReferenceResolveStatus.NOT_FOUND : WebReferenceResolveStatus.OK,
                WebReferenceResolveMode.TREE, List.of(), List.of(), 0, 0, nodes.size(), nodes);
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
                                                           Map<String, Map<String, Object>> selectionProjections) {
        WebReferenceResolveItem item = new WebReferenceResolveItem(record.getId(),
                referenceAbility.referenceTitle(record), null, selectionProjections.get(record.getId()), null);
        return new WebTreeNode<>(item, children.getOrDefault(record.getId(), List.of()).stream()
                .map(child -> treeNode(referenceAbility, children, child, selectionProjections))
                .toList());
    }

    private Map<String, Map<String, Object>> selectionProjections(ReferencePlan plan, List<String> ids) {
        if (plan.selectionProjections().isEmpty() || ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (ReferenceSelectionProjection projection : plan.selectionProjections()) {
            Map<String, Object> values = selectionProjectionValues(plan.target(), projection, ids);
            values.forEach((id, value) -> result.computeIfAbsent(id, ignored -> new LinkedHashMap<>())
                    .put(projection.key(), value));
        }
        return result.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                entry -> Map.copyOf(entry.getValue()), (left, right) -> left, LinkedHashMap::new));
    }

    private Map<String, Object> selectionProjectionValues(ReferenceTarget target,
                                                          ReferenceSelectionProjection projection,
                                                          List<String> ids) {
        if (projection.path().size() == 1) {
            Map<String, Map<String, Object>> values = targetProjections(target, ids, projection.path());
            return values.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                    entry -> entry.getValue().get(projection.targetField()), (left, right) -> left, LinkedHashMap::new));
        }
        ReferenceLoadPath path = selectionProjectionPath(target, projection);
        return ReferenceLoadReader.readAll(path, ids, this::referenceAbility);
    }

    private ReferenceLoadPath selectionProjectionPath(ReferenceTarget sourceTarget,
                                                       ReferenceSelectionProjection projection) {
        ReferenceTarget current = sourceTarget;
        List<ReferenceLoadPath.Hop> hops = new java.util.ArrayList<>();
        for (String field : projection.path().subList(0, projection.path().size() - 1)) {
            ReferenceTarget hopSource = current;
            ReferencePlan hop = StaticReferenceResolver.plans(referenceAbility(hopSource).modelClass()).stream()
                    .filter(candidate -> candidate.sourceField().equals(field))
                    .findFirst().orElseThrow(() -> new PlatformException("selection projection hop is not a declared reference: "
                            + hopSource.qualifiedName() + "." + field));
            if (hop.cardinality() != ReferenceCardinality.ONE) {
                throw new PlatformException("selection projection hop requires cardinality ONE: "
                        + hopSource.qualifiedName() + "." + field);
            }
            hops.add(new ReferenceLoadPath.Hop(hop.target(), field));
            current = hop.target();
        }
        return new ReferenceLoadPath("selection", sourceTarget, hops, projection.targetField(), projection.key());
    }

    private ReferenceAbility<?> referenceAbility(ReferenceTarget target) {
        return abilities.findReference(target)
                .orElseThrow(() -> new PlatformException("selection projection target is not a static reference: "
                        + target.qualifiedName()));
    }

    private Map<String, Map<String, Object>> targetProjections(ReferenceTarget target, List<String> ids,
                                                                 List<String> fields) {
        ReferenceAbility<?> staticTarget = abilities.findReference(target).orElse(null);
        if (staticTarget != null) return staticTarget.projections(ids, fields);
        if (dynamicRecords != null) return dynamicRecords.projections(target.moduleAlias(), target.entityAlias(), ids, fields);
        throw new PlatformException("reference target is not available: " + target.qualifiedName());
    }

    private Criteria candidateCriteria(ReferencePlan plan, WebReferenceResolveRequest request) {
        return ReferenceCandidateCriteria.from(plan.candidateDependencies(), request.formValues());
    }

    private PageResult<ReferenceOption> referenceOptions(ReferenceTarget target, Criteria criteria, PageRequest pageRequest) {
        ReferenceAbility<?> staticTarget = abilities.findReference(target).orElse(null);
        if (staticTarget != null) return staticTarget.referenceOptions(criteria, pageRequest);
        if (dynamicRecords != null) {
            return dynamicRecords.referenceOptions(target.moduleAlias(), target.entityAlias(), criteria, pageRequest);
        }
        throw new PlatformException("reference target is not available: " + target.qualifiedName());
    }

    private String titleField(ReferenceTarget target) {
        return abilities.findReference(target)
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
