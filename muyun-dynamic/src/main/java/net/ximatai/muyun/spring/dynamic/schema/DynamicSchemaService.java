package net.ximatai.muyun.spring.dynamic.schema;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.orm.MigrationOptions;
import net.ximatai.muyun.database.core.orm.MigrationResult;
import net.ximatai.muyun.database.core.orm.SchemaManager;
import net.ximatai.muyun.database.core.builder.sql.SchemaBuildRules;
import net.ximatai.muyun.spring.common.schema.PlatformSchemaMigrationPolicy;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionValidator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class DynamicSchemaService {
    private final IDatabaseOperations<?> operations;
    private final DynamicTableMapper tableMapper;
    private final ModuleDefinitionValidator validator;
    private final PlatformSchemaMigrationPolicy migrationPolicy;

    public DynamicSchemaService(IDatabaseOperations<?> operations) {
        this(operations, new DynamicTableMapper(), new ModuleDefinitionValidator());
    }

    public DynamicSchemaService(IDatabaseOperations<?> operations,
                                DynamicTableMapper tableMapper,
                                ModuleDefinitionValidator validator) {
        this(operations, tableMapper, validator, PlatformSchemaMigrationPolicy.executeByDefault());
    }

    public DynamicSchemaService(IDatabaseOperations<?> operations,
                                DynamicTableMapper tableMapper,
                                ModuleDefinitionValidator validator,
                                PlatformSchemaMigrationPolicy migrationPolicy) {
        this.operations = operations;
        this.tableMapper = tableMapper;
        this.validator = validator;
        this.migrationPolicy = migrationPolicy == null
                ? PlatformSchemaMigrationPolicy.executeByDefault()
                : migrationPolicy;
    }

    public boolean ensureTable(EntityDefinition entity) {
        return ensureTable(entity, (MigrationOptions) null).isChanged();
    }

    public MigrationResult ensureTable(EntityDefinition entity, MigrationOptions options) {
        return new SchemaManager(operations).ensureTable(tableMapper.toTable(entity), migrationPolicy.resolve(options));
    }

    public MigrationResult ensureTable(EntityDefinition entity, EntityDefinition previousEntity, MigrationOptions options) {
        return new SchemaManager(operations).ensureTable(tableMapper.toTable(entity, previousEntity), migrationPolicy.resolve(options));
    }

    /** Removes an empty dynamic entity table after its metadata definition has passed deletion checks. */
    public void dropTable(EntityDefinition entity) {
        validator.validateEntity(entity);
        String table = SchemaBuildRules.qualifiedName(entity.schemaName(), entity.tableName(),
                operations.getDBInfo().getDatabaseType());
        operations.execute("DROP TABLE " + table);
        operations.resetDBInfo();
    }

    public Map<String, Boolean> ensureModule(ModuleDefinition module) {
        Map<String, MigrationResult> migrations = ensureModule(module, (MigrationOptions) null);
        Map<String, Boolean> results = new LinkedHashMap<>();
        migrations.forEach((entityAlias, migration) -> results.put(entityAlias, migration.isChanged()));
        return results;
    }

    public Map<String, MigrationResult> ensureModule(ModuleDefinition module, MigrationOptions options) {
        return ensureModule(module, null, options);
    }

    public Map<String, MigrationResult> ensureModule(ModuleDefinition module,
                                                     ModuleDefinition previousModule,
                                                     MigrationOptions options) {
        MigrationOptions effectiveOptions = migrationPolicy.resolve(options);
        validator.validate(module);
        if (previousModule != null) {
            validator.validate(previousModule);
        }
        Map<String, EntityDefinition> previousEntities = previousModule == null
                ? Map.of()
                : previousModule.entities().stream().collect(Collectors.toMap(EntityDefinition::alias, Function.identity()));
        Map<String, MigrationResult> results = new LinkedHashMap<>();
        for (EntityDefinition entity : module.entities()) {
            try {
                results.put(entity.alias(), ensureTable(entity, previousEntities.get(entity.alias()), effectiveOptions));
            } catch (RuntimeException e) {
                throw new DynamicSchemaMigrationException(module.moduleAlias(), entity.alias(), results, e);
            }
        }
        return results;
    }
}
