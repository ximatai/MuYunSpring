package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.ability.security.FieldOutputRenderer;
import net.ximatai.muyun.spring.common.formula.FormulaRuntimeReport;
import net.ximatai.muyun.spring.common.model.capability.DataScopeCapable;
import net.ximatai.muyun.spring.common.model.capability.EnabledCapable;
import net.ximatai.muyun.spring.common.model.capability.ApprovalCapable;
import net.ximatai.muyun.spring.common.model.capability.TitledCapable;
import net.ximatai.muyun.spring.common.model.capability.TreeCapable;
import net.ximatai.muyun.spring.common.security.FieldOutputContext;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicAbilityFields;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldBehaviorSupport;
import net.ximatai.muyun.spring.dynamic.metadata.FieldCompanionDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldCompanionRules;
import net.ximatai.muyun.spring.dynamic.metadata.FieldCompanionRole;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicFieldValueSupport;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionValidator;

import java.time.Instant;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class DynamicRecord implements EntityContract, TreeCapable, EnabledCapable, TitledCapable, DataScopeCapable,
        ApprovalCapable {
    private final EntityDefinition entity;
    private final Map<String, FieldDefinition> fields;
    private final Map<String, Object> values = new LinkedHashMap<>();
    private final Map<String, Object> loadedValues = new LinkedHashMap<>();
    private final Map<String, List<DynamicRecord>> children = new LinkedHashMap<>();
    private final Set<String> partialChildRelations = new LinkedHashSet<>();
    private final Set<String> explicitFields = new HashSet<>();
    private final Map<String, Object> mutationMetadata = new LinkedHashMap<>();
    private final Map<String, FieldCompanionDefinition> companionFields;
    private FormulaRuntimeReport formulaReport = new FormulaRuntimeReport();

    private String id;
    private String tenantId;
    private Integer version;
    private Boolean deleted;
    private Instant deletedAt;
    private String deletedBy;
    private String createdBy;
    private Instant createdAt;
    private String updatedBy;
    private Instant updatedAt;

    public DynamicRecord(EntityDefinition entity) {
        new ModuleDefinitionValidator().validateEntity(entity);
        this.entity = entity;
        this.fields = recordFields(entity).stream()
                .collect(Collectors.toUnmodifiableMap(FieldDefinition::code, Function.identity()));
        this.companionFields = FieldCompanionRules.companionsByField(entity);
    }

    public EntityDefinition getEntity() {
        return entity;
    }

    public DynamicRecord setValue(String fieldCode, Object value) {
        FieldDefinition field = fields.get(fieldCode);
        if (field == null) {
            throw new IllegalArgumentException("unknown dynamic field: " + fieldCode);
        }
        if (isInternalGeneratedField(fieldCode) || isApprovalManagedField(fieldCode)) {
            throw new IllegalArgumentException("dynamic field is platform managed: " + fieldCode);
        }
        values.put(fieldCode, normalizeValue(field, value, true));
        explicitFields.add(fieldCode);
        return this;
    }

    public DynamicRecord putDisplayValue(String fieldCode, Object value) {
        FieldDefinition field = fields.get(fieldCode);
        if (field == null) {
            throw new IllegalArgumentException("unknown dynamic field: " + fieldCode);
        }
        if (field.isPhysical()) {
            throw new IllegalArgumentException("dynamic display value requires non-physical field: " + fieldCode);
        }
        if (isInternalGeneratedField(fieldCode) || isApprovalManagedField(fieldCode)) {
            throw new IllegalArgumentException("dynamic field is platform managed: " + fieldCode);
        }
        loadedValues.put(fieldCode, value);
        return this;
    }

    public Object getValue(String fieldCode) {
        if (isInternalGeneratedField(fieldCode)) {
            throw new IllegalArgumentException("dynamic field is platform managed: " + fieldCode);
        }
        FieldDefinition field = fields.get(fieldCode);
        if (field == null) {
            if (loadedValues.containsKey(fieldCode)) {
                return loadedValues.get(fieldCode);
            }
            throw new IllegalArgumentException("unknown dynamic field: " + fieldCode);
        }
        if (!field.isPhysical() && loadedValues.containsKey(fieldCode)) {
            return loadedValues.get(fieldCode);
        }
        return values.get(fieldCode);
    }

    public Map<String, Object> getValues() {
        Map<String, Object> visible = new LinkedHashMap<>();
        values.forEach((fieldCode, value) -> {
            if (!isInternalGeneratedField(fieldCode)) {
                visible.put(fieldCode, value);
            }
        });
        loadedValues.forEach((fieldCode, value) -> {
            if (!isInternalGeneratedField(fieldCode)) {
                visible.put(fieldCode, value);
            }
        });
        return Collections.unmodifiableMap(visible);
    }

    public Map<String, Object> outputValues(FieldOutputContext context) {
        Map<String, Object> output = new LinkedHashMap<>();
        values.forEach((fieldCode, value) -> {
            if (isInternalGeneratedField(fieldCode)) {
                return;
            }
            FieldDefinition field = fields.get(fieldCode);
            output.put(fieldCode, field == null
                    ? value
                    : FieldOutputRenderer.renderValue(fieldCode, value, field.protection(), context, null));
        });
        loadedValues.forEach((fieldCode, value) -> {
            if (isInternalGeneratedField(fieldCode)) {
                return;
            }
            FieldDefinition field = fields.get(fieldCode);
            output.put(fieldCode, field == null
                    ? value
                    : FieldOutputRenderer.renderValue(fieldCode, value, field.protection(), context, null));
        });
        return Collections.unmodifiableMap(output);
    }

    public boolean isExplicitlySet(String fieldCode) {
        return explicitFields.contains(fieldCode);
    }

    public Set<String> explicitFieldCodes() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(explicitFields));
    }

    public DynamicRecord setChildren(String relationCode, List<DynamicRecord> records) {
        if (relationCode == null || relationCode.isBlank()) {
            throw new IllegalArgumentException("relationCode must not be blank");
        }
        if (records == null) {
            children.put(relationCode, null);
            return this;
        }
        children.put(relationCode, List.copyOf(records));
        return this;
    }

    public DynamicRecord setPartialChildren(String relationCode, List<DynamicRecord> records) {
        setChildren(relationCode, records);
        partialChildRelations.add(relationCode);
        return this;
    }

    public boolean isPartialChildren(String relationCode) {
        return partialChildRelations.contains(relationCode);
    }

    public List<DynamicRecord> getChildren(String relationCode) {
        return children.get(relationCode);
    }

    public Map<String, List<DynamicRecord>> getChildren() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(children));
    }

    public DynamicRecord putMutationMetadata(String key, Object value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("mutation metadata key must not be blank");
        }
        if (value == null) {
            mutationMetadata.remove(key);
        } else {
            mutationMetadata.put(key, value);
        }
        return this;
    }

    public Map<String, Object> mutationMetadata() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(mutationMetadata));
    }

    public DynamicRecord copy() {
        DynamicRecord copy = new DynamicRecord(entity);
        values.forEach((fieldCode, value) -> copy.values.put(fieldCode, copyValue(value)));
        loadedValues.forEach((fieldCode, value) -> copy.loadedValues.put(fieldCode, copyValue(value)));
        mutationMetadata.forEach((key, value) -> copy.mutationMetadata.put(key, copyValue(value)));
        copy.explicitFields.addAll(explicitFields);
        children.forEach((relationCode, records) -> copy.children.put(
                relationCode,
                records == null ? null : records.stream().map(DynamicRecord::copy).toList()
        ));
        copy.partialChildRelations.addAll(partialChildRelations);
        copy.id = id;
        copy.tenantId = tenantId;
        copy.version = version;
        copy.deleted = deleted;
        copy.deletedAt = deletedAt;
        copy.deletedBy = deletedBy;
        copy.createdBy = createdBy;
        copy.createdAt = createdAt;
        copy.updatedBy = updatedBy;
        copy.updatedAt = updatedAt;
        return copy;
    }

    @SuppressWarnings("unchecked")
    private Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copied = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> copied.put(key, copyValue(nestedValue)));
            return copied;
        }
        if (value instanceof List<?> list) {
            List<Object> copied = new ArrayList<>();
            list.forEach(item -> copied.add(copyValue(item)));
            return copied;
        }
        if (value instanceof Set<?> set) {
            Set<Object> copied = new LinkedHashSet<>();
            set.forEach(item -> copied.add(copyValue(item)));
            return copied;
        }
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        if (value instanceof Object[] objects) {
            Object[] copied = objects.clone();
            for (int i = 0; i < copied.length; i++) {
                copied[i] = copyValue(copied[i]);
            }
            return copied;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return new java.sql.Timestamp(timestamp.getTime());
        }
        if (value instanceof java.sql.Date date) {
            return new java.sql.Date(date.getTime());
        }
        if (value instanceof Date date) {
            return new Date(date.getTime());
        }
        return value;
    }

    String parentId() {
        if (!entity.supports(EntityCapability.TREE) || !hasField(PlatformAbilityFields.TREE_PARENT_FIELD)) {
            return null;
        }
        return stringValue(values.get(PlatformAbilityFields.TREE_PARENT_FIELD));
    }

    void parentId(String parentId) {
        if (entity.supports(EntityCapability.TREE)) {
            setPlatformValueIfPresent(PlatformAbilityFields.TREE_PARENT_FIELD, parentId);
        }
    }

    Integer sortOrder() {
        if (!entity.supports(EntityCapability.SORT) || !hasField(PlatformAbilityFields.SORT_FIELD)) {
            return null;
        }
        return numberValue(values.get(PlatformAbilityFields.SORT_FIELD));
    }

    void sortOrder(Integer sortOrder) {
        if (entity.supports(EntityCapability.SORT)) {
            setPlatformValueIfPresent(PlatformAbilityFields.SORT_FIELD, sortOrder);
        }
    }

    String title() {
        if (!entity.supports(EntityCapability.REFERENCE) || !hasField(PlatformAbilityFields.TITLE_FIELD)) {
            return null;
        }
        return stringValue(values.get(PlatformAbilityFields.TITLE_FIELD));
    }

    Boolean enabled() {
        if (!entity.supports(EntityCapability.ENABLE) || !hasField(PlatformAbilityFields.ENABLED_FIELD)) {
            return null;
        }
        Object value = values.get(PlatformAbilityFields.ENABLED_FIELD);
        return value instanceof Boolean enabled ? enabled : null;
    }

    void enabled(Boolean enabled) {
        if (entity.supports(EntityCapability.ENABLE)) {
            setPlatformValueIfPresent(PlatformAbilityFields.ENABLED_FIELD, enabled);
        }
    }

    @Override
    public String getParentId() {
        return parentId();
    }

    @Override
    public void setParentId(String parentId) {
        parentId(parentId);
    }

    @Override
    public Integer getSortOrder() {
        return sortOrder();
    }

    @Override
    public void setSortOrder(Integer sortOrder) {
        sortOrder(sortOrder);
    }

    @Override
    public String getTitle() {
        return title();
    }

    @Override
    public Boolean getEnabled() {
        return enabled();
    }

    @Override
    public void setEnabled(Boolean enabled) {
        enabled(enabled);
    }

    @Override
    public String getAuthUserId() {
        return dataScopeValue(PlatformAbilityFields.AUTH_USER_FIELD);
    }

    @Override
    public void setAuthUserId(String authUserId) {
        setDataScopeValue(PlatformAbilityFields.AUTH_USER_FIELD, authUserId);
    }

    @Override
    public String getAuthAssigneeIds() {
        return dataScopeValue(PlatformAbilityFields.AUTH_ASSIGNEE_FIELD);
    }

    @Override
    public void setAuthAssigneeIds(String authAssigneeIds) {
        setDataScopeValue(PlatformAbilityFields.AUTH_ASSIGNEE_FIELD, authAssigneeIds);
    }

    @Override
    public String getAuthMemberIds() {
        return dataScopeValue(PlatformAbilityFields.AUTH_MEMBER_FIELD);
    }

    @Override
    public void setAuthMemberIds(String authMemberIds) {
        setDataScopeValue(PlatformAbilityFields.AUTH_MEMBER_FIELD, authMemberIds);
    }

    @Override
    public String getAuthOrganizationId() {
        return dataScopeValue(PlatformAbilityFields.AUTH_ORGANIZATION_FIELD);
    }

    @Override
    public void setAuthOrganizationId(String authOrganizationId) {
        setDataScopeValue(PlatformAbilityFields.AUTH_ORGANIZATION_FIELD, authOrganizationId);
    }

    @Override
    public String getAuthDepartmentId() {
        return dataScopeValue(PlatformAbilityFields.AUTH_DEPARTMENT_FIELD);
    }

    @Override
    public void setAuthDepartmentId(String authDepartmentId) {
        setDataScopeValue(PlatformAbilityFields.AUTH_DEPARTMENT_FIELD, authDepartmentId);
    }

    @Override
    public String getAuthModuleAlias() {
        return dataScopeValue(PlatformAbilityFields.AUTH_MODULE_FIELD);
    }

    @Override
    public void setAuthModuleAlias(String authModuleAlias) {
        setDataScopeValue(PlatformAbilityFields.AUTH_MODULE_FIELD, authModuleAlias);
    }

    @Override
    public String getApprovalInstanceId() {
        return approvalValue(PlatformAbilityFields.APPROVAL_INSTANCE_FIELD);
    }

    @Override
    public void setApprovalInstanceId(String approvalInstanceId) {
        setApprovalValue(PlatformAbilityFields.APPROVAL_INSTANCE_FIELD, approvalInstanceId);
    }

    @Override
    public String getApprovalStatus() {
        return approvalValue(PlatformAbilityFields.APPROVAL_STATUS_FIELD);
    }

    @Override
    public void setApprovalStatus(String approvalStatus) {
        setApprovalValue(PlatformAbilityFields.APPROVAL_STATUS_FIELD, approvalStatus);
    }

    @Override
    public String getApprovalSubmittedBy() {
        return approvalValue(PlatformAbilityFields.APPROVAL_SUBMITTED_BY_FIELD);
    }

    @Override
    public void setApprovalSubmittedBy(String approvalSubmittedBy) {
        setApprovalValue(PlatformAbilityFields.APPROVAL_SUBMITTED_BY_FIELD, approvalSubmittedBy);
    }

    @Override
    public Instant getApprovalSubmittedAt() {
        return instantValue(values.get(PlatformAbilityFields.APPROVAL_SUBMITTED_AT_FIELD));
    }

    @Override
    public void setApprovalSubmittedAt(Instant approvalSubmittedAt) {
        setApprovalValue(PlatformAbilityFields.APPROVAL_SUBMITTED_AT_FIELD, approvalSubmittedAt);
    }

    @Override
    public Instant getApprovalCompletedAt() {
        return instantValue(values.get(PlatformAbilityFields.APPROVAL_COMPLETED_AT_FIELD));
    }

    @Override
    public void setApprovalCompletedAt(Instant approvalCompletedAt) {
        setApprovalValue(PlatformAbilityFields.APPROVAL_COMPLETED_AT_FIELD, approvalCompletedAt);
    }

    Set<String> fieldCodes() {
        return fields.keySet();
    }

    void putLoadedValue(String fieldCode, Object value) {
        FieldDefinition field = fields.get(fieldCode);
        if (field != null) {
            values.put(fieldCode, normalizeValue(field, value, false));
        } else {
            loadedValues.put(fieldCode, value);
        }
    }

    public DynamicRecord putProjectedValue(String fieldCode, Object value) {
        FieldDefinition field = fields.get(fieldCode);
        if (field != null && field.isPhysical()) {
            putLoadedValue(fieldCode, value);
        } else {
            putVirtualValue(fieldCode, value);
        }
        return this;
    }

    void putVirtualValue(String fieldCode, Object value) {
        if (isInternalGeneratedField(fieldCode) || isApprovalManagedField(fieldCode)) {
            throw new IllegalArgumentException("dynamic field is platform managed: " + fieldCode);
        }
        loadedValues.put(fieldCode, value);
    }

    public void putGeneratedValue(String fieldCode, Object value) {
        putPlatformValue(fieldCode, value);
    }

    /** Aggregate ownership is supplied by ChildRelation, even when a form echoes the stored key. */
    void bindAggregateParent(String fieldCode, String parentId) {
        putPlatformValue(fieldCode, parentId);
        explicitFields.remove(fieldCode);
    }

    void putPlatformValue(String fieldCode, Object value) {
        FieldDefinition field = fields.get(fieldCode);
        if (field == null) {
            throw new IllegalArgumentException("unknown dynamic field: " + fieldCode);
        }
        values.put(fieldCode, normalizeValue(field, value, true));
    }

    Object getPlatformValue(String fieldCode) {
        if (!fields.containsKey(fieldCode)) {
            throw new IllegalArgumentException("unknown dynamic field: " + fieldCode);
        }
        return values.get(fieldCode);
    }

    public Map<String, Object> getPlatformValues() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    void validateForInsert() {
        for (FieldDefinition field : fields.values()) {
            if (field.isRequired() && values.get(field.code()) == null) {
                throw new IllegalArgumentException("required dynamic field is missing: " + field.code());
            }
        }
        FieldCompanionRules.validateForInsert(entity, values);
    }

    void validateForUpdate() {
        FieldCompanionRules.validateForUpdate(entity, explicitFields);
    }

    public FormulaRuntimeReport formulaReport() {
        return formulaReport;
    }

    void formulaReport(FormulaRuntimeReport formulaReport) {
        this.formulaReport = formulaReport == null ? new FormulaRuntimeReport() : formulaReport;
    }

    void applyDefaultsForInsert() {
        for (FieldDefinition field : fields.values()) {
            if (!values.containsKey(field.code()) && field.behavior().defaultValue() != null) {
                Object defaultValue = FieldBehaviorSupport.parseDefaultValue(field.type(), field.behavior().defaultValue());
                values.put(field.code(), normalizeValue(field, defaultValue, true));
            }
        }
    }

    private Object normalizeValue(FieldDefinition field, Object value, boolean enforceRequired) {
        if (value == null) {
            if (enforceRequired && field.isRequired()) {
                throw new IllegalArgumentException("required dynamic field must not be null: " + field.code());
            }
            return null;
        }
        Object normalized;
        try {
            FieldCompanionDefinition companion = companionFields.get(field.code());
            normalized = companion != null
                    ? FieldCompanionRules.normalizeCompanionValue(companion, value)
                    : (enforceRequired
                    ? DynamicFieldValueSupport.normalize(field.type(), value)
                    : DynamicFieldValueSupport.normalizeLoaded(field.type(), value));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid value type for dynamic field: " + field.code());
        }
        if (field.behavior().validationRegex() != null
                && normalized instanceof String text
                && !text.matches(field.behavior().validationRegex())) {
            throw new IllegalArgumentException("dynamic field value does not match validationRegex: " + field.code());
        }
        return normalized;
    }

    private boolean hasField(String fieldCode) {
        return fields.containsKey(fieldCode);
    }

    private boolean isInternalGeneratedField(String fieldCode) {
        FieldCompanionDefinition companion = companionFields.get(fieldCode);
        return companion != null && companion.role() == FieldCompanionRole.SIGNATURE;
    }

    private boolean isApprovalManagedField(String fieldCode) {
        return entity.supports(EntityCapability.APPROVAL)
                && (PlatformAbilityFields.APPROVAL_INSTANCE_FIELD.equals(fieldCode)
                || PlatformAbilityFields.APPROVAL_STATUS_FIELD.equals(fieldCode)
                || PlatformAbilityFields.APPROVAL_SUBMITTED_BY_FIELD.equals(fieldCode)
                || PlatformAbilityFields.APPROVAL_SUBMITTED_AT_FIELD.equals(fieldCode)
                || PlatformAbilityFields.APPROVAL_COMPLETED_AT_FIELD.equals(fieldCode));
    }

    private static List<FieldDefinition> recordFields(EntityDefinition entity) {
        List<FieldDefinition> values = new ArrayList<>();
        if (entity.supports(EntityCapability.DATA_SCOPE)) {
            values.addAll(DynamicAbilityFields.dataScopeFields());
        }
        if (entity.supports(EntityCapability.APPROVAL)) {
            values.addAll(DynamicAbilityFields.approvalFields());
        }
        values.addAll(FieldCompanionRules.recordFields(entity));
        return List.copyOf(values);
    }

    private String dataScopeValue(String fieldCode) {
        if (!entity.supports(EntityCapability.DATA_SCOPE) || !hasField(fieldCode)) {
            return null;
        }
        return stringValue(values.get(fieldCode));
    }

    private String approvalValue(String fieldCode) {
        if (!entity.supports(EntityCapability.APPROVAL) || !hasField(fieldCode)) {
            return null;
        }
        return stringValue(values.get(fieldCode));
    }

    private void setDataScopeValue(String fieldCode, String value) {
        if (entity.supports(EntityCapability.DATA_SCOPE)) {
            setPlatformValueIfPresent(fieldCode, value);
        }
    }

    private void setApprovalValue(String fieldCode, Object value) {
        if (entity.supports(EntityCapability.APPROVAL)) {
            setPlatformValueIfPresent(fieldCode, value);
        }
    }

    private void setPlatformValueIfPresent(String fieldCode, Object value) {
        if (hasField(fieldCode)) {
            putPlatformValue(fieldCode, value);
        }
    }

    private Integer numberValue(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Instant instantValue(Object value) {
        return value instanceof Instant instant ? instant : null;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public void setId(String id) {
        this.id = id;
    }

    @Override
    public String getTenantId() {
        return tenantId;
    }

    @Override
    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    @Override
    public Integer getVersion() {
        return version;
    }

    @Override
    public void setVersion(Integer version) {
        this.version = version;
    }

    @Override
    public Boolean getDeleted() {
        return deleted;
    }

    @Override
    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }

    @Override
    public Instant getDeletedAt() {
        return deletedAt;
    }

    @Override
    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    @Override
    public String getDeletedBy() {
        return deletedBy;
    }

    @Override
    public void setDeletedBy(String deletedBy) {
        this.deletedBy = deletedBy;
    }

    @Override
    public String getCreatedBy() {
        return createdBy;
    }

    @Override
    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    @Override
    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String getUpdatedBy() {
        return updatedBy;
    }

    @Override
    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    @Override
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
