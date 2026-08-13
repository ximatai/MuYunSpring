package net.ximatai.muyun.spring.iam.tenant;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.tenant.TenantCreationProvisioner;
import net.ximatai.muyun.spring.platform.attachment.ManagedFileAssetService;
import net.ximatai.muyun.spring.ability.RecycleBinAbility;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Base64;
import java.util.stream.Stream;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantServiceContractTest {
    @Test
    void shouldCreateTenantInSystemContext() {
        TenantDao dao = mock(TenantDao.class);
        when(dao.insert(any())).thenAnswer(invocation -> invocation.<Tenant>getArgument(0).getId());
        TenantService service = new TenantService(dao);
        Tenant tenant = tenant("ximatai", "Ximatai");
        tenant.setTenantId("should-be-cleared");

        String id;
        try (TenantContext.Scope ignored = TenantContext.system("test system context")) {
            id = service.insert(tenant);
        }

        assertThat(id).isEqualTo("ximatai");
        assertThat(tenant.getId()).isEqualTo("ximatai");
        assertThat(tenant.getTenantId()).isNull();
        assertThat(tenant.getEnabled()).isTrue();
    }

    @Test
    void shouldRequireSystemContextForTenantMutation() {
        TenantService service = new TenantService(mock(TenantDao.class));

        assertThatThrownBy(() -> service.insert(tenant("ximatai", "Ximatai")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("system context");
        assertThatThrownBy(() -> service.beforeUpdate(tenant("ximatai", "Ximatai")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("system context");
        assertThatThrownBy(() -> service.beforeDelete("ximatai"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("system context");

        try (TenantContext.Scope ignored = TenantContext.use("ximatai")) {
            assertThatThrownBy(() -> service.insert(tenant("tenant_b", "Tenant B")))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("system context");
            assertThatThrownBy(() -> service.beforeUpdate(tenant("tenant_b", "Tenant B")))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("system context");
            assertThatThrownBy(() -> service.beforeDelete("tenant_b"))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("system context");
        }
    }

    @Test
    void shouldRejectInvalidTenantAlias() {
        TenantService service = new TenantService(mock(TenantDao.class));

        try (TenantContext.Scope ignored = TenantContext.system("test system context")) {
            assertThatThrownBy(() -> service.insert(tenant("tenant-a", "Tenant A")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tenantAlias");
        }
    }

    @Test
    void shouldKeepLogoContentOutOfTenantPersistenceModel() {
        TenantDao dao = mock(TenantDao.class);
        when(dao.insert(any())).thenAnswer(invocation -> invocation.<Tenant>getArgument(0).getId());
        TenantService service = new TenantService(dao);
        Tenant tenant = tenant("ximatai", "Ximatai");
        tenant.setLightLogoAssetId("asset-1");

        try (TenantContext.Scope ignored = TenantContext.system("test system context")) {
            service.insert(tenant);
        }

        assertThat(tenant.getLightLogoAssetId()).isEqualTo("asset-1");
    }

    @Test
    void shouldRequireSquareLogoForLogoWithTitleBranding() throws Exception {
        ManagedFileAssetService assets = mock(ManagedFileAssetService.class);
        when(assets.readInlineContent("ximatai", "asset-1")).thenReturn(imageDataUrl(200, 80));
        TenantService service = new TenantService(mock(TenantDao.class), null, null, assets);
        Tenant tenant = tenant("ximatai", "Ximatai");
        tenant.setWorkbenchBrandMode(TenantWorkbenchBrandMode.LOGO_WITH_TITLE);
        tenant.setLightLogoAssetId("asset-1");

        assertThatThrownBy(() -> service.normalizeBeforeMutation(tenant))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("正方形图片");
    }

    @Test
    void shouldAllowHorizontalLogoForLogoOnlyBranding() throws Exception {
        ManagedFileAssetService assets = mock(ManagedFileAssetService.class);
        TenantService service = new TenantService(mock(TenantDao.class), null, null, assets);
        Tenant tenant = tenant("ximatai", "Ximatai");
        tenant.setWorkbenchBrandMode(TenantWorkbenchBrandMode.LOGO_ONLY);
        tenant.setLightLogoAssetId("asset-1");

        service.normalizeBeforeMutation(tenant);

        org.mockito.Mockito.verifyNoInteractions(assets);
    }

    @Test
    void shouldExplainConflictWithSoftDeletedTenantInsteadOfLeakingPrimaryKeyFailure() {
        TenantDao dao = mock(TenantDao.class);
        Tenant deleted = tenant("demo", "演示租户");
        deleted.setDeleted(Boolean.TRUE);
        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(deleted));
        TenantService service = new TenantService(dao);

        try (TenantContext.Scope ignored = TenantContext.system("test system context")) {
            assertThatThrownBy(() -> service.insert(tenant("demo", "新的演示租户")))
                    .isInstanceOf(PlatformException.class)
                    .satisfies(exception -> {
                        PlatformException platformException = (PlatformException) exception;
                        assertThat(platformException.code())
                                .isEqualTo(PlatformErrorCodes.RESOURCE_SOFT_DELETED_CONFLICT);
                        assertThat(platformException.details())
                                .containsEntry("resourceModuleAlias", TenantService.MODULE_ALIAS)
                                .containsEntry("resourceRecordId", "demo")
                                .containsEntry("recoveryAvailable", Boolean.TRUE);
                    });
        }
    }

    @Test
    void shouldExposeRecycleBinOnlyThroughTenantSystemBoundary() {
        TenantDao dao = mock(TenantDao.class);
        Tenant deleted = tenant("demo", "演示租户");
        deleted.setDeleted(Boolean.TRUE);
        when(dao.pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(PageResult.of(List.of(deleted), 1, PageRequest.of(1, 20)));
        TenantService service = new TenantService(dao);

        assertThat(service).isInstanceOf(RecycleBinAbility.class);
        assertThatThrownBy(() -> service.listRecycleBin(PageRequest.of(1, 20)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("system context");

        try (TenantContext.Scope ignored = TenantContext.system("test system context")) {
            assertThat(service.listRecycleBin(PageRequest.of(1, 20))).containsExactly(deleted);
        }
    }

    @Test
    void shouldNotAllowTenantRootPurgeBeforeTenantScopeArchivingExists() {
        TenantService service = new TenantService(mock(TenantDao.class));

        assertThat(service.isRecycleBinPurgeEnabled()).isFalse();
        assertThatThrownBy(() -> service.purge("tenant_a"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("not enabled");
    }

    @Test
    void shouldRequireActiveTenant() {
        TenantDao dao = mock(TenantDao.class);
        when(dao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(tenant("active", "Active")))
                .thenReturn(List.of(disabledTenant("disabled", "Disabled")))
                .thenReturn(List.of());
        TenantService service = new TenantService(dao);

        assertThat(service.requireActiveTenant("active").getTitle()).isEqualTo("Active");
        assertThatThrownBy(() -> service.requireActiveTenant("disabled"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("not active");
        assertThatThrownBy(() -> service.requireActiveTenant("missing"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("not active");
    }

    @Test
    void shouldNotReservePlatformTenantAlias() {
        TenantService service = new TenantService(mock(TenantDao.class));

        try (TenantContext.Scope ignored = TenantContext.system("test system context")) {
            service.beforeUpdate(disabledTenant("platform", "平台租户"));
            service.beforeDelete("platform");
        }
    }

    @Test
    void shouldAllowTenantProvisioningReplay() {
        TenantDao dao = mock(TenantDao.class);
        when(dao.insert(any())).thenAnswer(invocation -> invocation.<Tenant>getArgument(0).getId());
        TenantCreationProvisioner provisioner = mock(TenantCreationProvisioner.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<TenantCreationProvisioner> provisioners = mock(ObjectProvider.class);
        when(provisioners.orderedStream()).thenAnswer(invocation -> Stream.of(provisioner));
        TenantService service = new TenantService(dao, provisioners);

        try (TenantContext.Scope ignored = TenantContext.system("test system context")) {
            service.insert(tenant("ximatai", "Ximatai"));
        }
        service.provisionTenant("ximatai");

        verify(provisioner, times(2)).afterTenantCreated("ximatai");
    }

    private Tenant tenant(String alias, String title) {
        Tenant tenant = new Tenant();
        tenant.setAlias(alias);
        tenant.setTitle(title);
        tenant.setEnabled(Boolean.TRUE);
        return tenant;
    }

    private Tenant disabledTenant(String alias, String title) {
        Tenant tenant = tenant(alias, title);
        tenant.setEnabled(Boolean.FALSE);
        return tenant;
    }

    private String imageDataUrl(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

}
