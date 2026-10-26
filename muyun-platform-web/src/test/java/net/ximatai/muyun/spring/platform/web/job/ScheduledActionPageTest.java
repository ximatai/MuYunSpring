package net.ximatai.muyun.spring.platform.web.job;

import net.ximatai.muyun.spring.common.i18n.*;
import net.ximatai.muyun.spring.platform.job.*;
import net.ximatai.muyun.spring.platform.web.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScheduledActionPageTest {
    @Test void pagesHaveEnglishResourcesAndOnlyContractSearchFields() {
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        var plans = new ScheduledActionPlanService(mock(ScheduledActionPlanDao.class), mock(JobService.class),
                mock(net.ximatai.muyun.spring.platform.module.ModuleActionInvoker.class),
                beans.getBeanProvider(net.ximatai.muyun.spring.common.identity.TrustedExecutionIdentityResolver.class),
                mock(net.ximatai.muyun.spring.common.platform.JobSystemActionPolicy.class), mock(JobManagementAccess.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), mock(net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier.class));
        var whitelist = new SystemActionWhitelistService(mock(SystemActionWhitelistDao.class));
        check(ScheduledActionPlanService.MODULE_ALIAS, "定时动作计划", ScheduledActionPlan.class,
                new ScheduledActionPlanWebController(mock(TextResolver.class)).moduleUiDefinition(), plans.queryDescriptor());
        check(SystemActionWhitelistService.MODULE_ALIAS, "系统身份定时调用白名单", SystemActionWhitelist.class,
                new SystemActionWhitelistWebController().moduleUiDefinition(), whitelist.queryDescriptor());
    }
    @Test void executionTenantUsesTheStandardPickerAndInitiatorCannotSelectScope() {
        var descriptor = ModuleUiDescriptorCompiler.compile(StaticModuleDefinition.builder("platform",
                ScheduledActionPlanService.MODULE_ALIAS, "定时动作计划").modelClass(ScheduledActionPlan.class)
                .uiDefinition(new ScheduledActionPlanWebController(mock(TextResolver.class)).moduleUiDefinition()).build());
        assertThat(descriptor.page().detail().editor().fields())
                .noneSatisfy(field -> assertThat(field.fieldRef().fieldName()).isEqualTo("tenantId"))
                .filteredOn(field -> field.fieldRef().fieldName().equals("executionTenantId"))
                .singleElement().satisfies(field -> {
                    assertThat(field.uiType()).isEqualTo("recordPicker");
                    assertThat(field.reference().targetModuleAlias()).isEqualTo("iam.tenant");
                    assertThat(field.readOnly().formula().expression()).contains("identityMode", "SYSTEM", "PRESENT({id})");
                });
    }
    @Test void runPagePublishesInlineLocalizedOptionsForBothListAndDetail() {
        var descriptor = ModuleUiDescriptorCompiler.compile(StaticModuleDefinition.builder("platform",
                JobRunManagementService.MODULE_ALIAS, "作业运行").modelClass(JobRun.class)
                .uiDefinition(new JobRunWebController(mock(JobService.class)).moduleUiDefinition()).build());
        var fields = new ArrayList<>(descriptor.page().detail().display().fields());
        fields.addAll(descriptor.page().list().fields().fields());
        assertThat(fields).filteredOn(field -> Set.of("status", "identityMode", "triggerType", "resultCode")
                .contains(field.fieldRef().fieldName())).hasSize(6).allSatisfy(field -> {
                    assertThat(field.option().inlineItems()).isNotEmpty();
                    assertThat(field.option().inlineItems()).allSatisfy(item -> assertThat(item.titleSource()).isNotNull());
                });
    }
    @Test void targetEndpointProjectsLocalizedTitlesAndStableSourceCodes() throws Exception {
        var service = mock(ScheduledActionPlanService.class);
        var module = TextReference.code("test.module", "测试模块");
        var action = TextReference.code("test.action", "执行动作");
        when(service.scheduledTargets("tenant-a")).thenReturn(List.of(
                new net.ximatai.muyun.spring.platform.module.ModuleActionInvoker.ScheduledTargetDescriptor(
                        "test.target", module, "execute", action,
                        net.ximatai.muyun.spring.platform.module.ModuleKind.STATIC, true)));
        TextResolver texts = (source, arguments, tag) -> new TextResources.Resolution(
                source.equals(module) ? "Test module" : "Execute action", tag, tag, false, false);
        var controller = new ScheduledActionPlanWebController(texts);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "service", service);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().registerModule(
                new net.ximatai.muyun.spring.web.MuYunSpringJacksonConfiguration().codeTitleEnumJacksonModule());
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(mapper)).build();
        try (var tenant = net.ximatai.muyun.spring.common.tenant.TenantContext.system("test");
             var language = LanguageContext.use("en")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/platform.scheduled_action_plan/scheduled-targets").param("executionTenantId", "tenant-a"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].moduleTitle").value("Test module"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].actionTitle").value("Execute action"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].source").value("static"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].parametersRequired").value(true));
        }
        verify(service).scheduledTargets("tenant-a");
    }

    private void check(String alias, String title, Class<?> model, ModuleUiDefinition ui, net.ximatai.muyun.spring.ability.query.QueryDescriptor query) {
        var descriptor = ModuleUiDescriptorCompiler.compile(StaticModuleDefinition.builder("platform", alias, title).modelClass(model).uiDefinition(ui).build());
        assertThat(query.quickSearchFields().stream().map(net.ximatai.muyun.spring.ability.query.QueryField::fieldName).toList())
                .containsAll(descriptor.page().quickSearchFields());
        var languages = LanguageCatalog.load(getClass().getClassLoader(), "zh-CN");
        var resources = TextResources.load(getClass().getClassLoader(), languages);
        var missing = descriptor.sourceTexts().values().stream().filter(source -> {
            var result = resources.resolve(source, Map.of(), "en");
            return result.missing() || result.text().matches(".*[\\p{IsHan}].*");
        }).map(source -> source.sourceId() + "=" + source.originalText()).toList();
        assertThat(missing).isEmpty();
    }
}
