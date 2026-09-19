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

        ResolvedAiModelRoute route = new ResolvedAiModelRoute(AiModelProviderService.LM_STUDIO_ID,
                AiModelProtocol.OPENAI_COMPATIBLE, "http://127.0.0.1:1234/v1", modelId, apiKey);

        AiTextResponse response = new OpenAiCompatibleModelClient(new ObjectMapper())
                .generate(route, AiTextRequest.userText("Reply with exactly OK."));

        assertThat(response.text().trim()).isEqualTo("OK");
    }
}
