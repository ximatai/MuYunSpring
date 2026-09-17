package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.platform.ai.AiModelConfiguration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiModelConfigurationJsonTest {
    @Test
    void acceptsWriteOnlyKeyButNeverSerializesCredentialFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AiModelConfiguration input = mapper.readValue("""
                {"provider":"LM_STUDIO","modelId":"local-model","apiKeyInput":"model-secret"}
                """, AiModelConfiguration.class);
        input.setApiKey("plain-never-returned");
        input.setApiKeySignature("signature-never-returned");
        input.setApiKeyConfigured(Boolean.TRUE);

        String output = mapper.writeValueAsString(input);

        assertThat(input.getApiKeyInput()).isEqualTo("model-secret");
        assertThat(output).contains("\"apiKeyConfigured\":true")
                .doesNotContain("apiKeyInput", "plain-never-returned", "signature-never-returned", "model-secret");
    }
}
