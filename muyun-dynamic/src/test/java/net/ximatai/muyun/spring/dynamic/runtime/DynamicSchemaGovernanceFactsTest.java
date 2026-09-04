package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.metadata.DBInfo;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamicSchemaGovernanceFactsTest {
    @AfterEach
    void clearContext() {
        TenantContext.clear();
        TransactionSynchronizationManager.clear();
    }

    @Test
    void shouldCountPhysicalRowsWithoutTenantOrScopedQueryRuntime() {
        DynamicRecordRuntime runtime = mock(DynamicRecordRuntime.class);
        DynamicEntityService entity = mock(DynamicEntityService.class);
        DynamicRecordDao dao = mock(DynamicRecordDao.class);
        when(runtime.entityService("crm.customer", "customer")).thenReturn(entity);
        when(entity.dynamicDao()).thenReturn(dao);
        when(dao.count(any(Criteria.class))).thenReturn(2L);
        TenantContext.setTenantId("tenant-a");

        long records = new DynamicSchemaGovernanceFacts(runtime)
                .countPhysicalRecords("crm.customer", "customer", Criteria.of());

        assertThat(records).isEqualTo(2L);
        verify(dao).count(any(Criteria.class));
    }

    @Test
    void shouldLockExistingPostgresTableBeforeTheCallerCountsOrExecutesDdl() {
        DynamicRecordRuntime runtime = mock(DynamicRecordRuntime.class);
        @SuppressWarnings("unchecked") IDatabaseOperations<Object> operations = mock(IDatabaseOperations.class);
        DBInfo info = mock(DBInfo.class);
        doReturn(operations).when(runtime).operations();
        when(operations.getDBInfo()).thenReturn(info);
        when(info.getDatabaseType()).thenReturn(DBInfo.Type.POSTGRESQL);
        when(operations.query(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("relation", "public.crm_customer")));
        TransactionSynchronizationManager.setActualTransactionActive(true);

        boolean locked = new DynamicSchemaGovernanceFacts(runtime)
                .lockExistingTableForSchemaMutation("public", "crm_customer");

        assertThat(locked).isTrue();
        InOrder order = inOrder(operations);
        order.verify(operations).query(eq("select to_regclass(?::text) as relation\n"),
                eq(new Object[]{"public.crm_customer"}));
        order.verify(operations).execute("lock table \"public\".\"crm_customer\" in access exclusive mode");
    }

    @Test
    void shouldRejectDestructiveSchemaGovernanceOutsidePostgresql() {
        DynamicRecordRuntime runtime = mock(DynamicRecordRuntime.class);
        @SuppressWarnings("unchecked") IDatabaseOperations<Object> operations = mock(IDatabaseOperations.class);
        DBInfo info = mock(DBInfo.class);
        doReturn(operations).when(runtime).operations();
        when(operations.getDBInfo()).thenReturn(info);
        when(info.getDatabaseType()).thenReturn(DBInfo.Type.MYSQL);
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> new DynamicSchemaGovernanceFacts(runtime)
                .lockExistingTableForSchemaMutation("public", "crm_customer"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("PostgreSQL");
    }
}
