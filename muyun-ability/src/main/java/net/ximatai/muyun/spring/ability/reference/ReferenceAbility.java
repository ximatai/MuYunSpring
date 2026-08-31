package net.ximatai.muyun.spring.ability.reference;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.PageRequests;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.ability.security.FieldProtectionAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.model.constraint.StaticTenantUniqueConstraints;
import net.ximatai.muyun.spring.common.model.title.TitleFieldResolver;
import net.ximatai.muyun.spring.common.model.capability.TitledCapable;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.common.security.FieldOutputContext;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public interface ReferenceAbility<T extends EntityContract & TitledCapable> extends CrudAbility<T> {
    default ReferenceTarget referenceTarget() {
        return ReferenceTargets.fromModuleAlias(getModuleAlias());
    }

    default void clearReferenceReferrers(String id) {
        ReferenceDependencyRegistry.clearReferrers(referenceTarget(), id);
    }

    default String title(String id) {
        T entity = selectReferenceRaw(id);
        return entity == null ? null : referenceTitleForOutput(entity);
    }

    default Map<String, String> titles(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        LinkedHashSet<String> normalizedIds = new LinkedHashSet<>(ids);
        List<T> entities = getDao().query(
                activeCriteria(Criteria.of().in(StandardEntitySchema.ID_FIELD, List.copyOf(normalizedIds))),
                PageRequests.all()
        ).stream().peek(this::restoreReferenceProtectedFields).toList();
        Map<String, String> loadedTitles = new LinkedHashMap<>();
        for (T entity : entities) {
            loadedTitles.put(entity.getId(), referenceTitleForOutput(entity));
        }
        Map<String, String> titles = new LinkedHashMap<>();
        for (String id : normalizedIds) {
            if (loadedTitles.containsKey(id)) {
                titles.put(id, loadedTitles.get(id));
            }
        }
        return titles;
    }

    default Map<String, Map<String, Object>> projections(Collection<String> ids, Collection<String> fieldNames) {
        if (ids == null || ids.isEmpty() || fieldNames == null || fieldNames.isEmpty()) {
            return Map.of();
        }
        LinkedHashSet<String> normalizedIds = new LinkedHashSet<>(ids);
        LinkedHashSet<String> normalizedFields = new LinkedHashSet<>(fieldNames);
        List<T> entities = referenceOptionPage(Criteria.of().in(StandardEntitySchema.ID_FIELD,
                List.copyOf(normalizedIds)), PageRequests.all()).getRecords();
        Map<String, Map<String, Object>> loaded = new LinkedHashMap<>();
        for (T entity : entities) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (String fieldName : normalizedFields) {
                values.put(fieldName, referenceProjectionValue(fieldName, ReferenceFieldResolver.read(entity, fieldName)));
            }
            loaded.put(entity.getId(), Collections.unmodifiableMap(new LinkedHashMap<>(values)));
        }
        Map<String, Map<String, Object>> ordered = new LinkedHashMap<>();
        for (String id : normalizedIds) {
            if (loaded.containsKey(id)) {
                ordered.put(id, loaded.get(id));
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
    }

    default T selectReferenceRaw(String id) {
        T entity = selectActiveRaw(id);
        restoreReferenceProtectedFields(entity);
        return entity;
    }

    default PageResult<ReferenceOption> referenceOptions(Criteria criteria, PageRequest pageRequest) {
        PageResult<T> page = referenceOptionPage(criteria, pageRequest);
        return PageResult.of(
                page.getRecords().stream()
                        .map(entity -> new ReferenceOption(entity.getId(), referenceTitleForOutput(entity)))
                        .toList(),
                page.getTotal(),
                pageRequest
        );
    }

    /**
     * Reads picker candidates through a declared reference contract instead of assuming that
     * every target stores its business key in {@code id} and its label in {@code title}.
     */
    default PageResult<ReferenceOption> referenceOptions(ReferencePlan plan,
                                                          Criteria criteria,
                                                          PageRequest pageRequest) {
        requireReferencePlan(plan);
        PageResult<T> page = referenceOptionPage(criteria, pageRequest);
        return PageResult.of(
                page.getRecords().stream().map(entity -> referenceOption(entity, plan)).toList(),
                page.getTotal(), pageRequest
        );
    }

    /**
     * Stable model-facing contract for validating a configured persisted reference key.
     * {@code id} is inherently unique; every other static field must have an explicit,
     * single-field tenant-unique declaration before it can be selected.
     */
    default ReferenceCandidateKey referenceCandidateKey(String fieldName) {
        String normalized = fieldName == null || fieldName.isBlank() ? StandardEntitySchema.ID_FIELD : fieldName.trim();
        if (StandardEntitySchema.ID_FIELD.equals(normalized)) {
            return new ReferenceCandidateKey(normalized, true, true);
        }
        Class<?> type = modelClass();
        if (type == null) {
            return new ReferenceCandidateKey(normalized, false, false);
        }
        boolean readable = ReferenceFieldResolver.isReadable(type, normalized);
        boolean unique = readable && StaticTenantUniqueConstraints.resolve(type).stream()
                .anyMatch(constraint -> constraint.fieldNames().equals(List.of(normalized)));
        return new ReferenceCandidateKey(normalized, readable, unique);
    }

    /**
     * Lists every static target field that is safe to persist as a reference value.
     * The immutable record id is always present; business candidates require both a readable
     * model field and an explicit single-field tenant-unique declaration.
     */
    default List<ReferenceCandidateKey> referenceCandidateKeys() {
        LinkedHashSet<String> fieldNames = new LinkedHashSet<>();
        fieldNames.add(StandardEntitySchema.ID_FIELD);
        Class<?> type = modelClass();
        if (type != null) {
            StaticTenantUniqueConstraints.resolve(type).stream()
                    .map(constraint -> constraint.fieldNames())
                    .filter(constraintFields -> constraintFields.size() == 1)
                    .map(List::getFirst)
                    .forEach(fieldNames::add);
        }
        return fieldNames.stream()
                .map(this::referenceCandidateKey)
                .filter(ReferenceCandidateKey::usable)
                .toList();
    }

    /**
     * Lists readable target fields that may provide a reference label.  The declared title field,
     * or the conventional {@code title} field when available, is marked as the default.
     */
    default List<ReferenceCandidateField> referenceCandidateLabels() {
        Class<?> type = modelClass();
        if (type == null) {
            return List.of();
        }
        String titleField = TitleFieldResolver.resolveFieldName(type)
                .orElse(PlatformAbilityFields.TITLE_FIELD);
        return ReferenceFieldResolver.readableFieldNames(type).stream()
                .map(fieldName -> new ReferenceCandidateField(fieldName, titleField.equals(fieldName)))
                .toList();
    }

    /**
     * Resolves persisted reference values against the configured candidate key.  The returned
     * keys are exactly the values accepted by the source field; duplicate target keys are a
     * contract violation rather than an arbitrary first-record choice.
     */
    default Map<String, String> referenceLabels(ReferencePlan plan, Collection<String> values) {
        if (values == null || values.isEmpty()) return Map.of();
        requireReferencePlan(plan);
        LinkedHashSet<String> requested = normalizedReferenceValues(values);
        if (requested.isEmpty()) return Map.of();
        PageResult<ReferenceOption> page = referenceOptions(plan,
                Criteria.of().in(plan.targetKeyField(), List.copyOf(requested)), PageRequests.all());
        Map<String, String> loaded = uniqueLabels(plan, page.getRecords());
        Map<String, String> ordered = new LinkedHashMap<>();
        for (String value : requested) {
            if (loaded.containsKey(value)) ordered.put(value, loaded.get(value));
        }
        return Collections.unmodifiableMap(ordered);
    }

    /** Resolves configured persisted values to immutable target record ids for path reads. */
    default Map<String, String> referenceRecordIds(ReferencePlan plan, Collection<String> values) {
        if (values == null || values.isEmpty()) return Map.of();
        requireReferencePlan(plan);
        LinkedHashSet<String> requested = normalizedReferenceValues(values);
        if (requested.isEmpty()) return Map.of();
        PageResult<ReferenceOption> page = referenceOptions(plan,
                Criteria.of().in(plan.targetKeyField(), List.copyOf(requested)), PageRequests.all());
        Map<String, String> loaded = new LinkedHashMap<>();
        for (ReferenceOption option : page.getRecords()) {
            if (loaded.putIfAbsent(option.id(), option.recordId()) != null) {
                throw duplicateTargetKey(plan, option.id());
            }
        }
        Map<String, String> ordered = new LinkedHashMap<>();
        for (String value : requested) {
            if (loaded.containsKey(value)) ordered.put(value, loaded.get(value));
        }
        return Collections.unmodifiableMap(ordered);
    }

    /** Reads arbitrary target fields and indexes the result by the configured persisted key. */
    default Map<String, Map<String, Object>> projections(ReferencePlan plan,
                                                          Collection<String> values,
                                                          Collection<String> fieldNames) {
        if (values == null || values.isEmpty() || fieldNames == null || fieldNames.isEmpty()) return Map.of();
        requireReferencePlan(plan);
        LinkedHashSet<String> requested = normalizedReferenceValues(values);
        LinkedHashSet<String> fields = new LinkedHashSet<>(fieldNames);
        if (requested.isEmpty() || fields.isEmpty()) return Map.of();
        PageResult<T> page = referenceOptionPage(Criteria.of().in(plan.targetKeyField(), List.copyOf(requested)),
                PageRequests.all());
        Map<String, Map<String, Object>> loaded = new LinkedHashMap<>();
        for (T entity : page.getRecords()) {
            String key = referenceKeyForOutput(entity, plan);
            if (loaded.containsKey(key)) {
                throw duplicateTargetKey(plan, key);
            }
            Map<String, Object> projected = new LinkedHashMap<>();
            for (String fieldName : fields) {
                projected.put(fieldName, referenceProjectionValue(fieldName, referenceFieldValue(entity, fieldName)));
            }
            loaded.put(key, Collections.unmodifiableMap(projected));
        }
        Map<String, Map<String, Object>> ordered = new LinkedHashMap<>();
        for (String value : requested) {
            if (loaded.containsKey(value)) ordered.put(value, loaded.get(value));
        }
        return Collections.unmodifiableMap(ordered);
    }

    @SuppressWarnings("unchecked")
    private PageResult<T> referenceOptionPage(Criteria criteria, PageRequest pageRequest) {
        if (this instanceof DataScopeAbility<?> dataScopeAbility) {
            return ((DataScopeAbility<T>) dataScopeAbility)
                    .pageQueryForAction(PlatformAction.REFERENCE, criteria, pageRequest);
        }
        return pageQuery(criteria, pageRequest);
    }

    default String referenceTitle(T entity) {
        String title = TitleFieldResolver.readAsString(entity);
        if (title != null) {
            return title;
        }
        title = entity.getTitle();
        if (title != null) {
            return title;
        }
        throw new PlatformException("reference entity requires @TitleField or non-null TitledCapable title: "
                + entity.getClass().getName());
    }

    private String referenceTitleForOutput(T entity) {
        String title = referenceTitle(entity);
        String titleFieldName = TitleFieldResolver.resolveFieldName(modelClass()).orElse(PlatformAbilityFields.TITLE_FIELD);
        Object rendered = referenceProjectionValue(titleFieldName, title);
        return rendered == null ? null : String.valueOf(rendered);
    }

    private ReferenceOption referenceOption(T entity, ReferencePlan plan) {
        return new ReferenceOption(referenceKeyForOutput(entity, plan), referenceLabelForOutput(entity, plan), entity.getId());
    }

    private String referenceKeyForOutput(T entity, ReferencePlan plan) {
        Object value = referenceFieldValue(entity, plan.targetKeyField());
        if (value == null || String.valueOf(value).isBlank()) {
            throw new PlatformException("reference target key must not be blank: "
                    + plan.target().qualifiedName() + "." + plan.targetKeyField());
        }
        return String.valueOf(value);
    }

    private String referenceLabelForOutput(T entity, ReferencePlan plan) {
        if (plan.targetLabelField() == null) {
            return referenceTitleForOutput(entity);
        }
        Object value = referenceProjectionValue(plan.targetLabelField(),
                referenceFieldValue(entity, plan.targetLabelField()));
        return value == null ? null : String.valueOf(value);
    }

    private Object referenceFieldValue(T entity, String fieldName) {
        if (StandardEntitySchema.ID_FIELD.equals(fieldName)) return entity.getId();
        return ReferenceFieldResolver.read(entity, fieldName);
    }

    private void requireReferencePlan(ReferencePlan plan) {
        if (plan == null) throw new PlatformException("reference plan must not be null");
        Class<?> type = modelClass();
        if (type == null) return;
        if (!StandardEntitySchema.ID_FIELD.equals(plan.targetKeyField())) {
            ReferenceCandidateKey candidateKey = referenceCandidateKey(plan.targetKeyField());
            if (!candidateKey.readable()) {
                throw new PlatformException("reference target key field is unavailable: "
                        + plan.target().qualifiedName() + "." + plan.targetKeyField());
            }
            if (!candidateKey.unique()) {
                throw new PlatformException("reference target key field is not uniquely declared: "
                        + plan.target().qualifiedName() + "." + plan.targetKeyField());
            }
        }
        if (plan.targetLabelField() != null) {
            ReferenceFieldResolver.requireReadable(type, plan.targetLabelField());
        }
    }

    private LinkedHashSet<String> normalizedReferenceValues(Collection<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        values.stream().filter(java.util.Objects::nonNull).map(String::valueOf).map(String::trim)
                .filter(value -> !value.isBlank()).forEach(normalized::add);
        return normalized;
    }

    private Map<String, String> uniqueLabels(ReferencePlan plan, List<ReferenceOption> options) {
        Map<String, String> loaded = new LinkedHashMap<>();
        for (ReferenceOption option : options) {
            if (loaded.putIfAbsent(option.id(), option.title()) != null) {
                throw duplicateTargetKey(plan, option.id());
            }
        }
        return loaded;
    }

    private PlatformException duplicateTargetKey(ReferencePlan plan, String key) {
        return new PlatformException("reference target key is not unique: "
                + plan.target().qualifiedName() + "." + plan.targetKeyField() + "=" + key);
    }

    @SuppressWarnings("unchecked")
    private Object referenceProjectionValue(String fieldName, Object value) {
        if (this instanceof FieldProtectionAbility<?> fieldProtectionAbility) {
            return ((FieldProtectionAbility<T>) fieldProtectionAbility)
                    .maskProtectedValue(fieldName, value, FieldOutputContext.REFERENCE);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private void restoreReferenceProtectedFields(T entity) {
        if (entity != null && this instanceof FieldProtectionAbility<?> fieldProtectionAbility) {
            ((FieldProtectionAbility<T>) fieldProtectionAbility).restoreProtectedFieldsFromStorage(entity);
        }
    }
}
