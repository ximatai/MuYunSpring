package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.platform.ui.PlatformPageConfigSnapshot;
import net.ximatai.muyun.spring.platform.ui.PlatformResolvedPageConfig;
import net.ximatai.muyun.spring.platform.ui.PlatformResolvedUiField;
import net.ximatai.muyun.spring.platform.ui.PlatformUiClientType;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfig;
import net.ximatai.muyun.spring.platform.ui.PlatformUiSet;
import net.ximatai.muyun.spring.platform.ui.PlatformUiSetType;
import net.ximatai.muyun.spring.platform.ui.PlatformPageLayoutNavigator;
import net.ximatai.muyun.spring.platform.ui.PlatformPageNavigatorLayout;
import net.ximatai.muyun.spring.platform.ui.PlatformPageNavigatorLevel;
import net.ximatai.muyun.spring.platform.ui.PlatformPageContextBinding;
import net.ximatai.muyun.spring.platform.ui.PlatformPublishedPageComposition;
import net.ximatai.muyun.spring.common.formula.FormulaEngine;
import net.ximatai.muyun.spring.common.formula.FormulaEvaluationException;
import net.ximatai.muyun.spring.common.formula.FormulaNode;
import net.ximatai.muyun.spring.common.formula.FormulaProgram;
import net.ximatai.muyun.spring.common.formula.FormulaRuleKind;
import net.ximatai.muyun.spring.common.formula.FormulaRulePhase;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicFormulaRuleDescriptor;

