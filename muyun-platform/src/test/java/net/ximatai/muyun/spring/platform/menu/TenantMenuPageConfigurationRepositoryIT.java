package net.ximatai.muyun.spring.platform.menu;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.PlatformManagedMutationContext;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.model.standard.StandardTitledEntity;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldService;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import net.ximatai.muyun.spring.platform.task.ModuleCompletionCheckService;
import net.ximatai.muyun.spring.platform.ui.PlatformPageBootstrapService;
import net.ximatai.muyun.spring.platform.ui.PlatformPageConfigSnapshotService;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryGroupOperator;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryItem;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryItemDao;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryItemService;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryTemplate;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryTemplateDao;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryTemplateService;
import net.ximatai.muyun.spring.platform.ui.PlatformTaskCheckBlock;
import net.ximatai.muyun.spring.platform.ui.PlatformTaskCheckType;
import net.ximatai.muyun.spring.platform.ui.PlatformUiClientType;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfig;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfigDao;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfigField;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfigFieldDao;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfigFieldService;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfigService;
import net.ximatai.muyun.spring.platform.ui.PlatformUiSet;
import net.ximatai.muyun.spring.platform.ui.PlatformUiSetDao;
import net.ximatai.muyun.spring.platform.ui.PlatformUiSetService;
import net.ximatai.muyun.spring.platform.ui.PlatformUiSetType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = TenantMenuPageConfigurationRepositoryIT.TestApplication.class)
class TenantMenuPageConfigurationRepositoryIT extends PlatformPostgresIntegrationTest {
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }

    @Autowired MenuDao menus;
    @Autowired MenuSchemeDao schemes;
    @Autowired PlatformUiSetDao sets;
    @Autowired PlatformUiConfigDao configs;
    @Autowired PlatformUiConfigFieldDao fields;
    @Autowired PlatformQueryTemplateDao templates;
    @Autowired PlatformQueryItemDao items;

    @Test
    void copiesGlobalDefaultsAndConsumesOnlyPublishedConfigurationsFromTheVisibleOwner() {
        var moduleDao = new TestMemoryDao<PlatformModule>();
        var module = new PlatformModule();
        module.setId("crm.customer");
        module.setAlias("crm.customer");
        module.setApplicationAlias("crm");
        module.setModuleKind(ModuleKind.STATIC);
        moduleDao.insert(module);
        var moduleService = new PlatformModuleService(moduleDao, event -> {});
        var schemeService = new MenuSchemeService(schemes);
        var setService = new PlatformUiSetService(sets, moduleService);
        var configService = new PlatformUiConfigService(configs, setService);
        var templateService = new PlatformQueryTemplateService(templates, moduleService);
        var fieldService = new PlatformUiConfigFieldService(fields, configService, setService, null, null, null, null);
        var itemService = new PlatformQueryItemService(items, templateService, null, null);
        var menuService = new MenuService(menus, schemeService, moduleService, Optional.of((alias, user) -> true),
                configService, setService, templateService);
        var snapshots = new PlatformPageConfigSnapshotService(setService, configService, fieldService, templateService, itemService);
        var bootstrap = new PlatformPageBootstrapService(menuService, snapshots, mock(ModuleMetadataFieldService.class));

        // Seed persisted published facts directly, including invalid legacy ownership aggregates.
        seedConfiguration("global", null);
        seedConfiguration("local", "tenant-a");
        seedConfiguration("other", "tenant-b");
        PlatformUiConfig mixed = configs.list(Criteria.of().eq("id", "other-config")).getFirst();
        mixed.setId("mixed-config");
        mixed.setUiSetId("global-set");
        mixed.setTenantId("tenant-a");
        mixed.setClientType(PlatformUiClientType.APP);
        configs.insert(mixed);
        PlatformUiConfigField foreignField = field("foreign-field", "global-config", "tenant-b");
        fields.insert(foreignField);
        PlatformQueryItem foreignItem = item("foreign-item", "global-query", "tenant-b");
        foreignItem.setParentId("missing-parent");
        items.insert(foreignItem);
        PlatformUiConfigField mixedLocalField = field("mixed-local-field", "global-config", "tenant-a");
        fields.insert(mixedLocalField);
        var mixedLocalItem = item("mixed-local-item", "global-query", "tenant-a");
        mixedLocalItem.setParentId("missing-parent");
        items.insert(mixedLocalItem);

        String sourceId;
        try (TenantContext.Scope ignored = TenantContext.system("Seed declared system menu")) {
            var scheme = new MenuScheme();
            scheme.setId(MenuSchemeService.ADMIN_SCHEME_ID);
            scheme.setAlias(MenuSchemeService.ADMIN_SCHEME_ALIAS);
            scheme.setTitle("Admin");
            scheme.setScopeType(MenuScopeType.SYSTEM);
            schemeService.insert(scheme);
            Menu source = menu(MenuSchemeService.ADMIN_SCHEME_ID, "global-config", "global-query");
            source.setSystemManaged(true);
            sourceId = PlatformManagedMutationContext.runAsPlatformManaged(() -> menuService.insert(source));
            assertThat(snapshots.snapshot("crm.customer").uiConfigs()).extracting(PlatformUiConfig::getId)
                    .containsExactly("global-config");
        }

        var provisioner = new DefaultTenantMenuProvisioner(schemeService, menuService);
        provisioner.afterTenantCreated("tenant-a");
        provisioner.reconcileTenantAdminMenus("tenant-a");
        String copyId = DefaultTenantMenuProvisioner.tenantMenuId("tenant-a", sourceId);
        try (TenantContext.Scope ignored = TenantContext.system("Manage tenant menu with an explicit scheme")) {
            String schemeId = DefaultTenantMenuProvisioner.tenantAdminSchemeId("tenant-a");
            String ownMenuId = menuService.insert(menu(schemeId, "local-config", "local-query"));
            assertThat(menuService.select(ownMenuId).getTenantId()).isEqualTo("tenant-a");
            Menu forgedOwner = menu(schemeId, "other-config", null);
            forgedOwner.setTenantId("tenant-b");
            assertThatThrownBy(() -> menuService.insert(forgedOwner)).isInstanceOf(PlatformException.class);
            assertThatThrownBy(() -> menuService.insert(menu(MenuSchemeService.ADMIN_SCHEME_ID, "local-config", null)))
                    .isInstanceOf(PlatformException.class);
            assertThat(TenantContext.isSystem()).isTrue();
        }
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a");
             CurrentUserContext.Scope user = CurrentUserContext.use(CurrentUser.tenantUser("u", "user", "tenant-a", null))) {
            Menu copy = menuService.select(copyId);
            assertThat(copy.getDefaultUiConfigId()).isEqualTo("global-config");
            assertThat(copy.getDefaultQueryTemplateId()).isEqualTo("global-query");
            assertThat(configService.select("global-config")).isNull();
            assertThat(templateService.select("global-query")).isNull();
            assertThatThrownBy(() -> configService.requireUiConfig("global-config")).isInstanceOf(PlatformException.class);
            assertThat(configService.requireVisibleUiConfig("local-config").getTenantId()).isEqualTo("tenant-a");

            var snapshot = snapshots.snapshot("crm.customer");
            assertThat(snapshot.uiSets()).extracting(PlatformUiSet::getId).containsExactly("local-set", "global-set");
            assertThat(snapshot.uiConfigs()).extracting(PlatformUiConfig::getId).containsExactly("local-config", "global-config");
            assertThat(snapshot.uiFields()).extracting(PlatformUiConfigField::getId).containsExactly("local-field", "global-field");
            assertThat(snapshot.queryTemplates()).extracting(PlatformQueryTemplate::getId).containsExactly("local-query", "global-query");
            assertThat(snapshot.queryItems()).extracting(PlatformQueryItem::getId).containsExactly("local-item", "global-item");

            // Empty metadata bindings keep this bootstrap focused on the actual global query condition group.
            fields.deleteById("global-field");
            fields.deleteById("local-field");
            var page = bootstrap.bootstrapByMenu(copyId);
            assertThat(page.entry().defaultUiConfigId()).isEqualTo("global-config");
            assertThat(page.entry().defaultQueryTemplateId()).isEqualTo("global-query");
            assertThat(page.resolvedConfig().queryItems()).hasSize(2);
            assertThatThrownBy(() -> itemService.compile("global-query"))
                    .isInstanceOf(PlatformException.class).hasMessageContaining("existing config");
            assertThat(itemService.compileVisiblePublished("global-query", Map.of())).isNotNull();
            assertThatThrownBy(() -> itemService.compileVisiblePublished("other-query", Map.of()))
                    .isInstanceOf(PlatformException.class).hasMessageContaining("existing config");
            var records = mock(DynamicRecordService.class);
            when(records.mainEntityAlias("crm.customer")).thenReturn("main");
            when(records.count(eq("crm.customer"), eq("main"), any(Criteria.class))).thenAnswer(invocation -> {
                assertThat(TenantContext.currentTenantId()).contains("tenant-a");
                return 2L;
            });
            @SuppressWarnings("unchecked")
            ObjectProvider<CrudAbility<?>> abilities = mock(ObjectProvider.class);
            when(abilities.orderedStream()).thenAnswer(invocation -> java.util.stream.Stream.empty());
            var completion = new ModuleCompletionCheckService(records, itemService, templateService,
                    Optional.empty(), abilities, (alias, id) -> Map.of());
            var check = new PlatformTaskCheckBlock(PlatformTaskCheckType.QUERY_TEMPLATE,
                    null, "global-query", null, null, null, 1, null);
            assertThat(completion.check("crm.customer", "record-a", check).actualCount()).isEqualTo(2L);
            assertThat(completion.check("crm.customer", "record-a", check,
                    templateService.requireVisibleQueryTemplate("global-query")).passed()).isTrue();
            assertThat(TenantContext.currentTenantId()).contains("tenant-a");

            String schemeId = copy.getSchemeId();
            Menu forgedOwner = menu(schemeId, "other-config", null);
            forgedOwner.setTenantId("tenant-b");
            assertThatThrownBy(() -> menuService.insert(forgedOwner)).isInstanceOf(PlatformException.class);
            assertThatThrownBy(() -> menuService.insert(menu(schemeId, "other-config", null)))
                    .isInstanceOf(PlatformException.class).hasMessageContaining("existing config");
            assertThatThrownBy(() -> menuService.insert(menu(schemeId, null, "other-query")))
                    .isInstanceOf(PlatformException.class).hasMessageContaining("existing config");
            assertThatThrownBy(() -> menuService.insert(menu(schemeId, "mixed-config", null)))
                    .isInstanceOf(PlatformException.class);

            PlatformUiConfig global = configs.list(Criteria.of().eq("id", "global-config")).getFirst();
            global.setPublished(false);
            configs.updateById(global);
            assertThatThrownBy(() -> menuService.insert(menu(schemeId, "global-config", null)))
                    .isInstanceOf(PlatformException.class).hasMessageContaining("published");
            assertThatThrownBy(() -> bootstrap.bootstrapByMenu(copyId)).isInstanceOf(PlatformException.class);
            global.setPublished(true);
            global.setEnabled(false);
            configs.updateById(global);
            assertThatThrownBy(() -> menuService.insert(menu(schemeId, "global-config", null)))
                    .isInstanceOf(PlatformException.class).hasMessageContaining("enabled");
            PlatformQueryTemplate query = templates.list(Criteria.of().eq("id", "global-query")).getFirst();
            query.setPublished(false);
            templates.updateById(query);
            assertThatThrownBy(() -> itemService.compileVisiblePublished("global-query", Map.of()))
                    .isInstanceOf(PlatformException.class).hasMessageContaining("unavailable");
            assertThatThrownBy(() -> menuService.insert(menu(schemeId, null, "global-query")))
                    .isInstanceOf(PlatformException.class).hasMessageContaining("published");
            query.setPublished(true);
            query.setEnabled(false);
            templates.updateById(query);
            assertThatThrownBy(() -> menuService.insert(menu(schemeId, null, "global-query")))
                    .isInstanceOf(PlatformException.class).hasMessageContaining("enabled");
        }
    }

    private void seedConfiguration(String prefix, String tenantId) {
        PlatformUiSet set = prepared(new PlatformUiSet(), prefix + "-set", tenantId);
        set.setModuleAlias("crm.customer");
        set.setAlias(prefix);
        set.setSetType(PlatformUiSetType.LIST);
        set.setDefaultSet(true);
        sets.insert(set);
        PlatformUiConfig config = prepared(new PlatformUiConfig(), prefix + "-config", tenantId);
        config.setUiSetId(set.getId());
        config.setClientType(PlatformUiClientType.WEB);
        config.setPublished(true);
        configs.insert(config);
        fields.insert(field(prefix + "-field", config.getId(), tenantId));
        PlatformQueryTemplate query = prepared(new PlatformQueryTemplate(), prefix + "-query", tenantId);
        query.setModuleAlias("crm.customer");
        query.setAlias(prefix);
        query.setDefaultTemplate(true);
        query.setPublished(true);
        templates.insert(query);
        items.insert(item(prefix + "-item", query.getId(), tenantId));
    }

    private PlatformUiConfigField field(String id, String configId, String tenantId) {
        PlatformUiConfigField field = prepared(new PlatformUiConfigField(), id, tenantId);
        field.setUiConfigId(configId);
        field.setModuleMetadataFieldId(id);
        return field;
    }

    private PlatformQueryItem item(String id, String templateId, String tenantId) {
        PlatformQueryItem item = prepared(new PlatformQueryItem(), id, tenantId);
        item.setQueryTemplateId(templateId);
        item.setGroupOperator(PlatformQueryGroupOperator.AND);
        item.setParentId(TreeAbility.ROOT_ID);
        return item;
    }

    private <T extends StandardTitledEntity> T prepared(T record, String id, String tenantId) {
        record.setId(id);
        record.setTenantId(tenantId);
        record.setTitle(id);
        EntityLifecycle.prepareInsert(record, Instant.now());
        return record;
    }

    private Menu menu(String schemeId, String configId, String queryId) {
        Menu menu = new Menu();
        menu.setSchemeId(schemeId);
        menu.setTitle("Customers");
        menu.setParentId(TreeAbility.ROOT_ID);
        menu.setOpenMode(MenuOpenMode.TAB);
        menu.setModuleAlias("crm.customer");
        menu.setDefaultUiConfigId(configId);
        menu.setDefaultQueryTemplateId(queryId);
        return menu;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = {MenuDao.class, PlatformUiConfigDao.class})
    static class TestApplication {
        @Bean DataSource dataSource() {
            return DataSourceBuilder.create().url(postgres.getJdbcUrl())
                    .username(postgres.getUsername()).password(postgres.getPassword())
                    .driverClassName(postgres.getDriverClassName()).build();
        }
    }
}
