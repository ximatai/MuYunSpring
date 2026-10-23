package net.ximatai.muyun.spring.starter.configuration.runtime;

import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicEntityOperations;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.dynamic.web.DynamicRecordJacksonConfiguration;
import net.ximatai.muyun.spring.iam.role.Role;
import net.ximatai.muyun.spring.web.MuYunSpringJacksonConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Consumer classpath deliberately retains both Jackson generations, without dependency exclusions. */
@SuppressWarnings("removal")
class JacksonMvcConsumerContractTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                    Jackson2AutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
                    WebMvcAutoConfiguration.class))
            .withUserConfiguration(MuYunSpringJacksonConfiguration.class,
                    DynamicRecordJacksonConfiguration.class, EndpointConfiguration.class);
    private final WebApplicationContextRunner jackson2Runner = runner
            .withPropertyValues("spring.http.converters.preferred-json-mapper=jackson2");

    @Test
    void shouldUsePlatformEnumCodesForHttpRoundTrip() {
        jackson2Runner.run(context -> {
            assertThat(context).hasNotFailed();
            var adapter = context.getBean(RequestMappingHandlerAdapter.class);
            var converter = adapter.getMessageConverters().stream()
                    .filter(candidate -> candidate.canWrite(Role.class, MediaType.APPLICATION_JSON))
                    .findFirst().orElseThrow();
            assertThat(converter).isInstanceOf(
                    org.springframework.http.converter.json.MappingJackson2HttpMessageConverter.class);
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(post("/static-contract").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"roleKind\":\"dataGrant\"}"))
                    .andExpect(status().isOk())
                    .andExpect(content().json("{\"roleKind\":\"dataGrant\"}"));
            mvc.perform(post("/static-contract").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"roleKind\":\"unknown\"}"))
                    .andExpect(status().isBadRequest());
        });
    }

    @Test
    void bootDefaultWithBothGenerationsDoesNotUsePlatformEnumContract() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(post("/static-contract").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"roleKind\":\"dataGrant\"}"))
                    .andExpect(status().isBadRequest());
        });
    }

    @Test
    void shouldRetainStaticContentLanguageWriteIntentOverHttp() {
        jackson2Runner.run(context -> {
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            String input = """
                    {"roleKind":"dataGrant","$contentLanguages":{"title":{"languageTag":"en","expectedRevision":1}}}
                    """;
            mvc.perform(post("/static-contract").contentType(MediaType.APPLICATION_JSON).content(input))
                    .andExpect(status().isOk())
                    .andExpect(content().json(input));
        });
    }

    @Test
    void shouldUseDynamicRecordProtocolOverHttp() {
        jackson2Runner.run(context -> {
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            String input = """
                    {"id":"record-1","version":7,"values":{"description":"Original text"},
                     "$contentLanguages":{"description":{"languageTag":"en","expectedRevision":1}}}
                    """;
            mvc.perform(post("/sample.records").contentType(MediaType.APPLICATION_JSON).content(input))
                    .andExpect(status().isOk())
                    .andExpect(content().json(input))
                    .andExpect(jsonPath("$.entity").doesNotExist())
                    .andExpect(jsonPath("$.mutationMetadata").doesNotExist());
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class EndpointConfiguration {
        @Bean
        DynamicRecordService dynamicRecordService() {
            var entity = new EntityDefinition("main", "sample_record", "Sample",
                    List.of(FieldDefinition.text("description", "Description")));
            var records = mock(DynamicRecordService.class);
            var operations = mock(DynamicEntityOperations.class);
            when(records.mainEntityAlias("sample.records")).thenReturn("main");
            when(records.mainEntity("sample.records")).thenReturn(operations);
            when(operations.newRecord()).thenAnswer(call -> new DynamicRecord(entity));
            return records;
        }

        @Bean
        ContractEndpoints contractEndpoints() {
            return new ContractEndpoints();
        }
    }

    @RestController
    static class ContractEndpoints {
        @PostMapping("/static-contract")
        Role echoStatic(@RequestBody Role payload) {
            return payload;
        }

        @PostMapping("/sample.records")
        DynamicRecord echoDynamic(@RequestBody DynamicRecord payload) {
            return payload;
        }
    }
}
