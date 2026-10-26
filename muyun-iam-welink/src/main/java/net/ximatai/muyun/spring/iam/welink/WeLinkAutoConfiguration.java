package net.ximatai.muyun.spring.iam.welink;

import net.ximatai.muyun.spring.iam.federation.ConditionalOnExternalIdentityFederation;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Optional vendor plugin, deliberately absent from the standard Starter dependencies. */
@AutoConfiguration
@ConditionalOnExternalIdentityFederation
@ConditionalOnProperty(prefix = "muyun.iam.federation.welink", name = "enabled", havingValue = "true")
public class WeLinkAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    WeLinkClient weLinkClient(
            @org.springframework.beans.factory.annotation.Value("${muyun.iam.federation.welink.allow-http-loopback:false}")
            boolean allowHttpLoopback) { return new WeLinkClient(allowHttpLoopback); }

    @Bean
    @ConditionalOnMissingBean
    WeLinkLoginProtocol weLinkLoginProtocol(WeLinkClient client) { return new WeLinkLoginProtocol(client); }

    @Bean
    @ConditionalOnMissingBean
    WeLinkDirectoryProtocol weLinkDirectoryProtocol(WeLinkClient client) { return new WeLinkDirectoryProtocol(client); }
}
