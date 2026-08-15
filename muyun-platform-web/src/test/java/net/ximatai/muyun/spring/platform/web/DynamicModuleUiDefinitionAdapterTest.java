package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.ui.PlatformPageConfigSnapshot;
import net.ximatai.muyun.spring.platform.ui.PlatformResolvedPageConfig;
import net.ximatai.muyun.spring.platform.ui.PlatformResolvedUiField;
import net.ximatai.muyun.spring.platform.ui.PlatformUiClientType;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfig;
import net.ximatai.muyun.spring.platform.ui.PlatformUiFixedPosition;
import net.ximatai.muyun.spring.platform.ui.PlatformUiSet;
import net.ximatai.muyun.spring.platform.ui.PlatformUiSetType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicModuleUiDefinitionAdapterTest {
    @Test
    void shouldRejectDynamicBooleanStatusWithoutPresentationMetadata() {
        PlatformUiSet listSet = uiSet("set-list", "crm.customer", "customer_list", PlatformUiSetType.LIST);
        PlatformUiConfig listConfig = uiConfig("ui-list-web", "set-list", "客户列表", true, 10);
        PlatformResolvedPageConfig resolved = new PlatformResolvedPageConfig(
                List.of(resolvedField("ui-list-web", "field-online", null, "online", "在线状态",
                        "booleanStatus", true, false, null, null, null, null)),
                List.of());

        assertThatThrownBy(() -> DynamicModuleUiDefinitionAdapter.fromPublishedSnapshot(
                new PlatformPageConfigSnapshot("crm.customer", List.of(listSet), List.of(listConfig), List.of(),
                        List.of(), List.of()), resolved))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot use uiType booleanStatus until dynamic UI configuration declares its presentation");
    }

    @Test
    void shouldRejectDynamicFileTransferUntilItCanUseTheUnifiedFileReferenceLifecycle() {
        PlatformUiSet formSet = uiSet("set-form", "crm.document", "document_form", PlatformUiSetType.FORM);
        PlatformUiConfig formConfig = uiConfig("ui-form-web", "set-form", "文档", true, 10);
        PlatformResolvedPageConfig resolved = new PlatformResolvedPageConfig(
                List.of(resolvedField("ui-form-web", "field-file", null, "fileId", "文件",
                        "fileTransfer", true, false, null, null, null, null)),
                List.of());

        assertThatThrownBy(() -> DynamicModuleUiDefinitionAdapter.fromPublishedSnapshot(
                new PlatformPageConfigSnapshot("crm.document", List.of(formSet), List.of(formConfig), List.of(),
                        List.of(), List.of()), resolved))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot use file transfer until the unified file-reference lifecycle is available");
    }

    @Test
    void shouldConvertPublishedDynamicSnapshotToModuleUiDefinition() {
        PlatformUiSet listSet = uiSet("set-list", "crm.customer", "customer_list", PlatformUiSetType.LIST);
        PlatformUiSet formSet = uiSet("set-form", "crm.customer", "customer_form", PlatformUiSetType.FORM);
        PlatformUiConfig listConfig = uiConfig("ui-list-web", "set-list", "客户列表", true, 10);
        PlatformUiConfig formConfig = uiConfig("ui-form-web", "set-form", "客户表单", true, 20);
        PlatformUiConfig draftConfig = uiConfig("ui-draft-web", "set-list", "草稿列表", false, 30);
        PlatformUiConfig appConfig = uiConfig("ui-list-app", "set-list", "客户列表 APP", true, 40);
        appConfig.setClientType(PlatformUiClientType.APP);
        PlatformPageConfigSnapshot snapshot = new PlatformPageConfigSnapshot(
                "crm.customer",
                List.of(listSet, formSet),
                List.of(listConfig, formConfig, draftConfig, appConfig),
                List.of(),
                List.of(),
                List.of()
        );
        PlatformResolvedPageConfig resolved = new PlatformResolvedPageConfig(
                List.of(
                        resolvedField("ui-list-web", "field-name", null, "name", "客户名称",
                                "text", true, false, null, 180, "left", PlatformUiFixedPosition.LEFT),
                        resolvedField("ui-list-web", "field-owner", "owner", "title", "负责人",
                                "reference", true, false, null, 160, null, null),
                        resolvedField("ui-form-web", "field-name", null, "name", "客户名称",
                                "input", true, false, true, null, null, null),
                        resolvedField("ui-draft-web", "field-draft", null, "draftOnly", "草稿字段",
                                "input", true, false, null, null, null, null)
                ),
                List.of()
        );

        ModuleUiDefinition definition = DynamicModuleUiDefinitionAdapter.fromPublishedSnapshot(snapshot, resolved);

        assertThat(definition.moduleAlias()).isEqualTo("crm.customer");
        assertThat(ModuleUiDescriptorCompiler.compile(
                definition,
                net.ximatai.muyun.spring.platform.module.ModuleKind.DYNAMIC,
                "客户",
                java.util.Map.of(),
                "name").recordLabelField()).isEqualTo("name");
        assertThat(definition.page()).isInstanceOf(ListDetailCardPageDefinition.class);
        ListDetailCardPageDefinition page = (ListDetailCardPageDefinition) definition.page();
        ViewDefinition listView = page.list().list();
        assertThat(listView.viewKind()).isEqualTo(ModuleViewKind.LIST);
        assertThat(listView.title()).isEqualTo("客户列表");
        assertThat(listView.fields()).extracting(field -> field.fieldRef().fieldId())
                .containsExactly("field-name", "field-owner");
        assertThat(listView.fields()).extracting(field -> field.fieldRef().fieldName())
                .containsExactly("name", "title");
        assertThat(listView.fields()).extracting(field -> field.fieldRef().relationCode())
                .containsExactly(null, "owner");
        assertThat(listView.fields().get(0).uiType()).isEqualTo("text");
        assertThat(listView.fields().get(0).width()).isEqualTo("180px");
        assertThat(listView.fields().get(0).align()).isEqualTo("left");
        assertThat(listView.fields().get(0).fixed()).isTrue();

        ViewDefinition formView = page.detail().editor();
        assertThat(formView.viewKind()).isEqualTo(ModuleViewKind.FORM);
        assertThat(formView.fields()).hasSize(1);
        assertThat(formView.fields().get(0).required().constant()).isTrue();
        assertThat(formView.fields().get(0).readOnly().constant()).isFalse();
    }

    @Test
    void shouldMapDynamicNavigatorLevelsFromThePageRoot() {
        PlatformUiSet listSet = uiSet("set-list", "crm.customer", "customer_list", PlatformUiSetType.LIST);
        PlatformUiSet formSet = uiSet("set-form", "crm.customer", "customer_form", PlatformUiSetType.FORM);
        PlatformUiConfig listConfig = uiConfig("ui-list-web", "set-list", "客户列表", true, 10);
        listConfig.setLayoutJson("""
                {"template":"LIST_DETAIL_CARD","traits":[],"navigator":{"levels":[
                  {"key":"tenant","kind":"MICRO_LIST","sourceModuleAlias":"iam.tenant","title":"租户",
                   "queryBindings":[{"field":"tenantId","queryCriteriaKey":"tenantId"}],
                   "childBindings":[{"childLevelKey":"organization","childQueryCriteriaKey":"tenantId"}]},
                  {"key":"organization","kind":"TREE","sourceModuleAlias":"iam.organization","title":"组织",
                   "queryBindings":[{"field":"organizationId","queryCriteriaKey":"organizationId"}]}
                ]}}""");
        PlatformUiConfig formConfig = uiConfig("ui-form-web", "set-form", "客户", true, 20);

        ModuleUiDefinition definition = DynamicModuleUiDefinitionAdapter.fromPublishedSnapshot(
                new PlatformPageConfigSnapshot("crm.customer", List.of(listSet, formSet), List.of(listConfig, formConfig),
                        List.of(), List.of(), List.of()), PlatformResolvedPageConfig.empty());

        PageNavigatorDefinition navigator = ((ListDetailCardPageDefinition) definition.page()).navigator();
        assertThat(navigator.levels()).hasSize(2);
        assertThat(navigator.levels().getFirst().childBindings()).containsExactly(
                new PageNavigatorChildBindingDefinition("organization", "tenantId"));
        assertThat(navigator.levels().get(1).queryBindings()).containsExactly(
                new PageNavigatorQueryBindingDefinition("organizationId", "organizationId"));
        assertThat(net.ximatai.muyun.spring.platform.ui.PlatformPageLayoutNavigator.queryBindings(listConfig))
                .containsExactly(
                        new net.ximatai.muyun.spring.platform.ui.PlatformPageNavigatorQueryBinding("tenantId", "tenantId"),
                        new net.ximatai.muyun.spring.platform.ui.PlatformPageNavigatorQueryBinding(
                                "organizationId", "organizationId"));
    }

    @Test
    void shouldAdaptDynamicMaximumDisplayLinesToTheSourceNeutralDescriptor() {
        PlatformUiSet listSet = uiSet("set-list", "crm.customer", "customer_list", PlatformUiSetType.LIST);
        PlatformUiSet formSet = uiSet("set-form", "crm.customer", "customer_form", PlatformUiSetType.FORM);
        PlatformUiConfig listConfig = uiConfig("ui-list-web", "set-list", "客户列表", true, 10);
        PlatformUiConfig formConfig = uiConfig("ui-form-web", "set-form", "客户", true, 20);
        PlatformResolvedUiField field = new PlatformResolvedUiField(
                "ui-list-web", "field-name", null, "customer", "name", "name", "客户名称", "string",
                "NORMAL", "text", true, false, null, null, null, 180, 1, "left", null, 3);

        ModuleUiDefinition definition = DynamicModuleUiDefinitionAdapter.fromPublishedSnapshot(
                new PlatformPageConfigSnapshot("crm.customer", List.of(listSet, formSet), List.of(listConfig, formConfig), List.of(),
                        List.of(), List.of()),
                new PlatformResolvedPageConfig(List.of(field), List.of()));

        assertThat(((ListDetailCardPageDefinition) definition.page()).list().list())
                .satisfies(view -> assertThat(view.fields()).singleElement()
                        .satisfies(resolved -> assertThat(resolved.maxDisplayLines()).isEqualTo(3)));
    }

    private PlatformUiSet uiSet(String id, String moduleAlias, String alias, PlatformUiSetType setType) {
        PlatformUiSet uiSet = new PlatformUiSet();
        uiSet.setId(id);
        uiSet.setModuleAlias(moduleAlias);
        uiSet.setAlias(alias);
        uiSet.setSetType(setType);
        uiSet.setTitle(alias);
        uiSet.setEnabled(Boolean.TRUE);
        return uiSet;
    }

    private PlatformUiConfig uiConfig(String id, String uiSetId, String title, boolean published, int sortOrder) {
        PlatformUiConfig config = new PlatformUiConfig();
        config.setId(id);
        config.setUiSetId(uiSetId);
        config.setTitle(title);
        config.setClientType(PlatformUiClientType.WEB);
        config.setPublished(published);
        config.setEnabled(Boolean.TRUE);
        config.setSortOrder(sortOrder);
        config.setLayoutJson("{\"template\":\"LIST_DETAIL_CARD\",\"traits\":[]}");
        return config;
    }

    private PlatformResolvedUiField resolvedField(String uiConfigId,
                                                  String moduleMetadataFieldId,
                                                  String relationAlias,
                                                  String fieldName,
                                                  String fieldTitle,
                                                  String fieldUiControlAlias,
                                                  Boolean visible,
                                                  Boolean readOnly,
                                                  Boolean required,
                                                  Integer width,
                                                  String align,
                                                  PlatformUiFixedPosition fixedPosition) {
        return new PlatformResolvedUiField(
                uiConfigId,
                moduleMetadataFieldId,
                relationAlias,
                "customer",
                fieldName,
                fieldName,
                fieldTitle,
                "string",
                "NORMAL",
                fieldUiControlAlias,
                visible,
                readOnly,
                required,
                null,
                null,
                width,
                align,
                fixedPosition
        );
    }
}
