package net.ximatai.muyun.spring.common.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantContextTest {
    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void shouldRejectForeignOwnershipWhenInitializingNewRecord() {
        TestEntity entity = new TestEntity();
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            TenantContext.applyToNewEntity(entity);
            assertThat(entity.getTenantId()).isEqualTo("tenant-a");
            TenantContext.applyToNewEntity(entity);
            entity.setTenantId("tenant-b");
            assertThatThrownBy(() -> TenantContext.applyToNewEntity(entity))
                    .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
        }
        try (TenantContext.Scope ignored = TenantContext.system("trusted provisioning")) {
            TenantContext.applyToNewEntity(entity);
            assertThat(entity.getTenantId()).isEqualTo("tenant-b");
        }
    }

    @Test
    void shouldAllowForeignOwnershipOnlyWithinExplicitBypassScope() {
        TestEntity entity = new TestEntity();
        entity.setTenantId("tenant-b");
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            try (TenantContext.Scope bypass = TenantContext.bypassTenantFilter("trusted cross tenant provisioning")) {
                TenantContext.applyToNewEntity(entity);
                assertThat(entity.getTenantId()).isEqualTo("tenant-b");
                assertThat(TenantContext.currentTenantId()).contains("tenant-a");
            }
            assertThat(TenantContext.tenantFilterBypassed()).isFalse();
            assertThatThrownBy(() -> TenantContext.applyToNewEntity(entity))
                    .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            assertThat(entity.getTenantId()).isEqualTo("tenant-b");
        }
        assertThat(TenantContext.hasContext()).isFalse();
    }

    @Test
    void shouldExposeNoContextByDefault() {
        assertThat(TenantContext.hasContext()).isFalse();
        assertThat(TenantContext.isSystem()).isFalse();
        assertThat(TenantContext.currentTenantId()).isEmpty();
    }

    @Test
    void shouldUseTenantScopeAndRestorePreviousContext() {
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(TenantContext.hasContext()).isTrue();
            assertThat(TenantContext.isSystem()).isFalse();
            assertThat(TenantContext.currentTenantId()).contains("tenant-a");

            try (TenantContext.Scope nested = TenantContext.use("tenant-b")) {
                assertThat(TenantContext.currentTenantId()).contains("tenant-b");
            }

            assertThat(TenantContext.currentTenantId()).contains("tenant-a");
        }

        assertThat(TenantContext.hasContext()).isFalse();
        assertThat(TenantContext.currentTenantId()).isEmpty();
    }

    @Test
    void shouldUseSystemScopeAndRestoreTenantContext() {
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            try (TenantContext.Scope system = TenantContext.system("bootstrap tenant catalog")) {
                assertThat(TenantContext.hasContext()).isTrue();
                assertThat(TenantContext.isSystem()).isTrue();
                assertThat(TenantContext.currentTenantId()).isEmpty();
                assertThat(TenantContext.systemReason()).contains("bootstrap tenant catalog");
            }

            assertThat(TenantContext.isSystem()).isFalse();
            assertThat(TenantContext.currentTenantId()).contains("tenant-a");
            assertThat(TenantContext.systemReason()).isEmpty();
        }
    }

    @Test
    void shouldBypassTenantFilterAndRestoreTenantContext() {
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            try (TenantContext.Scope bypass = TenantContext.bypassTenantFilter("cross tenant data scope")) {
                assertThat(TenantContext.hasContext()).isTrue();
                assertThat(TenantContext.isSystem()).isFalse();
                assertThat(TenantContext.currentTenantId()).contains("tenant-a");
                assertThat(TenantContext.tenantFilterBypassed()).isTrue();
            }

            assertThat(TenantContext.currentTenantId()).contains("tenant-a");
            assertThat(TenantContext.tenantFilterBypassed()).isFalse();
        }
    }

    @Test
    void shouldMatchAnyTenantWhenTenantFilterIsBypassed() {
        TestEntity entity = new TestEntity();
        entity.setTenantId("tenant-b");

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(TenantContext.matchesCurrentTenant(entity)).isFalse();

            try (TenantContext.Scope bypass = TenantContext.bypassTenantFilter("cross tenant data scope")) {
                assertThat(TenantContext.matchesCurrentTenant(entity)).isTrue();
            }
        }
    }

    @Test
    void shouldRequireSystemScopeReason() {
        assertThatThrownBy(() -> TenantContext.system(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("system context requires reason");
    }

    @Test
    void shouldRequireBypassTenantFilterReason() {
        assertThatThrownBy(() -> TenantContext.bypassTenantFilter(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant filter bypass requires reason");
    }

    @Test
    void shouldClearBlankTenantToNoContext() {
        TenantContext.setTenantId("tenant-a");
        TenantContext.setTenantId(" ");

        assertThat(TenantContext.hasContext()).isFalse();
        assertThat(TenantContext.currentTenantId()).isEmpty();
    }

    @Test
    void tenantSystemRetainsFilteringAndReasonWithoutGlobalSystemPrivileges() {
        try (var previous = TenantContext.use("request-tenant")) {
            try (var job = TenantContext.tenantSystem("tenant-a", "iam.directory-sync")) {
                assertThat(TenantContext.currentTenantId()).contains("tenant-a");
                assertThat(TenantContext.isSystem()).isFalse();
                assertThat(TenantContext.isTenantSystem()).isTrue();
                assertThat(TenantContext.systemReason()).contains("后台作业 iam.directory-sync");
                assertThat(TenantContext.systemOperatorId()).contains("job:xZpF4KQDqr-UTEv-yTciicR5O5O7eQXW1iRfcjOM6Ek");
                TestEntity owned = new TestEntity();
                net.ximatai.muyun.spring.common.model.EntityLifecycle.prepareInsert(owned, java.time.Instant.now());
                assertThat(owned.getTenantId()).isEqualTo("tenant-a");
                assertThat(owned.getCreatedBy()).isEqualTo("job:xZpF4KQDqr-UTEv-yTciicR5O5O7eQXW1iRfcjOM6Ek");
                TestEntity foreign = new TestEntity();
                foreign.setTenantId("tenant-b");
                assertThat(TenantContext.matchesCurrentTenant(foreign)).isFalse();
                assertThatThrownBy(() -> TenantContext.applyToNewEntity(foreign))
                        .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
                assertThatThrownBy(() -> net.ximatai.muyun.spring.common.model.EntityLifecycle.prepareUpdate(
                        foreign, java.time.Instant.now()))
                        .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
                TestEntity shared = new TestEntity();
                assertThatThrownBy(() -> net.ximatai.muyun.spring.common.model.EntityLifecycle.prepareUpdate(
                        shared, java.time.Instant.now()))
                        .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
                assertThat(shared.getTenantId()).isNull();
            }
            assertThat(TenantContext.currentTenantId()).contains("request-tenant");
            assertThat(TenantContext.systemReason()).isEmpty();
            assertThat(TenantContext.backgroundJobType()).isEmpty();
        }
    }

    @Test
    void platformJobUsesExistingSystemSemanticsAndRequiresAType() {
        assertThatThrownBy(() -> TenantContext.tenantSystem(" ", "job"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantContext.tenantSystem("tenant-a", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantContext.backgroundJob(null))
                .isInstanceOf(IllegalArgumentException.class);
        try (var job = TenantContext.backgroundJob("platform.cleanup")) {
            assertThat(TenantContext.isSystem()).isTrue();
            assertThat(TenantContext.currentTenantId()).isEmpty();
            assertThat(TenantContext.systemReason()).contains("后台作业 platform.cleanup");
        }
    }

    private static final class TestEntity extends net.ximatai.muyun.spring.common.model.standard.StandardEntity {
    }
}
