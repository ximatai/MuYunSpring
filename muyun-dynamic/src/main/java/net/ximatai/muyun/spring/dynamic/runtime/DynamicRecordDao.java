package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.AggregateQuery;
import net.ximatai.muyun.database.core.orm.CriteriaClause;
import net.ximatai.muyun.database.core.orm.CriteriaGroup;
import net.ximatai.muyun.database.core.orm.CriteriaOperator;
import net.ximatai.muyun.database.core.orm.DatabaseValueConverter;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.RuntimeTableGateway;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.database.core.orm.TableMeta;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicAbilityFields;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicFieldColumnMetadata;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldCompanionRules;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionValidator;
import net.ximatai.muyun.spring.dynamic.runtime.mapping.DynamicRecordMapping;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class DynamicRecordDao implements BaseDao<DynamicRecord, String> {
    private final IDatabaseOperations<Object> operations;
    private final EntityDefinition entity;
    private final String schema;
    private final DynamicRecordMapping mapping;
    private final RuntimeTableGateway tableGateway;

    @SuppressWarnings("unchecked")
    public DynamicRecordDao(IDatabaseOperations<?> operations, EntityDefinition entity) {
        this(operations, entity, DatabaseValueConverter.DEFAULT);
    }

    @SuppressWarnings("unchecked")
    public DynamicRecordDao(IDatabaseOperations<?> operations,
                            EntityDefinition entity,
                            DatabaseValueConverter valueConverter) {
        this.operations = (IDatabaseOperations<Object>) Objects.requireNonNull(operations, "operations must not be null");
        new ModuleDefinitionValidator().validateEntity(entity);
        this.entity = entity;
        this.schema = entity.schemaName();
        this.mapping = new DynamicRecordMapping(entity);
        DatabaseValueConverter normalizedValueConverter = valueConverter == null
                ? DatabaseValueConverter.DEFAULT
                : valueConverter;
        this.tableGateway = new RuntimeTableGateway(this.operations, tableMeta(), normalizedValueConverter);
    }

    @Override
    public boolean ensureTable() {
        throw new UnsupportedOperationException("dynamic table schema is managed by DynamicSchemaService");
    }

    @Override
    public String insert(DynamicRecord record) {
        requireSameEntity(record);
        Object id = tableGateway.insert(toColumnMap(record, false));
        if (id != null) {
            record.setId(String.valueOf(id));
        }
        return record.getId();
    }

    @Override
    public DynamicRecord findById(String id) {
        return loadById(id);
    }

    @Override
    public int updateById(DynamicRecord record) {
        throw new UnsupportedOperationException("dynamic record update must go through conditional update");
    }

    @Override
    public int updateByIdAndVersion(DynamicRecord record, Integer expectedVersion) {
        if (expectedVersion == null) {
            throw new IllegalArgumentException("expectedVersion must not be null");
        }
        return updateByIdAndCondition(record, Map.of(StandardEntitySchema.VERSION_FIELD, expectedVersion));
    }

    @Override
    public int updateByIdAndCondition(DynamicRecord record, Map<String, Object> conditions) {
        requireSameEntity(record);
        if (record.getId() == null || record.getId().isBlank()) {
            throw new IllegalArgumentException("dynamic record id must not be blank");
        }
        if (conditions == null || conditions.isEmpty()) {
            throw new IllegalArgumentException("dynamic record update conditions must not be empty");
        }
        Map<String, Object> body = Boolean.TRUE.equals(record.getDeleted()) ? toDeleteMap(record) : toUpdateMap(record);
        Map<String, Object> where = new LinkedHashMap<>();
        where.putAll(toConditionColumnMap(conditions));
        if (record.getTenantId() != null && !record.getTenantId().isBlank()) {
            where.put(StandardEntitySchema.TENANT_ID_COLUMN, record.getTenantId());
        }
        where.put(StandardEntitySchema.ID_COLUMN, record.getId());
        return tableGateway.patchWhere(body, where);
    }

    @Override
    public int deleteById(String id) {
        throw new UnsupportedOperationException("dynamic record delete must go through DynamicEntityService");
    }

    @Override
    public int deleteByIdAndCondition(String id, Map<String, Object> conditions) {
        throw new UnsupportedOperationException("dynamic record delete must go through DynamicEntityService");
    }

    public boolean existsById(String id) {
        return findById(id) != null;
    }

    /** Executes a metadata-checked aggregate over this entity's physical table. */
    public List<Map<String, Object>> aggregate(Criteria criteria, AggregateQuery query) {
        return tableGateway.aggregate(criteria, query);
    }

    @Override
    public List<DynamicRecord> query(Criteria criteria, PageRequest pageRequest, Sort... sorts) {
        Objects.requireNonNull(pageRequest, "pageRequest must not be null");
        validateQueryable(criteria, sorts);
        return tableGateway.queryColumns(criteria, pageRequest, sorts).stream()
                .map(this::fromColumnMap)
                .toList();
    }

    @Override
    public List<DynamicRecord> list(Criteria criteria, Sort... sorts) {
        validateQueryable(criteria, sorts);
        return tableGateway.listColumns(criteria, sorts).stream()
                .map(this::fromColumnMap)
                .toList();
    }

    @Override
    public PageResult<DynamicRecord> page(Criteria criteria, PageRequest pageRequest, Sort... sorts) {
        return pageQuery(criteria, pageRequest, sorts);
    }

    @Override
    public PageResult<DynamicRecord> pageQuery(Criteria criteria, PageRequest pageRequest, Sort... sorts) {
        long total = count(criteria);
        return PageResult.of(query(criteria, pageRequest, sorts), total, pageRequest);
    }

    public EntityDefinition getEntity() {
        return entity;
    }

    @Override
    public long count(Criteria criteria) {
        validateQueryable(criteria);
        return tableGateway.count(criteria);
    }

    @Override
    public int upsert(DynamicRecord entity) {
        throw new UnsupportedOperationException("dynamic record upsert is not supported yet");
    }

    private DynamicRecord loadById(String id) {
        return query(Criteria.of().eq(StandardEntitySchema.ID_FIELD, id), new PageRequest(0, 1)).stream().findFirst().orElse(null);
    }

    private void validateQueryable(Criteria criteria, Sort... sorts) {
        validateQueryable(criteria);
        if (sorts == null) {
            return;
        }
        for (Sort sort : sorts) {
            if (sort != null) {
                mapping.requireQueryAllowed(sort.getField());
            }
        }
    }

    private void validateQueryable(Criteria criteria) {
        if (criteria == null || criteria.isEmpty()) {
            return;
        }
        validateQueryableNode(criteria.getRoot());
    }

    private void validateQueryableNode(Object node) {
        if (node instanceof CriteriaClause clause) {
            if (clause.getOperator() == CriteriaOperator.RAW && mapping.hasProtectedStorageFields()) {
                throw new IllegalArgumentException(
                        "raw criteria cannot be used when dynamic entity has protected storage fields");
            }
            if (clause.getField() != null) {
                mapping.requireQueryAllowed(clause.getField());
            }
            return;
        }
        if (node instanceof CriteriaGroup group) {
            group.getEntries().forEach(entry -> validateQueryableNode(entry.getNode()));
        }
    }

    private TableMeta tableMeta() {
        TableMeta.Builder builder = TableMeta.builder(schema, entity.tableName())
                .id(StandardEntitySchema.ID_FIELD, StandardEntitySchema.ID_COLUMN, ColumnType.VARCHAR, String.class)
                .field(StandardEntitySchema.TENANT_ID_FIELD, StandardEntitySchema.TENANT_ID_COLUMN, ColumnType.VARCHAR, String.class)
                .field(StandardEntitySchema.VERSION_FIELD, StandardEntitySchema.VERSION_COLUMN, ColumnType.INT, Integer.class)
                .field(StandardEntitySchema.DELETED_FIELD, StandardEntitySchema.DELETED_COLUMN, ColumnType.BOOLEAN, Boolean.class)
                .field(StandardEntitySchema.DELETED_AT_FIELD, StandardEntitySchema.DELETED_AT_COLUMN, ColumnType.TIMESTAMP, Instant.class)
                .field(StandardEntitySchema.DELETED_BY_FIELD, StandardEntitySchema.DELETED_BY_COLUMN, ColumnType.VARCHAR, String.class)
                .field(StandardEntitySchema.CREATED_BY_FIELD, StandardEntitySchema.CREATED_BY_COLUMN, ColumnType.VARCHAR, String.class)
                .field(StandardEntitySchema.CREATED_AT_FIELD, StandardEntitySchema.CREATED_AT_COLUMN, ColumnType.TIMESTAMP, Instant.class)
                .field(StandardEntitySchema.UPDATED_BY_FIELD, StandardEntitySchema.UPDATED_BY_COLUMN, ColumnType.VARCHAR, String.class)
                .field(StandardEntitySchema.UPDATED_AT_FIELD, StandardEntitySchema.UPDATED_AT_COLUMN, ColumnType.TIMESTAMP, Instant.class);
        persistentFields().forEach(field -> addFieldMeta(builder, field));
        return builder.build();
    }

    private void addFieldMeta(TableMeta.Builder builder, FieldDefinition field) {
        if (DynamicFieldColumnMetadata.isJsonSetField(field)) {
            builder.jsonSet(field.code(), field.columnName(), List.class, String.class);
            return;
        }
        builder.field(
                field.code(),
                field.columnName(),
                DynamicFieldColumnMetadata.columnType(field),
                DynamicFieldColumnMetadata.fieldJavaType(field)
        );
    }

    private Map<String, Object> toColumnMap(DynamicRecord record, boolean includeId) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (includeId) {
            body.put(StandardEntitySchema.ID_COLUMN, record.getId());
        }
        body.put(StandardEntitySchema.TENANT_ID_COLUMN, record.getTenantId());
        body.put(StandardEntitySchema.VERSION_COLUMN, record.getVersion());
        body.put(StandardEntitySchema.DELETED_COLUMN, record.getDeleted());
        body.put(StandardEntitySchema.DELETED_AT_COLUMN, record.getDeletedAt());
        body.put(StandardEntitySchema.DELETED_BY_COLUMN, record.getDeletedBy());
        body.put(StandardEntitySchema.CREATED_BY_COLUMN, record.getCreatedBy());
        body.put(StandardEntitySchema.CREATED_AT_COLUMN, record.getCreatedAt());
        body.put(StandardEntitySchema.UPDATED_BY_COLUMN, record.getUpdatedBy());
        body.put(StandardEntitySchema.UPDATED_AT_COLUMN, record.getUpdatedAt());
        for (FieldDefinition field : persistentFields()) {
            if (record.getPlatformValues().containsKey(field.code())) {
                body.put(field.columnName(), record.getPlatformValues().get(field.code()));
            }
        }
        if (!includeId) {
            body.put(StandardEntitySchema.ID_COLUMN, record.getId());
        }
        return body;
    }

    private Map<String, Object> toUpdateMap(DynamicRecord record) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(StandardEntitySchema.VERSION_COLUMN, record.getVersion());
        body.put(StandardEntitySchema.UPDATED_AT_COLUMN, record.getUpdatedAt());
        if (record.getUpdatedBy() != null) {
            body.put(StandardEntitySchema.UPDATED_BY_COLUMN, record.getUpdatedBy());
        }
        if (record.getDeleted() != null) {
            body.put(StandardEntitySchema.DELETED_COLUMN, record.getDeleted());
            body.put(StandardEntitySchema.DELETED_AT_COLUMN, record.getDeletedAt());
            body.put(StandardEntitySchema.DELETED_BY_COLUMN, record.getDeletedBy());
        }
        for (FieldDefinition field : persistentFields()) {
            if (record.getPlatformValues().containsKey(field.code())) {
                body.put(field.columnName(), record.getPlatformValues().get(field.code()));
            }
        }
        return body;
    }

    private Map<String, Object> toDeleteMap(DynamicRecord record) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(StandardEntitySchema.DELETED_COLUMN, Boolean.TRUE);
        body.put(StandardEntitySchema.DELETED_AT_COLUMN, record.getDeletedAt());
        body.put(StandardEntitySchema.DELETED_BY_COLUMN, record.getDeletedBy());
        body.put(StandardEntitySchema.VERSION_COLUMN, record.getVersion());
        body.put(StandardEntitySchema.UPDATED_AT_COLUMN, record.getUpdatedAt());
        if (record.getUpdatedBy() != null) {
            body.put(StandardEntitySchema.UPDATED_BY_COLUMN, record.getUpdatedBy());
        }
        return body;
    }

    private Map<String, Object> toConditionColumnMap(Map<String, Object> conditions) {
        Map<String, Object> columns = new LinkedHashMap<>();
        conditions.forEach((field, value) -> columns.put(mapping.resolveColumn(field), value));
        return columns;
    }

    private DynamicRecord fromColumnMap(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        DynamicRecord record = new DynamicRecord(entity);
        record.setId(stringValue(row.get(StandardEntitySchema.ID_COLUMN)));
        record.setTenantId(stringValue(row.get(StandardEntitySchema.TENANT_ID_COLUMN)));
        record.setVersion(numberValue(row.get(StandardEntitySchema.VERSION_COLUMN)));
        record.setDeleted((Boolean) row.get(StandardEntitySchema.DELETED_COLUMN));
        record.setDeletedAt(instantValue(row.get(StandardEntitySchema.DELETED_AT_COLUMN)));
        record.setDeletedBy(stringValue(row.get(StandardEntitySchema.DELETED_BY_COLUMN)));
        record.setCreatedBy(stringValue(row.get(StandardEntitySchema.CREATED_BY_COLUMN)));
        record.setCreatedAt(instantValue(row.get(StandardEntitySchema.CREATED_AT_COLUMN)));
        record.setUpdatedBy(stringValue(row.get(StandardEntitySchema.UPDATED_BY_COLUMN)));
        record.setUpdatedAt(instantValue(row.get(StandardEntitySchema.UPDATED_AT_COLUMN)));
        for (FieldDefinition field : persistentFields()) {
            record.putLoadedValue(field.code(), row.get(field.columnName()));
        }
        return record;
    }

    private List<FieldDefinition> recordFields() {
        List<FieldDefinition> fields = new ArrayList<>();
        if (entity.supports(EntityCapability.DATA_SCOPE)) {
            fields.addAll(DynamicAbilityFields.dataScopeFields());
        }
        if (entity.supports(EntityCapability.APPROVAL)) {
            fields.addAll(DynamicAbilityFields.approvalFields());
        }
        fields.addAll(FieldCompanionRules.recordFields(entity));
        return fields;
    }

    private List<FieldDefinition> persistentFields() {
        return recordFields().stream()
                .filter(FieldDefinition::isPhysical)
                .toList();
    }

    private Integer numberValue(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Instant instantValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof java.time.LocalDateTime localDateTime) {
            return localDateTime.toInstant(ZoneOffset.UTC);
        }
        return Instant.parse(String.valueOf(value));
    }

    private void requireSameEntity(DynamicRecord record) {
        Objects.requireNonNull(record, "record must not be null");
        if (!entity.alias().equals(record.getEntity().alias())) {
            throw new IllegalArgumentException("dynamic record entity mismatch: " + record.getEntity().alias());
        }
    }
}
