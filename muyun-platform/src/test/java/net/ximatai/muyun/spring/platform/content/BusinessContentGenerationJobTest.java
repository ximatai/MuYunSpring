package net.ximatai.muyun.spring.platform.content;

import net.ximatai.muyun.spring.platform.job.JobScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BusinessContentGenerationJobTest {
    @Test @SuppressWarnings("unchecked")
    void topicTypesKeepTheOriginalExecutionBudgetAndCannotRotateIdentityThroughAdministrativeRetry() {
        for (var scope : JobScope.values()) {
            var definition = BusinessContentGenerationJob.handler(scope, mock(ObjectProvider.class),
                    mock(BusinessContentGenerationStore.class)).definition();
            assertThat(definition.scope()).isEqualTo(scope);
            assertThat(definition.defaultSchedule()).isNull();
            assertThat(definition.channel()).isEqualTo("content-generation");
            assertThat(definition.leaseDuration()).isEqualTo(Duration.ofMinutes(5));
            assertThat(definition.maximumRuntime()).isEqualTo(Duration.ofMinutes(5));
            assertThat(definition.maximumAttempts()).isEqualTo(3);
            assertThat(definition.backoff(1)).isEqualTo(Duration.ofSeconds(15));
            assertThat(definition.backoff(2)).isEqualTo(Duration.ofSeconds(30));
            assertThat(definition.failureNotification()).isFalse();
            assertThat(definition.manualRetry()).isFalse();
            assertThat(definition.retentionDays()).isEqualTo(30);
        }
    }
}
