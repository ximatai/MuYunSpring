package net.ximatai.muyun.spring.iam.web;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.PlatformPageEntryChild;
import net.ximatai.muyun.spring.platform.web.PlatformStaticWebScope;
import net.ximatai.muyun.spring.web.NestedCrudWebSupport;
import net.ximatai.muyun.spring.web.WebListResponse;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.iam.tenant.Tenant;
import net.ximatai.muyun.spring.iam.tenant.TenantApplication;
import net.ximatai.muyun.spring.iam.tenant.TenantApplicationService;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/** Standard child resource for a tenant's application entitlements. */
@RestController
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@PlatformStaticModule(application = net.ximatai.muyun.spring.iam.application.IamApplication.class,
        alias = TenantApplicationService.MODULE_ALIAS, title = "租户已开通应用")
@PlatformPageEntryChild(parentModuleAlias = TenantService.MODULE_ALIAS)
@RequestMapping("/iam.tenant/{tenantId}/applications")
public class TenantApplicationWebController
        extends NestedCrudWebSupport<TenantApplication, TenantApplicationService> {
    private final TenantService tenantService;

    public TenantApplicationWebController(TenantService tenantService) {
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService must not be null");
    }

    @Override
    protected void appendScope(Criteria criteria, HttpServletRequest request) {
        requireTenant(request);
        criteria.eq("tenantId", tenantId(request));
    }

    @Override
    protected void bindScope(TenantApplication record, HttpServletRequest request) {
        requireTenant(request);
        record.setTenantId(tenantId(request));
    }

    @Override
    protected boolean inScope(TenantApplication record, HttpServletRequest request) {
        requireTenant(request);
        return Objects.equals(record.getTenantId(), tenantId(request));
    }

    @Override
    protected String scopedRecordNotFoundMessage(HttpServletRequest request, String id) {
        return "tenant application does not belong to tenant: " + tenantId(request) + "." + id;
    }

    @PostMapping("/configure")
    @ActionEndpoint(PlatformAction.UPDATE)
    public WebListResponse<String> configure(HttpServletRequest request,
                                             @RequestBody(required = false) TenantApplicationsConfigureRequest requestBody) {
        return webScope(() -> {
            requireTenant(request);
            List<String> aliases = requestBody == null || requestBody.applicationAliases() == null
                    ? List.of() : requestBody.applicationAliases();
            service().configureApplications(tenantId(request), aliases);
            return new WebListResponse<>(service().openedApplicationAliases(tenantId(request)));
        });
    }

    private Tenant requireTenant(HttpServletRequest request) {
        Tenant tenant = tenantService.select(tenantId(request));
        if (tenant == null) {
            throw new IllegalArgumentException("tenant does not exist: " + tenantId(request));
        }
        return tenant;
    }

    private String tenantId(HttpServletRequest request) {
        String tenantId = pathVariable(request, "tenantId");
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        return tenantId;
    }

    public record TenantApplicationsConfigureRequest(List<String> applicationAliases) {
    }
}
