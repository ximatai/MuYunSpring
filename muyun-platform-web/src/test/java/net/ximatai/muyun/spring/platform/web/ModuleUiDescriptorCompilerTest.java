package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.option.DictionaryField;
import net.ximatai.muyun.spring.common.option.OptionLoad;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.model.title.TitleField;
import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.ability.reference.ReferenceHop;
import net.ximatai.muyun.spring.ability.reference.ReferenceLoad;
import net.ximatai.muyun.spring.ability.reference.ReferenceSummary;
import net.ximatai.muyun.spring.ability.reference.ReferenceTo;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.platform.module.ModuleEntryType;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleUiDescriptorCompilerTest {
    @Test
    void shouldCompileFlatManagementAsPageRootWithOnlyItsSupportedSlots() {
        ModuleUiDefinition definition = ModuleUiDefinition.builder("platform.application")
                .page(ModulePageDefinition.flatManagement(page -> page
                        .explorer(explorer -> explorer
                                .title("应用列表")
                                .searchPlaceholder("搜索应用")
                                .recordLabel("应用")
                                .titleField("title")
                                .secondaryField("alias")
                                .mutedWhenDisabled())
                        .detail(detail -> detail
                                .emptyDescription("请选择应用")
                                .createTitle("新建应用")
                                .display(display -> display.field("title").field("alias"))
                                .editor(editor -> editor
                                        .title("应用")
                                        .field("alias", field -> field.required())
                                        .field("title", field -> field.required())))
                        .traits(traits -> traits.standardCrud().enabledStatus().recycleBin())))
                .build();

        ResolvedModulePageDescriptor page = ModuleUiDescriptorCompiler.compile(definition).page();

        assertThat(page.template()).isEqualTo(ModulePageTemplate.FLAT_MANAGEMENT);
        assertThat(page.explorer()).satisfies(explorer -> {
            assertThat(explorer.title()).isEqualTo("应用列表");
            assertThat(explorer.recordLabel()).isEqualTo("应用");
            assertThat(explorer.secondaryField()).isEqualTo("alias");
            assertThat(explorer.mutedWhenDisabled()).isTrue();
        });
        assertThat(page.navigator()).isNull();
        assertThat(page.list()).isNull();
        assertThat(page.detail().display().fields()).extracting(field -> field.fieldRef().fieldName())
                .containsExactly("title", "alias");
        assertThat(page.detail().editor().viewKind()).isEqualTo(ModuleViewKind.FORM);
        assertThat(page.detail().editor().fields()).extracting(field -> field.fieldRef().fieldName())
                .containsExactly("alias", "title");
        assertThat(page.traits()).containsExactlyInAnyOrder(PageTrait.STANDARD_CRUD,
                PageTrait.ENABLED_STATUS, PageTrait.RECYCLE_BIN);
    }

    @Test
    void shouldCompileTreeManagementAsPageRootWithOnlyItsSupportedSlots() {
        ModuleUiDefinition definition = ModuleUiDefinition.builder("mr.tag")
                .page(PageTemplates.treeManagement(page -> page
                        .detail(detail -> detail
                                .emptyDescription("请选择标签，或新建根标签")
                                .editor(editor -> editor
                                        .title("标签")
                                        .field("title", field -> field.required())
                                        .field("parentId")
                                        .field("color")))
                        .traits(traits -> traits.standardCrud())))
                .build();

        ResolvedModulePageDescriptor page = ModuleUiDescriptorCompiler.compile(definition).page();

        assertThat(page.template()).isEqualTo(ModulePageTemplate.TREE_MANAGEMENT);
        assertThat(page.explorer()).isNull();
        assertThat(page.navigator()).isNull();
        assertThat(page.list()).isNull();
        assertThat(page.detail().editor().fields()).extracting(field -> field.fieldRef().fieldName())
                .containsExactly("title", "parentId", "color");
        assertThat(page.traits()).containsExactly(PageTrait.STANDARD_CRUD);
    }

    @Test
    void shouldCompileListDetailCardWithOptionalTreeNavigator() {
        ModuleUiDefinition definition = ModuleUiDefinition.builder("crm.customer")
                .page(ModulePageDefinition.listDetailCard(page -> page
                        .navigator(navigator -> navigator.level("organization", level -> level
                                .tree("iam.organization", "所属组织", "搜索组织")
                                .bindQuery("organizationId")))
                        .list(list -> list
                                .searchPlaceholder("搜索客户")
                                .fields(fields -> fields
                                        .title("客户列表")
                                        .field("code")
                                        .field("title")))
                        .detail(detail -> detail.editor(editor -> editor
                                .title("客户")
                                .field("code", field -> field.required())
                                .field("title", field -> field.required())))
                        .traits(traits -> traits.standardCrud().responsiveDetailSurface())))
                .build();

        ResolvedModulePageDescriptor page = ModuleUiDescriptorCompiler.compile(definition, null, null, Map.of(),
                Map.of("organizationId", new ResolvedReferenceFieldDescriptor("iam.organization",
                        ReferenceCardinality.ONE)), null).page();

        assertThat(page.template()).isEqualTo(ModulePageTemplate.LIST_DETAIL_CARD);
        assertThat(page.explorer()).isNull();
        assertThat(page.navigator()).satisfies(navigator -> {
            assertThat(navigator.levels()).singleElement().satisfies(level -> {
                assertThat(level.key()).isEqualTo("organization");
                assertThat(level.kind()).isEqualTo(PageNavigatorKind.TREE);
                assertThat(level.sourceModuleAlias()).isEqualTo("iam.organization");
                assertThat(level.queryBindings()).containsExactly(
                        new ResolvedPageNavigatorQueryBindingDescriptor("organizationId", "organizationId"));
            });
        });
        assertThat(page.list().searchPlaceholder()).isEqualTo("搜索客户");
        assertThat(page.list().fields().fields()).extracting(field -> field.fieldRef().fieldName())
                .containsExactly("code", "title");
        assertThat(page.detail().editor().fields()).extracting(field -> field.fieldRef().fieldName())
                .containsExactly("code", "title");
    }

    @Test
    void shouldPublishDeclaredDetailWorkspaceViewWithoutLeakingClientImplementation() {
        ModuleUiDefinition definition = ModuleUiDefinition.builder("mr.device")
                .page(PageTemplates.listDetailCard(page -> page
                        .list(list -> list.fields(fields -> fields.title("设备").field("code")))
                        .detail(detail -> detail
                                .workspaceView("mr.device.detail")
                                .editor(editor -> editor.title("设备").field("code")))
                        .traits(traits -> traits.responsiveDetailSurface())))
                .build();

        ResolvedPageDetailWorkspaceViewDescriptor workspaceView = ModuleUiDescriptorCompiler.compile(definition)
                .page().detail().workspaceView();

        assertThat(workspaceView.type()).isEqualTo("mr.device.detail");
    }

    @Test
    void shouldCompileEditorlessListDetailCardWithADisplay() {
        ModuleUiDefinition definition = ModuleUiDefinition.builder("iam.remote_support")
                .page(PageTemplates.listDetailCard(page -> page
                        .list(list -> list.fields(fields -> fields.field("title")))
                        .detail(detail -> detail.display(display -> display.field("title")))
                        .traits(traits -> traits.responsiveDetailSurface())))
                .build();

        ResolvedModulePageDescriptor page = ModuleUiDescriptorCompiler.compile(definition).page();

        assertThat(page.detail().display().fields()).extracting(field -> field.fieldRef().fieldName())
                .containsExactly("title");
        assertThat(page.detail().editor()).isNull();
        assertThatCode(() -> ModuleUiDescriptorCompiler.compile(staticDefinition(definition)))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectEditorlessListDetailCardWithoutADisplay() {
        assertThatThrownBy(() -> ModuleUiDefinition.builder("mr.remote_support")
                .page(PageTemplates.listDetailCard(page -> page
                        .list(list -> list.fields(fields -> fields.field("title")))
                        .detail(detail -> { })
                        .traits(traits -> traits.standardCrud())))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("editorless list/detail card requires a detail display");
    }

    @Test
    void shouldCompileOrderedNavigatorLevelsWithIndependentListAndChildBindings() {
        ModuleUiDefinition definition = ModuleUiDefinition.builder("crm.customer")
                .page(PageTemplates.listDetailCard(page -> page
                        .navigator(navigator -> navigator
                                .level("tenant", level -> level
                                        .microList("iam.tenant", "租户", "搜索租户")
                                        .singleResultPolicy(PageNavigatorSingleResultPolicy.AUTO_SELECT_AND_HIDE)
                                        .bindQuery("tenantId")
                                        .bindChild("organization", "tenantId"))
                                .level("organization", level -> level
                                        .tree("iam.organization", "所属组织", "搜索组织")
                                        .bindQuery("organizationId")))
                        .list(list -> list.fields(fields -> fields.field("title")))
                        .detail(detail -> detail.editor(editor -> editor.field("title")))
                        .traits(traits -> { })))
                .build();

        ResolvedPageNavigatorDescriptor navigator = ModuleUiDescriptorCompiler.compile(definition, null, null,
                Map.of(), Map.of(
                        "tenantId", new ResolvedReferenceFieldDescriptor("iam.tenant", ReferenceCardinality.ONE),
                        "organizationId", new ResolvedReferenceFieldDescriptor("iam.organization", ReferenceCardinality.ONE)),
                null).page().navigator();

        assertThat(navigator.levels()).hasSize(2);
        assertThat(navigator.levels().getFirst().singleResultPolicy())
                .isEqualTo(PageNavigatorSingleResultPolicy.AUTO_SELECT_AND_HIDE);
        assertThat(navigator.levels().getFirst().queryBindings()).containsExactly(
                new ResolvedPageNavigatorQueryBindingDescriptor("tenantId", "tenantId"));
        assertThat(navigator.levels().getFirst().childBindings()).containsExactly(
                new ResolvedPageNavigatorChildBindingDescriptor("organization", "tenantId"));
        assertThat(navigator.levels().get(1).queryBindings()).containsExactly(
                new ResolvedPageNavigatorQueryBindingDescriptor("organizationId", "organizationId"));
    }

    @Test
    void shouldAllowNoPageForAModuleWithoutUiContribution() {
        ResolvedModuleUiDescriptor descriptor = ModuleUiDescriptorCompiler.compile(
                ModuleUiDefinition.builder("crm.customer").build());

        assertThat(descriptor.page()).isNull();
        assertThat(descriptor.actions()).isEmpty();
        assertThat(descriptor.editorContributions()).isEmpty();
    }

    @Test
    void shouldCompileDefaultAndNamedEditorSurfacesWithoutClaimingAPageTemplate() {
        ModuleUiDefinition definition = ModuleUiDefinition.builder("crm.customer")
                .editors(editors -> editors
                        .defaultEditor(editor -> editor.title("客户").field("title", field -> field.required()))
                        .editor("quick_rename", editor -> editor.title("重命名客户")
                                .field("title", field -> field.required())))
                .build();

        ResolvedModuleUiDescriptor descriptor = ModuleUiDescriptorCompiler.compile(definition);

        assertThat(descriptor.page()).isNull();
        assertThat(descriptor.defaultEditor()).satisfies(editor -> {
            assertThat(editor.title()).isEqualTo("客户");
            assertThat(editor.fields()).extracting(field -> field.fieldRef().fieldName()).containsExactly("title");
        });
        assertThat(descriptor.editorSurfaces()).singleElement().satisfies(surface -> {
            assertThat(surface.key()).isEqualTo("quick_rename");
            assertThat(surface.editor().title()).isEqualTo("重命名客户");
        });
    }

    @Test
    void shouldCompileUiDefinitionToResolvedDescriptorWithoutPhysicalReadDetails() {
        ModuleUiDefinition definition = ModuleUiDefinition.builder("iam.employee")
                .page(PageTemplates.listDetailCard(page -> page
                        .list(list -> list.fields(fields -> fields.title("职员列表")
                                .field("employeeNo", field -> field.label("职员编号").width("160px").maxDisplayLines(2))
                                .field("enabled", field -> field.label("状态").uiType("enabledStatus").align("center"))
                                .field("online", field -> field.label("在线状态").booleanStatus("在线", "离线"))))
                        .detail(detail -> detail.editor(form -> form.title("职员档案")
                                .field("organizationId", field -> field.label("所属机构").required().readOnly())
                                .field("departmentId", field -> field.label("所属部门").required().uiType("recordPicker").columnSpan(2))))
                        .traits(traits -> { })))
                .build();

        ResolvedModuleUiDescriptor descriptor = ModuleUiDescriptorCompiler.compile(definition);

        assertThat(descriptor.moduleAlias()).isEqualTo("iam.employee");
        assertThat(descriptor.schemaVersion()).isEqualTo(ResolvedModuleUiDescriptor.SCHEMA_VERSION);
        assertThat(descriptor.page().list().fields())
                .satisfies(view -> {
                    assertThat(view.viewKind()).isEqualTo(ModuleViewKind.LIST);
                    assertThat(view.fields()).extracting(field -> field.fieldRef().fieldName())
                            .containsExactly("employeeNo", "enabled", "online");
                    assertThat(view.fields()).element(1)
                            .satisfies(field -> {
                                assertThat(field.uiType()).isEqualTo("enabledStatus");
                                assertThat(field.align()).isEqualTo("center");
                            });
                    assertThat(view.fields()).first().satisfies(field ->
                            assertThat(field.maxDisplayLines()).isEqualTo(2));
                    assertThat(view.fields()).last().satisfies(field -> {
                        assertThat(field.uiType()).isEqualTo("booleanStatus");
                        assertThat(field.booleanStatus().trueLabel()).isEqualTo("在线");
                        assertThat(field.booleanStatus().falseLabel()).isEqualTo("离线");
                    });
                });
        assertThat(descriptor.page().detail().editor())
                .satisfies(view -> {
                    assertThat(view.fields()).extracting(field -> field.fieldRef().fieldName())
                            .containsExactly("organizationId", "departmentId");
                    assertThat(view.fields()).first()
                            .satisfies(field -> {
                                assertThat(field.required().constant()).isTrue();
                                assertThat(field.readOnly().constant()).isTrue();
                            });
                    assertThat(view.fields()).last()
                            .satisfies(field -> {
                                assertThat(field.uiType()).isEqualTo("recordPicker");
                                assertThat(field.columnSpan()).isEqualTo(2);
                            });
                });
    }

    @Test
    void shouldRequireBusinessBooleanPresentationForItsUiType() {
        assertThatThrownBy(() -> ViewFieldDefinition.field("online").uiType("booleanStatus").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("uiType booleanStatus requires boolean status presentation");
        assertThatCode(() -> ViewFieldDefinition.field("online").booleanStatus("在线", "离线").build())
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ViewFieldDefinition.field("description").maxDisplayLines(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxDisplayLines must be at least 1");
    }

    @Test
    void shouldRejectFileTransferUntilTheUnifiedFileReferenceLifecycleExists() {
        assertThatThrownBy(() -> ViewFieldDefinition.field("fileId").uiType("fileTransfer").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("file transfer requires the unified file-reference lifecycle");
    }

    @Test
    void shouldRequireBooleanStatusToBeReadOnlyInFormViews() {
        ModuleUiDefinition writableDefinition = editorPage("iam.employee",
                form -> form.field("online", field -> field.booleanStatus("在线", "离线")));
        ModuleUiDefinition readOnlyDefinition = editorPage("iam.employee",
                form -> form.field("online", field -> field.booleanStatus("在线", "离线").readOnly()));

        assertThatThrownBy(() -> ModuleUiDescriptorCompiler.compile(writableDefinition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("booleanStatus UI field must be read-only in FORM views: online");
        assertThatCode(() -> ModuleUiDescriptorCompiler.compile(readOnlyDefinition))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldCompileStaticDefinitionWhenUiFieldsExistInModelFacts() {
        ModuleUiDefinition uiDefinition = listPage("iam.employee", list -> list
                        .field("employeeNo")
                        .field("title")
                        .field("enabled"));

        ResolvedModuleUiDescriptor descriptor = ModuleUiDescriptorCompiler.compile(staticDefinition(uiDefinition));

        assertThat(descriptor.moduleKind()).isEqualTo(net.ximatai.muyun.spring.platform.module.ModuleKind.STATIC);
        assertThat(descriptor.title()).isEqualTo("职员管理");
        assertThat(descriptor.page().list().fields())
                .satisfies(view -> assertThat(view.fields()).extracting(field -> field.fieldRef().fieldName())
                        .containsExactly("employeeNo", "title", "enabled"));
    }

    @Test
    void shouldPublishStaticAuditFieldTypeWithoutRequiringQueryCapability() {
        ModuleUiDefinition uiDefinition = listPage("iam.employee",
                list -> list.field("createdAt", field -> field.label("创建时间")));

        ResolvedViewFieldDescriptor field = ModuleUiDescriptorCompiler.compile(staticDefinition(uiDefinition))
                .page().list().fields().fields().getFirst();

        assertThat(field.valueType()).isEqualTo(FieldValueType.TIMESTAMP);
    }

    @Test
    void shouldDefaultEditableBusinessBooleansToSwitchWhileKeepingEnablementSemantic() {
        StaticModuleDefinition definition = StaticModuleDefinition.builder("demo", "demo.brand", "品牌配置")
                .entities(List.of(new EntityDefinition("brand", "demo_brand", "Brand",
                        List.of(
                                FieldDefinition.bool("showTitleArea", "展示标题区"),
                                FieldDefinition.bool("enabled", "启用状态")
                        ))))
                .uiDefinition(editorPage("demo.brand", form -> form
                                .field("showTitleArea", field -> field.label("展示标题区"))
                                .field("enabled", field -> field.label("启用状态"))))
                .build();

        List<ResolvedViewFieldDescriptor> fields = ModuleUiDescriptorCompiler.compile(definition)
                .page().detail().editor().fields();

        assertThat(fields).extracting(field -> field.fieldRef().fieldName())
                .containsExactly("showTitleArea", "enabled");
        assertThat(fields).extracting(ResolvedViewFieldDescriptor::uiType)
                .containsExactly("switch", "enabledStatus");
    }

    @Test
    void shouldPublishFormFieldGroupMetadataWithStaticFields() {
        StaticModuleDefinition definition = StaticModuleDefinition.builder("demo", "demo.brand", "品牌配置")
                .entities(List.of(new EntityDefinition("brand", "demo_brand", "Brand",
                        List.of(FieldDefinition.bool("showTitleArea", "展示标题区")))))
                .uiDefinition(editorPage("demo.brand", form -> form.group("branding", "品牌配置", "配置标题和 Logo", group -> group
                        .field("showTitleArea", field -> { }))))
                .build();

        ResolvedFormGroupDescriptor group = ModuleUiDescriptorCompiler.compile(definition)
                .page().detail().editor().formGroups().getFirst();

        assertThat(group).isEqualTo(new ResolvedFormGroupDescriptor("branding", "品牌配置", "配置标题和 Logo",
                List.of(ViewFieldRef.main("showTitleArea"))));
    }

    @Test
    void shouldCompileFileSizeAsTypedPresentationWithoutChangingLongValueType() {
        StaticModuleDefinition definition = StaticModuleDefinition.builder("mr", "mr.knowledge_file", "知识文件")
                .entities(List.of(new EntityDefinition("knowledge_file", "mr_knowledge_file", "Knowledge File",
                        List.of(FieldDefinition.longInteger("fileSize", "文件大小")))))
                .uiDefinition(listPage("mr.knowledge_file",
                        list -> list.field("fileSize", ViewFieldDefinition.Builder::fileSize)))
                .build();

        ResolvedViewFieldDescriptor field = ModuleUiDescriptorCompiler.compile(definition)
                .page().list().fields().fields().getFirst();

        assertThat(field.valueType()).isEqualTo(FieldValueType.LONG);
        assertThat(field.valuePresentation()).isEqualTo(FieldValuePresentation.FILE_SIZE);
        assertThat(field.uiType()).isNull();
    }

    @Test
    void shouldRejectFileSizePresentationForNonLongOrWritableFormFields() {
        StaticModuleDefinition wrongType = StaticModuleDefinition.builder("demo", "demo.asset", "资产")
                .entities(List.of(new EntityDefinition("asset", "demo_asset", "Asset",
                        List.of(FieldDefinition.string("fileSize", "文件大小")))))
                .uiDefinition(listPage("demo.asset",
                        list -> list.field("fileSize", ViewFieldDefinition.Builder::fileSize)))
                .build();
        StaticModuleDefinition writableForm = StaticModuleDefinition.builder("demo", "demo.asset", "资产")
                .entities(List.of(new EntityDefinition("asset", "demo_asset", "Asset",
                        List.of(FieldDefinition.longInteger("fileSize", "文件大小")))))
                .uiDefinition(editorPage("demo.asset",
                        form -> form.field("fileSize", ViewFieldDefinition.Builder::fileSize)))
                .build();

        assertThatThrownBy(() -> ModuleUiDescriptorCompiler.compile(wrongType))
                .hasMessage("file size presentation requires LONG field: fileSize");
        assertThatThrownBy(() -> ModuleUiDescriptorCompiler.compile(writableForm))
                .hasMessage("file size presentation must be read-only in FORM views: fileSize");
        assertThatThrownBy(() -> ViewFieldDefinition.field("fileSize").uiType("fileSize").build())
                .hasMessage("file size must use value presentation instead of uiType");
    }

    @Test
    void shouldPublishRecordLabelFactWithoutUiContributor() {
        StaticModuleDefinition definition = StaticModuleDefinition.builder("demo", "demo.customer", "客户管理")
                .entities(List.of(new EntityDefinition("customer", "demo_customer", "Customer",
                        List.of(FieldDefinition.string("displayName", "显示名称")))))
                .modelClass(CustomerRecord.class)
                .build();

        ModuleUiCompilationResult result = ModuleUiDescriptorCompiler.compileModule(definition);

        assertThat(result.uiDescriptor().recordLabelField()).isEqualTo("displayName");
        assertThat(result.uiDescriptor().page()).isNull();
        assertThat(result.uiDescriptor().actions()).isEmpty();
    }

    @Test
    void shouldCompileStaticOptionFieldAsResolvedFieldFact() {
        ModuleUiDefinition uiDefinition = editorPage("iam.employee",
                form -> form.field("gender", field -> field.label("性别")));
        StaticModuleDefinition definition = StaticModuleDefinition.builder("iam", "iam.employee", "职员管理")
                .entities(List.of(new EntityDefinition("employee", "iam_employee", "Employee",
                        List.of(FieldDefinition.string("gender", "性别")))))
                .uiDefinition(uiDefinition)
                .modelClass(OptionEmployee.class)
                .build();

        ResolvedViewFieldDescriptor field = ModuleUiDescriptorCompiler.compile(definition).page().detail().editor()
                .fields().getFirst();

        assertThat(field.option()).isNotNull();
        assertThat(field.option().binding().sourceType()).isEqualTo("dictionary");
        assertThat(field.option().binding().source()).isEqualTo("iam.gender");
        assertThat(field.option().selectionMode().name()).isEqualTo("SINGLE");
        assertThat(field.option().titleField()).isEqualTo("genderTitle");
    }

    @Test
    void shouldCompileStaticDefinitionReadModelFromLogicalFieldFacts() {
        ModuleUiDefinition uiDefinition = listPage("iam.employee", list -> list
                .field("employeeNo")
                .field("title")
                .field("enabled"));

        ModuleUiCompilationResult result = ModuleUiDescriptorCompiler.compileModule(staticDefinition(uiDefinition));

        assertThat(result.readModel().moduleAlias()).isEqualTo("iam.employee");
        assertThat(result.readModel().mainEntityAlias()).isEqualTo("employee");
        assertThat(result.readModel().fields()).extracting(ResolvedModuleReadField::fieldName)
                .containsExactly("employeeNo", "mobile", "title", "enabled");
        assertThat(result.readModel().fields()).filteredOn(ResolvedModuleReadField::platformManaged)
                .extracting(ResolvedModuleReadField::fieldName)
                .containsExactly("title", "enabled");
    }

    @Test
    void shouldRejectStaticDefinitionFieldOutsideModelFacts() {
        ModuleUiDefinition uiDefinition = listPage("iam.employee", list -> list.field("employeeNo").field("ghostField"));

        StaticModuleDefinition definition = staticDefinition(uiDefinition);

        assertThatThrownBy(() -> ModuleUiDescriptorCompiler.compile(definition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("iam.employee.default_list.ghostField");
    }

    @Test
    void shouldPublishReferenceOutputsAsStaticReadModelFields() {
        ModuleUiDefinition uiDefinition = listPage("sales.order",
                list -> list.field("orderNo").field("customerTitle").field("customerLevel"));
        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.order", "订单")
                .entities(List.of(new EntityDefinition("order", "sales_order", "Order",
                        List.of(FieldDefinition.string("orderNo", "订单号"),
                                FieldDefinition.string("customerId", "客户")))))
                .uiDefinition(uiDefinition)
                .modelClass(ReferenceOrder.class)
                .build();

        ModuleUiCompilationResult result = ModuleUiDescriptorCompiler.compileModule(definition);

        assertThat(result.readModel().fields()).extracting(ResolvedModuleReadField::fieldName)
                .containsExactly("orderNo", "customerId", "customerTitle", "customerLevel", "tagSummaries");
        assertThat(result.readModel().fields()).filteredOn(ResolvedModuleReadField::platformManaged)
                .extracting(ResolvedModuleReadField::fieldName)
                .containsExactly("customerTitle", "customerLevel", "tagSummaries");
    }

    @Test
    void shouldPublishTheCompleteModuleAliasForStaticReferenceFields() {
        ModuleUiDefinition uiDefinition = editorPage("sales.order", form -> form
                .field("customerId", field -> field.label("客户"))
                .field("tagIds", field -> field.label("标签")));
        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.order", "订单")
                .entities(List.of(new EntityDefinition("order", "sales_order", "Order",
                        List.of(FieldDefinition.string("customerId", "客户"),
                                FieldDefinition.string("tagIds", "标签")))))
                .uiDefinition(uiDefinition)
                .modelClass(ReferenceOrder.class)
                .build();

        List<ResolvedViewFieldDescriptor> fields = ModuleUiDescriptorCompiler.compile(definition).page().detail().editor()
                .fields();

        assertThat(fields.getFirst().reference())
                .satisfies(reference -> {
                    assertThat(reference.targetModuleAlias()).isEqualTo("crm.customer");
                    assertThat(reference.cardinality()).isEqualTo(ReferenceCardinality.ONE);
                    assertThat(reference.titleField()).isEqualTo("customerTitle");
                });
        assertThat(fields.get(1).reference())
                .satisfies(reference -> {
                    assertThat(reference.targetModuleAlias()).isEqualTo("crm.tag");
                    assertThat(reference.cardinality()).isEqualTo(ReferenceCardinality.MANY);
                });
    }

    @Test
    void shouldCompileNavigatorQueryBindingOnlyForItsSingleReferenceField() {
        ModuleUiDefinition uiDefinition = navigatorListPage("sales.order", "crm.customer", "customerId", "customerId",
                "项目", "搜索项目", list -> list.field("orderNo"));
        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.order", "订单")
                .entities(List.of(new EntityDefinition("order", "sales_order", "Order",
                        List.of(FieldDefinition.string("orderNo", "订单号"),
                                FieldDefinition.string("customerId", "项目")))))
                .uiDefinition(uiDefinition)
                .modelClass(ReferenceOrder.class)
                .build();

        ResolvedPageNavigatorLevelDescriptor level = ModuleUiDescriptorCompiler.compile(definition)
                .page().navigator().levels().getFirst();

        assertThat(level.sourceModuleAlias()).isEqualTo("crm.customer");
        assertThat(level.key()).isEqualTo("scope");
        assertThat(level.queryBindings()).containsExactly(
                new ResolvedPageNavigatorQueryBindingDescriptor("customerId", "customerId"));
    }

    @Test
    void shouldPublishTreeNavigatorLevel() {
        ModuleUiDefinition uiDefinition = navigatorListPage("sales.order", "crm.customer", "customerId", "customerId",
                "项目", "搜索项目", list -> list.field("customerId"));
        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.order", "订单")
                .entities(List.of(new EntityDefinition("order", "sales_order", "Order",
                        List.of(FieldDefinition.string("customerId", "项目")))))
                .uiDefinition(uiDefinition)
                .modelClass(ReferenceOrder.class)
                .build();

        assertThat(ModuleUiDescriptorCompiler.compile(definition).page().navigator().levels().getFirst().kind())
                .isEqualTo(PageNavigatorKind.TREE);
    }

    @Test
    void shouldPublishManageableNavigatorWithItsNamedSourceEditor() {
        ModuleUiDefinition definition = ModuleUiDefinition.builder("sales.order")
                .page(PageTemplates.listDetailCard(page -> page
                        .navigator(navigator -> navigator.level("directory", level -> level
                                .tree("sales.directory", "目录", "搜索目录")
                                .manageable("quick_manage")))
                        .list(list -> list.fields(fields -> fields.field("title")))
                        .detail(detail -> detail.editor(editor -> editor.field("title")))
                        .traits(traits -> traits.standardCrud())))
                .build();

        ResolvedPageNavigatorManagementDescriptor management = ModuleUiDescriptorCompiler.compile(definition)
                .page().navigator().levels().getFirst().management();

        assertThat(management).isNotNull();
        assertThat(management.editorSurface()).isEqualTo("quick_manage");
    }

    @Test
    void shouldRejectEmptyNavigator() {
        assertThatThrownBy(() -> ModuleUiDefinition.builder("sales.order")
                .page(PageTemplates.listDetailCard(page -> page
                        .navigator(navigator -> { })
                        .list(list -> list.fields(fields -> { }))
                        .detail(detail -> detail.editor(editor -> { }))
                        .traits(traits -> { })))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("navigator requires at least one level");
    }

    @Test
    void shouldRejectNavigatorBindingWithWrongReferenceTargetOrCardinality() {
        ModuleUiDefinition wrongTarget = navigatorListPage("sales.order", "crm.tag", "customerId", "customerId",
                "标签", null, list -> { });
        ModuleUiDefinition multipleReference = navigatorListPage("sales.order", "crm.tag", "tagIds", "tagIds",
                "标签", null, list -> { });
        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.order", "订单")
                .entities(List.of(new EntityDefinition("order", "sales_order", "Order",
                        List.of(FieldDefinition.string("customerId", "项目"), FieldDefinition.string("tagIds", "标签")))))
                .modelClass(ReferenceOrder.class)
                .build();

        assertThatThrownBy(() -> ModuleUiDescriptorCompiler.compile(definition.toBuilder()
                .uiDefinition(wrongTarget).build()))
                .hasMessageContaining("navigator query reference target must match level source");
        assertThatThrownBy(() -> ModuleUiDescriptorCompiler.compile(definition.toBuilder()
                .uiDefinition(multipleReference).build()))
                .hasMessageContaining("must be a single reference");
    }

    @Test
    void shouldRejectNavigatorChildBindingToAnEarlierLevel() {
        assertThatThrownBy(() -> ModuleUiDefinition.builder("sales.order")
                .page(PageTemplates.listDetailCard(page -> page
                        .navigator(navigator -> navigator
                                .level("organization", level -> level.tree("iam.organization", "组织", null))
                                .level("tenant", level -> level.microList("iam.tenant", "租户", null)
                                        .bindChild("organization", "tenantId")))
                        .list(list -> list.fields(fields -> { }))
                        .detail(detail -> detail.editor(editor -> { }))
                        .traits(traits -> { })))
                .build())
                .hasMessage("navigator child level must follow its parent: tenant -> organization");
    }

    @Test
    void shouldPublishMultiHopReferenceLoadAsStaticReadModelField() {
        ModuleUiDefinition uiDefinition = listPage("sales.order", list -> list.field("orderNo").field("assistantTitle"));
        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.order", "订单")
                .entities(List.of(new EntityDefinition("order", "sales_order", "Order",
                        List.of(FieldDefinition.string("orderNo", "订单号"),
                                FieldDefinition.string("classroomId", "班级")))))
                .uiDefinition(uiDefinition)
                .modelClass(MultiHopReferenceOrder.class)
                .build();

        ModuleUiCompilationResult result = ModuleUiDescriptorCompiler.compileModule(definition);

        assertThat(result.readModel().fields()).extracting(ResolvedModuleReadField::fieldName)
                .containsExactly("orderNo", "classroomId", "assistantTitle");
    }

    @Test
    void shouldCompileTagListOnlyFromAManyTitleReferenceSummary() {
        ModuleUiDefinition uiDefinition = listPage("sales.order",
                list -> list.field("tagSummaries", field -> field.label("标签").tagList()));
        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.order", "订单")
                .entities(List.of(new EntityDefinition("order", "sales_order", "Order",
                        List.of(FieldDefinition.string("tagIds", "标签")))))
                .uiDefinition(uiDefinition)
                .modelClass(ReferenceOrder.class)
                .build();

        ResolvedViewFieldDescriptor field = ModuleUiDescriptorCompiler.compile(definition).page().list().fields()
                .fields().getFirst();

        assertThat(field.referenceSummary()).satisfies(summary -> {
            assertThat(summary.sourceField()).isEqualTo("tagIds");
            assertThat(summary.targetModuleAlias()).isEqualTo("crm.tag");
            assertThat(summary.cardinality()).isEqualTo(ReferenceCardinality.MANY);
            assertThat(summary.fields()).containsExactly("title", "color");
        });
    }

    @Test
    void shouldRejectTagListWithoutATitleReferenceSummary() {
        ModuleUiDefinition uiDefinition = listPage("sales.order",
                list -> list.field("tagSummaries", field -> field.tagList()));
        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.order", "订单")
                .entities(List.of(new EntityDefinition("order", "sales_order", "Order",
                        List.of(FieldDefinition.string("tagIds", "标签")))))
                .uiDefinition(uiDefinition)
                .modelClass(IdOnlySummaryOrder.class)
                .build();

        assertThatThrownBy(() -> ModuleUiDescriptorCompiler.compile(definition))
                .hasMessageContaining("tagList reference summary must include title");
    }

    @Test
    void shouldRejectTagListBoundToARawReferenceField() {
        ModuleUiDefinition uiDefinition = listPage("sales.order", list -> list.field("tagIds", field -> field.tagList()));
        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.order", "订单")
                .entities(List.of(new EntityDefinition("order", "sales_order", "Order",
                        List.of(FieldDefinition.string("tagIds", "标签")))))
                .uiDefinition(uiDefinition)
                .modelClass(ReferenceOrder.class)
                .build();

        assertThatThrownBy(() -> ModuleUiDescriptorCompiler.compile(definition))
                .hasMessageContaining("tagList UI field must be a structured reference summary");
    }

    @Test
    void shouldRejectTagListOutsideListViewsUntilOtherRunnersSupportIt() {
        ModuleUiDefinition uiDefinition = editorPage("sales.order", form -> form.field("tagSummaries", field -> field.tagList()));
        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.order", "订单")
                .entities(List.of(new EntityDefinition("order", "sales_order", "Order",
                        List.of(FieldDefinition.string("tagIds", "标签")))))
                .uiDefinition(uiDefinition)
                .modelClass(ReferenceOrder.class)
                .build();

        assertThatThrownBy(() -> ModuleUiDescriptorCompiler.compile(definition))
                .hasMessageContaining("tagList UI field is only supported in LIST views");
    }

    @Test
    void shouldAllowTagListWithTitleOnlyReferenceSummary() {
        ModuleUiDefinition uiDefinition = listPage("sales.order",
                list -> list.field("tagSummaries", field -> field.tagList()));
        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.order", "订单")
                .entities(List.of(new EntityDefinition("order", "sales_order", "Order",
                        List.of(FieldDefinition.string("tagIds", "标签")))))
                .uiDefinition(uiDefinition)
                .modelClass(TitleOnlySummaryOrder.class)
                .build();

        assertThat(ModuleUiDescriptorCompiler.compile(definition).page().list().fields().fields().getFirst()
                .referenceSummary().fields()).containsExactly("title");
    }

    @Test
    void shouldRejectTagListForDynamicDescriptorUntilDynamicReferenceSummariesExist() {
        ModuleUiDefinition uiDefinition = listPage("sales.order",
                list -> list.field("tagSummaries", field -> field.tagList()));

        assertThatThrownBy(() -> ModuleUiDescriptorCompiler.compile(uiDefinition, ModuleKind.DYNAMIC, "订单",
                java.util.Map.of(), java.util.Map.of(), null))
                .hasMessageContaining("tagList UI field must be a structured reference summary");
    }

    @Test
    void shouldRejectChildResourceFormWhenRelationIsOutsideModelFacts() {
        assertThatThrownBy(() -> ModuleUiDefinition.builder("iam.position_category")
                .page(emptyEditorPage())
                .editorContribution("position", form -> form
                        .field("position", "code", field -> field.label("岗位编码"))
                        .field("ghost", "code", field -> field.label("错误资源")))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("editor contribution fields must be qualified by resource: position");
    }

    @Test
    void shouldRejectChildResourceFormWhenFieldIsOutsideRelationModelFacts() {
        ModuleUiDefinition uiDefinition = ModuleUiDefinition.builder("iam.position_category")
                .page(emptyEditorPage())
                .editorContribution("position", form -> form
                        .field("position", "ghostField", field -> field.label("错误字段")))
                .build();

        StaticModuleDefinition definition = staticDefinition(uiDefinition, positionEntities());

        assertThatThrownBy(() -> ModuleUiDescriptorCompiler.compile(definition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("static module UI field is not declared by model facts")
                .hasMessageContaining("iam.position_category.position_default_form.position.ghostField");
    }

    private StaticModuleDefinition staticDefinition(ModuleUiDefinition uiDefinition) {
        return staticDefinition(uiDefinition, employeeEntities());
    }

    private ModuleUiDefinition editorPage(String moduleAlias, java.util.function.Consumer<ViewDefinition.Builder> editor) {
        return ModuleUiDefinition.builder(moduleAlias)
                .page(flatManagementEditorPage(editor))
                .build();
    }

    private FlatManagementPageDefinition emptyEditorPage() {
        return flatManagementEditorPage(editor -> { });
    }

    private FlatManagementPageDefinition flatManagementEditorPage(
            java.util.function.Consumer<ViewDefinition.Builder> editor) {
        return PageTemplates.flatManagement(page -> page
                .explorer(explorer -> explorer.title("记录"))
                .detail(detail -> detail.editor(editor))
                .traits(traits -> { }));
    }

    private ModuleUiDefinition listPage(String moduleAlias, java.util.function.Consumer<ViewDefinition.Builder> list) {
        return ModuleUiDefinition.builder(moduleAlias)
                .page(PageTemplates.listDetailCard(page -> page
                        .list(slot -> slot.fields(list))
                        .detail(detail -> detail.editor(editor -> { }))
                        .traits(traits -> { })))
                .build();
    }

    private ModuleUiDefinition navigatorListPage(String moduleAlias, String sourceModuleAlias, String bindingField,
                                              String queryCriteriaKey, String title, String searchPlaceholder,
                                              java.util.function.Consumer<ViewDefinition.Builder> list) {
        return ModuleUiDefinition.builder(moduleAlias)
                .page(PageTemplates.listDetailCard(page -> page
                        .navigator(navigator -> navigator.level("scope", level -> level
                                .tree(sourceModuleAlias, title, searchPlaceholder)
                                .bindQuery(bindingField, queryCriteriaKey)))
                        .list(slot -> slot.fields(list))
                        .detail(detail -> detail.editor(editor -> { }))
                        .traits(traits -> { })))
                .build();
    }

    private StaticModuleDefinition staticDefinition(ModuleUiDefinition uiDefinition, List<EntityDefinition> entities) {
        return StaticModuleDefinition.builder("iam", uiDefinition.moduleAlias(), "职员管理")
                       .parentModuleAlias(null)
                       .entry(ModuleEntryType.ROUTE, "/iam/employees", null)
                       .capabilities(Set.of(EntityCapability.CRUD))
                       .actions(List.of())
                       .entities(entities)
                       .uiDefinition(uiDefinition)
                       .build();
    }

    private List<EntityDefinition> employeeEntities() {
        return List.of(new EntityDefinition(
                        "employee",
                        "iam_employee",
                        "Employee",
                        List.of(
                                FieldDefinition.string("employeeNo", "职员编号"),
                                FieldDefinition.string("mobile", "手机号")
                        )
                )
        );
    }

    private List<EntityDefinition> positionEntities() {
        return List.of(
                new EntityDefinition(
                        "position_category",
                        "iam_position_category",
                        "PositionCategory",
                        List.of(FieldDefinition.string("code", "分类编码"))
                ),
                new EntityDefinition(
                        "position",
                        "iam_position",
                        "Position",
                        List.of(
                                FieldDefinition.string("categoryId", "所属分类"),
                                FieldDefinition.string("code", "岗位编码")
                        )
                )
        );
    }

    private static class OptionEmployee {
        @DictionaryField(source = "iam.gender")
        private String gender;

        @OptionLoad(source = "gender")
        private String genderTitle;
    }

    private static final class CustomerRecord extends StandardEntity {
        @TitleField
        private String displayName;
    }

    private static final class ReferenceOrder {
        @ReferenceTo(moduleAlias = "crm", entityAlias = "customer")
        private String customerId;

        @ReferenceTo(moduleAlias = "crm", entityAlias = "tag", cardinality = ReferenceCardinality.MANY)
        private Set<String> tagIds;

        @ReferenceLoad(source = "customerId", field = "title")
        private transient String customerTitle;

        @ReferenceLoad(source = "customerId", field = "level")
        private transient String customerLevel;

        @ReferenceSummary(source = "tagIds", fields = {"title", "color"})
        private transient List<java.util.Map<String, Object>> tagSummaries;
    }

    private static final class IdOnlySummaryOrder {
        @ReferenceTo(moduleAlias = "crm", entityAlias = "tag", cardinality = ReferenceCardinality.MANY)
        private Set<String> tagIds;

        @ReferenceSummary(source = "tagIds", fields = {"id"})
        private transient List<java.util.Map<String, Object>> tagSummaries;
    }

    private static final class TitleOnlySummaryOrder {
        @ReferenceTo(moduleAlias = "crm", entityAlias = "tag", cardinality = ReferenceCardinality.MANY)
        private Set<String> tagIds;

        @ReferenceSummary(source = "tagIds", fields = "title")
        private transient List<java.util.Map<String, Object>> tagSummaries;
    }

    private static final class MultiHopReferenceOrder {
        @ReferenceTo(moduleAlias = "education.school", entityAlias = "classroom")
        private String classroomId;

        @ReferenceLoad(source = "classroomId", hops = @ReferenceHop(target = AssistantService.class, via = "assistantId"))
        private transient String assistantTitle;
    }

    public static final class AssistantService {
        public static final String MODULE_ALIAS = "education.school.assistant";
    }
}
