package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.springframework.stereotype.Service;

/** Privileged configuration diagnostic; it intentionally returns no model content or credential detail. */
@Service
public class AiModelConnectionTester {
    private static final AiTextRequest PROBE = AiTextRequest.userText("Reply with exactly OK.");

    private final AiModelConfigurationService configurationService;
    private final AiModelClient client;

    public AiModelConnectionTester(AiModelConfigurationService configurationService, AiModelClient client) {
        this.configurationService = configurationService;
        this.client = client;
    }

    public AiModelConnectionTestResult test(String configurationId) {
        AiModelConfiguration configuration = configurationService.select(configurationId);
        if (configuration == null) {
            throw new PlatformException("AI model configuration does not exist: " + configurationId);
        }
        long startedAt = System.nanoTime();
        client.generate(configuration, PROBE);
        return new AiModelConnectionTestResult(true, (System.nanoTime() - startedAt) / 1_000_000L);
    }
}
