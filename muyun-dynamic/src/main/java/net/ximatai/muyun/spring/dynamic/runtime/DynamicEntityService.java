package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.child.ChildPlan;
import net.ximatai.muyun.spring.ability.CacheAbility;
import net.ximatai.muyun.spring.ability.child.ChildAbility;
import net.ximatai.muyun.spring.ability.child.ChildRelation;
import net.ximatai.muyun.spring.ability.child.ChildrenAbility;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.ability.reference.ReferenceOption;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.ability.reference.ReferenceLoadPath;
import net.ximatai.muyun.spring.ability.reference.ReferenceLoadReader;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetProvider;
import net.ximatai.muyun.spring.ability.reference.ReferencerAbility;
import net.ximatai.muyun.spring.ability.security.FieldCryptoProvider;
import net.ximatai.muyun.spring.ability.security.FieldProtectionAbility;
import net.ximatai.muyun.spring.ability.security.FieldProtectionPlan;
import net.ximatai.muyun.spring.ability.security.FieldSigner;
import net.ximatai.muyun.spring.ability.security.ProtectedFieldAccessor;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.TenantUniqueConstraintProvider;
import net.ximatai.muyun.spring.ability.deletion.DeletionRecoveryAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.SortPartition;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.formula.FormulaRuntimeReport;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.common.time.BusinessTimeContext;
import net.ximatai.muyun.spring.common.time.PlatformTimeService;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionDefinition;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.security.FieldOutputContext;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityReferenceDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityReferenceLoadDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityReferencedByDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityRelationDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityStandardActionCatalog;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;

import java.util.Collection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

