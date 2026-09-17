package net.ximatai.muyun.spring.platform.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in real-provider verification. It is deliberately excluded from normal CI and never logs its credential.
 */
@EnabledIfSystemProperty(named = "lmstudio.e2e", matches = "true")
class LmStudioOpenAiCompatibleModelE2ETest {
    @Test
    void shouldGenerateTextThroughTheLocalOpenAiCompatibleEndpoint() {
        String apiKey = System.getenv("LM_STUDIO_KEY");
        String modelId = System.getProperty("lmstudio.model-id");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "LM_STUDIO_KEY must be configured");
        Assumptions.assumeTrue(modelId != null && !modelId.isBlank(), "lmstudio.model-id must be configured");

        AiModelConfiguration configuration = new AiModelConfiguration();
        configuration.setProvider(AiModelProvider.LM_STUDIO);
        configuration.setProtocol(AiModelProtocol.OPENAI_COMPATIBLE);
        configuration.setModelId(modelId);
        configuration.setApiKey(apiKey);

        AiTextResponse response = new OpenAiCompatibleModelClient(new ObjectMapper(),
                ignored -> AiModelProvider.LM_STUDIO.baseUrl())
                .generate(configuration, AiTextRequest.userText("Reply with exactly OK."));

        assertThat(response.text().trim()).isEqualTo("OK");
    }
}
