package net.ximatai.muyun.spring.iam.support;

import net.ximatai.muyun.spring.common.platform.TenantApplicationCatalog;
import net.ximatai.muyun.spring.common.tenant.TenantCreationProvisioner;
import net.ximatai.muyun.spring.iam.tenant.TenantApplicationDao;
import net.ximatai.muyun.spring.iam.tenant.TenantApplicationService;
import net.ximatai.muyun.spring.iam.tenant.TenantDao;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import net.ximatai.muyun.spring.platform.attachment.ManagedFileAssetService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.mockito.Mockito.mock;

/** Supplies explicit collaborators while retaining the production tenant child lifecycle. */
public final class TenantServiceTestFactory {
    private TenantServiceTestFactory() { }

    public static TenantService create(TenantDao dao) {
        return create(dao, new StaticListableBeanFactory().getBeanProvider(TenantCreationProvisioner.class));
    }

    public static TenantService create(TenantDao dao, ObjectProvider<TenantCreationProvisioner> provisioners) {
        return new TenantService(dao, provisioners, applicationService(), mock(ManagedFileAssetService.class));
    }

    public static TenantApplicationService applicationService() {
        return new TenantApplicationService(mock(TenantApplicationDao.class), mock(TenantApplicationCatalog.class));
    }
}