import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class DynamicModuleUiDefinitionAdapter {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final FormulaEngine FORMULA_ENGINE = new FormulaEngine();
    private DynamicModuleUiDefinitionAdapter() {
    }

    public static ModuleUiDefinition fromPublishedSnapshot(PlatformPageConfigSnapshot snapshot,
                                                           PlatformResolvedPageConfig resolvedPageConfig) {
        return fromPublishedSnapshot(snapshot, resolvedPageConfig, List.of());
    }

    /**
     * Maps only already-declared dynamic calculation rules that are directly executable by the
     * browser profile. No UI-specific formula persistence model is introduced here.
     */
    public static ModuleUiDefinition fromPublishedSnapshot(PlatformPageConfigSnapshot snapshot,
                                                           PlatformResolvedPageConfig resolvedPageConfig,
                                                           List<DynamicFormulaRuleDescriptor> mainFormulaRules) {
        return fromPublishedSnapshot(snapshot, resolvedPageConfig, mainFormulaRules, Map.of());
    }

    public static ModuleUiDefinition fromPublishedSnapshot(PlatformPageConfigSnapshot snapshot,
                                                           PlatformResolvedPageConfig resolvedPageConfig,
                                                           List<DynamicFormulaRuleDescriptor> mainFormulaRules,
                                                           Map<ViewFieldRef, FieldValueType> fieldTypes) {
        if (snapshot == null) {
            throw new IllegalArgumentException("platform page config snapshot must not be null");
        }
        if (resolvedPageConfig == null) {
            throw new IllegalArgumentException("platform resolved page config must not be null");
        }
        Map<String, PlatformUiSet> uiSets = snapshot.uiSets().stream()
                .collect(Collectors.toMap(PlatformUiSet::getId, Function.identity(), (left, ignored) -> left));
        Map<String, List<PlatformResolvedUiField>> fieldsByConfig = resolvedPageConfig.uiFields().stream()
                .collect(Collectors.groupingBy(PlatformResolvedUiField::uiConfigId,
                        java.util.LinkedHashMap::new,
                        Collectors.toList()));
        // Keep configuration-time field compatibility diagnostics independent from whether this
        // snapshot also happens to contain the complementary list/form slot.
        snapshot.uiConfigs().stream()
                .filter(config -> Boolean.TRUE.equals(config.getPublished()))
                .filter(config -> !Boolean.FALSE.equals(config.getEnabled()))
                .filter(config -> config.getClientType() == PlatformUiClientType.WEB)
                .forEach(config -> {
                    PlatformUiSet uiSet = uiSets.get(config.getUiSetId());
                    ModuleViewKind viewKind = viewKind(uiSet);
                    if (viewKind != null) {
                        view(config, uiSet, viewKind, fieldsByConfig.get(config.getId()), List.of());
                    }
                });
        PlatformPublishedPageComposition composition = PlatformPublishedPageComposition.resolve(snapshot,
                PlatformUiClientType.WEB);
        PlatformUiConfig listConfig = composition.listConfig();
        if (listConfig == null) {
            throw new IllegalArgumentException("dynamic page requires a published list slot: " + snapshot.moduleAlias());
        }
        PlatformUiConfig formConfig = composition.formConfig();
        if (formConfig == null) {
            throw new IllegalArgumentException("dynamic page requires a published form editor slot: " + snapshot.moduleAlias());
        }
        ViewDefinition list = view(listConfig, uiSets.get(listConfig.getUiSetId()), ModuleViewKind.LIST,
                fieldsByConfig.get(listConfig.getId()), List.of());
        List<PlatformResolvedUiField> formFields = fieldsByConfig.get(formConfig.getId());
        ViewDefinition editor = view(formConfig, uiSets.get(formConfig.getUiSetId()), ModuleViewKind.FORM,
                formFields, formComputeRules(mainFormulaRules, formFields, fieldTypes));
        ModulePageDefinition page = page(list, editor, listConfig);
        return new ModuleUiDefinition(snapshot.moduleAlias(), List.of(), page, null, List.of(), List.of());
    }

    private static ViewDefinition view(PlatformUiConfig config,
                                       PlatformUiSet uiSet,
                                       ModuleViewKind viewKind,
                                       List<PlatformResolvedUiField> fields,
                                       List<FormComputeRuleDefinition> formComputeRules) {
        return new ViewDefinition(
                uiSet.getAlias(),
                viewKind,
                ModuleUiClientType.WEB,
                viewTitle(config, uiSet),
                fields(fields),
                config.getId(),
                null,
                formComputeRules
        );
    }

    private static List<FormComputeRuleDefinition> formComputeRules(
            List<DynamicFormulaRuleDescriptor> dynamicRules,
            List<PlatformResolvedUiField> formFields,
            Map<ViewFieldRef, FieldValueType> fieldTypes) {
        if (dynamicRules == null || dynamicRules.isEmpty()) return List.of();
        Map<String, PlatformResolvedUiField> mainFields = formFields == null ? Map.of() : formFields.stream()
                .filter(field -> field.relationAlias() == null || field.relationAlias().isBlank())
                .filter(field -> field.fieldName() != null && !field.fieldName().isBlank())
                .collect(Collectors.toMap(PlatformResolvedUiField::fieldName, Function.identity(),
                        (left, ignored) -> left, java.util.LinkedHashMap::new));
        Map<ViewFieldRef, FieldValueType> resolvedTypes = fieldTypes == null ? Map.of() : fieldTypes;
        java.util.ArrayList<FormComputeRuleDefinition> rules = new java.util.ArrayList<>();
        for (DynamicFormulaRuleDescriptor rule : dynamicRules) {
            if (rule == null || !rule.enabled() || rule.kind() != FormulaRuleKind.CALCULATION
                    || rule.phase() != FormulaRulePhase.BEFORE_SAVE) continue;
            try {
                FormulaProgram program = FORMULA_ENGINE.compileFormComputeProgram(rule.expression());
                String target = assignedTarget(program);
                if (target == null || (rule.targetField() != null && !rule.targetField().equals(target))) continue;
                PlatformResolvedUiField targetField = mainFields.get(target);
                List<String> inputs = valueSideFields(program);
                if (targetField == null || Boolean.TRUE.equals(targetField.readOnly()) || inputs.contains(target)
                        || !portableType(resolvedTypes.get(ViewFieldRef.main(target)))
                        || inputs.stream().anyMatch(field -> !mainFields.containsKey(field)
                                || !portableType(resolvedTypes.get(ViewFieldRef.main(field))))) {
                    continue;
                }
                rules.add(new FormComputeRuleDefinition(rule.code(), target, inputs, rule.expression()));
            } catch (FormulaEvaluationException ignored) {
                // Server-only or relation-scoped formula rules remain server-only; they are not UI failures.
            }
        }
        return List.copyOf(rules);
    }

    private static boolean portableType(FieldValueType type) {
        return type != null && type != FieldValueType.JSON;
    }

    private static String assignedTarget(FormulaProgram program) {
        FormulaNode root = program.root();
        if (root.kind() != FormulaNode.Kind.ASSIGN || root.arguments().size() != 2
                || root.arguments().getFirst().kind() != FormulaNode.Kind.FIELD) return null;
        return root.arguments().getFirst().field();
    }

    private static List<String> valueSideFields(FormulaProgram program) {
        java.util.LinkedHashSet<String> fields = new java.util.LinkedHashSet<>();
        collectFields(program.root().arguments().get(1), fields);
        return List.copyOf(fields);
    }

    private static void collectFields(FormulaNode node, java.util.Set<String> fields) {
        if (node == null) return;
        if (node.kind() == FormulaNode.Kind.FIELD && node.field() != null) fields.add(node.field());
        node.arguments().forEach(argument -> collectFields(argument, fields));
    }

    private static ModulePageDefinition page(ViewDefinition list, ViewDefinition editor, PlatformUiConfig listConfig) {
        JsonNode root = pageRoot(listConfig);
        String template = root.path("template").asText("LIST_DETAIL_CARD");
        if ("FLAT_MANAGEMENT".equals(template)) {
            JsonNode explorer = root.path("explorer");
            JsonNode detail = root.path("detail");
            return new FlatManagementPageDefinition(navigator(listConfig), new PageExplorerDefinition(
                    explorer.path("title").asText(list.title()),
                    explorer.path("searchPlaceholder").asText(null), explorer.path("emptyDescription").asText(null),
                    explorer.path("recordLabel").asText(null), explorer.path("fallbackTitle").asText(null),
                    explorer.path("titleField").asText("title"), explorer.path("secondaryField").asText(null),
                    explorer.path("mutedWhenDisabled").asBoolean(false)),
                    new PageDetailDefinition(detail.path("emptyDescription").asText(null),
                            detail.path("createTitle").asText(editor.title()), null, editor,
                            workspaceView(detail)),
                    traits(root));
        }
        if ("TREE_MANAGEMENT".equals(template)) {
            return new TreeManagementPageDefinition(navigator(listConfig),
                    new PageDetailDefinition(null, editor.title(), null, editor, workspaceView(root.path("detail"))), traits(root));
        }
        if (!"LIST_DETAIL_CARD".equals(template)) {
            throw new IllegalArgumentException("dynamic page template must be FLAT_MANAGEMENT, LIST_DETAIL_CARD or TREE_MANAGEMENT: "
                    + listConfig.getId());
        }
        PageNavigatorDefinition navigator = navigator(listConfig);
        return new ListDetailCardPageDefinition(navigator, new PageListDefinition(list.title(), list),
                new PageDetailDefinition(null, editor.title(), null, editor, workspaceView(root.path("detail"))),
                traits(root));
    }

    private static PageDetailWorkspaceViewDefinition workspaceView(JsonNode detail) {
        String type = detail.path("workspaceView").path("type").asText(null);
        return type == null || type.isBlank() ? null : new PageDetailWorkspaceViewDefinition(type);
    }

    private static PageNavigatorDefinition navigator(PlatformUiConfig config) {
        PlatformPageNavigatorLayout navigator = PlatformPageLayoutNavigator.navigator(config);
        if (navigator == null) return null;
        List<PageNavigatorLevelDefinition> levels = navigator.levels().stream().map(level -> new PageNavigatorLevelDefinition(
                level.key(), PageNavigatorKind.valueOf(level.kind()), level.sourceModuleAlias(), level.title(),
                level.searchPlaceholder(), level.management() == null ? null
                        : new PageNavigatorManagementDefinition(level.management().editorSurface(), managementActions(level)),
                PageNavigatorSingleResultPolicy.valueOf(level.singleResultPolicy()),
                PageNavigatorInitialSelectionPolicy.valueOf(level.initialSelectionPolicy()),
                PageNavigatorSourceScope.valueOf(level.sourceScope()))).toList();
        List<PageContextBindingDefinition> bindings = navigator.contextBindings().stream()
                .map(DynamicModuleUiDefinitionAdapter::contextBinding)
                .toList();
        return new PageNavigatorDefinition(levels, bindings);
    }

    private static PageContextBindingDefinition contextBinding(PlatformPageContextBinding binding) {
        return new PageContextBindingDefinition(PageContextSource.valueOf(binding.source()), binding.sourceKey(),
                PageContextTarget.valueOf(binding.target()), binding.targetKey(), binding.targetNavigatorLevelKey(),
                binding.targetPickerFieldKey());
    }

    private static Set<PageNavigatorManagementAction> managementActions(PlatformPageNavigatorLevel level) {
        if (level.management().actions() == null) return null;
        return level.management().actions().stream().map(PageNavigatorManagementAction::valueOf).collect(java.util.stream.Collectors.toSet());
    }

    private static JsonNode pageRoot(PlatformUiConfig config) {
        if (config.getLayoutJson() == null || config.getLayoutJson().isBlank()) {
            throw new IllegalArgumentException("dynamic list config must declare a page root layout: " + config.getId());
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(config.getLayoutJson());
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("dynamic page root layout must be an object: " + config.getId());
            }
            return root;
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("dynamic page root layout cannot be decoded: " + config.getId(), exception);
        }
    }

    private static PageTraitsDefinition traits(JsonNode root) {
        java.util.LinkedHashSet<PageTrait> traits = new java.util.LinkedHashSet<>();
        JsonNode values = root.path("traits");
        if (values.isArray()) {
            values.forEach(value -> traits.add(PageTrait.valueOf(value.asText())));
        }
        return new PageTraitsDefinition(traits);
    }

    private static List<ViewFieldDefinition> fields(List<PlatformResolvedUiField> fields) {
        if (fields == null || fields.isEmpty()) {
            return List.of();
        }
        return fields.stream()
                .filter(field -> field.fieldName() != null && !field.fieldName().isBlank())
                .map(DynamicModuleUiDefinitionAdapter::field)
                .toList();
    }

    private static ViewFieldDefinition field(PlatformResolvedUiField field) {
        if ("booleanStatus".equals(field.fieldUiControlAlias())) {
            throw new IllegalArgumentException("dynamic field " + field.fieldName()
                    + " cannot use uiType booleanStatus until dynamic UI configuration declares its presentation");
        }
        if ("fileTransfer".equals(field.fieldUiControlAlias())
                || "file_transfer".equals(field.fieldUiControlAlias())) {
            throw new IllegalArgumentException("dynamic field " + field.fieldName()
                    + " cannot use file transfer until the unified file-reference lifecycle is available");
        }
        return new ViewFieldDefinition(
                new ViewFieldRef(field.relationAlias(), field.fieldName(), field.moduleMetadataFieldId()),
                field.fieldTitle(),
                rule(field.visible(), field.visibleWhen(), Boolean.TRUE),
                UiRule.constant(field.requiredOverride() == null ? Boolean.FALSE : field.requiredOverride()),
                rule(field.readOnly(), field.readOnlyWhen(), Boolean.FALSE),
                uiType(field),
                valuePresentation(field),
                width(field),
                field.columnSpan(),
                field.align(),
                field.fixedPosition() == null ? null : Boolean.TRUE,
                null,
                field.maxDisplayLines(),
                null
        );
    }

    private static UiRule<Boolean> rule(Boolean constant, String predicate, Boolean defaultValue) {
        return predicate == null || predicate.isBlank()
                ? UiRule.constant(constant == null ? defaultValue : constant)
                : UiRule.formula(UiFormula.booleanExpression(predicate));
    }

    private static String uiType(PlatformResolvedUiField field) {
        return "file_size".equals(field.fieldUiControlAlias()) ? null : field.fieldUiControlAlias();
    }

    private static FieldValuePresentation valuePresentation(PlatformResolvedUiField field) {
        return "file_size".equals(field.fieldUiControlAlias()) ? FieldValuePresentation.FILE_SIZE : null;
    }

    private static String width(PlatformResolvedUiField field) {
        return field.width() == null ? null : field.width() + "px";
    }

    private static String viewTitle(PlatformUiConfig config, PlatformUiSet uiSet) {
        if (config.getTitle() != null && !config.getTitle().isBlank()) {
            return config.getTitle();
        }
        return uiSet == null ? null : uiSet.getTitle();
    }

    private static ModuleViewKind viewKind(PlatformUiSet uiSet) {
        if (uiSet == null || uiSet.getSetType() == null) {
            return null;
        }
        if (Objects.equals(uiSet.getSetType(), PlatformUiSetType.LIST)) {
            return ModuleViewKind.LIST;
        }
        if (Objects.equals(uiSet.getSetType(), PlatformUiSetType.FORM)) {
            return ModuleViewKind.FORM;
        }
        if (Objects.equals(uiSet.getSetType(), PlatformUiSetType.DETAIL)) {
            return ModuleViewKind.DETAIL;
        }
        return null;
    }
}
