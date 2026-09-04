package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.builder.sql.SchemaBuildRules;
import net.ximatai.muyun.database.core.metadata.DBInfo;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Internal facts used only while governing a dynamic entity's physical schema.
 *
 * <p>Unlike the normal {@link DynamicRecordService} query surface, these reads deliberately
 * bypass action data scope, tenant filtering and soft-delete filtering: a destructive schema
 * operation must account for every physical row, not merely rows visible to its operator.</p>
 */
public class DynamicSchemaGovernanceFacts {
    private final DynamicRecordRuntime runtime;

    public DynamicSchemaGovernanceFacts(DynamicRecordRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
    }

    /** Counts every persisted row matching the physical entity criteria without business read scope. */
    public long countPhysicalRecords(String moduleAlias, String entityAlias, Criteria criteria) {
        return runtime.entityService(PlatformNameRules.requireModuleAlias(moduleAlias),
                        PlatformNameRules.requireDatabaseName(entityAlias, "entityAlias"))
                .dynamicDao()
                .count(criteria == null ? Criteria.of() : criteria);
    }

    /**
     * Takes PostgreSQL's strongest table lock for a destructive schema mutation.
     *
     * <p>The caller must perform the physical-row count and all DDL in the same surrounding
     * transaction after this method returns. {@code ACCESS EXCLUSIVE} conflicts with ordinary
     * inserts, updates and deletes, closing the count-to-DDL write window.</p>
     *
     * @return {@code true} when the physical table exists and was locked; {@code false} when it
     * does not exist yet and therefore cannot contain rows or accept concurrent writes.
     */
    public boolean lockExistingTableForSchemaMutation(String schemaName, String tableName) {
        requireActiveTransaction();
        String schema = PlatformNameRules.requireDatabaseName(schemaName, "schemaName");
        String table = PlatformNameRules.requireDatabaseName(tableName, "tableName");
        IDatabaseOperations<?> operations = postgresOperations();
        if (!tableExists(operations, schema, table)) {
            return false;
        }
        String qualifiedTable = SchemaBuildRules.quoteIdentifier(schema, DBInfo.Type.POSTGRESQL)
                + "." + SchemaBuildRules.quoteIdentifier(table, DBInfo.Type.POSTGRESQL);
        operations.execute("lock table " + qualifiedTable + " in access exclusive mode");
        return true;
    }

    /** Returns the database type after asserting this destructive path supports it. */
    public DBInfo.Type databaseTypeForSchemaMutation() {
        requireActiveTransaction();
        return postgresOperations().getDBInfo().getDatabaseType();
    }

    /** Executes DDL only after the caller has established the schema-governance transaction boundary. */
    public int executeSchemaMutation(String sql) {
        requireActiveTransaction();
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("schema mutation sql must not be blank");
        }
        IDatabaseOperations<?> operations = postgresOperations();
        int affected = operations.execute(sql);
        operations.resetDBInfo();
        return affected;
    }

    private boolean tableExists(IDatabaseOperations<?> operations, String schema, String table) {
        List<Map<String, Object>> rows = operations.query("""
                select to_regclass(?::text) as relation
                """, schema + "." + table);
        return !rows.isEmpty() && rows.getFirst().get("relation") != null;
    }

    private IDatabaseOperations<?> postgresOperations() {
        IDatabaseOperations<?> operations = runtime.operations();
        DBInfo.Type databaseType = operations.getDBInfo().getDatabaseType();
        if (databaseType != DBInfo.Type.POSTGRESQL) {
            throw new PlatformException("破坏性动态 Schema 治理仅支持 PostgreSQL 表锁，当前数据库：" + databaseType);
        }
        return operations;
    }

    private void requireActiveTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("dynamic schema governance requires an active transaction");
        }
    }
}
