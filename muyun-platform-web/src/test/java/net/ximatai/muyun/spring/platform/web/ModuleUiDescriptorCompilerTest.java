package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.option.DictionaryField;
import net.ximatai.muyun.spring.common.option.OptionLoad;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.model.title.TitleField;
import net.ximatai.muyun.spring.iam.department.Department;
import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.ability.reference.ReferenceHop;
import net.ximatai.muyun.spring.ability.reference.ReferenceLoad;
import net.ximatai.muyun.spring.ability.reference.ReferenceSummary;
import net.ximatai.muyun.spring.ability.reference.ReferenceTo;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.platform.module.ModuleEntryType;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControl;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlBinding;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlProperty;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlValueShape;
import net.ximatai.muyun.spring.dynamic.metadata.ViewControlType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleUiDescriptorCompilerTest {
    @Test
    void shouldRejectTreeResourceThatRepeatsThePageMainEntity() {
        ModuleUiDefinition ui = ModuleUiDefinition.builder("demo.menu")
                .page(PageTemplates.treeManagement(page -> page
                        .navigator(navigator -> navigator.level("scheme", level -> level
                                .microList("demo.menu_scheme", "菜单方案", "搜索菜单方案")))
                        .treeResource("menu", "scheme", "schemeId", resource -> { })
                        .detail(detail -> detail.editor(editor -> editor.field("schemeId")))))
                .build();
        StaticModuleDefinition definition = StaticModuleDefinition.builder("demo", "demo.menu", "菜单")
                .entities(List.of(new EntityDefinition("menu", "demo_menu", "Menu",
                        List.of(FieldDefinition.string("schemeId", "菜单方案")))))
                .uiDefinition(ui)
                .build();

        assertThatThrownBy(() -> ModuleUiDescriptorCompiler.compile(definition))
                .hasMessageContaining("must be a contributed resource, not the page main entity");
    }

    @Test
    void shouldCompileAnExplicitOneReferenceSelectionProjectionForWebUiFormula() {
        ModuleUiDefinition definition = ModuleUiDefinition.builder("platform.menu")
                .page(PageTemplates.flatManagement(page -> page.explorer(explorer -> explorer.title("菜单"))
                        .detail(detail -> detail.editor(editor -> editor
                                .field("moduleAlias")
                                .field("pageMode", field -> field.visible(UiRule.formula(
                                        UiFormula.booleanExpression("{moduleAlias.entryType} == 'MODULE'"))))))))
                .build();
        ResolvedReferenceFieldDescriptor module = new ResolvedReferenceFieldDescriptor("platform.module",
                ReferenceCardinality.ONE, null, ReferencePickerMode.LIST, ReferenceCandidateDelivery.SOURCE_FIELD,
                "/platform.menu/references/moduleAlias/resolve", List.of(),
                List.of(new ResolvedReferenceSelectionProjectionDescriptor("entryType")));

        ResolvedViewFieldDescriptor field = ModuleUiDescriptorCompiler.compile(definition, ModuleKind.STATIC, "菜单",
                Map.of(), Map.of("moduleAlias", module), null, Map.of()).page().detail().editor().fields().get(1);

        assertThat(field.visible().formula().program().referencedFields()).containsExactly("moduleAlias.entryType");
    }

    @Test
    void shouldRejectWebUiReferenceFormulaThatIsNotExplicitlyAuthorised() {
        ModuleUiDefinition definition = ModuleUiDefinition.builder("platform.menu")
                .page(PageTemplates.flatManagement(page -> page.explorer(explorer -> explorer.title("菜单"))
                        .detail(detail -> detail.editor(editor -> editor
                                .field("moduleAlias")
                                .field("pageMode", field -> field.visible(UiRule.formula(
                                        UiFormula.booleanExpression("{moduleAlias.entryType} == 'MODULE'"))))))))
                .build();
        ResolvedReferenceFieldDescriptor module = new ResolvedReferenceFieldDescriptor("platform.module",
                ReferenceCardinality.ONE);

        assertThatThrownBy(() -> ModuleUiDescriptorCompiler.compile(definition, ModuleKind.STATIC, "菜单", Map.of(),
                Map.of("moduleAlias", module), null, Map.of()))
                .hasMessageContaining("not an explicitly authorised reference selection projection");
    }

    @Test
    void shouldRejectManyButCompileExplicitMultiHopWebUiReferenceFormulaPaths() {
        ModuleUiDefinition many = ModuleUiDefinition.builder("platform.menu")
                .page(PageTemplates.flatManagement(page -> page.explorer(explorer -> explorer.title("菜单"))
                        .detail(detail -> detail.editor(editor -> editor
                                .field("moduleAliases")
                                .field("pageMode", field -> field.visible(UiRule.formula(
                                        UiFormula.booleanExpression("{moduleAliases.entryType} == 'MODULE'"))))))))
                .build();
        ResolvedReferenceFieldDescriptor references = new ResolvedReferenceFieldDescriptor("platform.module",
                ReferenceCardinality.MANY, null, ReferencePickerMode.LIST, ReferenceCandidateDelivery.SOURCE_FIELD,
                null, List.of(), List.of(new ResolvedReferenceSelectionProjectionDescriptor("entryType")));
        assertThatThrownBy(() -> ModuleUiDescriptorCompiler.compile(many, ModuleKind.STATIC, "菜单", Map.of(),
                Map.of("moduleAliases", references), null, Map.of()))
                .hasMessageContaining("requires a ONE reference");

        ModuleUiDefinition multiHop = ModuleUiDefinition.builder("platform.menu")
                .page(PageTemplates.flatManagement(page -> page.explorer(explorer -> explorer.title("菜单"))
                        .detail(detail -> detail.editor(editor -> editor
                                .field("moduleAlias")
                                .field("pageMode", field -> field.visible(UiRule.formula(
                                        UiFormula.booleanExpression("{moduleAlias.ownerId.regionCode} == 'CN'"))))))))
                .build();
        ResolvedReferenceFieldDescriptor module = new ResolvedReferenceFieldDescriptor("platform.module",
                ReferenceCardinality.ONE, null, ReferencePickerMode.LIST, ReferenceCandidateDelivery.SOURCE_FIELD,
                null, List.of(), List.of(new ResolvedReferenceSelectionProjectionDescriptor(
                List.of("ownerId", "regionCode"))));
        assertThatCode(() -> ModuleUiDescriptorCompiler.compile(multiHop, ModuleKind.STATIC, "菜单", Map.of(),
                Map.of("moduleAlias", module), null, Map.of())).doesNotThrowAnyException();
    }

    @Test
    void shouldCompilePersistentListQueryControlAsUiState() {
        ResolvedPageListPersistentQueryControlDescriptor persistentControl = ModuleUiDescriptorCompiler.compile(
                ModuleUiDefinition.builder("sales.order")
                        .page(PageTemplates.listDetailCard(page -> page
                                .list(list -> list.fields(fields -> fields.field("code", field -> { }))
                                        .persistentQueries(queries -> queries.control("activeOnly", queryControl -> queryControl
                                                .label("仅启用")
                                                .uiType(ViewControlType.SWITCH)
                                                .defaultValue(true))))
                                .detail(detail -> detail.editor(editor -> editor.field("code", field -> { })))))
                        .build()).page().list().persistentQueryControls().getFirst();

        assertThat(persistentControl).isEqualTo(new ResolvedPageListPersistentQueryControlDescriptor(
                "activeOnly", "仅启用", ViewControlType.SWITCH, true));
    }

    @Test
    void shouldCompileStaticUiTypeToSourceNeutralFieldControlContract() {
        ResolvedViewFieldDescriptor field = ModuleUiDescriptorCompiler.compile(ModuleUiDefinition.builder("sales.order")
                .page(PageTemplates.flatManagement(page -> page.explorer(explorer -> explorer.title("订单"))
                        .detail(detail -> detail.editor(editor -> editor.field("quantity", view -> view.uiType("number"))))))
                .build()).page().detail().editor().fields().getFirst();

        assertThat(field.uiType()).isEqualTo("number");
        assertThat(field.fieldControl()).isEqualTo(new ResolvedFieldControlDescriptor("number", "DECIMAL",
                "SCALAR", Map.of(), List.of()));
    }

    @Test
    void shouldCompileColorPickerForTheStaticFormWhileKeepingReadViewsOnTheirUiType() {
        ResolvedModulePageDescriptor page = ModuleUiDescriptorCompiler.compile(ModuleUiDefinition.builder("mr.tag")
                .page(PageTemplates.listDetailCard(card -> card
                        .list(list -> list.fields(fields -> fields.field("color", field -> field.uiType("colorPicker"))))
                        .detail(detail -> detail
                                .display(display -> display.field("color", field -> field.uiType("colorPicker")))
                                .editor(editor -> editor.field("color", field -> field.uiType("colorPicker"))))))
                .build()).page();

        assertThat(page.detail().editor().fields().getFirst().fieldControl()).isEqualTo(
                new ResolvedFieldControlDescriptor("colorPicker", "COLOR_PICKER", "SCALAR", Map.of(), List.of()));
        assertThat(page.list().fields().fields().getFirst().uiType()).isEqualTo("colorPicker");
        assertThat(page.detail().display().fields().getFirst().uiType()).isEqualTo("colorPicker");
    }

    @Test
    void shouldRejectOverrideWhoseSourceIsNotDeclaredByTheSameForm() {
        ModuleUiDefinition definition = ModuleUiDefinition.builder("platform.module_action")
                .page(PageTemplates.flatManagement(page -> page.explorer(explorer -> explorer.title("模块动作"))
                        .detail(detail -> detail.editor(editor -> editor
                                .field("actionAuthOverride", field -> field.overrideOf("actionAuth"))))))
                .build();

        assertThatThrownBy(() -> ModuleUiDescriptorCompiler.compile(definition, ModuleKind.STATIC, "模块动作",
                Map.of(), Map.of(), null, Map.of(
                        ViewFieldRef.main("actionAuth"), FieldValueType.BOOLEAN,
                        ViewFieldRef.main("actionAuthOverride"), FieldValueType.BOOLEAN)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("override source field must be declared by the same form");
    }

    @Test
    void shouldRejectUnknownUiTypeInsteadOfLeavingBrowserFallback() {
        assertThatThrownBy(() -> ModuleUiDescriptorCompiler.compile(ModuleUiDefinition.builder("sales.order")
                .page(PageTemplates.flatManagement(page -> page.explorer(explorer -> explorer.title("订单"))
                        .detail(detail -> detail.editor(editor -> editor.field("quantity", view -> view.uiType("unsupported"))))))
                .build())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported field control alias: unsupported");
    }

    @Test
    void shouldRejectConfiguredControlWithoutExecutableWebRenderer() {
        FieldUiControl control = new FieldUiControl();
        control.setAlias("period");
        control.setEnabled(Boolean.TRUE);
        control.setRendererType(ViewControlType.DATE);
        control.setValueShape(FieldUiControlValueShape.COMPOSITE);
        FieldUiControlProperty property = new FieldUiControlProperty();
        property.setFieldUiControlAlias("period");
        property.setAttributeAlias("format");
        property.setDefaultValue("YYYY-MM-DD");
        FieldUiControlBinding binding = new FieldUiControlBinding();
        binding.setFieldUiControlAlias("period");
        binding.setValueKey("end");
        binding.setValueFieldSpecAlias("date");

        assertThatThrownBy(() -> FieldControlDescriptorCatalog.fromConfigured(List.of(control), List.of(property), List.of(binding)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no executable web value shape: period.COMPOSITE");
    }

    @Test
    void shouldRejectUnimplementedCompositeDateRangeBeforeStaticModuleStarts() {
        ModuleUiDefinition definition = ModuleUiDefinition.builder("sales.order")
                .page(PageTemplates.flatManagement(page -> page.explorer(explorer -> explorer.title("订单"))
                        .detail(detail -> detail.editor(editor -> editor.field("deliveryPeriod",
                                field -> field.uiType("date_range"))))))
                .build();

        assertThatThrownBy(() -> ModuleUiDescriptorCompiler.compile(definition, ModuleKind.STATIC, "订单", Map.of(),
                Map.of(), null, Map.of(ViewFieldRef.main("deliveryPeriod"), FieldValueType.DATE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported field control alias: date_range");
    }

    @Test
    void shouldNotLeakDynamicControlCatalogIntoSubsequentCompilation() {
        ModuleUiDefinition definition = ModuleUiDefinition.builder("sales.order")
                .page(PageTemplates.flatManagement(page -> page.explorer(explorer -> explorer.title("订单"))
                        .detail(detail -> detail.editor(editor -> editor.field("value", view -> view.uiType("text"))))))
                .build();
        ResolvedFieldControlDescriptor dynamicText = new ResolvedFieldControlDescriptor("text", "JSON", "SCALAR",
                Map.of("dynamic", "true"), List.of());

        assertThat(ModuleUiDescriptorCompiler.compile(definition, ModuleKind.DYNAMIC, "订单", Map.of(), Map.of(),
                null, Map.of(), Map.of("text", dynamicText)).page().detail().editor().fields().getFirst().fieldControl())
                .isEqualTo(dynamicText);
        assertThat(ModuleUiDescriptorCompiler.compile(definition).page().detail().editor().fields().getFirst().fieldControl())
                .isEqualTo(new ResolvedFieldControlDescriptor("text", "TEXT", "SCALAR", Map.of(), List.of()));
    }

    @Test
    void shouldCompileSourceNeutralFormComputeRuleToSignedProgram() {
        ModuleUiDefinition definition = ModuleUiDefinition.builder("sales.order")
                .page(PageTemplates.flatManagement(page -> page
                        .explorer(explorer -> explorer.title("订单"))
                        .detail(detail -> detail.editor(editor -> editor
                                .field("quantity")
                                .field("unitPrice")
                                .field("amount")
                                .formCompute("amountFromQuantity", "amount", List.of("quantity", "unitPrice"),
                                        "{amount} = {quantity} * {unitPrice}")))))
                .build();

        ResolvedFormComputeRuleDescriptor rule = compileFormCompute(definition,
                Map.of("quantity", FieldValueType.DECIMAL, "unitPrice", FieldValueType.DECIMAL,
                        "amount", FieldValueType.DECIMAL)).page().detail()
                .editor().formComputeRules().getFirst();

        assertThat(rule.code()).isEqualTo("amountFromQuantity");
        assertThat(rule.targetField()).isEqualTo("amount");
        assertThat(rule.targetValueType()).isEqualTo(FieldValueType.DECIMAL);
        assertThat(rule.triggerFields()).containsExactly("quantity", "unitPrice");
        assertThat(rule.writePolicy()).isEqualTo(FormComputeWritePolicy.ALWAYS);
        assertThat(rule.program().profile().name()).isEqualTo("FORM_COMPUTE");
        assertThat(rule.program().root().arguments().getFirst().field()).isEqualTo("amount");
    }

    @Test
    void shouldRejectFormComputeRulesOutsideTheirWritableMainFormFields() {
        assertThatThrownBy(() -> compileFormCompute(ModuleUiDefinition.builder("sales.order")
                .page(PageTemplates.flatManagement(page -> page
                        .explorer(explorer -> explorer.title("订单"))
                        .detail(detail -> detail.editor(editor -> editor
                                .field("quantity")
                                .field("amount", field -> field.readOnly())
                                .formCompute("amountFromQuantity", "amount", List.of("quantity"),
                                        "{amount} = {quantity}")))))
                .build(), Map.of("quantity", FieldValueType.DECIMAL, "amount", FieldValueType.DECIMAL)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target field must be writable");

        assertThatThrownBy(() -> compileFormCompute(ModuleUiDefinition.builder("sales.order")
                .page(PageTemplates.flatManagement(page -> page
                        .explorer(explorer -> explorer.title("订单"))
                        .detail(detail -> detail.editor(editor -> editor
                                .field("quantity")
                                .field("amount")
                                .formCompute("amountFromQuantity", "amount", List.of("quantity"),
                                        "{amount} = {amount} + {quantity}")))))
                .build(), Map.of("quantity", FieldValueType.DECIMAL, "amount", FieldValueType.DECIMAL)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot reference itself");

        assertThatThrownBy(() -> compileFormCompute(ModuleUiDefinition.builder("sales.order")
                .page(PageTemplates.flatManagement(page -> page
                        .explorer(explorer -> explorer.title("订单"))
                        .detail(detail -> detail.editor(editor -> editor
                                // JSON has no executable standard editor; make this test exercise
                                // the compute-program type guard rather than the form renderer gate.
                                .field("payload", field -> field.uiType("text"))
                                .field("amount")
                                .formCompute("amountFromPayload", "amount", List.of("payload"),
                                        "{amount} = {payload}")))))
                .build(), Map.of("payload", FieldValueType.JSON, "amount", FieldValueType.DECIMAL)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("portable non-JSON value type");
    }

    private ResolvedModuleUiDescriptor compileFormCompute(ModuleUiDefinition definition,
                                                          Map<String, FieldValueType> fieldTypes) {
        Map<ViewFieldRef, FieldValueType> resolved = fieldTypes.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(entry -> ViewFieldRef.main(entry.getKey()), Map.Entry::getValue));
        return ModuleUiDescriptorCompiler.compile(definition, null, null, Map.of(), Map.of(), null, resolved);
    }

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
    void shouldCompileTreeManagementAsPageRootWithOptionalNavigator() {
        ModuleUiDefinition definition = ModuleUiDefinition.builder("mr.tag")
                .page(PageTemplates.treeManagement(page -> page
                        .navigator(navigator -> navigator
                                .level("tenant", level -> level.microList("iam.tenant", "租户", "搜索租户"))
                                .bindNavigatorToList("tenant", "tenantId"))
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
        assertThat(page.navigator()).satisfies(navigator -> {
            assertThat(navigator.levels()).singleElement().satisfies(level -> {
                assertThat(level.key()).isEqualTo("tenant");
                assertThat(level.kind()).isEqualTo(PageNavigatorKind.MICRO_LIST);
            });
            assertThat(navigator.contextBindings()).containsExactly(
                    new ResolvedPageContextBindingDescriptor(PageContextSource.NAVIGATOR, "tenant",
                            PageContextTarget.LIST_QUERY, "tenantId", null),
                    new ResolvedPageContextBindingDescriptor(PageContextSource.NAVIGATOR, "tenant",
                            PageContextTarget.FORM_DEFAULT, "tenantId", null));
        });
        assertThat(page.list()).isNull();
        assertThat(page.detail().editor().fields()).extracting(field -> field.fieldRef().fieldName())
                .containsExactly("title", "parentId", "color");
        assertThat(page.traits()).containsExactly(PageTrait.STANDARD_CRUD);
    }

    @Test
    void shouldCompileApplicationScopeForTheTemplateOwnedModuleTree() {
        ModuleUiDefinition definition = ModuleUiDefinition.builder("platform.module")
                .page(PageTemplates.treeManagement(page -> page
                        .navigator(navigator -> navigator
                                .level("application", level -> level.microList("platform.application", "应用", null))
                                .bindNavigatorToList("application", "applicationAlias"))
                        .detail(detail -> detail.editor(editor -> editor.field("applicationAlias").field("parentId")))
                        .traits(traits -> traits.standardCrud())))
                .build();

        ResolvedModulePageDescriptor page = ModuleUiDescriptorCompiler.compile(definition, null, null, Map.of(),
                Map.of("applicationAlias", new ResolvedReferenceFieldDescriptor("platform.application",
                        ReferenceCardinality.ONE)), null).page();

        assertThat(page.template()).isEqualTo(ModulePageTemplate.TREE_MANAGEMENT);
        assertThat(page.navigator().levels()).extracting(ResolvedPageNavigatorLevelDescriptor::sourceModuleAlias)
                .containsExactly("platform.application");
        assertThat(page.navigator().contextBindings()).containsExactlyInAnyOrder(
                new ResolvedPageContextBindingDescriptor(PageContextSource.NAVIGATOR, "application",
                        PageContextTarget.LIST_QUERY, "applicationAlias", null),
                new ResolvedPageContextBindingDescriptor(PageContextSource.NAVIGATOR, "application",
                        PageContextTarget.FORM_DEFAULT, "applicationAlias", null));
    }

    @Test
    void shouldCompileListDetailCardWithOptionalTreeNavigator() {
        ModuleUiDefinition definition = ModuleUiDefinition.builder("crm.customer")
                .page(ModulePageDefinition.listDetailCard(page -> page
                        .navigator(navigator -> navigator
                                .level("organization", level -> level.tree("iam.organization", "所属组织", "搜索组织"))
                                .bindNavigatorToList("organization", "organizationId"))
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
            });
            assertThat(navigator.contextBindings()).containsExactly(
                    new ResolvedPageContextBindingDescriptor(PageContextSource.NAVIGATOR, "organization",
                            PageContextTarget.LIST_QUERY, "organizationId", null),
                    new ResolvedPageContextBindingDescriptor(PageContextSource.NAVIGATOR, "organization",
                            PageContextTarget.FORM_DEFAULT, "organizationId", null));
        });
        assertThat(page.list().searchPlaceholder()).isEqualTo("搜索客户");
        assertThat(page.list().fields().fields()).extracting(field -> field.fieldRef().fieldName())
                .containsExactly("code", "title");
        assertThat(page.detail().editor().fields()).extracting(field -> field.fieldRef().fieldName())
                .containsExactly("code", "title");
    }

    @Test
    void shouldCompileNavigatorPickerQueryForADeclaredRecordPicker() {
        ModuleUiDefinition definition = ModuleUiDefinition.builder("iam.department")
                .page(PageTemplates.treeManagement(page -> page
                        .navigator(navigator -> navigator
                                .level("organization", level -> level.tree("iam.organization", "机构", "搜索机构"))
                                .bindNavigatorToList("organization", "organizationId")
                                .bindNavigatorToPickerQuery("organization", "parentId", "organizationId"))
                        .detail(detail -> detail.editor(editor -> editor.field("organizationId").field("parentId")))
                        .traits(traits -> traits.standardCrud())))
                .build();

        ResolvedModulePageDescriptor page = ModuleUiDescriptorCompiler.compile(definition, null, null, Map.of(),
                Map.of("organizationId", new ResolvedReferenceFieldDescriptor("iam.organization", ReferenceCardinality.ONE)),
                null).page();

        assertThat(page.navigator().contextBindings()).contains(
                new ResolvedPageContextBindingDescriptor(PageContextSource.NAVIGATOR, "organization",
                        PageContextTarget.PICKER_QUERY, "organizationId", null, "parentId"));
    }

    @Test
    void shouldRejectNavigatorPickerQueryThatIsNotConsumedByThePageEditor() {
        ModuleUiDefinition definition = ModuleUiDefinition.builder("iam.department")
                .page(PageTemplates.treeManagement(page -> page
                        .navigator(navigator -> navigator
                                .level("organization", level -> level.tree("iam.organization", "机构", "搜索机构"))
                                .bindNavigatorToPickerQuery("organization", "parentId", "organizationId"))
                        .detail(detail -> detail.editor(editor -> editor.field("organizationId")))
                        .traits(traits -> traits.standardCrud())))
                .build();

        assertThatThrownBy(() -> ModuleUiDescriptorCompiler.compile(definition, null, null, Map.of(),
                Map.of("organizationId", new ResolvedReferenceFieldDescriptor("iam.organization", ReferenceCardinality.ONE)),
                null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("picker-query target must be declared by the page editor")
                .hasMessageContaining("page navigator.parentId");
    }

    @Test
    void shouldRejectParentIdPickerQueryOutsideATreeManagementPage() {
        ModuleUiDefinition definition = ModuleUiDefinition.builder("crm.directory")
                .page(PageTemplates.listDetailCard(page -> page
                        .navigator(navigator -> navigator
                                .level("organization", level -> level.tree("iam.organization", "机构", "搜索机构"))
                                .bindNavigatorToPickerQuery("organization", "parentId", "organizationId"))
                        .list(list -> list.fields(fields -> fields.field("title")))
                        .detail(detail -> detail.editor(editor -> editor.field("parentId")))
                        .traits(traits -> traits.standardCrud())))
                .build();

        assertThatThrownBy(() -> ModuleUiDescriptorCompiler.compile(definition, null, null, Map.of(),
                Map.of("organizationId", new ResolvedReferenceFieldDescriptor("iam.organization", ReferenceCardinality.ONE)),
                null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("picker-query target must be a single record reference")
                .hasMessageContaining("page navigator.parentId");
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
                                        .initialSelectionPolicy(PageNavigatorInitialSelectionPolicy.FIRST_RECORD))
                                .level("organization", level -> level.tree("iam.organization", "所属组织", "搜索组织"))
                                .bindNavigatorToList("tenant", "tenantId")
                                .bindNavigatorToNavigator("tenant", "organization", "tenantId")
                                .bindNavigatorToList("organization", "organizationId"))
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
        assertThat(navigator.levels().getFirst().initialSelectionPolicy())
                .isEqualTo(PageNavigatorInitialSelectionPolicy.FIRST_RECORD);
        assertThat(navigator.contextBindings()).containsExactly(
                new ResolvedPageContextBindingDescriptor(PageContextSource.NAVIGATOR, "tenant",
                        PageContextTarget.LIST_QUERY, "tenantId", null),
                new ResolvedPageContextBindingDescriptor(PageContextSource.NAVIGATOR, "tenant",
                        PageContextTarget.FORM_DEFAULT, "tenantId", null),
                new ResolvedPageContextBindingDescriptor(PageContextSource.NAVIGATOR, "tenant",
                        PageContextTarget.NAVIGATOR_QUERY, "tenantId", "organization"),
                new ResolvedPageContextBindingDescriptor(PageContextSource.NAVIGATOR, "organization",
                        PageContextTarget.LIST_QUERY, "organizationId", null),
                new ResolvedPageContextBindingDescriptor(PageContextSource.NAVIGATOR, "organization",
                        PageContextTarget.FORM_DEFAULT, "organizationId", null));
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
    void shouldPublishTreeParentAsSelfReferenceForTheSharedRecordPicker() {
        ModuleUiDefinition uiDefinition = ModuleUiDefinition.builder("iam.organization")
                .page(PageTemplates.treeManagement(page -> page
                        .detail(detail -> detail.editor(editor -> editor
                                .field("parentId", field -> field.label("上级机构").uiType("recordPicker"))))
                        .traits(traits -> traits.standardCrud())))
                .build();
        StaticModuleDefinition definition = StaticModuleDefinition.builder("iam", "iam.organization", "机构管理")
                .capabilities(Set.of(EntityCapability.CRUD, EntityCapability.TREE))
                .uiDefinition(uiDefinition)
                .build();

        ResolvedViewFieldDescriptor field = ModuleUiDescriptorCompiler.compile(definition).page().detail().editor()
                .fields().getFirst();

        assertThat(field.fieldControl().rendererType()).isEqualTo("RECORD_PICKER");
        assertThat(field.reference()).isEqualTo(new ResolvedReferenceFieldDescriptor("iam.organization",
                ReferenceCardinality.ONE, null, ReferencePickerMode.AUTO));
    }

    @Test
    void shouldPublishMultiReferencePickerAsCollectionControl() {
        ModuleUiDefinition uiDefinition = editorPage("sales.order", form -> form
                .field("tagIds", field -> field.label("标签").uiType("recordMultiPicker")));
        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.order", "订单")
                .entities(List.of(new EntityDefinition("order", "sales_order", "Order",
                        List.of(FieldDefinition.string("tagIds", "标签")))))
                .uiDefinition(uiDefinition)
                .modelClass(ReferenceOrder.class)
                .build();

        ResolvedViewFieldDescriptor field = ModuleUiDescriptorCompiler.compile(definition).page().detail().editor()
                .fields().getFirst();

        assertThat(field.fieldControl()).isEqualTo(new ResolvedFieldControlDescriptor("recordMultiPicker",
                "RECORD_PICKER", "COLLECTION", Map.of(), List.of()));
        assertThat(field.reference().cardinality()).isEqualTo(ReferenceCardinality.MANY);
    }

    @Test
    void shouldPublishDepartmentOrganizationReferenceTitleForDetailRendering() {
        ModuleUiDefinition uiDefinition = editorPage("iam.department", form -> form
                .field("organizationId", field -> field.label("所属机构").readOnly()));
        StaticModuleDefinition definition = StaticModuleDefinition.builder("iam", "iam.department", "部门管理")
                .capabilities(Set.of(EntityCapability.CRUD, EntityCapability.TREE))
                .entities(List.of(new EntityDefinition("department", "iam_department", "Department",
                        List.of(FieldDefinition.string("organizationId", "所属机构")))))
                .uiDefinition(uiDefinition)
                .modelClass(Department.class)
                .build();

        ResolvedViewFieldDescriptor field = ModuleUiDescriptorCompiler.compile(definition).page().detail().editor()
                .fields().getFirst();

        assertThat(field.uiType()).isEqualTo("recordPicker");
        assertThat(field.fieldControl().rendererType()).isEqualTo("RECORD_PICKER");
        assertThat(field.reference()).isEqualTo(new ResolvedReferenceFieldDescriptor("iam.organization",
                ReferenceCardinality.ONE, "organizationTitle", ReferencePickerMode.AUTO,
                ReferenceCandidateDelivery.SOURCE_FIELD,
                "/platform.module/iam.department/references/organizationId/resolve"));
    }

    @Test
    void shouldCompileReferencePickerModeFromResolvedTargetFacts() {
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

        List<ResolvedViewFieldDescriptor> fields = ModuleUiDescriptorCompiler.compile(definition,
                        alias -> "crm.tag".equals(alias) ? ReferencePickerMode.TREE : ReferencePickerMode.LIST)
                .page().detail().editor().fields();

        assertThat(fields).extracting(field -> field.reference().pickerMode())
                .containsExactly(ReferencePickerMode.LIST, ReferencePickerMode.TREE);
    }

    @Test
    void shouldSerializeReferencePickerModeAsClientContract() throws Exception {
        String json = new ObjectMapper().writeValueAsString(new ResolvedReferenceFieldDescriptor(
                "crm.category", ReferenceCardinality.ONE, "categoryTitle", ReferencePickerMode.TREE));

        assertThat(new ObjectMapper().readTree(json).path("pickerMode").asText()).isEqualTo("TREE");
        assertThat(new ObjectMapper().readTree(json).path("candidateDelivery").asText())
                .isEqualTo("TARGET_NAVIGATOR");
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
        assertThat(ModuleUiDescriptorCompiler.compile(definition).page().navigator().contextBindings()).containsExactly(
                new ResolvedPageContextBindingDescriptor(PageContextSource.NAVIGATOR, "scope",
                        PageContextTarget.LIST_QUERY, "customerId", null),
                new ResolvedPageContextBindingDescriptor(PageContextSource.NAVIGATOR, "scope",
                        PageContextTarget.FORM_DEFAULT, "customerId", null));
    }

    @Test
    void shouldPublishStaticSelectionBindingsWithoutADeclaredNavigator() {
        ModuleUiDefinition uiDefinition = ModuleUiDefinition.builder("iam.role")
                .page(PageTemplates.listDetailCard(page -> page
                        .list(list -> list.fields(fields -> fields.field("title")))
                        .detail(detail -> detail.editor(editor -> editor.field("title")))))
                .build();
        PageContextBindingDefinition binding = PageContextBindingDefinition.resolvedSelection(
                "roleScope", PageContextTarget.FORM_DEFAULT, "ownerScopeType");
        StaticModuleDefinition definition = StaticModuleDefinition.builder("iam", "iam.role", "角色")
                .entities(List.of(new EntityDefinition("role", "iam_role", "Role",
                        List.of(FieldDefinition.string("title", "名称"),
                                FieldDefinition.string("ownerScopeType", "归属范围")))))
                .uiDefinition(uiDefinition)
                .pageContextBindings(List.of(binding))
                .build();

        ResolvedPageNavigatorDescriptor navigator = ModuleUiDescriptorCompiler.compile(definition).page().navigator();

        assertThat(navigator.levels()).isEmpty();
        assertThat(navigator.contextBindings()).containsExactly(ResolvedPageContextBindingDescriptor.from(binding));
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
                                .level("tenant", level -> level.microList("iam.tenant", "租户", null))
                                .bindNavigatorToNavigator("tenant", "organization", "tenantId"))
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

    @Test
    void shouldResolveReadOnlyStaticDetailRelationWithoutInventingQueryOrMutationContract() {
        ModuleUiDefinition uiDefinition = ModuleUiDefinition.builder("iam.position_category")
                .page(emptyEditorPage())
                .detailRelation("positions", "岗位", "position", "categoryId", true)
                .build();

        ResolvedModuleUiDescriptor descriptor = ModuleUiDescriptorCompiler.compile(
                staticDefinition(uiDefinition, positionEntities()));

        assertThat(descriptor.detailRelations()).singleElement().satisfies(relation -> {
            assertThat(relation.code()).isEqualTo("positions");
            assertThat(relation.sourceModuleAlias()).isEqualTo("iam.position_category");
            assertThat(relation.sourceEntityAlias()).isEqualTo("position_category");
            assertThat(relation.targetEntityAlias()).isEqualTo("position");
            assertThat(relation.parentBinding()).isEqualTo("categoryId");
            assertThat(relation.readOnly()).isTrue();
            assertThat(relation.hasExecutableQueryContract()).isFalse();
            assertThat(relation.hasExecutableMutationContract()).isFalse();
        });
    }

    @Test
    void shouldRequireAnExplicitMutationContractForManagedStaticDetailRelation() {
        ModuleUiDefinition uiDefinition = ModuleUiDefinition.builder("iam.position_category")
                .page(emptyEditorPage())
                .editorContribution("position", form -> form.field("position", "title", field -> field.label("岗位")))
                .managedDetailRelation("positions", "岗位", "position", "categoryId",
                        PageDetailRelationMutationDefinition.standardCrud(),
                        PageDetailRelationPaginationDefinition.unpaged())
                .build();

        ResolvedModuleUiDescriptor descriptor = ModuleUiDescriptorCompiler.compile(
                staticDefinition(uiDefinition, positionEntities()));

        assertThat(descriptor.detailRelations()).singleElement().satisfies(relation -> {
            assertThat(relation.readOnly()).isFalse();
            assertThat(relation.hasExecutableQueryContract()).isTrue();
            assertThat(relation.queryContract().managedGateway()).isTrue();
            assertThat(relation.queryContract().actionCode()).isEqualTo("position_query");
            assertThat(relation.queryContract().pageable()).isFalse();
            assertThat(relation.queryContract().pageSize()).isNull();
            assertThat(relation.queryContract().pageSizeOptions()).isEmpty();
            assertThat(relation.hasExecutableMutationContract()).isTrue();
            assertThat(relation.mutationContract()).satisfies(mutation -> {
                assertThat(mutation.createActionCode()).isEqualTo("position_create");
                assertThat(mutation.updateActionCode()).isEqualTo("position_update");
                assertThat(mutation.deleteActionCode()).isEqualTo("position_delete");
            });
        });
    }

    @Test
    void shouldCompileManagedQueryWithoutImplicitMutationCapability() {
        ModuleUiDefinition uiDefinition = ModuleUiDefinition.builder("iam.position_category")
                .page(emptyEditorPage())
                .editorContribution("position", form -> form.field("position", "title", field -> field.label("岗位")))
                .managedReadOnlyDetailRelation("positions", "岗位", "position", "categoryId", null)
                .build();

        ResolvedModuleUiDescriptor descriptor = ModuleUiDescriptorCompiler.compile(
                staticDefinition(uiDefinition, positionEntities()));

        assertThat(descriptor.detailRelations()).singleElement().satisfies(relation -> {
            assertThat(relation.readOnly()).isTrue();
            assertThat(relation.queryContract().actionCode()).isEqualTo("position_query");
            assertThat(relation.hasExecutableQueryContract()).isTrue();
            assertThat(relation.hasExecutableMutationContract()).isFalse();
            assertThat(relation.mutationContract()).isNull();
        });
    }

    @Test
    void shouldCompileAnAggregateRelationOnceForDetailAndListExpansion() {
        ModuleUiDefinition uiDefinition = ModuleUiDefinition.builder("iam.employee")
                .page(PageTemplates.listDetailCard(page -> page
                        .list(list -> list
                                .fields(fields -> fields.field("employeeNo"))
                                .expandRelation("positions", expansion -> expansion.columns(
                                        "organizationId", "departmentId", "positionId", "primaryPosition", "enabled")))
                        .detail(detail -> detail.editor(editor -> editor.field("employeeNo")))
                        .traits(traits -> { })))
                .editorContribution("employee_position", form -> form
                        .field("employee_position", "organizationId", field -> { })
                        .field("employee_position", "departmentId", field -> { })
                        .field("employee_position", "positionId", field -> { })
                        .field("employee_position", "primaryPosition", field -> { })
                        .field("employee_position", "enabled", field -> { }))
                .aggregateChildRelation("positions", "任职", "employee_position", "employeeId",
                        UiRule.constant(Boolean.TRUE), false, List.of(new net.ximatai.muyun.spring.ability.child.AggregateChildFormulaDefinition(
                                "positions", new net.ximatai.muyun.spring.common.formula.FormulaRule(
                                "primaryPositionExclusive", "others({positions.primaryPosition}) = false WHEN {positions.primaryPosition}"),
                                List.of("primaryPosition"))))
                .build();

        ResolvedModuleUiDescriptor descriptor = ModuleUiDescriptorCompiler.compile(staticDefinition(uiDefinition,
                List.of(
                        new EntityDefinition("employee", "iam_employee", "Employee",
                                List.of(FieldDefinition.string("employeeNo", "职员编号"))),
                        new EntityDefinition("employee_position", "iam_employee_position", "EmployeePosition",
                                List.of(
                                        FieldDefinition.string("employeeId", "职员"),
                                        FieldDefinition.string("organizationId", "所属机构"),
                                        FieldDefinition.string("departmentId", "所属部门"),
                                        FieldDefinition.string("positionId", "岗位"),
                                        FieldDefinition.bool("primaryPosition", "主岗位"),
                                        FieldDefinition.bool("enabled", "启用状态"))))));

        assertThat(descriptor.detailRelations()).singleElement().satisfies(relation -> {
            assertThat(relation.code()).isEqualTo("positions");
            assertThat(relation.embeddedField()).isEqualTo("positions");
            assertThat(relation.listProjection().fields()).extracting(field -> field.fieldName())
                    .containsExactly("organizationId", "departmentId", "positionId", "primaryPosition", "enabled");
            assertThat(relation.formComputeRules()).singleElement().satisfies(rule -> {
                assertThat(rule.targetField()).isEqualTo("primaryPosition");
                assertThat(rule.targetValueType()).isEqualTo("BOOLEAN");
                assertThat(rule.program().root().arguments().getFirst().kind().name()).isEqualTo("OTHERS");
                assertThat(rule.program().root().arguments().get(2).field()).isEqualTo("primaryPosition");
            });
        });
        assertThat(descriptor.page().list().relationExpansions()).containsExactly(
                new ResolvedPageListRelationExpansionDescriptor("positions",
                        List.of("organizationId", "departmentId", "positionId", "primaryPosition", "enabled")));
    }

    @Test
    void shouldRejectAnAggregateFormulaDeclaredForAnotherRelation() {
        ModuleUiDefinition uiDefinition = ModuleUiDefinition.builder("iam.employee")
                .page(PageTemplates.listDetailCard(page -> page
                        .list(list -> list.fields(fields -> fields.field("employeeNo")))
                        .detail(detail -> detail.editor(editor -> editor.field("employeeNo")))
                        .traits(traits -> { })))
                .editorContribution("employee_position", form -> form
                        .field("employee_position", "primaryPosition", field -> { }))
                .aggregateChildRelation("positions", "任职", "employee_position", "employeeId",
                        UiRule.constant(Boolean.TRUE), false, List.of(new net.ximatai.muyun.spring.ability.child.AggregateChildFormulaDefinition(
                                "delegations", new net.ximatai.muyun.spring.common.formula.FormulaRule(
                                "primaryPositionExclusive", "others({delegations.primaryPosition}) = false WHEN {delegations.primaryPosition}"),
                                List.of("primaryPosition"))))
                .build();

        assertThatThrownBy(() -> ModuleUiDescriptorCompiler.compile(staticDefinition(uiDefinition,
                List.of(
                        new EntityDefinition("employee", "iam_employee", "Employee",
                                List.of(FieldDefinition.string("employeeNo", "职员编号"))),
                        new EntityDefinition("employee_position", "iam_employee_position", "EmployeePosition",
                                List.of(
                                        FieldDefinition.string("employeeId", "职员"),
                                        FieldDefinition.bool("primaryPosition", "主岗位")))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregate child formula relation must match detail relation");
    }

    @Test
    void shouldCompileMultipleListRelationExpansionsInDeclarationOrder() {
        ModuleUiDefinition uiDefinition = ModuleUiDefinition.builder("iam.employee")
                .page(PageTemplates.listDetailCard(page -> page
                        .list(list -> list
                                .fields(fields -> fields.field("employeeNo"))
                                .expandRelation("positions", expansion -> expansion.columns("positionId"))
                                .expandRelation("delegations", expansion -> expansion.columns("delegateId")))
                        .detail(detail -> detail.editor(editor -> editor.field("employeeNo")))
                        .traits(traits -> { })))
                .aggregateChildRelation("positions", "任职", "employee_position", "employeeId",
                        UiRule.constant(Boolean.TRUE))
                .aggregateChildRelation("delegations", "代理", "employee_delegation", "employeeId",
                        UiRule.constant(Boolean.TRUE))
                .editorContribution("employee_position", form -> form
                        .field("employee_position", "positionId", field -> { }))
                .editorContribution("employee_delegation", form -> form
                        .field("employee_delegation", "delegateId", field -> { }))
                .build();

        List<EntityDefinition> entities = List.of(
                new EntityDefinition("employee", "iam_employee", "Employee",
                        List.of(FieldDefinition.string("employeeNo", "职员编号"))),
                new EntityDefinition("employee_position", "iam_employee_position", "EmployeePosition",
                        List.of(
                                FieldDefinition.string("employeeId", "职员"),
                                FieldDefinition.string("positionId", "岗位"))),
                new EntityDefinition("employee_delegation", "iam_employee_delegation", "EmployeeDelegation",
                        List.of(
                                FieldDefinition.string("employeeId", "职员"),
                                FieldDefinition.string("delegateId", "代理人"))));
        assertThat(ModuleUiDescriptorCompiler.compile(staticDefinition(uiDefinition, entities))
                .page().list().relationExpansions())
                .extracting(ResolvedPageListRelationExpansionDescriptor::relationCode)
                .containsExactly("positions", "delegations");
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
                        .navigator(navigator -> navigator
                                .level("scope", level -> level.tree(sourceModuleAlias, title, searchPlaceholder))
                                .bindNavigatorToList("scope", bindingField))
                        .list(slot -> slot.fields(list))
                        .detail(detail -> detail.editor(editor -> { }))
                        .traits(traits -> { })))
                .build();
    }

    private StaticModuleDefinition staticDefinition(ModuleUiDefinition uiDefinition, List<EntityDefinition> entities) {
        List<net.ximatai.muyun.spring.platform.module.StaticModuleActionDefinition> relationActions =
                uiDefinition.detailRelations().stream()
                        .filter(PageDetailRelationDefinition::managedQuery)
                        .flatMap(relation -> {
                            java.util.ArrayList<String> operations = new java.util.ArrayList<>();
                            operations.add("query");
                            if (relation.mutation() != null) {
                                if (relation.mutation().createAllowed()) operations.add("create");
                                if (relation.mutation().updateAllowed()) operations.add("update");
                                if (relation.mutation().deleteAllowed()) operations.add("delete");
                            }
                            return operations.stream().map(operation ->
                                    net.ximatai.muyun.spring.platform.module.StaticModuleActionDefinition.recordAction(
                                            relation.targetEntityAlias() + "_" + operation, operation));
                        }).toList();
        return StaticModuleDefinition.builder("iam", uiDefinition.moduleAlias(), "职员管理")
                       .parentModuleAlias(null)
                       .entry(ModuleEntryType.ROUTE, "/iam/employees", null)
                       .capabilities(Set.of(EntityCapability.CRUD))
                       .actions(relationActions)
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