public class DynamicEntityService implements
        CrudAbility<DynamicRecord>,
        SoftDeleteAbility<DynamicRecord>,
        DeletionRecoveryAbility<DynamicRecord>,
        ChildAbility<DynamicRecord>,
        ChildrenAbility<DynamicRecord>,
        ReferencerAbility<DynamicRecord>,
        CacheAbility<DynamicRecord>,
        FieldProtectionAbility<DynamicRecord>,
        TenantUniqueConstraintProvider<DynamicRecord>,
        ReferenceTargetProvider {
    private final DynamicRecordDao dao;
    private final String moduleAlias;
    private final DynamicRecordLifecycle lifecycle;
    private final ModuleDefinition module;
    private final Function<String, DynamicEntityService> relationServiceResolver;
    private final Function<ReferenceTarget, DynamicEntityService> referenceServiceResolver;
    private final String cacheNamespace;
    private final DynamicFieldValueValidator fieldValueValidator;
    private final FieldCryptoProvider fieldCryptoProvider;
    private final FieldSigner fieldSigner;
    private final FieldProtectionPlan<DynamicRecord> fieldProtectionPlan;
    private final PlatformTimeService timeService;
    private final DynamicOptionLoadPopulator optionLoadPopulator;

    DynamicEntityService(DynamicRecordDao dao, String moduleAlias) {
        this(dao, moduleAlias, DynamicRecordLifecycle.NONE, null, unsupportedRelationResolver(),
                unsupportedReferenceResolver(moduleAlias), null, DynamicFieldValueValidator.NONE,
                FieldCryptoProvider.UNAVAILABLE, FieldSigner.UNAVAILABLE, new PlatformTimeService());
    }

    static DynamicEntityService withLifecycle(DynamicRecordDao dao,
                                              String moduleAlias,
                                              DynamicRecordLifecycle lifecycle) {
        return new DynamicEntityService(dao, moduleAlias, lifecycle, null, unsupportedRelationResolver(),
                unsupportedReferenceResolver(moduleAlias), null, DynamicFieldValueValidator.NONE,
                FieldCryptoProvider.UNAVAILABLE, FieldSigner.UNAVAILABLE, new PlatformTimeService());
    }

    static DynamicEntityService withModule(DynamicRecordDao dao,
                                           String moduleAlias,
                                           DynamicRecordLifecycle lifecycle,
                                           ModuleDefinition module,
                                           Function<String, DynamicEntityService> relationServiceResolver) {
        return new DynamicEntityService(dao, moduleAlias, lifecycle, module, relationServiceResolver,
                sameModuleReferenceResolver(moduleAlias, relationServiceResolver), null,
                DynamicFieldValueValidator.NONE, FieldCryptoProvider.UNAVAILABLE, FieldSigner.UNAVAILABLE,
                new PlatformTimeService());
    }

    DynamicEntityService(DynamicRecordDao dao,
                                String moduleAlias,
                                DynamicRecordLifecycle lifecycle,
                                ModuleDefinition module,
                                Function<String, DynamicEntityService> relationServiceResolver,
                                Function<ReferenceTarget, DynamicEntityService> referenceServiceResolver,
                                String cacheNamespacePrefix,
                                DynamicFieldValueValidator fieldValueValidator,
                                FieldCryptoProvider fieldCryptoProvider,
                                FieldSigner fieldSigner,
                                PlatformTimeService timeService) {
        this(dao, moduleAlias, lifecycle, module, relationServiceResolver, referenceServiceResolver, cacheNamespacePrefix,
                fieldValueValidator, fieldCryptoProvider, fieldSigner, timeService, DynamicOptionLoadPopulator.NONE);
    }

    DynamicEntityService(DynamicRecordDao dao,
                         String moduleAlias,
                         DynamicRecordLifecycle lifecycle,
                         ModuleDefinition module,
                         Function<String, DynamicEntityService> relationServiceResolver,
                         Function<ReferenceTarget, DynamicEntityService> referenceServiceResolver,
                         String cacheNamespacePrefix,
                         DynamicFieldValueValidator fieldValueValidator,
                         FieldCryptoProvider fieldCryptoProvider,
                         FieldSigner fieldSigner,
                         PlatformTimeService timeService,
                         DynamicOptionLoadPopulator optionLoadPopulator) {
        this.dao = Objects.requireNonNull(dao, "dao must not be null");
        this.moduleAlias = requireModuleAlias(moduleAlias);
        this.lifecycle = lifecycle == null ? DynamicRecordLifecycle.NONE : lifecycle;
        this.module = module;
        this.relationServiceResolver = Objects.requireNonNull(relationServiceResolver, "relationServiceResolver must not be null");
        this.referenceServiceResolver = Objects.requireNonNull(referenceServiceResolver,
                "referenceServiceResolver must not be null");
        this.cacheNamespace = resolveCacheNamespace(cacheNamespacePrefix);
        this.fieldValueValidator = Objects.requireNonNull(fieldValueValidator, "fieldValueValidator must not be null");
        this.fieldCryptoProvider = fieldCryptoProvider == null ? FieldCryptoProvider.UNAVAILABLE : fieldCryptoProvider;
        this.fieldSigner = fieldSigner == null ? FieldSigner.UNAVAILABLE : fieldSigner;
        this.timeService = timeService == null ? new PlatformTimeService() : timeService;
        this.optionLoadPopulator = optionLoadPopulator == null ? DynamicOptionLoadPopulator.NONE : optionLoadPopulator;
        this.fieldProtectionPlan = new FieldProtectionPlan<DynamicRecord>(dao.getEntity().fields().stream()
                .filter(field -> field.protection().enabled())
                .map(field -> (ProtectedFieldAccessor<DynamicRecord>) new DynamicProtectedFieldAccessor(field))
                .toList());
    }

    private static Function<String, DynamicEntityService> unsupportedRelationResolver() {
        return entityAlias -> {
            throw new IllegalStateException("dynamic relation service resolver is not configured");
        };
    }

    private static Function<ReferenceTarget, DynamicEntityService> unsupportedReferenceResolver(String moduleAlias) {
        return target -> {
            if (!Objects.equals(requireModuleAlias(moduleAlias), target.moduleAlias())) {
                throw new IllegalArgumentException(
                        "cross module dynamic reference is not supported: " + target.qualifiedName());
            }
            throw new IllegalStateException("dynamic reference service resolver is not configured");
        };
    }

    private static Function<ReferenceTarget, DynamicEntityService> sameModuleReferenceResolver(
            String moduleAlias,
            Function<String, DynamicEntityService> relationServiceResolver) {
        return target -> {
            if (!Objects.equals(requireModuleAlias(moduleAlias), target.moduleAlias())) {
                throw new IllegalArgumentException(
                        "cross module dynamic reference is not supported: " + target.qualifiedName());
            }
            return relationServiceResolver.apply(target.entityAlias());
        };
    }

    @Override
    public BaseDao<DynamicRecord, String> getDao() {
        return dao;
    }

    DynamicRecordDao dynamicDao() {
        return dao;
    }

    @Override
    public String getModuleAlias() {
        return moduleAlias;
    }

    @Override
    public ReferenceTarget referenceTarget() {
        return ReferenceTarget.of(moduleAlias, dao.getEntity().alias());
    }

    /** Exposes the dynamic entity through the shared reference-read contract. */
    public net.ximatai.muyun.spring.ability.reference.ReferenceAbility<?> referenceAbility() {
        requireCapability(EntityCapability.REFERENCE);
        return referenceRuntime();
    }

    public DynamicActionAvailability actionAvailability(String actionCode, DynamicRecord record) {
        if (record != null) {
            requireSameEntity(record);
        }
        EntityActionDefinition action = actionDefinition(actionCode);
        if (!action.enabled()) {
            return DynamicActionAvailability.unavailable(action.actionCode(), disabledActionMessage(action));
        }
        if (!action.hasAvailabilityCondition()) {
            return DynamicActionAvailability.available(action.actionCode());
        }
        DynamicRecord existing = record != null && record.getId() != null && !record.getId().isBlank()
                ? activeRaw(record.getId())
                : null;
        return new DynamicActionAvailabilityRuntime(dao.getEntity(), module).evaluate(action, record, existing);
    }

    @Override
    public String cacheNamespace() {
        return cacheNamespace;
    }

    @Override
    public FieldProtectionPlan<DynamicRecord> fieldProtectionPlan() {
        return fieldProtectionPlan;
    }

    @Override
    public FieldCryptoProvider fieldCryptoProvider() {
        return fieldCryptoProvider;
    }

    @Override
    public FieldSigner fieldSigner() {
        return fieldSigner;
    }

    @Override
    public DynamicRecord copyForCache(DynamicRecord entity) {
        return entity == null ? null : entity.copy();
    }

    @Override
    public List<net.ximatai.muyun.spring.common.model.constraint.TenantUniqueConstraintDefinition> tenantUniqueConstraints() {
        return dao.getEntity().resolvedTenantUniqueConstraints();
    }

    @Override
    public Object tenantUniqueConstraintValue(DynamicRecord entity, String fieldName) {
        return entity == null ? null : entity.getValue(fieldName);
    }

    @Override
    public void beforeInsert(DynamicRecord record) {
        rejectWriteProtectedFields(record);
        record.applyDefaultsForInsert();
        prepareDynamicAbilityDefaults(record);
        lifecycle.beforeInsert(record);
        record.formulaReport(formulaRuntime().beforeInsert(record));
        validateChildPayload(record);
        record.validateForInsert();
        validateFieldValues(record);
        validateReferenceValues(record);
        validateTreePlacement(record);
    }

    @Override
    public void beforeUpdate(DynamicRecord record) {
        rejectWriteProtectedFields(record);
        lifecycle.beforeUpdate(record);
        DynamicFormulaRuntime formulaRuntime = formulaRuntime();
        if (formulaRuntime.hasBeforeUpdateRules(record)) {
            FormulaRuntimeReport report = formulaRuntime.beforeUpdate(
                    record,
                    activeRaw(record.getId()),
                    existingChildrenForFormula(record)
            );
            record.formulaReport(report);
        } else {
            record.formulaReport(new FormulaRuntimeReport());
        }
        validateChildPayload(record);
        record.validateForUpdate();
        validateFieldValues(record);
        validateReferenceValues(record);
        validateTreePlacement(record);
    }

    @Override
    public void beforeDelete(String id) {
        lifecycle.beforeDelete(id);
    }

    @Override
    public void beforeRestore(String id) {
        DynamicRecord record = selectIgnoreSoftDelete(id);
        if (record != null) {
            validateReferenceValues(record, false);
        }
    }

    public DynamicFormulaPreviewResult previewFormula(DynamicRecord record) {
        DynamicRecord working = record == null ? new DynamicRecord(dao.getEntity()) : record.copy();
        return formulaRuntime().preview(working, null);
    }

    @Override
    public void afterSelect(DynamicRecord record) {
        lifecycle.afterSelect(record);
        optionLoadPopulator.populate(dao.getEntity(), record == null ? List.of() : List.of(record));
    }

    @Override
    public void afterReferenceSelect(DynamicRecord record) {
        populateReferenceReadFields(record == null ? List.of() : List.of(record));
    }

    @Override
    public void afterChanged(DynamicRecord record) {
        if (dao.getEntity().supports(EntityCapability.REFERENCE) && record != null && record.getId() != null) {
            referenceRuntime().clearReferenceReferrers(record.getId());
        }
    }

    @Override
    public Integer expectedVersionForUpdate(DynamicRecord record) {
        if (record.getVersion() != null) {
            return record.getVersion();
        }
        DynamicRecord current = activeRaw(record.getId());
        if (current == null) {
            throw new IllegalArgumentException("dynamic record not found: " + record.getId());
        }
        return current.getVersion();
    }

    private void prepareDynamicAbilityDefaults(DynamicRecord record) {
        if (dao.getEntity().supports(EntityCapability.TREE)
                && (record.parentId() == null || record.parentId().isBlank())) {
            record.parentId(DynamicTreeRuntime.ROOT_ID);
        }
        if (dao.getEntity().supports(EntityCapability.ENABLE) && record.enabled() == null) {
            record.enabled(Boolean.TRUE);
        }
        if (dao.getEntity().supports(EntityCapability.SORT) && record.sortOrder() == null) {
            record.sortOrder(nextSortOrder(record));
        }
    }

    private int nextSortOrder(DynamicRecord record) {
        Criteria scope = sortCriteria(record);
        int maxOrder = sortedList(scope).stream()
                .map(DynamicRecord::sortOrder)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0);
        if (maxOrder > Integer.MAX_VALUE - SortAbility.SORT_STEP) {
            List<String> orderedIds = sortedList(scope).stream()
                    .map(DynamicRecord::getId)
                    .toList();
            if (!orderedIds.isEmpty()) {
                reorder(orderedIds);
                maxOrder = sortedList(scope).stream()
                        .map(DynamicRecord::sortOrder)
                        .filter(Objects::nonNull)
                        .max(Integer::compareTo)
                        .orElse(0);
            }
        }
        if (maxOrder > Integer.MAX_VALUE - SortAbility.SORT_STEP) {
            throw new PlatformException("Cannot allocate dynamic sort order; sort scope is too large");
        }
        return maxOrder + SortAbility.SORT_STEP;
    }

    public int enable(String id) {
        return enable(id, null);
    }

    public int enable(String id, Integer expectedVersion) {
        requireCapability(EntityCapability.ENABLE);
        return updateEnabled(id, Boolean.TRUE, expectedVersion);
    }

    public int disable(String id) {
        return disable(id, null);
    }

    public int disable(String id, Integer expectedVersion) {
        requireCapability(EntityCapability.ENABLE);
        return updateEnabled(id, Boolean.FALSE, expectedVersion);
    }

    public boolean isEnabled(String id) {
        requireCapability(EntityCapability.ENABLE);
        DynamicRecord entity = selectActiveRaw(id);
        return entity != null && Boolean.TRUE.equals(entity.enabled());
    }

    public Criteria enabledCriteria(Criteria criteria) {
        requireCapability(EntityCapability.ENABLE);
        Criteria scoped = Criteria.of();
        if (criteria != null && !criteria.isEmpty()) {
            scoped.andGroup(criteria.getRoot());
        }
        scoped.eq(PlatformAbilityFields.ENABLED_FIELD, Boolean.TRUE);
        return scoped;
    }

    public Criteria queryCriteria(Collection<DynamicQueryCondition> conditions) {
        return new DynamicQueryCriteriaBuilder(dao.getEntity(), timeService, BusinessTimeContext.empty()).build(conditions);
    }

    @Override
    public PageResult<DynamicRecord> pageQuery(Criteria criteria, PageRequest pageRequest, Sort... sorts) {
        PageResult<DynamicRecord> page = getDao().pageQuery(activeCriteria(criteria), pageRequest, sorts);
        List<DynamicRecord> records = page.getRecords();
        applyReadPipeline(records);
        return PageResult.of(records, page.getTotal(), pageRequest);
    }

    @Override
    public List<DynamicRecord> list(Criteria criteria, PageRequest pageRequest, Sort... sorts) {
        List<DynamicRecord> records = getDao().query(activeCriteria(criteria), pageRequest, sorts);
        applyReadPipeline(records);
        return records;
    }

    @Override
    public List<DynamicRecord> list(Criteria criteria, Sort... sorts) {
        List<DynamicRecord> records = getDao().list(activeCriteria(criteria), sorts);
        applyReadPipeline(records);
        return records;
    }

    public List<DynamicRecord> sortedList(Criteria criteria) {
        requireCapability(EntityCapability.SORT);
        List<DynamicRecord> records;
        if (dao.getEntity().supports(EntityCapability.TREE)) {
            records = treeRuntime().sortedList(criteria).stream().map(DynamicTreeRecord::record).toList();
        } else {
            records = sortRuntime().sortedList(criteria).stream().map(DynamicSortRecord::record).toList();
        }
        applyReadPipeline(records);
        return records;
    }

    public void reorder(List<String> orderedIds) {
        requireCapability(EntityCapability.SORT);
        if (dao.getEntity().supports(EntityCapability.TREE)) {
            treeRuntime().reorder(orderedIds);
            return;
        }
        sortRuntime().reorder(orderedIds);
    }

    public void moveBefore(String id, String beforeId) {
        requireCapability(EntityCapability.SORT);
        if (dao.getEntity().supports(EntityCapability.TREE)) {
            treeRuntime().moveBefore(id, beforeId);
            return;
        }
        sortRuntime().moveBefore(id, beforeId);
    }

    public void moveAfter(String id, String afterId) {
        requireCapability(EntityCapability.SORT);
        if (dao.getEntity().supports(EntityCapability.TREE)) {
            treeRuntime().moveAfter(id, afterId);
            return;
        }
        sortRuntime().moveAfter(id, afterId);
    }

    public void moveInTree(String id, String previousId, String nextId, String parentId) {
        requireCapability(EntityCapability.TREE);
        treeRuntime().moveInTree(id, previousId, nextId, parentId);
    }

    public List<DynamicRecord> children(String parentId) {
        requireCapability(EntityCapability.TREE);
        return treeRuntime().children(parentId).stream()
                .map(DynamicTreeRecord::record)
                .peek(this::applyReadPipeline)
                .toList();
    }

    public List<DynamicRecord> children(Criteria scopeCriteria, String parentId) {
        requireCapability(EntityCapability.TREE);
        return treeRuntime().children(scopeCriteria, parentId).stream()
                .map(DynamicTreeRecord::record)
                .peek(this::applyReadPipeline)
                .toList();
    }

    @Override
    public List<DynamicRecord> selectChildRows(Criteria criteria) {
        List<DynamicRecord> rows;
        if (dao.getEntity().supports(EntityCapability.SORT)) {
            return sortedList(criteria);
        } else {
            rows = ChildAbility.super.selectChildRows(criteria);
        }
        rows.forEach(this::applyReadPipeline);
        return rows;
    }

    public List<String> ancestorIds(String id) {
        requireCapability(EntityCapability.TREE);
        return treeRuntime().ancestorIds(id);
    }

    public List<String> ancestorIdsAndSelf(String id) {
        requireCapability(EntityCapability.TREE);
        return treeRuntime().ancestorIdsAndSelf(id);
    }

    public List<String> descendantIds(String id) {
        requireCapability(EntityCapability.TREE);
        return treeRuntime().descendantIds(id);
    }

    public void validateTreePlacement(DynamicRecord record) {
        if (dao.getEntity().supports(EntityCapability.TREE)) {
            treeRuntime().validateTreePlacement(new DynamicTreeRecord(record));
        }
    }

    private Criteria sortCriteria(DynamicRecord record) {
        Criteria scope;
        if (dao.getEntity().supports(EntityCapability.TREE)) {
            scope = Criteria.of().eq(PlatformAbilityFields.TREE_PARENT_FIELD, record.parentId());
        } else {
            scope = Criteria.of();
        }
        for (String fieldName : dao.getEntity().sortPartitionFields()) {
            scope.eq(fieldName, record.getValue(fieldName));
        }
        return scope;
    }

    public SortPartition<DynamicRecord> sortPartition() {
        return new SortPartition<>() {
            @Override
            public Criteria criteriaFor(DynamicRecord record) {
                return sortCriteria(record);
            }

            @Override
            public void requireSamePartition(DynamicRecord left, DynamicRecord right) {
                if (dao.getEntity().supports(EntityCapability.TREE)
                        && !SortAbility.sameValue(left.parentId(), right.parentId())) {
                    throw new PlatformException("Tree sort can only move records within the same parent");
                }
                for (String fieldName : dao.getEntity().sortPartitionFields()) {
                    if (!SortAbility.sameValue(left.getValue(fieldName), right.getValue(fieldName))) {
                        throw new PlatformException("Sort can only move records within the same partition: " + fieldName);
                    }
                }
            }
        };
    }

    public String title(String id) {
        requireCapability(EntityCapability.REFERENCE);
        return referenceRuntime().title(id);
    }

    public String referenceTitle(DynamicRecord entity) {
        requireCapability(EntityCapability.REFERENCE);
        if (entity == null) {
            return null;
        }
        Object rendered = maskProtectedValue(PlatformAbilityFields.TITLE_FIELD, entity.title(), FieldOutputContext.REFERENCE);
        return rendered == null ? null : String.valueOf(rendered);
    }

    public Map<String, String> titles(Collection<String> ids) {
        requireCapability(EntityCapability.REFERENCE);
        return referenceRuntime().titles(ids);
    }

    public Map<String, Map<String, Object>> projections(Collection<String> ids, Collection<String> fieldNames) {
        requireCapability(EntityCapability.REFERENCE);
        if (ids == null || ids.isEmpty() || fieldNames == null || fieldNames.isEmpty()) {
            return Map.of();
        }
        LinkedHashSet<String> normalizedIds = new LinkedHashSet<>(ids);
        LinkedHashSet<String> normalizedFields = new LinkedHashSet<>(fieldNames);
        List<DynamicRecord> records = list(
                Criteria.of().in(StandardEntitySchema.ID_FIELD, List.copyOf(normalizedIds)),
                new PageRequest(0, Integer.MAX_VALUE)
        );
        Map<String, Map<String, Object>> loaded = new LinkedHashMap<>();
        for (DynamicRecord record : records) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (String fieldName : normalizedFields) {
                values.put(fieldName, maskProtectedValue(fieldName, record.getValue(fieldName), FieldOutputContext.REFERENCE));
            }
            loaded.put(record.getId(), Collections.unmodifiableMap(new LinkedHashMap<>(values)));
        }
        Map<String, Map<String, Object>> ordered = new LinkedHashMap<>();
        for (String id : normalizedIds) {
            if (loaded.containsKey(id)) {
                ordered.put(id, loaded.get(id));
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
    }

    public PageResult<ReferenceOption> referenceOptions(Criteria criteria, PageRequest pageRequest) {
        requireCapability(EntityCapability.REFERENCE);
        return referenceRuntime().referenceOptions(criteria, pageRequest);
    }

    public DynamicReferenceResolveResponse resolveReference(String sourceField,
                                                            DynamicReferenceResolveRequest request) {
        EntityReferenceDefinition reference = referenceDefinition(sourceField);
        ReferencePlan plan = reference.plan();
        return new DynamicReferenceResolver(this, plan, referenceService(plan.target()), reference.affects()).resolve(request);
    }

    @Override
    public List<ChildRelation<? extends EntityContract, DynamicRecord>> childRelations() {
        if (module == null) {
            return List.of();
        }
        List<ChildRelation<? extends EntityContract, DynamicRecord>> relations = new ArrayList<>();
        for (EntityRelationDefinition relation : module.relations()) {
            if (dao.getEntity().alias().equals(relation.parentEntityAlias())) {
                relations.add(toChildRelation(relation));
            }
        }
        return List.copyOf(relations);
    }

    @Override
    public Map<ReferenceTarget, Set<String>> collectReferenceIdsByTarget(DynamicRecord record) {
        if (record == null || module == null) {
            return Map.of();
        }
        requireSameEntity(record);
        Map<ReferenceTarget, Set<String>> ids = new LinkedHashMap<>();
        for (ReferencePlan plan : referencePlans()) {
            Object value = record.getValue(plan.sourceField());
            List<String> values = plan.normalizeValues(value);
            if (!values.isEmpty()) {
                ids.computeIfAbsent(plan.target(), ignored -> new LinkedHashSet<>())
                        .addAll(values);
            }
        }
        Map<ReferenceTarget, Set<String>> copy = new LinkedHashMap<>();
        ids.forEach((target, values) -> copy.put(target, Collections.unmodifiableSet(new LinkedHashSet<>(values))));
        return Collections.unmodifiableMap(copy);
    }

    private void populateReferenceTitles(DynamicRecord record) {
        populateReferenceTitles(record == null ? List.of() : List.of(record));
    }

    private void applyReadPipeline(DynamicRecord record) {
        restoreProtectedFieldsFromStorage(record);
        populateReferenceReadFields(record == null ? List.of() : List.of(record));
        optionLoadPopulator.populate(dao.getEntity(), record == null ? List.of() : List.of(record));
        refreshReferenceDependencies(record);
    }

    private void applyReadPipeline(List<DynamicRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        records.forEach(this::restoreProtectedFieldsFromStorage);
        populateReferenceReadFields(records);
        optionLoadPopulator.populate(dao.getEntity(), records);
        records.forEach(this::refreshReferenceDependencies);
    }

    private void populateReferenceReadFields(List<DynamicRecord> records) {
        populateReferenceTitles(records);
        populateReferenceLoads(records);
        populateReferencedBys(records);
    }

    private void populateReferenceLoads(List<DynamicRecord> records) {
        for (EntityReferenceLoadDefinition definition : referenceLoadDefinitions()) {
            EntityReferenceDefinition sourceReference = referenceDefinition(definition.sourceField());
            ReferenceLoadPath path = definition.path(sourceReference.target());
            for (DynamicRecord record : records) {
                List<String> ids = referencePlan(definition.sourceField()).normalizeValues(record.getValue(path.sourceField()));
                Object value = path.hops().isEmpty()
                        ? loadDirectReference(sourceReference, path, ids)
                        : loadReferencePath(path, ids);
                record.putVirtualValue(path.outputField(), value);
            }
        }
    }

    private Object loadDirectReference(EntityReferenceDefinition source,
                                       ReferenceLoadPath path,
                                       List<String> ids) {
        if (ids.isEmpty()) {
            return source.cardinality() == net.ximatai.muyun.spring.ability.reference.ReferenceCardinality.MANY
                    ? List.of() : null;
        }
        Map<String, Map<String, Object>> values = referenceAbility(path.sourceTarget())
                .projections(ids, List.of(path.terminalField()));
        if (source.cardinality() == net.ximatai.muyun.spring.ability.reference.ReferenceCardinality.MANY) {
            return ids.stream().map(id -> values.getOrDefault(id, Map.of()).get(path.terminalField()))
                    .filter(java.util.Objects::nonNull).toList();
        }
        return values.getOrDefault(ids.getFirst(), Map.of()).get(path.terminalField());
    }

    private Object loadReferencePath(ReferenceLoadPath path, List<String> ids) {
        return ReferenceLoadReader.read(path, ids, this::referenceAbility);
    }

    private void populateReferencedBys(List<DynamicRecord> records) {
        for (EntityReferencedByDefinition definition : referencedByDefinitions()) {
            DynamicEntityService source = relationServiceResolver.apply(definition.sourceEntityAlias());
            for (DynamicRecord record : records) {
                List<DynamicRecord> rows = record.getId() == null ? List.of()
                        : source.list(Criteria.of().eq(definition.sourceField(), record.getId()));
                record.putVirtualValue(definition.outputField(), rows);
            }
        }
    }

    private void populateReferenceTitles(List<DynamicRecord> records) {
        if (records == null || records.isEmpty() || module == null) {
            return;
        }
        List<ReferencePlan> plans = referencePlans().stream()
                .filter(plan -> !plan.projections().isEmpty())
                .toList();
        if (plans.isEmpty()) {
            return;
        }
        Map<ReferenceTarget, ReferenceReadRequest> requests = new LinkedHashMap<>();
        for (ReferencePlan plan : plans) {
            ReferenceReadRequest request = requests.computeIfAbsent(plan.target(), ignored -> new ReferenceReadRequest());
            plan.projections().stream().map(net.ximatai.muyun.spring.ability.reference.ReferenceProjection::targetField)
                    .forEach(request.fields::add);
            for (DynamicRecord record : records) {
                requireSameEntity(record);
                request.ids.addAll(plan.normalizeValues(record.getValue(plan.sourceField())));
            }
        }
        Map<ReferenceTarget, ReferenceReadValues> values = new LinkedHashMap<>();
        for (Map.Entry<ReferenceTarget, ReferenceReadRequest> entry : requests.entrySet()) {
            ReferenceReadRequest request = entry.getValue();
            if (request.ids.isEmpty()) {
                values.put(entry.getKey(), ReferenceReadValues.EMPTY);
                continue;
            }
            net.ximatai.muyun.spring.ability.reference.ReferenceAbility<?> target = referenceAbility(entry.getKey());
            List<String> ids = List.copyOf(request.ids);
            values.put(entry.getKey(), new ReferenceReadValues(
                    Map.of(),
                    request.fields.isEmpty() ? Map.of() : target.projections(ids, List.copyOf(request.fields))));
        }
        for (DynamicRecord record : records) {
            for (ReferencePlan plan : plans) {
                List<String> ids = plan.normalizeValues(record.getValue(plan.sourceField()));
                for (net.ximatai.muyun.spring.ability.reference.ReferenceProjection projection : plan.projections()) {
                    record.putVirtualValue(projection.outputField(), ids.isEmpty() ? null : referenceProjectionValue(ids,
                            values.get(plan.target()).projections, plan, projection.targetField()));
                }
            }
        }
    }

    private static final class ReferenceReadRequest {
        private final Set<String> ids = new LinkedHashSet<>();
        private final Set<String> fields = new LinkedHashSet<>();
    }

    private record ReferenceReadValues(Map<String, String> titles, Map<String, Map<String, Object>> projections) {
        private static final ReferenceReadValues EMPTY = new ReferenceReadValues(Map.of(), Map.of());
    }

    private List<ReferencePlan> referencePlans() {
        if (module == null) {
            return List.of();
        }
        return module.references().stream()
                .filter(reference -> dao.getEntity().alias().equals(reference.sourceEntityAlias()))
                .map(EntityReferenceDefinition::plan)
                .toList();
    }

    private List<EntityReferenceLoadDefinition> referenceLoadDefinitions() {
        if (module == null) {
            return List.of();
        }
        return module.referenceLoads().stream()
                .filter(load -> dao.getEntity().alias().equals(load.sourceEntityAlias()))
                .toList();
    }

    private List<EntityReferencedByDefinition> referencedByDefinitions() {
        if (module == null) {
            return List.of();
        }
        return module.referencedBys().stream()
                .filter(definition -> dao.getEntity().alias().equals(definition.targetEntityAlias()))
                .toList();
    }

    private ReferencePlan referencePlan(String sourceField) {
        return referenceDefinition(sourceField).plan();
    }

    private EntityReferenceDefinition referenceDefinition(String sourceField) {
        if (sourceField == null || sourceField.isBlank()) {
            throw new ModuleDefinitionException("reference sourceField must not be blank");
        }
        if (module == null) {
            throw new ModuleDefinitionException("unknown dynamic reference: "
                    + moduleAlias + "." + dao.getEntity().alias() + "." + sourceField);
        }
        return module.references().stream()
                .filter(reference -> dao.getEntity().alias().equals(reference.sourceEntityAlias()))
                .filter(reference -> sourceField.equals(reference.sourceField()))
                .findFirst()
                .orElseThrow(() -> new ModuleDefinitionException("unknown dynamic reference: "
                        + moduleAlias + "." + dao.getEntity().alias() + "." + sourceField));
    }

    void requireSameEntityAliasForReference(ReferencePlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("reference plan must not be null");
        }
        referencePlan(plan.sourceField());
    }

    private DynamicEntityService referenceService(ReferenceTarget target) {
        return referenceServiceResolver.apply(target);
    }

    private net.ximatai.muyun.spring.ability.reference.ReferenceAbility<?> referenceAbility(ReferenceTarget target) {
        try {
            return referenceService(target).referenceAbility();
        } catch (RuntimeException dynamicResolutionFailure) {
            return net.ximatai.muyun.spring.ability.PlatformAbilityRuntime.referenceTargetResolver()
                    .resolve(target)
                    .orElseThrow(() -> dynamicResolutionFailure);
        }
    }

    private Object referenceProjectionValue(List<String> ids,
                                            Map<String, Map<String, Object>> loaded,
                                            ReferencePlan plan,
                                            String sourceField) {
        if (plan.cardinality() == ReferenceCardinality.MANY) {
            return ids.stream()
                    .map(id -> fieldValue(loaded, id, sourceField))
                    .filter(Objects::nonNull)
                    .toList();
        }
        return fieldValue(loaded, ids.getFirst(), sourceField);
    }

    private Object fieldValue(Map<String, Map<String, Object>> loaded, String id, String sourceField) {
        Map<String, Object> fields = loaded.get(id);
        return fields == null ? null : fields.get(sourceField);
    }

    DynamicRecord activeRaw(String id) {
        return getDao().query(activeCriteria(Criteria.of().eq(StandardEntitySchema.ID_FIELD, id)), new PageRequest(0, 1))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private DynamicTreeRuntime treeRuntime() {
        return new DynamicTreeRuntime(this);
    }

    private DynamicSortRuntime sortRuntime() {
        return new DynamicSortRuntime(this);
    }

    private DynamicReferenceRuntime referenceRuntime() {
        return new DynamicReferenceRuntime(this);
    }

    private DynamicFormulaRuntime formulaRuntime() {
        return new DynamicFormulaRuntime(moduleAlias, dao.getEntity(), module);
    }

    private EntityActionDefinition actionDefinition(String actionCode) {
        if (actionCode == null || actionCode.isBlank()) {
            throw new IllegalArgumentException("dynamic action code must not be blank");
        }
        EntityDefinition entity = dao.getEntity();
        EntityActionDefinition configured = configuredAction(actionCode);
        if (configured != null) {
            return configured;
        }
        if (!EntityStandardActionCatalog.supportsStandardAction(entity, actionCode)) {
            throw new IllegalArgumentException("unknown dynamic action: " + moduleAlias + "." + entity.alias() + "." + actionCode);
        }
        return EntityStandardActionCatalog.from(entity).stream()
                .filter(action -> action.actionCode().equals(actionCode))
                .findFirst()
                .orElseThrow();
    }

    private String disabledActionMessage(EntityActionDefinition action) {
        return action.unavailableMessage() == null ? "action is disabled" : action.unavailableMessage();
    }

    private EntityActionDefinition configuredAction(String actionCode) {
        if (module == null) {
            return null;
        }
        return module.actions().stream()
                .filter(action -> dao.getEntity().alias().equals(action.entityAlias()))
                .filter(action -> action.actionCode().equals(actionCode))
                .findFirst()
                .orElse(null);
    }

    private Map<String, List<DynamicRecord>> existingChildrenForFormula(DynamicRecord record) {
        if (module == null || record == null || record.getId() == null || record.getId().isBlank()) {
            return Map.of();
        }
        Map<String, List<DynamicRecord>> values = new LinkedHashMap<>();
        for (EntityRelationDefinition relation : module.relations()) {
            if (!dao.getEntity().alias().equals(relation.parentEntityAlias())
                    || !record.getChildren().containsKey(relation.code())
                    || record.getChildren(relation.code()) == null) {
                continue;
            }
            DynamicEntityService childService = relationServiceResolver.apply(relation.childEntityAlias());
            values.put(relation.code(), childService.selectChildRows(
                    Criteria.of().eq(relation.childForeignKeyField(), record.getId())
            ));
        }
        return values;
    }

    private void requireCapability(EntityCapability capability) {
        if (!dao.getEntity().supports(capability)) {
            throw new PlatformException("dynamic entity does not support capability: " + capability);
        }
    }

    private int updateEnabled(String id, Boolean enabled, Integer expectedVersion) {
        DynamicRecord entity = selectActiveRaw(id);
        if (entity == null) {
            return 0;
        }
        entity.enabled(enabled);
        if (expectedVersion != null) {
            entity.setVersion(expectedVersion);
        }
        return update(entity);
    }

    private ChildRelation<DynamicRecord, DynamicRecord> toChildRelation(EntityRelationDefinition relation) {
        ChildPlan plan = relation.plan(module == null ? null : module.moduleAlias(),
                module == null ? List.of() : module.references());
        DynamicEntityService childService = relationServiceResolver.apply(plan.childEntityAlias());
        ChildRelation<DynamicRecord, DynamicRecord> childRelation = new ChildRelation<>(
                plan.relationCode(), childService,
                (child, parentId) -> child.putPlatformValue(plan.childForeignKeyField(), parentId),
                plan.childForeignKeyField(),
                parent -> parent.getChildren(plan.relationCode())
        );
        if (plan.autoPopulate()) {
            childRelation.autoPopulate((parent, children) -> parent.setChildren(plan.relationCode(), children));
        }
        if (plan.cascadeOnParentUnavailable()) {
            childRelation.cascadeOnParentUnavailable();
        }
        return childRelation;
    }


    private void validateChildPayload(DynamicRecord record) {
        requireSameEntity(record);
        if (module == null || record.getChildren().isEmpty()) {
            return;
        }
        Map<String, EntityRelationDefinition> relations = new LinkedHashMap<>();
        for (EntityRelationDefinition relation : module.relations()) {
            if (dao.getEntity().alias().equals(relation.parentEntityAlias())) {
                relations.put(relation.code(), relation);
            }
        }
        for (Map.Entry<String, List<DynamicRecord>> entry : record.getChildren().entrySet()) {
            EntityRelationDefinition relation = relations.get(entry.getKey());
            if (relation == null) {
                throw new IllegalArgumentException("unknown dynamic child relation: " + entry.getKey());
            }
            List<DynamicRecord> children = entry.getValue();
            if (children == null) {
                continue;
            }
            for (DynamicRecord child : children) {
                if (!relation.childEntityAlias().equals(child.getEntity().alias())) {
                    throw new IllegalArgumentException("dynamic child entity mismatch: " + child.getEntity().alias());
                }
            }
        }
    }

    private void validateFieldValues(DynamicRecord record) {
        requireSameEntity(record);
        for (FieldDefinition field : dao.getEntity().fields()) {
            if (record.getValues().containsKey(field.code())) {
                fieldValueValidator.validate(moduleAlias, dao.getEntity(), field, record.getValue(field.code()));
            }
        }
    }

    private void validateReferenceValues(DynamicRecord record) {
        validateReferenceValues(record, true);
    }

    private void validateReferenceValues(DynamicRecord record, boolean explicitFieldsOnly) {
        requireSameEntity(record);
        if (module == null) {
            return;
        }
        Map<String, FieldDefinition> fields = dao.getEntity().fields().stream()
                .collect(java.util.stream.Collectors.toMap(FieldDefinition::fieldName, Function.identity()));
        for (ReferencePlan plan : referencePlans()) {
            if (explicitFieldsOnly && !record.isExplicitlySet(plan.sourceField())) {
                continue;
            }
            FieldDefinition field = fields.get(plan.sourceField());
            List<String> ids = plan.normalizeValues(record.getValue(plan.sourceField()));
            if (field != null && field.isRequired() && ids.isEmpty()) {
                throw new IllegalArgumentException("required dynamic reference field must not be blank: " + plan.sourceField());
            }
            validateReferenceIds(plan, ids);
        }
    }

    private void validateReferenceIds(ReferencePlan plan, List<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        Set<String> resolved;
        try {
            resolved = referenceService(plan.target()).list(
                            Criteria.of().in(StandardEntitySchema.ID_FIELD, ids),
                            new PageRequest(0, ids.size()))
                    .stream()
                    .map(DynamicRecord::getId)
                    .collect(java.util.stream.Collectors.toSet());
        } catch (RuntimeException dynamicResolutionFailure) {
            resolved = referenceAbility(plan.target()).titles(ids).keySet();
        }
        Set<String> resolvedIds = resolved;
        List<String> unavailable = ids.stream().filter(id -> !resolvedIds.contains(id)).toList();
        if (!unavailable.isEmpty()) {
            throw new IllegalArgumentException("dynamic reference target not found: "
                    + plan.target().qualifiedName() + "."
                    + (unavailable.size() == 1 ? unavailable.getFirst() : unavailable));
        }
    }

    private void rejectWriteProtectedFields(DynamicRecord record) {
        requireSameEntity(record);
        for (FieldDefinition field : dao.getEntity().fields()) {
            if (field.behavior().writeProtected() && record.isExplicitlySet(field.code())) {
                throw new IllegalArgumentException("dynamic field is write protected: " + field.code());
            }
        }
    }

    private void requireSameEntity(DynamicRecord record) {
        if (!dao.getEntity().alias().equals(record.getEntity().alias())) {
            throw new IllegalArgumentException("dynamic record entity mismatch: " + record.getEntity().alias());
        }
    }

    private static String requireModuleAlias(String value) {
        Objects.requireNonNull(value, "moduleAlias must not be null");
        if (!value.contains(".")) {
            throw new IllegalArgumentException("dynamic moduleAlias must be a platform module alias: " + value);
        }
        return value;
    }

    private String resolveCacheNamespace(String cacheNamespacePrefix) {
        String suffix = moduleAlias + "." + dao.getEntity().alias();
        if (cacheNamespacePrefix != null && !cacheNamespacePrefix.isBlank()) {
            return cacheNamespacePrefix + "::" + suffix;
        }
        return suffix + "::" + System.identityHashCode(dao);
    }
}
