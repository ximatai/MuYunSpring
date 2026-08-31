package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
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
import net.ximatai.muyun.spring.ability.reference.ReferenceReadPipeline;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetProvider;
import net.ximatai.muyun.spring.ability.reference.ReferencerAbility;
import net.ximatai.muyun.spring.ability.discriminator.DiscriminatedValueCasePlan;
import net.ximatai.muyun.spring.ability.discriminator.DiscriminatedValuePlan;
import net.ximatai.muyun.spring.ability.discriminator.DiscriminatedValueSource;
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
import net.ximatai.muyun.database.core.orm.AggregateQuery;
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
import net.ximatai.muyun.spring.dynamic.capability.CapabilityModuleRegistry;

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
    private final DynamicEntityCapabilityRuntimeBundle capabilityRuntimes;

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
        this.capabilityRuntimes = DynamicEntityCapabilityRuntimeBundle.create(this, this.moduleAlias,
                dao.getEntity(), module);
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
        return capabilityRuntimes.reference();
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

    /** Uses an already-scoped persisted record and deliberately avoids another activeRaw lookup. */
    DynamicActionAvailability actionAvailabilityPersisted(String actionCode, DynamicRecord record) {
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
        return new DynamicActionAvailabilityRuntime(dao.getEntity(), module).evaluate(action, record, record);
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
        record.formulaReport(capabilityRuntimes.formula().beforeInsert(record));
        normalizeDiscriminatedValues(record, null);
        validateChildPayload(record);
        record.validateForInsert();
        validateFieldValues(record);
        validateReferenceValues(record);
        validateTreePlacement(record);
    }

    @Override
    public void beforeUpdate(DynamicRecord record) {
        beforeUpdate(record, record == null ? null : activeRaw(record.getId()));
    }

    /**
     * Reuses the mutation snapshot supplied by {@link net.ximatai.muyun.spring.ability.CrudAbility}
     * so formula evaluation, discriminated values and reference validation observe one persisted
     * state throughout an update.
     */
    @Override
    public void beforeUpdate(DynamicRecord record, DynamicRecord existing) {
        rejectWriteProtectedFields(record);
        lifecycle.beforeUpdate(record);
        DynamicFormulaRuntime formulaRuntime = capabilityRuntimes.formula();
        if (formulaRuntime.hasBeforeUpdateRules(record)) {
            FormulaRuntimeReport report = formulaRuntime.beforeUpdate(
                    record,
                    existing,
                    existingChildrenForFormula(record)
            );
            record.formulaReport(report);
        } else {
            record.formulaReport(new FormulaRuntimeReport());
        }
        normalizeDiscriminatedValues(record, existing);
        validateChildPayload(record);
        record.validateForUpdate();
        validateFieldValues(record);
        validateReferenceValues(record, existing);
        validateTreePlacement(record);
    }

    @Override
    public DynamicRecord selectExistingForScopedMutation(DynamicRecord record) {
        return record == null || record.getId() == null || record.getId().isBlank()
                ? null
                : activeRaw(record.getId());
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
        if (working.getId() == null || working.getId().isBlank()) {
            return capabilityRuntimes.formula().preview(working, null, Map.of());
        }
        DynamicRecord existing = activeRaw(working.getId());
        if (existing == null) {
            throw new IllegalArgumentException("dynamic record not found: " + working.getId());
        }
        return capabilityRuntimes.formula().preview(working, existing, existingChildrenForFormula(working));
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
            capabilityRuntimes.reference().clearReferenceReferrers(record.getId());
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
        if (dao.getEntity().supports(EntityCapability.ENABLE)) {
            CapabilityModuleRegistry.defaultRegistry().require(EntityCapability.ENABLE,
                            net.ximatai.muyun.spring.dynamic.capability.EnableCapabilityModule.class).definition()
                    .applyCreateDefault(record.enabled(), record::enabled);
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
        capabilityRuntimes.require(EntityCapability.ENABLE);
        return updateEnabled(id, Boolean.TRUE, expectedVersion);
    }

    public int disable(String id) {
        return disable(id, null);
    }

    public int disable(String id, Integer expectedVersion) {
        capabilityRuntimes.require(EntityCapability.ENABLE);
        return updateEnabled(id, Boolean.FALSE, expectedVersion);
    }

    public boolean isEnabled(String id) {
        capabilityRuntimes.require(EntityCapability.ENABLE);
        DynamicRecord entity = selectActiveRaw(id);
        return entity != null && Boolean.TRUE.equals(entity.enabled());
    }

    public Criteria enabledCriteria(Criteria criteria) {
        capabilityRuntimes.require(EntityCapability.ENABLE);
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

    /** Aggregate active rows through the entity's metadata-aware single-table DAO. */
    public List<Map<String, Object>> aggregate(Criteria criteria, AggregateQuery query) {
        return dao.aggregate(activeCriteria(criteria), query);
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

    /**
     * Applies the entity-owned read pipeline to children already selected through an authorised
     * parent aggregate. It deliberately does not execute a new child query: aggregate VIEW
     * visibility is owned by the parent relation, while reference and option presentation still
     * belongs to the child entity's metadata.
     */
    List<DynamicRecord> enrichAggregateViewChildren(List<DynamicRecord> records) {
        if (records == null || records.isEmpty()) return List.of();
        List<DynamicRecord> copies = records.stream().map(DynamicRecord::copy).toList();
        applyReadPipeline(copies);
        return List.copyOf(copies);
    }

    public List<DynamicRecord> sortedList(Criteria criteria) {
        capabilityRuntimes.require(EntityCapability.SORT);
        List<DynamicRecord> records;
        if (dao.getEntity().supports(EntityCapability.TREE)) {
            records = capabilityRuntimes.tree().sortedList(criteria).stream().map(DynamicTreeRecord::record).toList();
        } else {
            records = capabilityRuntimes.sort().sortedList(criteria).stream().map(DynamicSortRecord::record).toList();
        }
        applyReadPipeline(records);
        return records;
    }

    public void reorder(List<String> orderedIds) {
        capabilityRuntimes.require(EntityCapability.SORT);
        if (dao.getEntity().supports(EntityCapability.TREE)) {
            capabilityRuntimes.tree().reorder(orderedIds);
            return;
        }
        capabilityRuntimes.sort().reorder(orderedIds);
    }

    public void moveBefore(String id, String beforeId) {
        capabilityRuntimes.require(EntityCapability.SORT);
        if (dao.getEntity().supports(EntityCapability.TREE)) {
            capabilityRuntimes.tree().moveBefore(id, beforeId);
            return;
        }
        capabilityRuntimes.sort().moveBefore(id, beforeId);
    }

    public void moveAfter(String id, String afterId) {
        capabilityRuntimes.require(EntityCapability.SORT);
        if (dao.getEntity().supports(EntityCapability.TREE)) {
            capabilityRuntimes.tree().moveAfter(id, afterId);
            return;
        }
        capabilityRuntimes.sort().moveAfter(id, afterId);
    }

    public void moveInTree(String id, String previousId, String nextId, String parentId) {
        capabilityRuntimes.tree().moveInTree(id, previousId, nextId, parentId);
    }

    public List<DynamicRecord> children(String parentId) {
        return capabilityRuntimes.tree().children(parentId).stream()
                .map(DynamicTreeRecord::record)
                .peek(this::applyReadPipeline)
                .toList();
    }

    public List<DynamicRecord> children(Criteria scopeCriteria, String parentId) {
        return capabilityRuntimes.tree().children(scopeCriteria, parentId).stream()
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
        return capabilityRuntimes.tree().ancestorIds(id);
    }

    public List<String> ancestorIdsAndSelf(String id) {
        return capabilityRuntimes.tree().ancestorIdsAndSelf(id);
    }

    public List<String> descendantIds(String id) {
        return capabilityRuntimes.tree().descendantIds(id);
    }

    public void validateTreePlacement(DynamicRecord record) {
        if (dao.getEntity().supports(EntityCapability.TREE)) {
            capabilityRuntimes.tree().validateTreePlacement(new DynamicTreeRecord(record));
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
        return capabilityRuntimes.reference().title(id);
    }

    public String referenceTitle(DynamicRecord entity) {
        capabilityRuntimes.require(EntityCapability.REFERENCE);
        if (entity == null) {
            return null;
        }
        Object rendered = maskProtectedValue(PlatformAbilityFields.TITLE_FIELD, entity.title(), FieldOutputContext.REFERENCE);
        return rendered == null ? null : String.valueOf(rendered);
    }

    public Map<String, String> titles(Collection<String> ids) {
        return capabilityRuntimes.reference().titles(ids);
    }

    public Map<String, Map<String, Object>> projections(Collection<String> ids, Collection<String> fieldNames) {
        capabilityRuntimes.require(EntityCapability.REFERENCE);
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
        return capabilityRuntimes.reference().referenceOptions(criteria, pageRequest);
    }

    PageResult<ReferenceOption> referenceOptions(ReferencePlan plan, Criteria criteria, PageRequest pageRequest) {
        requireReferenceTargetFields(plan);
        PageResult<DynamicRecord> page = pageQuery(criteria, pageRequest);
        List<ReferenceOption> options = page.getRecords().stream()
                .map(record -> new ReferenceOption(record.getId(), referenceLabel(record, plan)))
                .toList();
        return PageResult.of(options, page.getTotal(), pageRequest);
    }

    public DynamicReferenceResolveResponse resolveReference(String sourceField,
                                                            DynamicReferenceResolveRequest request) {
        EntityReferenceDefinition reference = referenceDefinition(sourceField);
        ReferencePlan plan = reference.plan();
        try {
            return new DynamicReferenceResolver(this, plan, referenceService(plan.target()), reference.affects())
                    .resolve(request);
        } catch (ModuleDefinitionException dynamicTargetUnavailable) {
            var staticTarget = PlatformAbilityRuntime.referenceTargetResolver().resolve(plan.target());
            if (staticTarget.isEmpty()) {
                throw dynamicTargetUnavailable;
            }
            return new DynamicReferenceResolver(this, plan, staticTarget.get(), reference.affects()).resolve(request);
        }
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
    public boolean usesAutomaticChildRelations() {
        return false;
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
                ids.computeIfAbsent(plan.target(), ignored -> new LinkedHashSet<>()).addAll(values);
            }
        }
        Map<ReferenceTarget, Set<String>> copy = new LinkedHashMap<>();
        ids.forEach((target, values) -> copy.put(target, Collections.unmodifiableSet(new LinkedHashSet<>(values))));
        return Collections.unmodifiableMap(copy);
    }

    private void populateReferenceTitles(DynamicRecord record) {
        populateDeclaredReferenceProjections(record == null ? List.of() : List.of(record));
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
        populateDeclaredReferenceProjections(records);
        populateReferencedBys(records);
    }

    /** Uses the same target-grouped executor as static records; storage is the only source-specific concern. */
    private void populateDeclaredReferenceProjections(List<DynamicRecord> records) {
        if (records == null || records.isEmpty() || module == null) return;
        List<ReferenceLoadPath> paths = referenceLoadDefinitions().stream()
                .map(definition -> definition.path(referenceDefinition(definition.sourceField()).target()))
                .toList();
        new ReferenceReadPipeline<DynamicRecord>(referencePlans(), paths,
                DynamicRecord::getValues,
                (record, output) -> output.forEach(record::putVirtualValue),
                this::referenceAbility,
                net.ximatai.muyun.spring.ability.PlatformAbilityRuntime.referenceReadObserver())
                .populate(records);
    }

    private void populateReferencedBys(List<DynamicRecord> records) {
        for (EntityReferencedByDefinition definition : referencedByDefinitions()) {
            DynamicEntityService source = relationServiceResolver.apply(definition.sourceEntityAlias());
            List<String> targetIds = records.stream().map(DynamicRecord::getId)
                    .filter(id -> id != null && !id.isBlank()).distinct().toList();
            Map<String, List<DynamicRecord>> rowsByTarget = new LinkedHashMap<>();
            if (!targetIds.isEmpty()) {
                for (DynamicRecord row : source.list(Criteria.of().in(definition.sourceField(), targetIds))) {
                    Object targetId = row.getValue(definition.sourceField());
                    if (targetId != null) rowsByTarget.computeIfAbsent(String.valueOf(targetId), ignored -> new ArrayList<>()).add(row);
                }
            }
            for (DynamicRecord record : records) {
                record.putVirtualValue(definition.outputField(), List.copyOf(
                        rowsByTarget.getOrDefault(record.getId(), List.of())));
            }
        }
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

    /**
     * Adds read-side companions required to render the requested reference fields without a
     * client-side resolve round-trip.  The rule mirrors static list projection semantics.
     */
    List<String> expansionOutputFields(java.util.Collection<String> requestedFields) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        if (requestedFields != null) {
            requestedFields.stream().filter(name -> name != null && !name.isBlank()).map(String::trim)
                    .forEach(fields::add);
        }
        referencePlans().forEach(plan -> {
            if (fields.contains(plan.sourceField())) {
                plan.projections().forEach(projection -> fields.add(projection.outputField()));
            }
        });
        referenceLoadDefinitions().forEach(load -> {
            if (fields.contains(load.sourceField())) {
                fields.add(load.outputField());
            }
        });
        return List.copyOf(fields);
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

    ReferencePlan referencePlan(String sourceField) {
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

    DynamicEntityService referenceService(ReferenceTarget target) {
        return referenceServiceResolver.apply(target);
    }

    /** Supplies dynamic plans first and falls back to the platform's static/dynamic target registry. */
    net.ximatai.muyun.spring.ability.reference.ReferenceTargetResolver referenceTargetResolver() {
        return new net.ximatai.muyun.spring.ability.reference.ReferenceTargetResolver() {
            @Override
            public java.util.Optional<net.ximatai.muyun.spring.ability.reference.ReferenceAbility<?>> resolve(
                    ReferenceTarget target) {
                try {
                    return java.util.Optional.of(referenceAbility(target));
                } catch (RuntimeException ignored) {
                    return net.ximatai.muyun.spring.ability.PlatformAbilityRuntime.referenceTargetResolver().resolve(target);
                }
            }

            @Override
            public java.util.Optional<ReferencePlan> referencePlan(ReferenceTarget sourceTarget, String sourceField) {
                try {
                    return java.util.Optional.of(referenceService(sourceTarget).referencePlan(sourceField));
                } catch (RuntimeException ignored) {
                    return net.ximatai.muyun.spring.ability.PlatformAbilityRuntime.referenceTargetResolver()
                            .referencePlan(sourceTarget, sourceField);
                }
            }
        };
    }

    private net.ximatai.muyun.spring.ability.reference.ReferenceAbility<?> referenceAbility(ReferenceTarget target) {
        java.util.Optional<net.ximatai.muyun.spring.ability.reference.ReferenceAbility<?>> registered =
                net.ximatai.muyun.spring.ability.PlatformAbilityRuntime.referenceTargetResolver().resolve(target);
        if (registered.isPresent()) {
            return registered.get();
        }
        try {
            return referenceService(target).referenceAbility();
        } catch (RuntimeException dynamicResolutionFailure) {
            throw dynamicResolutionFailure;
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
            if (!dao.getEntity().alias().equals(relation.parentEntityAlias())) {
                continue;
            }
            DynamicEntityService childService = relationServiceResolver.apply(relation.childEntityAlias());
            values.put(relation.code(), childService.selectChildRows(
                    Criteria.of().eq(relation.childForeignKeyField(), record.getId())
            ));
        }
        return values;
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
                parent -> parent.getChildren(plan.relationCode()),
                child -> {
                    Object value = child.getValue(plan.childForeignKeyField());
                    return value == null ? null : String.valueOf(value);
                }
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
        validateReferenceValues(record, null, true);
    }

    private void validateReferenceValues(DynamicRecord record, boolean explicitFieldsOnly) {
        validateReferenceValues(record, null, explicitFieldsOnly);
    }

    private void validateReferenceValues(DynamicRecord record,
                                         DynamicRecord existing) {
        validateReferenceValues(record, existing, true);
    }

    /** Applies the same plan emitted by static {@code @DiscriminatedValue} declarations. */
    private void normalizeDiscriminatedValues(DynamicRecord record, DynamicRecord existing) {
        for (DiscriminatedValuePlan plan : discriminatedValuePlans()) {
            Object discriminator = isDiscriminatedValueExplicit(record, plan.discriminatorField()) || existing == null
                    ? discriminatedValue(record, plan.discriminatorField())
                    : discriminatedValue(existing, plan.discriminatorField());
            DiscriminatedValueCasePlan branch = plan.caseFor(discriminator);
            if (branch == null) {
                throw new IllegalArgumentException("dynamic discriminator value has no declared branch: " + plan.valueField());
            }
            if (branch.source() == DiscriminatedValueSource.FIXED) {
                record.setValue(plan.valueField(), branch.fixedValue());
            } else if (branch.source() == DiscriminatedValueSource.FIELD) {
                Object source = isDiscriminatedValueExplicit(record, branch.sourceField()) || existing == null
                        ? discriminatedValue(record, branch.sourceField())
                        : discriminatedValue(existing, branch.sourceField());
                if (source == null || String.valueOf(source).isBlank()) {
                    throw new IllegalArgumentException("dynamic discriminator source field is required: " + branch.sourceField());
                }
                record.setValue(plan.valueField(), source);
            } else {
                validateDiscriminatedReference(record, existing, plan.valueField(), branch.reference());
            }
        }
    }

    private Object discriminatedValue(DynamicRecord record, String fieldName) {
        if (StandardEntitySchema.TENANT_ID_FIELD.equals(fieldName)) {
            return record.getTenantId();
        }
        return record.getValue(fieldName);
    }

    private boolean isDiscriminatedValueExplicit(DynamicRecord record, String fieldName) {
        return StandardEntitySchema.TENANT_ID_FIELD.equals(fieldName)
                ? record.getTenantId() != null
                : record.isExplicitlySet(fieldName);
    }

    private List<DiscriminatedValuePlan> discriminatedValuePlans() {
        if (module == null) return List.of();
        return module.discriminatedValues().stream()
                .filter(value -> dao.getEntity().alias().equals(value.sourceEntityAlias()))
                .map(net.ximatai.muyun.spring.dynamic.metadata.EntityDiscriminatedValueDefinition::plan)
                .toList();
    }

    private void validateDiscriminatedReference(DynamicRecord record, DynamicRecord existing, String valueField, ReferencePlan plan) {
        Object value = isDiscriminatedValueExplicit(record, valueField) || existing == null
                ? discriminatedValue(record, valueField) : discriminatedValue(existing, valueField);
        List<String> ids = plan.normalizeValues(value);
        if (ids.isEmpty()) throw new IllegalArgumentException("dynamic discriminator reference value is required: " + valueField);
        validateReferenceIds(plan, ids, List.of());
        if (plan.candidateDependencies().isEmpty()) return;
        var targetAbility = referenceAbility(plan.target());
        List<String> dependencyFields = plan.candidateDependencies().stream()
                .map(dependency -> dependency.targetField()).toList();
        Map<String, Map<String, Object>> targets = targetAbility.projections(ids, dependencyFields);
        for (String id : ids) {
            Map<String, Object> target = targets.get(id);
            for (var dependency : plan.candidateDependencies()) {
                Object source = isDiscriminatedValueExplicit(record, dependency.sourceField()) || existing == null
                        ? discriminatedValue(record, dependency.sourceField())
                        : discriminatedValue(existing, dependency.sourceField());
                if (dependency.required() && (source == null || String.valueOf(source).isBlank())) {
                    throw new IllegalArgumentException("dynamic discriminator reference dependency is required: " + dependency.sourceField());
                }
                if (source != null && !Objects.equals(String.valueOf(source), String.valueOf(target == null ? null : target.get(dependency.targetField())))) {
                    throw new IllegalArgumentException("dynamic discriminator reference target does not satisfy dependency: " + dependency.sourceField());
                }
            }
        }
    }

    private void validateReferenceValues(DynamicRecord record,
                                         DynamicRecord existing,
                                         boolean explicitFieldsOnly) {
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
            List<String> persistedIds = existing == null
                    ? List.of()
                    : plan.normalizeValues(existing.getValue(plan.sourceField()));
            validateReferenceIds(plan, ids, persistedIds);
        }
    }

    private void validateReferenceIds(ReferencePlan plan, List<String> ids, List<String> persistedIds) {
        if (ids.isEmpty()) {
            return;
        }
        Set<String> resolved = referenceAbility(plan.target()).titles(ids).keySet();
        Set<String> resolvedIds = resolved;
        List<String> unavailable = ids.stream()
                .filter(id -> !resolvedIds.contains(id))
                .filter(id -> plan.integrity().onTargetUnavailable()
                        != net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy.PRESERVE_HISTORY
                        || !persistedIds.contains(id))
                .toList();
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

    private String referenceLabel(DynamicRecord record, ReferencePlan plan) {
        Object value = plan.targetLabelField() == null ? record.title()
                : referenceFieldValue(record, plan.targetLabelField());
        Object rendered = maskProtectedValue(plan.targetLabelField() == null ? PlatformAbilityFields.TITLE_FIELD
                : plan.targetLabelField(), value, FieldOutputContext.REFERENCE);
        return rendered == null ? null : String.valueOf(rendered);
    }

    private Object referenceFieldValue(DynamicRecord record, String fieldName) {
        return StandardEntitySchema.ID_FIELD.equals(fieldName) ? record.getId() : record.getValue(fieldName);
    }

    private void requireReferenceTargetFields(ReferencePlan plan) {
        if (plan == null) throw new PlatformException("reference plan must not be null");
        requireDynamicReferenceField(plan.targetKeyField(), plan, "target key");
        if (plan.targetLabelField() != null) {
            requireDynamicReferenceField(plan.targetLabelField(), plan, "target label");
        }
    }

    private void requireDynamicReferenceField(String fieldName, ReferencePlan plan, String purpose) {
        if (StandardEntitySchema.ID_FIELD.equals(fieldName)) return;
        boolean known = dao.getEntity().fields().stream().anyMatch(field -> fieldName.equals(field.fieldName()));
        if (!known) {
            throw new PlatformException("reference " + purpose + " field is unavailable: "
                    + plan.target().qualifiedName() + "." + fieldName);
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
