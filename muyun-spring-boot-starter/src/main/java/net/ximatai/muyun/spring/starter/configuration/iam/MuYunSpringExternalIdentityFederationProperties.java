package net.ximatai.muyun.spring.starter.configuration.iam;

import net.ximatai.muyun.spring.iam.federation.ExternalIdentityFederationSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * 外部身份联邦配置。{@code enabled} 为 false 时联邦服务、入口与管理模块均不装配；
 * 开启后必须配置 API 与工作台的对外地址，模拟身份源只允许在开发运行模式开启。
 */
@ConfigurationProperties("muyun.iam.federation")
public class MuYunSpringExternalIdentityFederationProperties implements ExternalIdentityFederationSettings {
    private boolean enabled;
    private URI apiBaseUrl;
    private URI workbenchUrl;
    private Duration transactionTtl = DEFAULT_TRANSACTION_TTL;
    private final Mock mock = new Mock();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public URI getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(URI apiBaseUrl) {
        this.apiBaseUrl = requireHttpUrl(apiBaseUrl, "muyun.iam.federation.api-base-url");
    }

    public URI getWorkbenchUrl() {
        return workbenchUrl;
    }

    public void setWorkbenchUrl(URI workbenchUrl) {
        this.workbenchUrl = requireHttpUrl(workbenchUrl, "muyun.iam.federation.workbench-url");
    }

    public Duration getTransactionTtl() {
        return transactionTtl;
    }

    public void setTransactionTtl(Duration transactionTtl) {
        if (transactionTtl == null || transactionTtl.isNegative() || transactionTtl.isZero()
                || transactionTtl.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("muyun.iam.federation.transaction-ttl must be within (0, 10m]");
        }
        this.transactionTtl = transactionTtl;
    }

    public Mock getMock() {
        return mock;
    }

    @Override
    public URI apiBaseUrl() {
        return apiBaseUrl;
    }

    @Override
    public URI workbenchUrl() {
        return workbenchUrl;
    }

    @Override
    public Duration transactionTtl() {
        return transactionTtl;
    }

    private static URI requireHttpUrl(URI value, String name) {
        // 浏览器会被重定向到这些地址，只接受绝对 http(s) 地址，避免开放跳转到任意协议。
        if (value == null || !value.isAbsolute() || value.getHost() == null
                || !("https".equals(value.getScheme()) || "http".equals(value.getScheme()))
                || value.getRawQuery() != null || value.getRawFragment() != null) {
            throw new IllegalArgumentException(name + " must be an absolute http(s) URL without query or fragment");
        }
        return value;
    }

    /** 由模拟身份源的装配条件直接读取 {@code muyun.iam.federation.mock.enabled}。 */
    public static class Mock {
        private boolean enabled;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
