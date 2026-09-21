package net.ximatai.muyun.spring.starter.configuration.iam;

import net.ximatai.muyun.spring.common.identity.CurrentUserProvider;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import net.ximatai.muyun.spring.iam.user.UserSessionService;
import net.ximatai.muyun.spring.iam.web.security.BearerTokenCurrentUserProvider;
import net.ximatai.muyun.spring.iam.web.security.BusinessRequestTenantVerifier;
import net.ximatai.muyun.spring.web.CurrentUserWebFilter;
import net.ximatai.muyun.spring.web.RequestTenantVerifier;
import net.ximatai.muyun.spring.web.RequestTraceWebFilter;
import net.ximatai.muyun.spring.web.RequestErrorLogRecorder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

/**
 * IAM 的 HTTP 上下文装配：将请求会话解析为当前用户，并补齐请求追踪信息。
 * 它只处理 Web 边界，不承载用户、会话或权限领域规则。
 */
@Configuration(proxyBeanMethods = false)
public class MuYunSpringIdentityWebConfiguration {
    @Bean
    @ConditionalOnMissingBean(CurrentUserProvider.class)
    /** 从可选会话服务构造当前用户来源；无会话能力时保持匿名上下文。 */
    CurrentUserProvider currentUserProvider(ObjectProvider<UserSessionService> userSessionService) {
        UserSessionService service = userSessionService.getIfAvailable();
        return service == null ? Optional::empty : new BearerTokenCurrentUserProvider(service);
    }

    @Bean
    @ConditionalOnMissingBean(RequestTenantVerifier.class)
    /**
     * 统一发布业务租户校验契约，供默认过滤器和业务自定义过滤器共同复用。
     * 依赖保持延迟解析，避免 Starter 配置解析早于平台组件扫描时误判为缺失。
     */
    RequestTenantVerifier requestTenantVerifier(ObjectProvider<TenantService> tenantService,
            ObjectProvider<ActionExecutionPolicyService> actionExecutionPolicyService) {
        return tenantId -> {
            TenantService tenants = tenantService.getIfAvailable();
            ActionExecutionPolicyService policies = actionExecutionPolicyService.getIfAvailable();
            if (tenants == null || policies == null) {
                throw new IllegalArgumentException("business tenant verification is unavailable");
            }
            new BusinessRequestTenantVerifier(tenants, policies).verify(tenantId);
        };
    }

    @Bean
    @ConditionalOnMissingBean(CurrentUserWebFilter.class)
    /** 在请求进入业务端点前绑定当前用户，离开请求后负责清理上下文。 */
    CurrentUserWebFilter currentUserWebFilter(CurrentUserProvider currentUserProvider,
            RequestTenantVerifier requestTenantVerifier) {
        return new CurrentUserWebFilter(currentUserProvider, requestTenantVerifier);
    }

    @Bean
    @ConditionalOnMissingBean(RequestTraceWebFilter.class)
    /** 为每个请求建立可贯穿日志、异常与审计的追踪标识。 */
    RequestTraceWebFilter requestTraceWebFilter(ObjectProvider<RequestErrorLogRecorder> recorder) {
        return new RequestTraceWebFilter(recorder.getIfAvailable(RequestErrorLogRecorder::noop));
    }
}
