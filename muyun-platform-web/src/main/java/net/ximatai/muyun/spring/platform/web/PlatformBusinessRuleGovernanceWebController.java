package net.ximatai.muyun.spring.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.metadata.BusinessRuleGovernanceService;
import net.ximatai.muyun.spring.platform.metadata.BusinessRuleGovernanceSnapshot;
import net.ximatai.muyun.spring.platform.metadata.BusinessRulePreview;
import net.ximatai.muyun.spring.platform.metadata.BusinessRulePreviewCommand;
import net.ximatai.muyun.spring.platform.metadata.BusinessRuleTrialCommand;
import net.ximatai.muyun.spring.platform.metadata.BusinessRuleTrialResult;
import net.ximatai.muyun.spring.platform.metadata.BusinessRuleApplyCommand;
import net.ximatai.muyun.spring.platform.metadata.BusinessRuleApplyResult;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFormulaRuleService;
import net.ximatai.muyun.spring.web.SystemScope;
import net.ximatai.muyun.spring.web.WebSupport;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/** HTTP read projection for business-rule governance; mutations are added by the same endpoint family. */
@RestController
@PlatformStaticWebProjection(module = ModuleMetadataFormulaRuleService.MODULE_ALIAS)
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@RequestMapping("/platform.module/{moduleAlias}/business-rules")
public class PlatformBusinessRuleGovernanceWebController extends WebSupport<BusinessRuleGovernanceService>
        implements SystemScope<BusinessRuleGovernanceService> {
    public PlatformBusinessRuleGovernanceWebController(BusinessRuleGovernanceService service) {
        this.service = service;
    }

    @GetMapping
    @CustomActionEndpoint(value = "viewBusinessRules", title = "查看业务规则", level = PlatformActionLevel.LIST, dataAuth = false)
    public BusinessRuleGovernanceSnapshot snapshot(HttpServletRequest request) {
        return webScope(() -> service().snapshot(moduleAlias(request)));
    }

    @PostMapping("/preview")
    @CustomActionEndpoint(value = "previewBusinessRules", title = "预检业务规则", level = PlatformActionLevel.LIST, dataAuth = false)
    public BusinessRulePreview preview(HttpServletRequest request, @RequestBody BusinessRulePreviewCommand command) {
        return webScope(() -> service().preview(moduleAlias(request), command));
    }

    @PostMapping("/trial")
    @CustomActionEndpoint(value = "trialBusinessRules", title = "试算业务规则", level = PlatformActionLevel.LIST, dataAuth = false)
    public BusinessRuleTrialResult trial(HttpServletRequest request, @RequestBody BusinessRuleTrialCommand command) {
        String requestTenantId = TenantContext.currentTenantId().orElse(null);
        return webScope(() -> service().trial(moduleAlias(request), command, requestTenantId));
    }

    @PostMapping("/apply")
    @CustomActionEndpoint(value = "applyBusinessRules", title = "应用业务规则", level = PlatformActionLevel.LIST, dataAuth = false)
    public BusinessRuleApplyResult apply(HttpServletRequest request, @RequestBody BusinessRuleApplyCommand command) {
        return webScope(() -> service().apply(moduleAlias(request), command));
    }

    @SuppressWarnings("unchecked")
    private String moduleAlias(HttpServletRequest request) {
        Object attributes = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String value = attributes instanceof Map<?, ?> values ? (String) values.get("moduleAlias") : null;
        return PlatformNameRules.requireModuleAlias(value);
    }
}
