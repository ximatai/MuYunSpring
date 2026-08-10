package net.ximatai.muyun.spring.starter.configuration.iam;

import net.ximatai.muyun.spring.iam.role.StaticModuleActionRegistry;
import net.ximatai.muyun.spring.iam.role.BuiltInRolePermissionTemplateService;
import net.ximatai.muyun.spring.platform.menu.DefaultTenantMenuProvisioner;
import net.ximatai.muyun.spring.iam.tenant.DefaultTenantApplicationProvisioner;
import net.ximatai.muyun.spring.iam.role.DefaultOrganizationRoleProvisioner;
import net.ximatai.muyun.spring.iam.role.DefaultTenantRoleProvisioner;
import net.ximatai.muyun.spring.iam.role.TenantAdminRoleReconciliationTask;
import net.ximatai.muyun.spring.iam.tenant.TenantApplicationReconciliationTask;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import net.ximatai.muyun.spring.iam.tenant.TenantApplicationService;
import net.ximatai.muyun.spring.iam.role.RoleService;
import net.ximatai.muyun.spring.ability.TenantActiveScopedAbility;
import net.ximatai.muyun.spring.platform.menu.MenuSchemeService;
import net.ximatai.muyun.spring.platform.menu.MenuService;
import net.ximatai.muyun.spring.platform.menu.SystemMenuSchemeAccessPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * IAM 宿主装配：提供租户有效性、系统菜单访问和租户初始化所需的默认协作对象。
 * 初始数据执行与启动任务编排由 {@code boot.bootstrap} 负责，避免身份配置越界。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MuYunSpringInitialAdminProperties.class)
public class MuYunSpringIdentityConfiguration {
    @Bean
    @Primary
    @ConditionalOnMissingBean(value = ActiveTenantVerifier.class,
            ignored = {TenantService.class, TenantActiveScopedAbility.class})
    /** 将租户服务作为默认有效性校验入口；应用自定义校验器时自动让位。 */
    public ActiveTenantVerifier activeTenantVerifier(TenantService tenantService) {
        return tenantService;
    }

    @Bean
    @ConditionalOnMissingBean(SystemMenuSchemeAccessPolicy.class)
    /** 默认拒绝租户用户回退到系统菜单方案，避免菜单越权。 */
    public SystemMenuSchemeAccessPolicy systemMenuSchemeAccessPolicy() {
        return SystemMenuSchemeAccessPolicy.DENY_ALL;
    }

    @Bean
    @ConditionalOnMissingBean(StaticModuleActionRegistry.class)
    /** 汇集静态模块动作，为角色授权与初始角色模板提供稳定目录。 */
    public StaticModuleActionRegistry staticModuleActionRegistry() {
        return new StaticModuleActionRegistry();
    }

    @Bean
    @ConditionalOnBean({TenantService.class, TenantApplicationService.class})
    @ConditionalOnMissingBean(TenantApplicationReconciliationTask.class)
    /** 在租户与应用均可用时注册对账任务，补齐租户应用开通状态。 */
    public TenantApplicationReconciliationTask tenantApplicationReconciliationTask(
            TenantService tenantService,
            TenantApplicationService tenantApplicationService) {
        return new TenantApplicationReconciliationTask(tenantService, tenantApplicationService);
    }

    @Bean
    @ConditionalOnBean({TenantService.class, DefaultTenantRoleProvisioner.class})
    @ConditionalOnMissingBean(TenantAdminRoleReconciliationTask.class)
    /** 为存量租户回填平台识别的租户管理员用途，保留既有账号授权事实。 */
    public TenantAdminRoleReconciliationTask tenantAdminRoleReconciliationTask(
            TenantService tenantService,
            DefaultTenantRoleProvisioner tenantRoleProvisioner) {
        return new TenantAdminRoleReconciliationTask(tenantService, tenantRoleProvisioner);
    }

    @Bean
    @ConditionalOnBean({MenuSchemeService.class, MenuService.class})
    @ConditionalOnMissingBean(DefaultTenantMenuProvisioner.class)
    /** 租户创建时复制默认菜单方案；具体菜单事实仍由平台菜单领域维护。 */
    public DefaultTenantMenuProvisioner defaultTenantMenuProvisioner(MenuSchemeService menuSchemeService,
                                                                    MenuService menuService) {
        return new DefaultTenantMenuProvisioner(menuSchemeService, menuService);
    }

    @Bean
    @ConditionalOnBean(TenantApplicationService.class)
    @ConditionalOnMissingBean(DefaultTenantApplicationProvisioner.class)
    /** 为新租户开通平台已交付的应用，不让租户流程了解静态应用细节。 */
    public DefaultTenantApplicationProvisioner defaultTenantApplicationProvisioner(
            TenantApplicationService tenantApplicationService) {
        return new DefaultTenantApplicationProvisioner(tenantApplicationService);
    }

    @Bean
    @ConditionalOnBean(RoleService.class)
    @ConditionalOnMissingBean(DefaultTenantRoleProvisioner.class)
    /** 准备平台识别的租户管理员角色；权限随租户已开通应用在运行时解释。 */
    public DefaultTenantRoleProvisioner defaultTenantRoleProvisioner(
            RoleService roleService) {
        return new DefaultTenantRoleProvisioner(roleService);
    }

    @Bean
    @ConditionalOnBean({RoleService.class, BuiltInRolePermissionTemplateService.class})
    @ConditionalOnMissingBean(DefaultOrganizationRoleProvisioner.class)
    /** 基于同一模板准备组织级角色，保持组织和租户角色语义一致。 */
    public DefaultOrganizationRoleProvisioner defaultOrganizationRoleProvisioner(
            RoleService roleService,
            BuiltInRolePermissionTemplateService rolePermissionTemplateService) {
        return new DefaultOrganizationRoleProvisioner(roleService, rolePermissionTemplateService);
    }

}
