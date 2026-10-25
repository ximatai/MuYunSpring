package net.ximatai.muyun.spring.platform.content;

import net.ximatai.muyun.spring.platform.job.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;
import java.util.Map;

/** Topic facts and original identity remain in content; the platform owns execution. */
@Configuration(proxyBeanMethods = false)
public class BusinessContentGenerationJob {
    public static final String CHANNEL = "content-generation";
    static String type(String tenant) {
        return tenant == null ? "platform.content-generation-platform" : "platform.content-generation";
    }

    @Bean JobHandler contentGenerationJob(ObjectProvider<BusinessContentGenerationService> service,
                                         BusinessContentGenerationStore store) {
        return handler(JobScope.TENANT, service, store);
    }
    @Bean JobHandler platformContentGenerationJob(ObjectProvider<BusinessContentGenerationService> service,
                                                 BusinessContentGenerationStore store) {
        return handler(JobScope.PLATFORM, service, store);
    }
    static JobHandler handler(JobScope scope, ObjectProvider<BusinessContentGenerationService> service,
                              BusinessContentGenerationStore store) {
        return new JobHandler() {
            public JobDefinition definition() {
                return new JobDefinition(type(scope == JobScope.PLATFORM ? null : "tenant"),
                        "platform.job.type.contentGeneration", scope, null, CHANNEL, Duration.ofMinutes(5),
                        Duration.ofMinutes(5), 3, Duration.ofSeconds(15), input -> (String) input.get("slot"),
                        false, 3, false, 30);
            }
            public void lockBusiness(Map<String, Object> input) { store.lock((String) input.get("generationId")); }
            public void stateChanged(JobRun run) { store.stateChanged(run); }
            public JobResult execute(JobContext context) { return service.getObject().execute(context); }
        };
    }
}
