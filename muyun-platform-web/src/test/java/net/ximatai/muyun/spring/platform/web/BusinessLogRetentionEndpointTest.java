package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.logging.BusinessLogEventType;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessLogRetentionEndpointTest {
    @Test
    void shouldRemainAnInternalGovernanceModuleWithoutPublishingAStandaloneMenuRoute() {
        PlatformStaticModule module = BusinessLogRetentionWebController.class.getAnnotation(PlatformStaticModule.class);

        assertThat(module).isNotNull();
        assertThat(module.route()).isEmpty();
        assertThat(BusinessLogRetentionWebController.class.getAnnotation(PlatformMenu.class)).isNull();
    }

    @Test
    void shouldKeepPolicyReadMutationAndDestructiveExecutionAsSeparateAuthorizedActions() throws Exception {
        Method policies = BusinessLogRetentionWebController.class.getMethod("policies");
        Method update = BusinessLogRetentionWebController.class.getMethod("updatePolicy",
                BusinessLogEventType.class, BusinessLogRetentionPolicyRequest.class);
        Method purge = BusinessLogRetentionWebController.class.getMethod("purge", BusinessLogEventType.class,
                BusinessLogRetentionPurgeRequest.class);

        assertThat(policies.getAnnotation(GetMapping.class).value()).containsExactly("/policies");
        assertThat(policies.getAnnotation(CustomActionEndpoint.class).value()).isEqualTo("viewRetentionPolicies");
        assertThat(update.getAnnotation(PostMapping.class).value()).containsExactly("/policies/{eventType}");
        assertThat(update.getAnnotation(CustomActionEndpoint.class).value()).isEqualTo("configureRetentionPolicy");
        assertThat(purge.getAnnotation(PostMapping.class).value())
                .containsExactly("/policies/{eventType}/purge");
        assertThat(purge.getAnnotation(CustomActionEndpoint.class).value()).isEqualTo("purgeExpiredLogs");
    }
}
