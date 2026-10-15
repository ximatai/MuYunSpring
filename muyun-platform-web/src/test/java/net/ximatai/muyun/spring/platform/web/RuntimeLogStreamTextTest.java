package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.i18n.LanguageCatalog;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.i18n.TextResources;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class RuntimeLogStreamTextTest {
    @Test
    void declaresTheActualStreamFailureWithoutExposingReadDiagnostics() throws Exception {
        var logs = mock(RuntimeLogFileService.class);
        when(logs.openActiveStream(anyInt())).thenThrow(new IllegalStateException("private file diagnostic"));
        var controller = new RuntimeLogWebController(logs);
        var mapper = new ObjectMapper();
        var mvc = standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
        try {
            var initial = mvc.perform(post("/platform.runtime_log/active/stream")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"tailLines\":500}")).andReturn();
            initial.getAsyncResult(2_000);
            String events = mvc.perform(asyncDispatch(initial)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(events).contains("event:error").doesNotContain("private file diagnostic");
            var payload = mapper.readTree(events.lines().filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring(5)).findFirst().orElseThrow());
            assertThat(payload.path("code").asText()).isEqualTo("RUNTIME_LOG_STREAM_INTERRUPTED");
            assertThat(payload.path("message").asText()).isEqualTo("程序日志流已中断");
            var source = mapper.treeToValue(payload.path("messageSource"), TextReference.class);
            assertThat(source).isEqualTo(TextReference.code("platform.runtime-log.stream-interrupted", "程序日志流已中断"));
            var catalog = LanguageCatalog.load(getClass().getClassLoader(), "zh-CN");
            var resolved = TextResources.load(getClass().getClassLoader(), catalog).resolve(source, Map.of(), "en");
            assertThat(resolved.text()).isEqualTo("The runtime log stream was interrupted.");
            assertThat(resolved.missing()).isFalse();
            assertThat(controller.availableStreamSlots()).isEqualTo(4);
        } finally {
            controller.closeStreams();
        }
    }
}
