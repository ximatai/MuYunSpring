package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.ability.logging.BusinessLogQueryContract;
import net.ximatai.muyun.spring.ability.logging.BusinessLogQueryProfile;
import net.ximatai.muyun.spring.ability.logging.BusinessLogPageRequest;
import net.ximatai.muyun.spring.ability.query.QuerySchema;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorIdentityLookup;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.iam.logging.LoginAuditGovernanceService;
import net.ximatai.muyun.spring.platform.logging.BusinessLogGovernanceService;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.BusinessLogEventResponse;
import net.ximatai.muyun.spring.platform.web.BusinessLogWebPageResponses;
import net.ximatai.muyun.spring.platform.web.PlatformMenu;
import net.ximatai.muyun.spring.platform.web.PlatformMenuGroups;
import net.ximatai.muyun.spring.platform.web.PlatformStaticWebScope;
import net.ximatai.muyun.spring.web.WebSupport;
import net.ximatai.muyun.spring.web.WebPageRequest;
import net.ximatai.muyun.spring.web.WebPageResponse;
import net.ximatai.muyun.spring.web.WebQueryRequest;
import net.ximatai.muyun.spring.web.query.WebQueryRequests;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administrative entry point for authentication attempts. */
@RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.iam.application.IamApplication.class,
        alias = LoginAuditGovernanceService.MODULE_ALIAS, title = "登录日志", route = "/iam/logs/login")
@PlatformMenu(parent = PlatformMenuGroups.LOG_MANAGEMENT, title = "登录日志", order = 10)
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@RequestMapping("/iam.login_audit_log")
public class LoginAuditLogWebController extends WebSupport<LoginAuditGovernanceService> {
    private static final BusinessLogQueryContract QUERY_CONTRACT = BusinessLogQueryContract
            .forProfile(BusinessLogQueryProfile.LOGIN_AUDIT);

    private final ObjectProvider<BusinessLogOperatorIdentityLookup> identityLookup;

    public LoginAuditLogWebController(LoginAuditGovernanceService service,
                                       ObjectProvider<BusinessLogOperatorIdentityLookup> identityLookup) {
        this.service = service;
        this.identityLookup = identityLookup;
    }

    @GetMapping("/query/schema")
    @CustomActionEndpoint(value = LoginAuditGovernanceService.QUERY_ACTION_CODE, title = "查询登录审计",
            level = PlatformActionLevel.LIST, dataAuth = false)
    public QuerySchema querySchema() {
        return webScope(QUERY_CONTRACT::schema);
    }

    @PostMapping("/query")
    @CustomActionEndpoint(value = LoginAuditGovernanceService.QUERY_ACTION_CODE, title = "查询登录审计",
            level = PlatformActionLevel.LIST, dataAuth = false)
    public WebPageResponse<BusinessLogEventResponse> query(@RequestBody(required = false) WebQueryRequest request) {
        return webScope(() -> BusinessLogWebPageResponses.from(service().queryPage(
                QUERY_CONTRACT.toQuery(WebQueryRequests.from(request), 200), page(request)),
                identityLookup.getIfAvailable()));
    }

    private BusinessLogPageRequest page(WebQueryRequest request) {
        if (request != null && request.unpagedEnabled()) {
            throw new IllegalArgumentException("business-log query must use standard paging");
        }
        WebPageRequest page = request == null ? WebPageRequest.DEFAULT : request.pageOrDefault();
        return new BusinessLogPageRequest(page.pageNum(), page.pageSize());
    }

    @GetMapping("/{eventId}")
    @CustomActionEndpoint(value = LoginAuditGovernanceService.DETAIL_ACTION_CODE, title = "查看登录审计详情",
            level = PlatformActionLevel.RECORD, recordIdPathVariable = "eventId", dataAuth = false)
    public ResponseEntity<BusinessLogEventResponse> detail(@PathVariable String eventId) {
        return webScope(() -> ResponseEntity.of(service().findDetail(eventId).map(event -> BusinessLogEventResponse.from(event, identityLookup.getIfAvailable()))));
    }
}
