package net.ximatai.muyun.spring.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.currency.TenantCurrencySetting;
import net.ximatai.muyun.spring.platform.currency.TenantCurrencySettingService;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.web.NestedCrudWebSupport;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.function.Supplier;

@RestController
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@StaticModuleTenantScopePolicy
@PlatformMenu(parent = PlatformMenuGroups.SETTINGS, title = "租户币种设置", order = 40)
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class, alias = TenantCurrencySettingService.MODULE_ALIAS,
        title = "租户币种设置")
@RequestMapping({"/platform.tenant_currency_setting", "/platform.tenant-currency-settings"})
public class TenantCurrencySettingWebController
        extends NestedCrudWebSupport<TenantCurrencySetting, TenantCurrencySettingService>
        implements StaticModuleUiContributor {

    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(TenantCurrencySettingService.MODULE_ALIAS)
                .page(PageTemplates.flatManagement(page -> page
                        .explorer(explorer -> explorer.title("租户币种设置").titleField("baseCurrencyCode"))
                        .detail(detail -> detail
                                .emptyDescription("当前租户尚未设置本位币。")
                                .editor(form -> form.title("租户币种设置")
                                        .group("base_currency", "本位币", "请填写已启用的币种编码，例如 CNY、USD。", group -> group
                                                .field("baseCurrencyCode", field -> field.label("本位币编码").required())))
                                .showSystemInfo(false))
                        .traits(traits -> traits.operations(operations -> operations.standardCrud()))))
                .build();
    }

    @Override
    public <T> T webScope(Supplier<T> action) {
        return action.get();
    }

    @Override
    protected void appendScope(Criteria criteria, HttpServletRequest request) {
        criteria.eq("tenantId", TenantContext.currentTenantId()
                .orElseThrow(() -> new IllegalArgumentException("tenant currency setting requires tenant context")));
    }

    @Override
    protected void bindScope(TenantCurrencySetting record, HttpServletRequest request) {
    }

    @Override
    protected boolean inScope(TenantCurrencySetting record, HttpServletRequest request) {
        return Objects.equals(record.getTenantId(), TenantContext.currentTenantId().orElse(null));
    }

    @Override
    protected String scopedRecordNotFoundMessage(HttpServletRequest request, String id) {
        return "tenant currency setting not found: " + id;
    }
}
