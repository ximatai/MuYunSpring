package net.ximatai.muyun.spring.platform.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.platform.exchange.RecordExchangeService;
import net.ximatai.muyun.spring.web.PlatformWebExceptionHandler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.util.List;

/**
 * Verifies standard inherited routes, multipart intent and provider installation through real MVC
 * conversion.
 */
class RecordExchangeWebTest {
    private final RecordExchangeWebSupport support = mock(RecordExchangeWebSupport.class);
    private final Controller controller = new Controller();
    private MockMvc mvc;
    private net.ximatai.muyun.spring.common.tenant.TenantContext.Scope tenant;

    @BeforeEach
    void setup() {
        tenant = net.ximatai.muyun.spring.common.tenant.TenantContext.use("tenant-a");
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("exchange", support);
        var registry = new Interceptors();
        new RecordExchangeWebConfiguration(beans.getBeanProvider(RecordExchangeWebSupport.class))
                .addInterceptors(registry);
        mvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setMessageConverters(
                                new MappingJackson2HttpMessageConverter(new ObjectMapper()))
                        .setControllerAdvice(new PlatformWebExceptionHandler())
                        .addInterceptors(registry.values())
                        .build();
    }

    @org.junit.jupiter.api.AfterEach
    void cleanup() { tenant.close(); }

    @Test
    void inheritedFileRoutesKeepPurposeLanguageAndReviewedMultipartCommand() throws Exception {
        byte[] file = new byte[] {1, 2, 3};
        var command =
                new RecordExchangeService.Command(
                        "document", "reviewed", List.of(), List.of(), "en", "de");
        when(support.execute(eq(controller), eq(file), eq(command)))
                .thenReturn(new RecordExchangeService.Result(1, 0, 0, 0, false, null, List.of()));
        mvc.perform(
                        multipart("/test.exchange/exchange/files/import")
                                .file(
                                        new MockMultipartFile(
                                                "file",
                                                "input.xlsx",
                                                "application/octet-stream",
                                                file))
                                .file(
                                        new MockMultipartFile(
                                                "command",
                                                "",
                                                MediaType.APPLICATION_JSON_VALUE,
                                                new ObjectMapper().writeValueAsBytes(command))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1));
        verify(support).execute(controller, file, command);
        var options = new RecordExchangeService.Export("DATA", "de", "Europe/Berlin");
        when(support.template(controller, options)).thenReturn(file);
        mvc.perform(
                        post("/test.exchange/exchange/files/template")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(new ObjectMapper().writeValueAsBytes(options)))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                        "Content-Disposition",
                                        "attachment; filename=\"test_exchange-template.xlsx\""))
                .andExpect(content().bytes(file));
    }

    @Test
    void importRequiresCommandAndNonemptyFileBeforeCallingTheEngine() throws Exception {
        mvc.perform(
                        multipart("/test.exchange/exchange/files/import")
                                .file(
                                        new MockMultipartFile(
                                                "file",
                                                "input.xlsx",
                                                "application/octet-stream",
                                                new byte[] {1})))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        multipart("/test.exchange/exchange/files/preview")
                                .file(
                                        new MockMultipartFile(
                                                "file",
                                                "empty.xlsx",
                                                "application/octet-stream",
                                                new byte[0])))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(support);
    }

    @RestController
    @RequestMapping("/test.exchange")
    static class Controller implements CrudWeb<StandardEntity, CrudAbility<StandardEntity>> {
        @SuppressWarnings("unchecked")
        private final CrudAbility<StandardEntity> service = mock(CrudAbility.class);

        @Override
        public CrudAbility<StandardEntity> service() {
            return service;
        }
    }

    static class Interceptors extends InterceptorRegistry {
        HandlerInterceptor[] values() {
            return getInterceptors().toArray(HandlerInterceptor[]::new);
        }
    }
}
