package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.logging.BusinessLogQueryContract;
import net.ximatai.muyun.spring.ability.logging.BusinessLogQueryProfile;
import net.ximatai.muyun.spring.ability.logging.BusinessLogPageRequest;
import net.ximatai.muyun.spring.ability.query.QuerySchema;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorIdentityLookup;
import net.ximatai.muyun.spring.ability.logging.BusinessLogReadScope;
import net.ximatai.muyun.spring.ability.logging.BusinessLogReadScopeResolver;
import net.ximatai.muyun.spring.ability.logging.RequestErrorLogDetails;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.platform.logging.BusinessLogGovernanceService;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.web.WebSupport;
import net.ximatai.muyun.spring.web.WebPageRequest;
import net.ximatai.muyun.spring.web.WebPageResponse;
import net.ximatai.muyun.spring.web.WebQueryRequest;
import net.ximatai.muyun.spring.web.query.WebQueryRequests;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administrative entry point for request-error facts and platform-only diagnostics. */
@RestController
@ConditionalOnBean(BusinessLogGovernanceService.class)
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class,
        alias = RequestErrorLogWebController.MODULE_ALIAS, title = "异常日志", route = "/platform/logs/errors")
@PlatformMenu(parent = PlatformMenuGroups.LOG_MANAGEMENT, title = "异常日志", order = 30)
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@RequestMapping("/platform.request_error_log")
public class RequestErrorLogWebController extends WebSupport<BusinessLogGovernanceService> {
    public static final String MODULE_ALIAS = "platform.request_error_log";
    private static final String QUERY_EVENTS = "queryEvents";
    private static final String VIEW_EVENT = "viewEvent";
    private static final String VIEW_INTERNAL_DIAGNOSTIC = "viewInternalDiagnostic";
    private static final BusinessLogQueryContract QUERY_CONTRACT = BusinessLogQueryContract
            .forProfile(BusinessLogQueryProfile.REQUEST_ERROR);

    private final BusinessLogReadScopeResolver scopeResolver;
    private final ObjectProvider<BusinessLogOperatorIdentityLookup> identityLookup;

    public RequestErrorLogWebController(BusinessLogGovernanceService service,
                                        BusinessLogReadScopeResolver scopeResolver,
                                        ObjectProvider<BusinessLogOperatorIdentityLookup> identityLookup) {
        this.service = service;
        this.scopeResolver = scopeResolver;
        this.identityLookup = identityLookup;
    }

    @GetMapping("/query/schema")
    @CustomActionEndpoint(value = QUERY_EVENTS, title = "查询接口异常日志",
            level = PlatformActionLevel.LIST, dataAuth = false)
    public QuerySchema querySchema() {
        return webScope(QUERY_CONTRACT::schema);
    }

    @PostMapping("/query")
    @CustomActionEndpoint(value = QUERY_EVENTS, title = "查询接口异常日志",
            level = PlatformActionLevel.LIST, dataAuth = false)
    public WebPageResponse<BusinessLogEventResponse> query(@RequestBody(required = false) WebQueryRequest request) {
        return webScope(() -> BusinessLogWebPageResponses.from(service().queryRequestErrorsPage(
                QUERY_CONTRACT.toQuery(WebQueryRequests.from(request), 200), scope(QUERY_EVENTS), page(request)),
                identityLookup.getIfAvailable()));
    }

    @GetMapping("/{eventId}")
    @CustomActionEndpoint(value = VIEW_EVENT, title = "查看接口异常日志详情",
            level = PlatformActionLevel.RECORD, recordIdPathVariable = "eventId", dataAuth = false)
    public ResponseEntity<BusinessLogEventResponse> detail(@PathVariable String eventId) {
        return webScope(() -> ResponseEntity.of(service().findRequestErrorDetail(eventId, scope(VIEW_EVENT))
                .map(event -> BusinessLogEventResponse.from(event, identityLookup.getIfAvailable()))));
    }

    @GetMapping("/{eventId}/diagnostic")
    @CustomActionEndpoint(value = VIEW_INTERNAL_DIAGNOSTIC, title = "查看接口异常内部诊断",
            level = PlatformActionLevel.RECORD, recordIdPathVariable = "eventId", dataAuth = false)
    public ResponseEntity<RequestErrorLogDetails> internalDiagnostic(@PathVariable String eventId) {
        return webScope(() -> ResponseEntity.of(service().findRequestErrorDiagnostic(eventId,
                scope(VIEW_INTERNAL_DIAGNOSTIC))));
    }

    private BusinessLogPageRequest page(WebQueryRequest request) {
        if (request != null && request.unpagedEnabled()) {
            throw new IllegalArgumentException("business-log query must use standard paging");
        }
        WebPageRequest page = request == null ? WebPageRequest.DEFAULT : request.pageOrDefault();
        return new BusinessLogPageRequest(page.pageNum(), page.pageSize());
    }

    private BusinessLogReadScope scope(String actionCode) {
        return scopeResolver.resolve(MODULE_ALIAS, actionCode);
    }
}
