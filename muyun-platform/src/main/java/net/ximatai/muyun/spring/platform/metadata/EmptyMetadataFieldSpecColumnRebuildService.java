package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.builder.sql.SchemaBuildRules;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicSchemaGovernanceFacts;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Rebuilds a physical business column when an empty entity changes its storage specification.
 *
 * <p>Dropping an empty column avoids database-specific implicit casts. The caller immediately
 * follows this operation with the normal schema ensure step, which recreates the column and its
 * metadata-declared indexes and constraints.</p>
 */
@Service
public class EmptyMetadataFieldSpecColumnRebuildService {
    private final DynamicRecordService recordService;
    private final FieldSpecService fieldSpecService;

    public EmptyMetadataFieldSpecColumnRebuildService(DynamicRecordService recordService,
                                                       FieldSpecService fieldSpecService) {
        this.recordService = Objects.requireNonNull(recordService, "recordService must not be null");
        this.fieldSpecService = Objects.requireNonNull(fieldSpecService, "fieldSpecService must not be null");
    }

    public void rebuildIfEmpty(String moduleAlias, Metadata metadata,
                               String previousFieldSpecAlias, MetadataField field) {
        if (Objects.equals(previousFieldSpecAlias, field.getFieldSpecAlias())
                || field.getFieldOwnership() != MetadataFieldOwnership.BUSINESS
                || field.getFieldForm() != MetadataFieldForm.PHYSICAL) {
            return;
        }
        String validModuleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        DynamicSchemaGovernanceFacts schemaFacts = recordService.schemaGovernanceFacts();
        if (!schemaFacts.lockExistingTableForSchemaMutation(metadata.getSchemaName(), metadata.getTableName())) {
            return;
        }
        long records = schemaFacts.countPhysicalRecords(validModuleAlias, metadata.getAlias(), Criteria.of());
        if (records > 0) {
            if (fieldSpecService.allowsDataSafeTarget(previousFieldSpecAlias, field.getFieldSpecAlias())) return;
            throw new PlatformException("字段规格变更前实体新增了 " + records + " 条业务数据，请重新预检。");
        }
        String schema = PlatformNameRules.requireDatabaseName(metadata.getSchemaName(), "schemaName");
        String table = PlatformNameRules.requireDatabaseName(metadata.getTableName(), "tableName");
        String column = PlatformNameRules.requireDatabaseName(field.getColumnName(), "columnName");
        var databaseType = schemaFacts.databaseTypeForSchemaMutation();
        String qualifiedTable = SchemaBuildRules.quoteIdentifier(schema, databaseType)
                + "." + SchemaBuildRules.quoteIdentifier(table, databaseType);
        schemaFacts.executeSchemaMutation("alter table " + qualifiedTable + " drop column "
                + SchemaBuildRules.quoteIdentifier(column, databaseType));
    }
}
