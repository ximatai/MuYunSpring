package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.dynamic.metadata.StaticEntityDefinitionCompiler;
import net.ximatai.muyun.spring.iam.federation.*;
import net.ximatai.muyun.spring.platform.module.StaticModuleActionDefinition;
import net.ximatai.muyun.spring.platform.web.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FederationPagePresentationTest {
    @Test
    void tenantPolicyUsesStandardGroupsAndDeliveredReferenceNames() {
        var definition = StaticModuleDefinition.builder("iam", TenantIdentityPolicyService.MODULE_ALIAS, "外部登录方式")
                .modelClass(TenantIdentityPolicy.class)
                .entities(List.of(new StaticEntityDefinitionCompiler().compile("policy", "Policy", TenantIdentityPolicy.class)))
                .actions(List.of(StaticModuleActionDefinition.recordAction("preview", "预览通讯录"),
                        StaticModuleActionDefinition.recordAction("synchronize", "立即同步")))
                .uiDefinition(new TenantIdentityPolicyWebController().moduleUiDefinition()).build();
        var editor = ModuleUiDescriptorCompiler.compile(definition).page().detail().editor();
        assertThat(editor.formGroups()).extracting(ResolvedFormGroupDescriptor::groupCode)
                .containsExactly("sign_in", "admission", "directory", "schedule");
        assertThat(editor.fields()).filteredOn(field -> field.fieldRef().fieldName().equals("syncPositionId"))
                .singleElement().satisfies(field -> assertThat(field.reference().titleField()).isEqualTo("syncPositionTitle"));
        assertThat(editor.fields()).filteredOn(field -> field.fieldRef().fieldName().equals("syncRoleId"))
                .singleElement().satisfies(field -> assertThat(field.reference().titleField()).isEqualTo("syncRoleTitle"));
        assertThat(editor.createDefaults()).containsEntry("loginEnabled", true).containsEntry("bindingEnabled", true);
        assertThat(editor.formGroups().get(3).fields()).extracting(ref -> ref.fieldName())
                .containsExactly("syncExpression", "syncZoneId", "syncEnabled");
        assertThat(editor.formGroups().getFirst().fields()).extracting(ref -> ref.fieldName())
                .containsExactly("providerId", "sourceStatusMaxAgeSeconds", "loginEnabled", "bindingEnabled");
        assertThat(editor.fields()).filteredOn(field -> field.fieldRef().fieldName().equals("sourceStatusMaxAgeSeconds"))
                .singleElement().satisfies(field -> assertThat(field.fieldControl().alias()).isEqualTo("duration_seconds"));
        assertThat(editor.fields()).filteredOn(field -> field.fieldRef().fieldName().equals("syncZoneId"))
                .singleElement().satisfies(field -> {
                    assertThat(field.fieldControl().alias()).isEqualTo("dictionary_dropdown");
                    assertThat(field.option().binding().source()).isEqualTo("platform.time_zone");
                });
    }

    @Test
    void providerGroupsKeepCredentialConfigurationSeparateFromProtocolSettings() {
        var definition = StaticModuleDefinition.builder("iam", IdentityProviderService.MODULE_ALIAS, "外部身份源")
                .modelClass(IdentityProvider.class)
                .entities(List.of(new StaticEntityDefinitionCompiler().compile("provider", "Provider", IdentityProvider.class)))
                .uiDefinition(new IdentityProviderWebController().moduleUiDefinition()).build();
        var editor = ModuleUiDescriptorCompiler.compile(definition).page().detail().editor();
        assertThat(editor.formGroups()).extracting(ResolvedFormGroupDescriptor::groupCode)
                .containsExactly("identity", "credentials", "protocol");
        assertThat(editor.formGroups().get(1).fields()).extracting(ref -> ref.fieldName())
                .containsExactly("clientId", "clientSecretEnvironmentVariable");
        assertThat(editor.fields()).extracting(field -> field.fieldRef().fieldName()).doesNotContain("clientSecret");
    }

    @Test
    void administrativeUnbindUsesTheStandardTypedConfirmation() {
        var ui = new ExternalIdentityWebController().moduleUiDefinition();
        assertThat(ui.pageActions()).singleElement().satisfies(action -> {
            assertThat(action.actionCode()).isEqualTo("delete");
            assertThat(action.title()).isEqualTo("解除绑定");
        });
        assertThat(ui.actions()).singleElement().satisfies(action ->
                assertThat(action.confirmation().requiredField()).isEqualTo("externalUserId"));
        var definition = StaticModuleDefinition.builder("iam", ExternalIdentityService.MODULE_ALIAS, "外部身份绑定")
                .modelClass(ExternalIdentity.class)
                .entities(List.of(new StaticEntityDefinitionCompiler().compile("binding", "Binding", ExternalIdentity.class)))
                .actions(List.of(StaticModuleActionDefinition.recordAction("delete", "删除")))
                .uiDefinition(ui).build();
        var descriptor = ModuleUiDescriptorCompiler.compile(definition);
        assertThat(descriptor.recordLabelField()).isEqualTo("externalUserId");
        assertThat(descriptor.page().managedActions()).isTrue();
        assertThat(descriptor.page().actions()).singleElement().satisfies(action -> {
            assertThat(action.operation()).isEqualTo(PageActionOperation.DELETE);
            assertThat(action.title()).isEqualTo("解除绑定");
        });
    }

    @Test
    void admissionReviewCanFilterStatusWithoutDefaultingAwayCompletedApplications() {
        var page = (ListDetailCardPageDefinition) new JoinApplicationWebController().moduleUiDefinition().page();
        assertThat(page.list().persistentQueryControls()).singleElement().satisfies(control -> {
            var field = (PageListFieldPersistentQueryControlDefinition) control;
            assertThat(field.fieldName()).isEqualTo("status");
            assertThat(field.defaultValues()).isEmpty();
        });
    }

    @Test
    void runFiltersUseStandardQueryableFieldsWithoutHidingFailuresByDefault() {
        var ui = new DirectorySyncRunWebController().moduleUiDefinition();
        var page = (ListDetailCardPageDefinition) ui.page();
        assertThat(page.list().persistentQueryControls()).hasSize(2).allSatisfy(control -> {
            assertThat(control).isInstanceOf(PageListFieldPersistentQueryControlDefinition.class);
            var field = (PageListFieldPersistentQueryControlDefinition) control;
            assertThat(field.fieldName()).isIn("mode", "status");
            assertThat(field.defaultValues()).isEmpty();
        });
        var query = new DirectorySyncRunService(org.mockito.Mockito.mock(DirectorySyncRunDao.class), tenant -> {}).queryDescriptor();
        assertThat(query.fields()).extracting(field -> field.fieldName()).contains("mode", "status");
    }
    @Test
    void relatedDetailsUseBusinessGroupsAndKeepDiagnosticsFullWidth() {
        var bindings = (FlatManagementPageDefinition) new ExternalIdentityWebController().moduleUiDefinition().page();
        assertThat(bindings.detail().display().formGroups()).extracting(FormGroupDefinition::groupCode)
                .containsExactly("identity", "binding", "source");
        assertThat(bindings.detail().display().fields()).extracting(field -> field.fieldRef().fieldName())
                .contains("userId", "providerId", "bindingSource").doesNotContain("userTitle", "providerTitle");
        var runUi = new DirectorySyncRunWebController().moduleUiDefinition();
        assertThat(ModuleUiDescriptorCompiler.compile(runUi).page().detail().display()).isNotNull();
        var runs = (ListDetailCardPageDefinition) runUi.page();
        assertThat(runs.detail().display().formGroups()).extracting(FormGroupDefinition::groupCode)
                .containsExactly("result", "diagnostics", "trace");
        assertThat(runs.detail().display().fields()).filteredOn(field ->
                List.of("statistics", "failedPages", "cursor").contains(field.fieldRef().fieldName()))
                .hasSize(3).allSatisfy(field -> assertThat(field.columnSpan()).isEqualTo(2));
    }

    @Test
    void admissionDetailsDeliverReferenceNamesAndReviewContext() {
        var definition = StaticModuleDefinition.builder("iam", JoinApplicationService.MODULE_ALIAS, "加入申请")
                .modelClass(JoinApplication.class)
                .entities(List.of(new StaticEntityDefinitionCompiler().compile("application", "Application", JoinApplication.class)))
                .actions(List.of(StaticModuleActionDefinition.recordAction("approve", "批准加入"),
                        StaticModuleActionDefinition.recordAction("reject", "拒绝加入")))
                .uiDefinition(new JoinApplicationWebController().moduleUiDefinition()).build();
        var view = ModuleUiDescriptorCompiler.compile(definition).page().detail().display();
        assertThat(view.formGroups()).extracting(ResolvedFormGroupDescriptor::groupCode)
                .containsExactly("applicant", "review");
        assertThat(view.fields()).filteredOn(field -> field.fieldRef().fieldName().equals("reviewerId"))
                .singleElement().satisfies(field -> assertThat(field.reference()).isNull());
        assertThat(view.fields()).filteredOn(field -> field.fieldRef().fieldName().equals("providerId"))
                .singleElement().satisfies(field -> assertThat(field.reference().titleField()).isEqualTo("providerTitle"));
    }

}
