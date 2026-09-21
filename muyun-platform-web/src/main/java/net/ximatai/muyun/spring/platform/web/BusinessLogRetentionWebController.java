package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.logging.BusinessLogEventType;
import net.ximatai.muyun.spring.ability.logging.BusinessLogReadScopeResolver;
import net.ximatai.muyun.spring.ability.logging.BusinessLogRetentionPolicy;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.platform.application.PlatformApplication;
import net.ximatai.muyun.spring.platform.logging.BusinessLogRetentionExecutionLimits;
import net.ximatai.muyun.spring.platform.logging.BusinessLogRetentionRunResult;
import net.ximatai.muyun.spring.platform.logging.BusinessLogRetentionService;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.web.WebSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Platform-only runtime governance for persisted business-log retention policies. */
@RestController
@ConditionalOnBean({BusinessLogRetentionService.class, BusinessLogRetentionExecutionLimits.class})
@PlatformStaticModule(application = PlatformApplication.class,
        alias = BusinessLogRetentionWebController.MODULE_ALIAS, title = "日志留存")
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@RequestMapping("/platform.business_log_retention")
public class BusinessLogRetentionWebController extends WebSupport<BusinessLogRetentionService> {
    public static final String MODULE_ALIAS = "platform.business_log_retention";
    public static final String VIEW_POLICIES = "viewRetentionPolicies";
    public static final String CONFIGURE_POLICY = "configureRetentionPolicy";
    public static final String PURGE_EXPIRED = "purgeExpiredLogs";

    private final BusinessLogReadScopeResolver scopeResolver;
    private final BusinessLogRetentionExecutionLimits executionLimits;

    public BusinessLogRetentionWebController(BusinessLogRetentionService service,
                                             BusinessLogReadScopeResolver scopeResolver,
                                             BusinessLogRetentionExecutionLimits executionLimits) {
        this.service = service;
        this.scopeResolver = scopeResolver;
        this.executionLimits = executionLimits;
    }

    @GetMapping("/policies")
    @CustomActionEndpoint(value = VIEW_POLICIES, title = "查看日志留存策略",
            level = PlatformActionLevel.LIST, dataAuth = false)
    public List<BusinessLogRetentionPolicy> policies() {
        return webScope(() -> service().policies(scopeResolver.resolve(MODULE_ALIAS, VIEW_POLICIES)));
    }

    @PostMapping("/policies/{eventType}")
    @CustomActionEndpoint(value = CONFIGURE_POLICY, title = "配置日志留存策略",
            level = PlatformActionLevel.RECORD, recordIdPathVariable = "eventType", dataAuth = false)
    public BusinessLogRetentionPolicy updatePolicy(@PathVariable BusinessLogEventType eventType,
                                                   @RequestBody BusinessLogRetentionPolicyRequest request) {
        return webScope(() -> service().updatePolicy(eventType, request.automaticCleanupEnabled(),
                request.retentionDays(), request.version(), currentUserId(),
                scopeResolver.resolve(MODULE_ALIAS, CONFIGURE_POLICY)));
    }

    @PostMapping("/policies/{eventType}/purge")
    @CustomActionEndpoint(value = PURGE_EXPIRED, title = "立即清理超期日志",
            level = PlatformActionLevel.RECORD, recordIdPathVariable = "eventType", dataAuth = false)
    public BusinessLogRetentionRunResult purge(@PathVariable BusinessLogEventType eventType,
                                               @RequestBody BusinessLogRetentionPurgeRequest request) {
        return webScope(() -> service().purgeNow(eventType, request.retentionDays(), executionLimits,
                scopeResolver.resolve(MODULE_ALIAS, PURGE_EXPIRED)));
    }

    private static String currentUserId() {
        return CurrentUserContext.currentUser().map(user -> user.userId()).orElse(null);
    }
}
