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
                new ScheduledActionPlanWebController().moduleUiDefinition(), plans.queryDescriptor());
        check(SystemActionWhitelistService.MODULE_ALIAS, "系统身份定时调用白名单", SystemActionWhitelist.class,
                new SystemActionWhitelistWebController().moduleUiDefinition(), whitelist.queryDescriptor());
    }
    @Test void executionTenantUsesTheStandardPickerAndInitiatorCannotSelectScope() {
        var descriptor = ModuleUiDescriptorCompiler.compile(StaticModuleDefinition.builder("platform",
                ScheduledActionPlanService.MODULE_ALIAS, "定时动作计划").modelClass(ScheduledActionPlan.class)
                .uiDefinition(new ScheduledActionPlanWebController().moduleUiDefinition()).build());
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
