package net.ximatai.muyun.spring.platform.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.i18n.*;
import net.ximatai.muyun.spring.common.option.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class JobDisplayVocabularyTest {
    @Test void runFieldsUseLocalizedOptionProjectionWithoutChangingPersistedCodes() throws Exception {
        var resources = TextResources.load(getClass().getClassLoader(), LanguageCatalog.load(getClass().getClassLoader(), "zh-CN"));
        for (String field : List.of("status", "triggerType", "identityMode", "resultCode")) {
            var binding = JobRun.class.getDeclaredField(field).getAnnotation(OptionField.class);
            assertThat(binding.type()).isEqualTo(OptionSourceType.ENUM);
            for (var value : binding.enumType().getEnumConstants()) {
                var source = TextReference.code("enum/" + binding.enumType().getName() + "/" + value.getCode() + "/title", value.getTitle());
                for (String language : List.of("zh-CN", "en")) {
                    var display = resources.resolve(source, Map.of(), language);
                    assertThat(display.missing()).as(source.sourceId()).isFalse();
                    assertThat(display.text()).isNotBlank().isNotEqualTo(value.getCode());
                }
            }
        }
        var run = new JobRun(); run.setStatus("FAILED"); run.setIdentityMode("INITIATOR");
        run.setTriggerType("MANUAL"); run.setResultCode("IDENTITY_WITHDRAWN");
        var wire = new ObjectMapper().valueToTree(run);
        assertThat(wire.get("status").asText()).isEqualTo("FAILED");
        assertThat(wire.get("resultCode").asText()).isEqualTo("IDENTITY_WITHDRAWN");
        assertThat(JobResultCode.IDENTITY_WITHDRAWN.getTitle()).contains("账号", "授权");
        assertThat(JobResultCode.EXECUTION_FAILED.getTitle()).contains("审计");
    }
}
