package net.ximatai.muyun.spring.platform.localization;

import net.ximatai.muyun.spring.common.tenant.TenantCreationProvisioner;
import net.ximatai.muyun.spring.platform.runtime.PlatformBootstrapTask;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Service;

/** Runs after configuration and menu-copy producers, both on startup and for new tenants. */
@Service
public class ConfiguredTextSeedBootstrap implements PlatformBootstrapTask, TenantCreationProvisioner, net.ximatai.muyun.spring.common.tenant.OrganizationCreationProvisioner, Ordered {
    private final ConfiguredTextSeedService seeds;
    public ConfiguredTextSeedBootstrap(ConfiguredTextSeedService seeds) { this.seeds = seeds; }
    @Override public String name() { return "platform.configuration-text-baselines"; }
    @Override public int order() { return 120; }
    @Override public int getOrder() { return 200; }
    @Override public void run() { seeds.seedDeployed(); }
    @Override public void afterTenantCreated(String tenantId) { seeds.seedTenant(tenantId); }
    @Override public void afterOrganizationCreated(String tenantId, String organizationId) { seeds.seedTenant(tenantId); }
}
