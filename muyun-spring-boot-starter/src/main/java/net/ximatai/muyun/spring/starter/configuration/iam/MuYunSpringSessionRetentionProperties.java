package net.ximatai.muyun.spring.starter.configuration.iam;

import net.ximatai.muyun.spring.iam.user.UserSessionRetentionPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("muyun.iam.session.retention")
public record MuYunSpringSessionRetentionProperties(
        @DefaultValue("30") int retentionDays,
        @DefaultValue("200") int batchSize,
        @DefaultValue("10") int maxBatches) {
    public UserSessionRetentionPolicy policy() {
        return new UserSessionRetentionPolicy(retentionDays, batchSize, maxBatches);
    }
}
