package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.springframework.stereotype.Service;

/** Privileged configuration diagnostic; it intentionally returns no model content or credential detail. */
@Service
public class AiModelConnectionTester {
    private static final AiTextRequest PROBE = AiTextRequest.userText("Reply with exactly OK.");

    private final AiModelConfigurationService configurationService;
    private final AiModelRouteResolver routeResolver;
    private final AiModelClient client;

    AiModelConnectionTester(AiModelConfigurationService configurationService,
                            AiModelRouteResolver routeResolver,
                            AiModelClient client) {
        this.configurationService = configurationService;
        this.routeResolver = routeResolver;
        this.client = client;
    }

    public AiModelConnectionTestResult test(String configurationId) {
        AiModelConfiguration configuration = configurationService.select(configurationId);
        if (configuration == null) {
            throw new PlatformException("AI model configuration does not exist: " + configurationId);
        }
        return testCandidate(configuration);
    }

    /** Tests a draft without persisting it or exposing its credential. */
    public AiModelConnectionTestResult testDraft(AiModelConfiguration draft) {
        if (draft == null || draft.getProvider() == null || draft.getProvider().isBlank()
                || draft.getModelId() == null || draft.getModelId().isBlank()) {
            throw new PlatformException("AI model provider and model id must be provided before testing");
        }
        String apiKey = draft.getApiKeyInput();
        if ((apiKey == null || apiKey.isBlank()) && draft.getId() != null) {
            AiModelConfiguration stored = configurationService.select(draft.getId());
            apiKey = stored == null ? null : stored.getApiKey();
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new PlatformException("AI model API key must be provided before testing");
        }
        AiModelConfiguration candidate = new AiModelConfiguration();
        candidate.setProvider(draft.getProvider().trim());
        candidate.setModelId(draft.getModelId().trim());
        candidate.setApiKey(apiKey.trim());
        return testCandidate(candidate);
    }

    private AiModelConnectionTestResult testCandidate(AiModelConfiguration configuration) {
        long startedAt = System.nanoTime();
        client.generate(routeResolver.resolveCandidate(configuration), PROBE);
        return new AiModelConnectionTestResult(true, (System.nanoTime() - startedAt) / 1_000_000L);
    }
}
