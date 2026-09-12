package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.logging.ActionLogStatistics;
import net.ximatai.muyun.spring.ability.logging.BusinessLogQueryContract;
import net.ximatai.muyun.spring.ability.logging.BusinessLogQueryProfile;
import net.ximatai.muyun.spring.ability.logging.BusinessLogPageRequest;
import net.ximatai.muyun.spring.ability.query.QuerySchema;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorIdentityLookup;
import net.ximatai.muyun.spring.ability.logging.BusinessLogReadScope;
import net.ximatai.muyun.spring.ability.logging.BusinessLogReadScopeResolver;
import net.ximatai.muyun.spring.ability.logging.PageAccessLogStatistics;
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

/** Administrative entry point for action and page-access facts. */
@RestController
@ConditionalOnBean(BusinessLogGovernanceService.class)
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class,
        alias = BusinessActivityLogWebController.MODULE_ALIAS, title = "操作日志", route = "/platform/logs/activity")
@PlatformMenu(parent = PlatformMenuGroups.LOG_MANAGEMENT, title = "操作日志", order = 20)
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@RequestMapping("/platform.business_activity_log")
public class BusinessActivityLogWebController extends WebSupport<BusinessLogGovernanceService> {
    public static final String MODULE_ALIAS = "platform.business_activity_log";
    private static final String QUERY_EVENTS = "queryEvents";
    private static final String VIEW_EVENT = "viewEvent";
    private static final String VIEW_ACTION_STATISTICS = "viewActionStatistics";
    private static final String VIEW_PAGE_ACCESS_STATISTICS = "viewPageAccessStatistics";
    private static final BusinessLogQueryContract QUERY_CONTRACT = BusinessLogQueryContract
            .forProfile(BusinessLogQueryProfile.BUSINESS_ACTIVITY);

    private final BusinessLogReadScopeResolver scopeResolver;
    private final ObjectProvider<BusinessLogOperatorIdentityLookup> identityLookup;

    public BusinessActivityLogWebController(BusinessLogGovernanceService service,
                                            BusinessLogReadScopeResolver scopeResolver,
                                            ObjectProvider<BusinessLogOperatorIdentityLookup> identityLookup) {
        this.service = service;
        this.scopeResolver = scopeResolver;
        this.identityLookup = identityLookup;
    }

    @GetMapping("/query/schema")
    @CustomActionEndpoint(value = QUERY_EVENTS, title = "查询业务活动日志",
            level = PlatformActionLevel.LIST, dataAuth = false)
    public QuerySchema querySchema() {
        return webScope(QUERY_CONTRACT::schema);
    }

    @PostMapping("/query")
    @CustomActionEndpoint(value = QUERY_EVENTS, title = "查询业务活动日志",
            level = PlatformActionLevel.LIST, dataAuth = false)
    public WebPageResponse<BusinessLogEventResponse> query(@RequestBody(required = false) WebQueryRequest request) {
        return webScope(() -> BusinessLogWebPageResponses.from(service().queryBusinessActivitiesPage(
                QUERY_CONTRACT.toQuery(WebQueryRequests.from(request), 200), scope(QUERY_EVENTS), page(request)),
                identityLookup.getIfAvailable()));
    }

    @GetMapping("/{eventId}")
    @CustomActionEndpoint(value = VIEW_EVENT, title = "查看业务活动日志详情",
            level = PlatformActionLevel.RECORD, recordIdPathVariable = "eventId", dataAuth = false)
    public ResponseEntity<BusinessLogEventResponse> detail(@PathVariable String eventId) {
        return webScope(() -> ResponseEntity.of(service().findBusinessActivityDetail(eventId, scope(VIEW_EVENT))
                .map(event -> BusinessLogEventResponse.from(event, identityLookup.getIfAvailable()))));
    }

    @PostMapping("/statistics/actions")
    @CustomActionEndpoint(value = VIEW_ACTION_STATISTICS, title = "查看动作统计",
            level = PlatformActionLevel.LIST, dataAuth = false)
    public ActionLogStatistics actionStatistics(@RequestBody(required = false) WebQueryRequest request) {
        return webScope(() -> service().actionStatistics(QUERY_CONTRACT.toStatisticsQuery(
                WebQueryRequests.from(request), 5_000), scope(VIEW_ACTION_STATISTICS)));
    }

    @PostMapping("/statistics/page-access")
    @CustomActionEndpoint(value = VIEW_PAGE_ACCESS_STATISTICS, title = "查看页面访问统计",
            level = PlatformActionLevel.LIST, dataAuth = false)
    public PageAccessLogStatistics pageAccessStatistics(@RequestBody(required = false) WebQueryRequest request) {
        return webScope(() -> service().pageAccessStatistics(QUERY_CONTRACT.toStatisticsQuery(
                WebQueryRequests.from(request), 5_000), scope(VIEW_PAGE_ACCESS_STATISTICS)));
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
