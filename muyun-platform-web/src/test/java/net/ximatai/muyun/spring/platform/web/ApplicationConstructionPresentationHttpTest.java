package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.spring.common.i18n.*;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.platform.application.*;
import net.ximatai.muyun.spring.platform.menu.MenuService;
import net.ximatai.muyun.spring.platform.runtime.DynamicRuntimeActivationService;
import net.ximatai.muyun.spring.platform.ui.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ApplicationConstructionPresentationHttpTest {
    @Test void deliversActualTaskDeclarationsAndAcceptanceResourcesThroughHttpWithoutProjectingBusinessValues() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var plans = mock(ApplicationConstructionPlanService.class);
        var fields = mock(ApplicationConstructionFieldService.class);
        var database = mock(IDatabaseOperations.class);
        var delivery = spy(new ApplicationConstructionDeliveryService(database, plans, fields,
                mock(ApplicationConstructionDeliveryDao.class), mock(ApplicationConstructionAcceptanceDao.class),
                mock(PlatformPageDefinitionService.class), mock(PlatformPresentationVariantService.class),
                mock(PlatformPresentationRevisionResolver.class), mock(MenuService.class), mock(DynamicRuntimeActivationService.class)));
        var content = new ApplicationConstructionPlanContent("用户方案", "用户目标", List.of("用户要求"), List.of(),
                List.of(new ApplicationConstructionPlanContent.BusinessObject("order", "用户对象", "用户用途")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of("用户验收例子"), List.of());
        when(plans.read("plan")).thenReturn(new ApplicationConstructionPlanService.Snapshot("plan", 3, content, Instant.EPOCH,
                "NOT_STARTED", List.of(), List.of(), List.of(), List.of()));
        var rule = ApplicationConstructionText.code("acceptance.rule", "业务规则：{statement}", Map.of("statement", "用户规则 $1\\path"));
        var preview = new ApplicationConstructionDeliveryService.AcceptancePreview("order", 3, List.of(rule.original()), "unchanged-fingerprint", List.of(rule));
        doReturn(preview).when(delivery).previewAcceptance("plan", "order");
        var work = ApplicationConstructionText.code("progress.runtime-inactive", "模块运行态尚未激活");
        var progress = new ApplicationConstructionDeliveryService.Progress("order", "sample.order", "PENDING", false, false,
                null, false, false, List.of(work.original()), List.of(), List.of(), List.of(work));
        doReturn(progress).when(delivery).progress("plan", "order");
        var beans = new DefaultListableBeanFactory();
        var catalog = LanguageCatalog.load(getClass().getClassLoader(), "zh-CN");
        beans.registerSingleton("catalog", catalog);
        beans.registerSingleton("texts", TextResources.load(getClass().getClassLoader(), catalog));
        var advice = new PlatformPresentationResponseAdvice(mapper, beans.getBeanProvider(TextResolver.class), beans.getBeanProvider(LanguageCatalog.class));
        var controller = new ApplicationConstructionPlanWebController(plans, mock(ApplicationConstructionInitializationService.class), fields, delivery);
        var mvc = standaloneSetup(controller).setControllerAdvice(advice).setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("operator", "实施人员")); var language = LanguageContext.use("en")) {
            var task = mapper.readTree(mvc.perform(get("/platform.application-construction-plans/plan/task").header("Accept-Language", "en"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(task.at("/objects/0/title").asText()).isEqualTo("用户对象");
            assertThat(task.at("/objects/0/options/0/action").asText()).isEqualTo("INITIALIZE");
            assertThat(task.at("/unmappedRequirements/0/statement").asText()).isEqualTo("用户要求");
            assertThat(task.at("/textBindings/~1objects~10~1options~10~1explanation/languages/en/text").asText()).startsWith("Review the current standard application");
            assertThat(task.at("/textBindings/~1unmappedRequirements~10~1explanation/languages/en/text").asText()).startsWith("The fulfillment method has not been confirmed");
            assertThat(task.at("/objects/0/options/0/explanationSource").isMissingNode()).isTrue();
            assertThat(task.path("planRevision").asInt()).isEqualTo(3);
            var acceptance = mapper.readTree(mvc.perform(get("/platform.application-construction-plans/plan/objects/order/acceptance-preview"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(acceptance.at("/checks/0").asText()).isEqualTo("业务规则：用户规则 $1\\path");
            assertThat(acceptance.at("/textBindings/~1checks~10/languages/en/text").asText()).isEqualTo("Business rule: 用户规则 $1\\path");
            assertThat(acceptance.at("/textBindings/~1checks~10/source/sourceVersion").asText()).isEqualTo(TextReference.versionOf("业务规则：{statement}"));
            assertThat(acceptance.path("fingerprint").asText()).isEqualTo("unchanged-fingerprint");
            assertThat(acceptance.path("checkSources").isMissingNode()).isTrue();
            var progressJson = mapper.readTree(mvc.perform(get("/platform.application-construction-plans/plan/objects/order/progress"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(progressJson.at("/textBindings/~1remainingWork~10/languages/en/text").asText()).isEqualTo("The module runtime is not active yet");
            assertThat(progressJson.path("businessDataStatus").asText()).isEqualTo("NOT_QUERIED");
            assertThat(progressJson.path("acceptanceConfirmed").asBoolean()).isFalse();
        }
        assertThat(preview.checks()).containsExactly("业务规则：用户规则 $1\\path");
        verifyNoInteractions(database);
        verify(delivery, never()).confirmAcceptance(anyString(), any());
    }
}
