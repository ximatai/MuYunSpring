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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleUiDescriptorCompilerTest {
    @Test
    void shouldCompileUiDefinitionToResolvedDescriptorWithoutPhysicalReadDetails() {
        ModuleUiDefinition definition = ModuleUiDefinition.builder("iam.employee")
                .listView(list -> list
                        .title("职员列表")
                        .field("employeeNo", field -> field.label("职员编号").width("160px").maxDisplayLines(2))
                        .field("enabled", field -> field.label("状态").uiType("enabledStatus").align("center"))
                        .field("online", field -> field.label("在线状态").booleanStatus("在线", "离线")))
                .formView(form -> form
                        .title("职员档案")
                        .field("organizationId", field -> field.label("所属机构").required().readOnly())
                        .field("departmentId", field -> field.label("所属部门").required().uiType("recordPicker").columnSpan(2)))
                .build();

        ResolvedModuleUiDescriptor descriptor = ModuleUiDescriptorCompiler.compile(definition);

        assertThat(descriptor.moduleAlias()).isEqualTo("iam.employee");
        assertThat(descriptor.schemaVersion()).isEqualTo(ResolvedModuleUiDescriptor.SCHEMA_VERSION);
        assertThat(descriptor.views()).hasSize(2);
        assertThat(descriptor.views()).filteredOn(view -> view.viewCode().equals("default_list"))
                .singleElement()
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
        assertThat(descriptor.views()).filteredOn(view -> view.viewCode().equals("default_form"))
                .singleElement()
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
        ModuleUiDefinition writableDefinition = ModuleUiDefinition.builder("iam.employee")
                .formView(form -> form.field("online", field -> field.booleanStatus("在线", "离线")))
                .build();
        ModuleUiDefinition readOnlyDefinition = ModuleUiDefinition.builder("iam.employee")
                .formView(form -> form.field("online", field -> field.booleanStatus("在线", "离线").readOnly()))
                .build();

        assertThatThrownBy(() -> ModuleUiDescriptorCompiler.compile(writableDefinition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("booleanStatus UI field must be read-only in FORM views: online");
        assertThatCode(() -> ModuleUiDescriptorCompiler.compile(readOnlyDefinition))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldCompileStaticDefinitionWhenUiFieldsExistInModelFacts() {
        ModuleUiDefinition uiDefinition = ModuleUiDefinition.builder("iam.employee")
                .listView(list -> list
                        .field("employeeNo")
                        .field("title")
                        .field("enabled"))
                .build();

        ResolvedModuleUiDescriptor descriptor = ModuleUiDescriptorCompiler.compile(staticDefinition(uiDefinition));

        assertThat(descriptor.moduleKind()).isEqualTo(net.ximatai.muyun.spring.platform.module.ModuleKind.STATIC);
        assertThat(descriptor.title()).isEqualTo("职员管理");
        assertThat(descriptor.views()).singleElement()
                .satisfies(view -> assertThat(view.fields()).extracting(field -> field.fieldRef().fieldName())
                        .containsExactly("employeeNo", "title", "enabled"));
    }

    @Test
    void shouldPublishStaticAuditFieldTypeWithoutRequiringQueryCapability() {
        ModuleUiDefinition uiDefinition = ModuleUiDefinition.builder("iam.employee")
                .listView(list -> list.field("createdAt", field -> field.label("创建时间")))
                .build();

        ResolvedViewFieldDescriptor field = ModuleUiDescriptorCompiler.compile(staticDefinition(uiDefinition))
                .views().getFirst().fields().getFirst();

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
                .uiDefinition(ModuleUiDefinition.builder("demo.brand")
                        .formView(form -> form
                                .field("showTitleArea", field -> field.label("展示标题区"))
                                .field("enabled", field -> field.label("启用状态")))
                        .build())
                .build();

        List<ResolvedViewFieldDescriptor> fields = ModuleUiDescriptorCompiler.compile(definition)
                .views().getFirst().fields();

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
                .uiDefinition(ModuleUiDefinition.builder("demo.brand")
                        .formView(form -> form.group("branding", "品牌配置", "配置标题和 Logo", group -> group
                                .field("showTitleArea", field -> { })))
                        .build())
                .build();

        ResolvedFormGroupDescriptor group = ModuleUiDescriptorCompiler.compile(definition)
                .views().getFirst().formGroups().getFirst();

        assertThat(group).isEqualTo(new ResolvedFormGroupDescriptor("branding", "品牌配置", "配置标题和 Logo",
                List.of(ViewFieldRef.main("showTitleArea"))));
    }

    @Test
    void shouldCompileFileSizeAsTypedPresentationWithoutChangingLongValueType() {
        StaticModuleDefinition definition = StaticModuleDefinition.builder("mr", "mr.knowledge_file", "知识文件")
                .entities(List.of(new EntityDefinition("knowledge_file", "mr_knowledge_file", "Knowledge File",
                        List.of(FieldDefinition.longInteger("fileSize", "文件大小")))))
                .uiDefinition(ModuleUiDefinition.builder("mr.knowledge_file")
                        .listView(list -> list.field("fileSize", ViewFieldDefinition.Builder::fileSize))
                        .build())
                .build();

        ResolvedViewFieldDescriptor field = ModuleUiDescriptorCompiler.compile(definition)
                .views().getFirst().fields().getFirst();

        assertThat(field.valueType()).isEqualTo(FieldValueType.LONG);
        assertThat(field.valuePresentation()).isEqualTo(FieldValuePresentation.FILE_SIZE);
        assertThat(field.uiType()).isNull();
    }

    @Test
    void shouldRejectFileSizePresentationForNonLongOrWritableFormFields() {
        StaticModuleDefinition wrongType = StaticModuleDefinition.builder("demo", "demo.asset", "资产")
                .entities(List.of(new EntityDefinition("asset", "demo_asset", "Asset",
                        List.of(FieldDefinition.string("fileSize", "文件大小")))))
                .uiDefinition(ModuleUiDefinition.builder("demo.asset")
                        .listView(list -> list.field("fileSize", ViewFieldDefinition.Builder::fileSize))
                        .build())
                .build();
        StaticModuleDefinition writableForm = StaticModuleDefinition.builder("demo", "demo.asset", "资产")
                .entities(List.of(new EntityDefinition("asset", "demo_asset", "Asset",
                        List.of(FieldDefinition.longInteger("fileSize", "文件大小")))))
                .uiDefinition(ModuleUiDefinition.builder("demo.asset")
                        .formView(form -> form.field("fileSize", ViewFieldDefinition.Builder::fileSize))
                        .build())
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
        assertThat(result.uiDescriptor().views()).isEmpty();
        assertThat(result.uiDescriptor().actions()).isEmpty();
    }

    @Test
    void shouldCompileStaticOptionFieldAsResolvedFieldFact() {
        ModuleUiDefinition uiDefinition = ModuleUiDefinition.builder("iam.employee")
                .formView(form -> form.field("gender", field -> field.label("性别")))
                .build();
        StaticModuleDefinition definition = StaticModuleDefinition.builder("iam", "iam.employee", "职员管理")
                .entities(List.of(new EntityDefinition("employee", "iam_employee", "Employee",
                        List.of(FieldDefinition.string("gender", "性别")))))
                .uiDefinition(uiDefinition)
                .modelClass(OptionEmployee.class)
                .build();

        ResolvedViewFieldDescriptor field = ModuleUiDescriptorCompiler.compile(definition).views().getFirst()
                .fields().getFirst();

        assertThat(field.option()).isNotNull();
        assertThat(field.option().binding().sourceType()).isEqualTo("dictionary");
        assertThat(field.option().binding().source()).isEqualTo("iam.gender");
        assertThat(field.option().selectionMode().name()).isEqualTo("SINGLE");
        assertThat(field.option().titleField()).isEqualTo("genderTitle");
    }

    @Test
    void shouldCompileStaticDefinitionReadModelFromLogicalFieldFacts() {
        ModuleUiDefinition uiDefinition = ModuleUiDefinition.builder("iam.employee")
                .listView(list -> list
                        .field("employeeNo")
                        .field("title")
                        .field("enabled"))
                .build();

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
        ModuleUiDefinition uiDefinition = ModuleUiDefinition.builder("iam.employee")
                .listView(list -> list.field("employeeNo").field("ghostField"))
                .build();

        StaticModuleDefinition definition = staticDefinition(uiDefinition);

        assertThatThrownBy(() -> ModuleUiDescriptorCompiler.compile(definition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("iam.employee.default_list.ghostField");
    }

    @Test
    void shouldPublishReferenceOutputsAsStaticReadModelFields() {
        ModuleUiDefinition uiDefinition = ModuleUiDefinition.builder("sales.order")
                .listView(list -> list.field("orderNo").field("customerTitle").field("customerLevel"))
                .build();
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
        ModuleUiDefinition uiDefinition = ModuleUiDefinition.builder("sales.order")
                .formView(form -> form
                        .field("customerId", field -> field.label("客户"))
                        .field("tagIds", field -> field.label("标签")))
                .build();
        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.order", "订单")
                .entities(List.of(new EntityDefinition("order", "sales_order", "Order",
                        List.of(FieldDefinition.string("customerId", "客户"),
                                FieldDefinition.string("tagIds", "标签")))))
                .uiDefinition(uiDefinition)
                .modelClass(ReferenceOrder.class)
                .build();

        List<ResolvedViewFieldDescriptor> fields = ModuleUiDescriptorCompiler.compile(definition).views().getFirst()
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
    void shouldCompileScopedListWorkspaceOnlyForItsSingleReferenceField() {
        ModuleUiDefinition uiDefinition = ModuleUiDefinition.builder("sales.order")
                .listView(list -> list.field("orderNo")
                        .scopedListWorkspace("crm.customer", "customerId", "customerId", "项目", "搜索项目", false,
                                ScopedListWorkspaceCreatePolicy.REQUIRE_SCOPE))
                .build();
        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.order", "订单")
                .entities(List.of(new EntityDefinition("order", "sales_order", "Order",
                        List.of(FieldDefinition.string("orderNo", "订单号"),
                                FieldDefinition.string("customerId", "项目")))))
                .uiDefinition(uiDefinition)
                .modelClass(ReferenceOrder.class)
                .build();

        ResolvedScopedListWorkspaceDescriptor workspace = ModuleUiDescriptorCompiler.compile(definition)
                .views().getFirst().scopedListWorkspace();

        assertThat(workspace.scopeModuleAlias()).isEqualTo("crm.customer");
        assertThat(workspace.scopeField()).isEqualTo("customerId");
        assertThat(workspace.queryCriteriaKey()).isEqualTo("customerId");
        assertThat(workspace.showScopeItemSubtitle()).isFalse();
        assertThat(workspace.manageScopeTree()).isFalse();
        assertThat(workspace.createPolicy()).isEqualTo(ScopedListWorkspaceCreatePolicy.REQUIRE_SCOPE);
    }

    @Test
    void shouldPublishManageableTreeScopeOnlyWhenExplicitlyEnabled() {
        ModuleUiDefinition uiDefinition = ModuleUiDefinition.builder("sales.order")
                .listView(list -> list.field("customerId")
                        .scopedListWorkspace("crm.customer", "customerId", "项目", "搜索项目")
                        .manageableScopedTree())
                .build();
        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.order", "订单")
                .entities(List.of(new EntityDefinition("order", "sales_order", "Order",
                        List.of(FieldDefinition.string("customerId", "项目")))))
                .uiDefinition(uiDefinition)
                .modelClass(ReferenceOrder.class)
                .build();

        assertThat(ModuleUiDescriptorCompiler.compile(definition).views().getFirst().scopedListWorkspace()
                .manageScopeTree()).isTrue();
    }

    @Test
    void shouldRejectManageableTreeScopeWithoutWorkspace() {
        assertThatThrownBy(() -> ModuleUiDefinition.builder("sales.order")
                .listView(ViewDefinition.Builder::manageableScopedTree))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("scopedListWorkspace must be configured before manageableScopedTree");
    }

    @Test
    void shouldRejectScopedListWorkspaceWithWrongReferenceTargetOrCardinality() {
        ModuleUiDefinition wrongTarget = ModuleUiDefinition.builder("sales.order")
                .listView(list -> list.scopedListWorkspace("crm.tag", "customerId", "标签", null))
                .build();
        ModuleUiDefinition multipleReference = ModuleUiDefinition.builder("sales.order")
                .listView(list -> list.scopedListWorkspace("crm.tag", "tagIds", "标签", null))
                .build();
        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.order", "订单")
                .entities(List.of(new EntityDefinition("order", "sales_order", "Order",
                        List.of(FieldDefinition.string("customerId", "项目"), FieldDefinition.string("tagIds", "标签")))))
                .modelClass(ReferenceOrder.class)
                .build();

        assertThatThrownBy(() -> ModuleUiDescriptorCompiler.compile(definition.toBuilder()
                .uiDefinition(wrongTarget).build()))
                .hasMessageContaining("reference target must match scope module");
        assertThatThrownBy(() -> ModuleUiDescriptorCompiler.compile(definition.toBuilder()
                .uiDefinition(multipleReference).build()))
                .hasMessageContaining("must be a single reference");
    }

    @Test
    void shouldAllowUnscopedCreationByDefaultForListViewWorkspaceDsl() {
        ModuleUiDefinition uiDefinition = ModuleUiDefinition.builder("sales.order")
                .listView(list -> list.scopedListWorkspace("crm.customer", "customerId", "项目", "搜索项目"))
                .build();
        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.order", "订单")
                .entities(List.of(new EntityDefinition("order", "sales_order", "Order",
                        List.of(FieldDefinition.string("customerId", "项目")))))
                .uiDefinition(uiDefinition)
                .modelClass(ReferenceOrder.class)
                .build();

        assertThat(ModuleUiDescriptorCompiler.compile(definition).views().getFirst().scopedListWorkspace().createPolicy())
                .isEqualTo(ScopedListWorkspaceCreatePolicy.ALLOW_UNSCOPED);
    }

    @Test
    void shouldPublishMultiHopReferenceLoadAsStaticReadModelField() {
        ModuleUiDefinition uiDefinition = ModuleUiDefinition.builder("sales.order")
                .listView(list -> list.field("orderNo").field("assistantTitle"))
                .build();
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
        ModuleUiDefinition uiDefinition = ModuleUiDefinition.builder("sales.order")
                .listView(list -> list.field("tagSummaries", field -> field.label("标签").tagList()))
                .build();
        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.order", "订单")
                .entities(List.of(new EntityDefinition("order", "sales_order", "Order",
                        List.of(FieldDefinition.string("tagIds", "标签")))))
                .uiDefinition(uiDefinition)
                .modelClass(ReferenceOrder.class)
                .build();

        ResolvedViewFieldDescriptor field = ModuleUiDescriptorCompiler.compile(definition).views().getFirst()
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
        ModuleUiDefinition uiDefinition = ModuleUiDefinition.builder("sales.order")
                .listView(list -> list.field("tagSummaries", field -> field.tagList()))
                .build();
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
        ModuleUiDefinition uiDefinition = ModuleUiDefinition.builder("sales.order")
                .listView(list -> list.field("tagIds", field -> field.tagList()))
                .build();
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
        ModuleUiDefinition uiDefinition = ModuleUiDefinition.builder("sales.order")
                .formView(form -> form.field("tagSummaries", field -> field.tagList()))
                .build();
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
        ModuleUiDefinition uiDefinition = ModuleUiDefinition.builder("sales.order")
                .listView(list -> list.field("tagSummaries", field -> field.tagList()))
                .build();
        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.order", "订单")
                .entities(List.of(new EntityDefinition("order", "sales_order", "Order",
                        List.of(FieldDefinition.string("tagIds", "标签")))))
                .uiDefinition(uiDefinition)
                .modelClass(TitleOnlySummaryOrder.class)
                .build();

        assertThat(ModuleUiDescriptorCompiler.compile(definition).views().getFirst().fields().getFirst()
                .referenceSummary().fields()).containsExactly("title");
    }

    @Test
    void shouldRejectTagListForDynamicDescriptorUntilDynamicReferenceSummariesExist() {
        ModuleUiDefinition uiDefinition = ModuleUiDefinition.builder("sales.order")
                .listView(list -> list.field("tagSummaries", field -> field.tagList()))
                .build();

        assertThatThrownBy(() -> ModuleUiDescriptorCompiler.compile(uiDefinition, ModuleKind.DYNAMIC, "订单",
                java.util.Map.of(), java.util.Map.of(), null))
                .hasMessageContaining("tagList UI field must be a structured reference summary");
    }

    @Test
    void shouldRejectChildResourceFormWhenRelationIsOutsideModelFacts() {
        ModuleUiDefinition uiDefinition = ModuleUiDefinition.builder("iam.position_category")
                .formView(ModuleUiViewCodes.childResourceDefaultForm("position"), form -> form
                        .field("position", "code", field -> field.label("岗位编码"))
                        .field("ghost", "code", field -> field.label("错误资源")))
                .build();

        StaticModuleDefinition definition = staticDefinition(uiDefinition, positionEntities());

        assertThatThrownBy(() -> ModuleUiDescriptorCompiler.compile(definition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("static module UI relation is not declared by model facts")
                .hasMessageContaining("iam.position_category.position_default_form.ghost");
    }

    @Test
    void shouldRejectChildResourceFormWhenFieldIsOutsideRelationModelFacts() {
        ModuleUiDefinition uiDefinition = ModuleUiDefinition.builder("iam.position_category")
                .formView(ModuleUiViewCodes.childResourceDefaultForm("position"), form -> form
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
