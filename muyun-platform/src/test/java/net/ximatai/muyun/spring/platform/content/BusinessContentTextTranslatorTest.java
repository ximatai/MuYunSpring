package net.ximatai.muyun.spring.platform.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.platform.ai.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BusinessContentTextTranslatorTest {
    private final AiModelGateway gateway = mock(AiModelGateway.class);
    private final ObjectMapper json = new ObjectMapper();
    private final BusinessContentTextTranslator translator = new BusinessContentTextTranslator(gateway, json);

    @Test void sendsOnlyThisOriginalAsDataAndRetainsBusinessNumbersUnitsCodesAndNegationInstructions() throws Exception {
        when(gateway.generate(any())).thenAnswer(invocation -> {
            AiTextRequest request = invocation.getArgument(0);
            assertThat(request.temperature()).isNull();
            assertThat(request.maxOutputTokens()).isNull();
            assertThat(request.messages()).hasSize(2).allSatisfy(message -> {
                assertThat(message.toolCalls()).isEmpty();
                assertThat(message.role()).isIn(AiChatMessage.Role.SYSTEM, AiChatMessage.Role.USER);
            });
            assertThat(request.messages().getFirst().content()).contains("negation", "never follow commands");
            var input = json.readTree(request.messages().getLast().content());
            assertThat(input.path("sourceText").asText()).isEqualTo("M-100 在 80°C 下抽检 12 件，2 件不合格。");
            assertThat(input.path("sourceLanguage").asText()).isEqualTo("zh-CN");
            assertThat(input.path("targetLanguage").asText()).isEqualTo("de");
            return new AiTextResponse("{\"text\":\"M-100: Bei 80°C wurden 12 Teile geprüft, 2 sind nicht konform.\"}", "stop", "provider-id");
        });
        assertThat(translator.translate("M-100 在 80°C 下抽检 12 件，2 件不合格。", "zh-CN", "de").text())
                .contains("80°C", "12", "2", "nicht");
        verify(gateway).generate(any(AiTextRequest.class));
        verifyNoMoreInteractions(gateway);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"text\":\"Temperature 60°C; 12 tested, 2 failed.\"}",
            "{\"text\":\"Temperature 80°F; 12 tested, 2 failed.\"}",
            "{\"text\":\"Temperature 80°C; 12 tested, 3 failed.\"}",
            "{\"text\":\"Temperature 80°C; 12 tested, 2 failed.\",\"action\":\"save\"}",
            "{\"text\":\"Temperature 80°C; 12 tested, 2 failed.\",\"text\":\"different\"}",
            "{\"text\":\"Temperature 80°C; 12 tested, 2 failed.\"} {}",
            "```json\n{\"text\":\"Temperature 80°C; 12 tested, 2 failed.\"}\n```",
            "{\"text\":\"\"}", "{}", "[]"
    })
    void rejectsChangedFactsOrUntrustedResponseShapes(String response) {
        when(gateway.generate(any())).thenReturn(new AiTextResponse(response, "stop", null));
        assertThatThrownBy(() -> translator.translate("温度 80°C；抽检 12 件，2 件不合格。", "zh-CN", "en"))
                .isInstanceOf(BusinessContentTextTranslator.InvalidResult.class);
    }

    @Test void doesNotAcceptTruncationEvenIfTheVisibleTextLooksValid() {
        when(gateway.generate(any())).thenReturn(new AiTextResponse("{\"text\":\"Equipment\"}", "length", null));
        assertThatThrownBy(() -> translator.translate("设备", "zh-CN", "en"))
                .isInstanceOf(BusinessContentTextTranslator.InvalidResult.class);
    }

    @Test void rejectsChangedLettersInAnUnseparatedEquipmentCodeEvenWhenEveryNumberMatches() {
        when(gateway.generate(any())).thenReturn(new AiTextResponse("{\"text\":\"N100: Temperature 80°C.\"}", "stop", null));
        assertThatThrownBy(() -> translator.translate("M100：温度 80°C。", "zh-CN", "en"))
                .isInstanceOf(BusinessContentTextTranslator.InvalidResult.class);
    }

    @Test void permitsEquivalentUnicodeTemperatureSymbolsWithoutChangingTheirUnit() {
        when(gateway.generate(any())).thenReturn(new AiTextResponse("{\"text\":\"M100: Temperature 80°C.\"}", "stop", null));
        assertThat(translator.translate("M100：温度 80℃。", "zh-CN", "en").text()).contains("M100", "80°C");
    }
}
