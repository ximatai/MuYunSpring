package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.platform.module.StaticModuleReadProjectionDefinition;
import net.ximatai.muyun.spring.common.model.title.RecordLabelResolver;
import net.ximatai.muyun.spring.common.option.OptionFieldDefinition;
import net.ximatai.muyun.spring.common.option.OptionFieldResolver;
import net.ximatai.muyun.spring.common.option.OptionItem;
import net.ximatai.muyun.spring.common.option.OptionBinding;
import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;
import net.ximatai.muyun.spring.common.option.OptionLoadResolver;
import net.ximatai.muyun.spring.common.option.OptionSelectionMode;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.ability.reference.ReferenceProjection;
import net.ximatai.muyun.spring.ability.reference.ReferenceSummaryPlan;
import net.ximatai.muyun.spring.ability.reference.StaticReferenceResolver;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.common.formula.FormulaEngine;
import net.ximatai.muyun.spring.common.formula.FormulaEvaluationException;
import net.ximatai.muyun.spring.common.formula.FormulaNode;
import net.ximatai.muyun.spring.common.formula.FormulaProgram;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationDescriptor;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class ModuleUiDescriptorCompiler {
    private static final FormulaEngine FORMULA_ENGINE = new FormulaEngine();
    private static final String ALIAS_FIELD = "alias";
    private static final Set<String> PLATFORM_FIELD_NAMES = platformFieldNames();
    private static final Map<String, FieldValueType> STANDARD_FIELD_TYPES = standardFieldTypes();

    private ModuleUiDescriptorCompiler() {
    }

    private static Set<String> platformFieldNames() {
        LinkedHashSet<String> fields = new LinkedHashSet<>(StandardEntitySchema.fieldNames());
        fields.add(PlatformAbilityFields.TITLE_FIELD);
        fields.add(PlatformAbilityFields.ENABLED_FIELD);
        fields.add(PlatformAbilityFields.TREE_PARENT_FIELD);
        fields.add(PlatformAbilityFields.SORT_FIELD);
        fields.add(ALIAS_FIELD);
        return Set.copyOf(fields);
    }

    public static ResolvedModuleUiDescriptor compile(StaticModuleDefinition definition) {
        ModuleUiCompilationResult result = compileModule(definition);
        return result == null ? null : result.uiDescriptor();
    }

    /** Compiles static references with target-module facts supplied by the module catalog. */
    public static ResolvedModuleUiDescriptor compile(StaticModuleDefinition definition,
                                                     Function<String, ReferencePickerMode> referencePickerModeResolver) {
        ModuleUiCompilationResult result = compileModule(definition, referencePickerModeResolver);
        return result == null ? null : result.uiDescriptor();
    }

    public static ModuleUiCompilationResult compileModule(StaticModuleDefinition definition) {
        return compileModule(definition, ignored -> ReferencePickerMode.AUTO);
    }

    public static ModuleUiCompilationResult compileModule(StaticModuleDefinition definition,
                                                          Function<String, ReferencePickerMode> referencePickerModeResolver) {
        if (definition == null) {
            return null;
        }
        ModuleUiDefinition uiDefinition = definition.uiDefinition() == null
                ? ModuleUiDefinition.builder(definition.moduleAlias()).build()
                : definition.uiDefinition();
        validateFields(uiDefinition, definition.entities(), definition.moduleAlias(), readOutputFields(definition));
        Map<String, ResolvedReferenceFieldDescriptor> referenceFields = staticReferenceFields(definition.modelClass(),
                referencePickerModeResolver == null ? ignored -> ReferencePickerMode.AUTO : referencePickerModeResolver);
        Map<String, ResolvedReferenceSummaryFieldDescriptor> referenceSummaryFields =
                staticReferenceSummaryFields(definition.modelClass());
        ResolvedModuleUiDescriptor descriptor = compileResolved(uiDefinition, ModuleKind.STATIC, definition.title(),
                        staticOptionFields(definition.modelClass()), referenceFields, referenceSummaryFields,
                        staticRecordLabelField(definition), fieldTypes(definition.entities()), FieldControlDescriptorCatalog.standard());
        return new ModuleUiCompilationResult(
                descriptor.withFileReferences(fileReferences(definition.entities(), uiDefinition))
                        .withDetailRelations(staticDetailRelations(definition, uiDefinition)),
                readModel(definition, uiDefinition)
        );
    }

    public static ResolvedModuleUiDescriptor compile(ModuleUiDefinition definition) {
        if (definition == null) {
            return null;
        }
        return compileResolved(definition, null, null, Map.of(), Map.of(), Map.of(), null, Map.of(), FieldControlDescriptorCatalog.standard());
    }

    public static ResolvedModuleUiDescriptor compile(ModuleUiDefinition definition,
                                                     ModuleKind moduleKind,
                                                     String title) {
        return compileResolved(definition, moduleKind, title, Map.of(), Map.of(), Map.of(), null, Map.of(), FieldControlDescriptorCatalog.standard());
    }

    public static ResolvedModuleUiDescriptor compile(ModuleUiDefinition definition,
                                                     ModuleKind moduleKind,
                                                     String title,
                                                     Map<String, ResolvedOptionFieldDescriptor> optionFields) {
        if (definition == null) {
            return null;
        }
        return compileResolved(definition, moduleKind, title, optionFields, Map.of(), Map.of(), null, Map.of(), FieldControlDescriptorCatalog.standard());
    }

    public static ResolvedModuleUiDescriptor compile(ModuleUiDefinition definition,
                                                     ModuleKind moduleKind,
                                                     String title,
                                                     Map<String, ResolvedOptionFieldDescriptor> optionFields,
                                                     String defaultRecordLabelField) {
        if (definition == null) return null;
        return compileResolved(definition, moduleKind, title, optionFields == null ? Map.of() : optionFields,
                Map.of(), Map.of(), defaultRecordLabelField, Map.of(), FieldControlDescriptorCatalog.standard());
    }

    /**
     * Compiles a source-neutral descriptor with both option and reference field metadata.
     * Static and dynamic modules use this same resolved projection.
     */
    public static ResolvedModuleUiDescriptor compile(ModuleUiDefinition definition,
                                                     ModuleKind moduleKind,
                                                     String title,
                                                     Map<String, ResolvedOptionFieldDescriptor> optionFields,
                                                     Map<String, ResolvedReferenceFieldDescriptor> referenceFields,
                                                     String defaultRecordLabelField) {
        if (definition == null) return null;
        return compileResolved(definition, moduleKind, title,
                optionFields == null ? Map.of() : optionFields,
                referenceFields == null ? Map.of() : referenceFields,
                Map.of(), defaultRecordLabelField, Map.of(), FieldControlDescriptorCatalog.standard());
    }

    public static ResolvedModuleUiDescriptor compile(ModuleUiDefinition definition,
                                                     ModuleKind moduleKind,
                                                     String title,
                                                     Map<String, ResolvedOptionFieldDescriptor> optionFields,
                                                     Map<String, ResolvedReferenceFieldDescriptor> referenceFields,
                                                     String defaultRecordLabelField,
                                                     Map<ViewFieldRef, FieldValueType> fieldTypes) {
        if (definition == null) return null;
        return compileResolved(definition, moduleKind, title,
                optionFields == null ? Map.of() : optionFields,
                referenceFields == null ? Map.of() : referenceFields,
                Map.of(), defaultRecordLabelField, fieldTypes == null ? Map.of() : fieldTypes, FieldControlDescriptorCatalog.standard());
    }

    /** Compiles a published dynamic definition using its already-resolved control catalog. */
    public static ResolvedModuleUiDescriptor compile(ModuleUiDefinition definition,
                                                     ModuleKind moduleKind,
                                                     String title,
                                                     Map<String, ResolvedOptionFieldDescriptor> optionFields,
                                                     Map<String, ResolvedReferenceFieldDescriptor> referenceFields,
                                                     String defaultRecordLabelField,
                                                     Map<ViewFieldRef, FieldValueType> fieldTypes,
                                                     Map<String, ResolvedFieldControlDescriptor> fieldControls) {
        if (fieldControls == null) {
            throw new IllegalArgumentException("resolved field controls must not be null");
        }
        return compileResolved(definition, moduleKind, title, optionFields == null ? Map.of() : optionFields,
                referenceFields == null ? Map.of() : referenceFields, Map.of(), defaultRecordLabelField,
                fieldTypes == null ? Map.of() : fieldTypes, Map.copyOf(fieldControls));
    }

    private static ResolvedModuleUiDescriptor compileResolved(ModuleUiDefinition definition,
                                                              ModuleKind moduleKind,
                                                              String title,
                                                              Map<String, ResolvedOptionFieldDescriptor> optionFields,
                                                              Map<String, ResolvedReferenceFieldDescriptor> referenceFields,
                                                              Map<String, ResolvedReferenceSummaryFieldDescriptor> referenceSummaryFields,
                                                              String defaultRecordLabelField,
                                                              Map<ViewFieldRef, FieldValueType> fieldTypes,
                                                              Map<String, ResolvedFieldControlDescriptor> fieldControls) {
        return new ResolvedModuleUiDescriptor(
                ResolvedModuleUiDescriptor.SCHEMA_VERSION,
                definition.moduleAlias(),
                moduleKind,
                title,
                definition.actions().stream()
                        .map(ModuleUiDescriptorCompiler::compileAction)
                        .toList(),
                defaultRecordLabelField,
                List.of(),
                compilePage(definition.page(), optionFields, referenceFields, referenceSummaryFields, fieldTypes, fieldControls),
                definition.defaultEditor() == null ? null : compileView(definition.defaultEditor(), optionFields,
                        referenceFields, referenceSummaryFields, fieldTypes, fieldControls),
                definition.editorSurfaces().stream().map(surface ->
                        new ResolvedEditorSurfaceDescriptor(surface.key(), compileView(surface.editor(), optionFields,
                                referenceFields, referenceSummaryFields, fieldTypes, fieldControls))).toList(),
                definition.editorContributions().stream().map(contribution ->
                        new ResolvedPageDetailEditorContribution(contribution.resource(), compileView(contribution.editor(),
                                optionFields, referenceFields, referenceSummaryFields, fieldTypes, fieldControls))).toList(),
                List.of()
        );
    }

    private static List<ResolvedDetailRelationDescriptor> staticDetailRelations(StaticModuleDefinition definition,
                                                                                  ModuleUiDefinition uiDefinition) {
        String sourceEntityAlias = definition.entities().isEmpty() ? null : definition.entities().getFirst().alias();
        if (sourceEntityAlias == null) {
            if (!uiDefinition.detailRelations().isEmpty()) {
                throw new IllegalArgumentException("static detail relation requires a declared source entity");
            }
            return List.of();
        }
        return uiDefinition.detailRelations().stream().map(relation -> {
            boolean targetExists = definition.entities().stream()
                    .anyMatch(entity -> relation.targetEntityAlias().equals(entity.alias()));
            if (!targetExists) {
                throw new IllegalArgumentException("static detail relation target entity is not declared by model facts: "
                        + relation.targetEntityAlias());
            }
            return new ResolvedDetailRelationDescriptor(relation.code(), relation.title(), relation.readOnly(),
                    definition.moduleAlias(), sourceEntityAlias, definition.moduleAlias(), relation.targetEntityAlias(),
                    relation.parentBinding(), null, relation.refreshOnDetailReload());
        }).toList();
    }

    private static ResolvedModulePageDescriptor compilePage(ModulePageDefinition page,
                                                            Map<String, ResolvedOptionFieldDescriptor> optionFields,
                                                            Map<String, ResolvedReferenceFieldDescriptor> referenceFields,
                                                            Map<String, ResolvedReferenceSummaryFieldDescriptor> referenceSummaryFields,
                                                            Map<ViewFieldRef, FieldValueType> fieldTypes,
                                                            Map<String, ResolvedFieldControlDescriptor> fieldControls) {
        if (page == null) return null;
        return switch (page) {
            case FlatManagementPageDefinition flat -> {
                if (flat.navigator() != null) {
                    validateNavigator(flat.navigator(), referenceFields, "page navigator", editorFieldNames(flat.detail()), false);
                }
                yield new ResolvedModulePageDescriptor(
                    flat.template(), ResolvedPageExplorerDescriptor.from(flat.explorer()),
                    ResolvedPageNavigatorDescriptor.from(flat.navigator()), null,
                    detail(flat.detail(), optionFields, referenceFields, referenceSummaryFields, fieldTypes, fieldControls),
                    List.copyOf(flat.traits().values()));
            }
            case ListDetailCardPageDefinition card -> {
                if (card.navigator() != null) {
                    validateNavigator(card.navigator(), referenceFields, "page navigator", editorFieldNames(card.detail()), false);
                }
                yield new ResolvedModulePageDescriptor(card.template(), null,
                        ResolvedPageNavigatorDescriptor.from(card.navigator()),
                        new ResolvedPageListDescriptor(card.list().searchPlaceholder(),
                                compileView(card.list().list(), optionFields, referenceFields,
                                        referenceSummaryFields, fieldTypes, fieldControls)),
                        detail(card.detail(), optionFields, referenceFields, referenceSummaryFields, fieldTypes, fieldControls),
                        List.copyOf(card.traits().values()));
            }
            case TreeManagementPageDefinition tree -> {
                if (tree.navigator() != null) {
                    validateNavigator(tree.navigator(), referenceFields, "page navigator", editorFieldNames(tree.detail()), true);
                }
                yield new ResolvedModulePageDescriptor(tree.template(), null,
                        ResolvedPageNavigatorDescriptor.from(tree.navigator()), null,
                        detail(tree.detail(), optionFields, referenceFields, referenceSummaryFields, fieldTypes, fieldControls),
                        List.copyOf(tree.traits().values()));
            }
        };
    }

    private static ResolvedPageDetailDescriptor detail(PageDetailDefinition detail,
                                                       Map<String, ResolvedOptionFieldDescriptor> optionFields,
                                                       Map<String, ResolvedReferenceFieldDescriptor> referenceFields,
                                                       Map<String, ResolvedReferenceSummaryFieldDescriptor> referenceSummaryFields,
                                                       Map<ViewFieldRef, FieldValueType> fieldTypes,
                                                       Map<String, ResolvedFieldControlDescriptor> fieldControls) {
        return new ResolvedPageDetailDescriptor(detail.emptyDescription(), detail.createTitle(),
                detail.display() == null ? null : compileView(detail.display(), optionFields, referenceFields,
                        referenceSummaryFields, fieldTypes, fieldControls),
                detail.editor() == null ? null : compileView(detail.editor(), optionFields, referenceFields,
                        referenceSummaryFields, fieldTypes, fieldControls),
                detail.workspaceView() == null ? null
                        : new ResolvedPageDetailWorkspaceViewDescriptor(detail.workspaceView().type()),
                detail.showSystemInfo());
    }

    private static void validateNavigator(PageNavigatorDefinition navigator,
                                          Map<String, ResolvedReferenceFieldDescriptor> referenceFields,
                                          String moduleAlias,
                                          Set<String> editorFieldNames,
                                          boolean treeParentPickerAllowed) {
        for (PageContextBindingDefinition binding : navigator.contextBindings()) {
            if (binding.source() != PageContextSource.NAVIGATOR
                    || (binding.target() != PageContextTarget.LIST_QUERY
                    && binding.target() != PageContextTarget.PICKER_QUERY)) continue;
            PageNavigatorLevelDefinition level = navigator.levels().stream()
                    .filter(candidate -> candidate.key().equals(binding.sourceKey())).findFirst().orElseThrow();
            ResolvedReferenceFieldDescriptor reference = referenceFields.get(binding.targetKey());
            if (binding.target() == PageContextTarget.PICKER_QUERY) {
                if (!editorFieldNames.contains(binding.targetPickerFieldKey())) {
                    throw new IllegalArgumentException("picker-query target must be declared by the page editor: "
                            + moduleAlias + "." + binding.targetPickerFieldKey());
                }
                ResolvedReferenceFieldDescriptor picker = referenceFields.get(binding.targetPickerFieldKey());
                if (!(treeParentPickerAllowed
                        && PlatformAbilityFields.TREE_PARENT_FIELD.equals(binding.targetPickerFieldKey()))
                        && (picker == null || picker.cardinality()
                        != net.ximatai.muyun.spring.ability.reference.ReferenceCardinality.ONE)) {
                    throw new IllegalArgumentException("picker-query target must be a single record reference: "
                            + moduleAlias + "." + binding.targetPickerFieldKey());
                }
            }
            if (isTenantScopeNavigator(level, binding)) {
                continue;
            }
            if (reference == null) {
                throw new IllegalArgumentException("navigator query field must be a reference: "
                        + moduleAlias + "." + binding.targetKey());
            }
            if (reference.cardinality() != net.ximatai.muyun.spring.ability.reference.ReferenceCardinality.ONE) {
                throw new IllegalArgumentException("navigator query field must be a single reference: "
                        + moduleAlias + "." + binding.targetKey());
            }
            if (!level.sourceModuleAlias().equals(reference.targetModuleAlias())) {
                throw new IllegalArgumentException("navigator query reference target must match level source: "
                        + moduleAlias + "." + binding.targetKey());
            }
        }
    }

    private static Set<String> editorFieldNames(PageDetailDefinition detail) {
        if (detail == null || detail.editor() == null) {
            return Set.of();
        }
        return detail.editor().fields().stream()
                .map(field -> field.fieldRef().fieldName())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * {@code tenantId} is a standard scope field rather than a model-declared reference. A tenant
     * navigator is therefore the one platform-owned navigator binding that does not require an
     * {@code @ReferenceTo} declaration on every tenant-scoped entity.
     */
    private static boolean isTenantScopeNavigator(PageNavigatorLevelDefinition level,
                                                  PageContextBindingDefinition binding) {
        return StandardEntitySchema.TENANT_ID_FIELD.equals(binding.targetKey())
                && "iam.tenant".equals(level.sourceModuleAlias());
    }

    private static ResolvedUiActionDescriptor compileAction(UiActionDefinition action) {
        UiActionConfirmationDefinition confirmation = action.confirmation();
        return new ResolvedUiActionDescriptor(
                action.actionCode(),
                confirmation == null ? null : new ResolvedUiActionConfirmationDescriptor(
                        ResolvedUiActionConfirmationDescriptor.TYPED_TEXT,
                        confirmation.requiredField())
        );
    }

    public static void validate(ModuleUiDefinition definition, List<EntityDefinition> entities) {
        if (definition == null || entities == null || entities.isEmpty()) {
            return;
        }
        validateFields(definition, entities);
    }

    private static ResolvedViewDescriptor compileView(ViewDefinition view,
                                                      Map<String, ResolvedOptionFieldDescriptor> optionFields,
                                                      Map<String, ResolvedReferenceFieldDescriptor> referenceFields,
                                                      Map<String, ResolvedReferenceSummaryFieldDescriptor> referenceSummaryFields,
                                                      Map<ViewFieldRef, FieldValueType> fieldTypes,
                                                      Map<String, ResolvedFieldControlDescriptor> fieldControls) {
        List<ResolvedViewFieldDescriptor> fields = view.fields().stream()
                .map(field -> compileField(view.viewKind(), field, optionFields, referenceFields,
                        referenceSummaryFields, fieldTypes, fieldControls))
                .toList();
        return new ResolvedViewDescriptor(
                view.viewCode(),
                view.viewKind(),
                view.clientType(),
                view.title(),
                fields,
                view.sourceUiConfigId(),
                view.formGroups().stream().map(group -> new ResolvedFormGroupDescriptor(
                        group.groupCode(), group.title(), group.subtitle(),
                        group.fields().stream().map(ViewFieldDefinition::fieldRef).toList())).toList(),
                compileFormComputeRules(view, fields)
        );
    }

    /**
     * Turns a source declaration into a signed program only after verifying that it can operate on
     * this exact form. The browser therefore never receives a calculation for a hidden relation or
     * an immutable field.
     */
    private static List<ResolvedFormComputeRuleDescriptor> compileFormComputeRules(
            ViewDefinition view, List<ResolvedViewFieldDescriptor> fields) {
        if (view.formComputeRules().isEmpty()) return List.of();
        if (view.viewKind() != ModuleViewKind.FORM) {
            throw new IllegalArgumentException("form compute rules require a FORM view: " + view.viewCode());
        }
        Map<String, ResolvedViewFieldDescriptor> mainFields = fields.stream()
                .filter(field -> field.fieldRef().relationCode() == null)
                .collect(java.util.stream.Collectors.toMap(field -> field.fieldRef().fieldName(), field -> field,
                        (left, ignored) -> left, LinkedHashMap::new));
        if (mainFields.size() != fields.size()) {
            throw new IllegalArgumentException("form compute rules only support main-record fields: " + view.viewCode());
        }
        Set<String> codes = new LinkedHashSet<>();
        java.util.ArrayList<ResolvedFormComputeRuleDescriptor> resolved = new java.util.ArrayList<>();
        for (FormComputeRuleDefinition rule : view.formComputeRules()) {
            if (!codes.add(rule.code())) {
                throw new IllegalArgumentException("duplicate form compute rule code: " + view.viewCode() + "." + rule.code());
            }
            ResolvedViewFieldDescriptor target = mainFields.get(rule.targetField());
            if (target == null) {
                throw new IllegalArgumentException("form compute target field must be declared by the same form: "
                        + view.viewCode() + "." + rule.targetField());
            }
            if (Boolean.TRUE.equals(target.readOnly().constant())) {
                throw new IllegalArgumentException("form compute target field must be writable: "
                        + view.viewCode() + "." + rule.targetField());
            }
            requirePortableFormComputeType(target, "target", view, rule);
            for (String trigger : rule.triggerFields()) {
                if (!mainFields.containsKey(trigger)) {
                    throw new IllegalArgumentException("form compute trigger field must be declared by the same form: "
                            + view.viewCode() + "." + trigger);
                }
                if (rule.targetField().equals(trigger)) {
                    throw new IllegalArgumentException("form compute target field cannot trigger itself: "
                            + view.viewCode() + "." + rule.targetField());
                }
            }
            FormulaProgram program;
            try {
                program = FORMULA_ENGINE.compileFormComputeProgram(rule.expression());
            } catch (FormulaEvaluationException exception) {
                throw new IllegalArgumentException("form compute rule must be a FormulaEngine FORM_COMPUTE expression: "
                        + view.viewCode() + "." + rule.code() + ", " + exception.getMessage(), exception);
            }
            String assignedTarget = assignedTarget(program);
            if (!rule.targetField().equals(assignedTarget)) {
                throw new IllegalArgumentException("form compute rule target must match formula assignment: "
                        + view.viewCode() + "." + rule.code());
            }
            Set<String> inputFields = valueSideFields(program);
            for (String field : inputFields) {
                ResolvedViewFieldDescriptor input = mainFields.get(field);
                if (input == null) {
                    throw new IllegalArgumentException("form compute expression may only reference fields declared by the same form: "
                            + view.viewCode() + "." + field);
                }
                requirePortableFormComputeType(input, "input", view, rule);
            }
            if (inputFields.contains(rule.targetField())) {
                throw new IllegalArgumentException("form compute target field cannot reference itself: "
                        + view.viewCode() + "." + rule.targetField());
            }
            resolved.add(new ResolvedFormComputeRuleDescriptor(rule.code(), program, rule.targetField(),
                    target.valueType(), rule.triggerFields(), rule.writePolicy()));
        }
        return List.copyOf(resolved);
    }

    private static void requirePortableFormComputeType(ResolvedViewFieldDescriptor field,
                                                       String role,
                                                       ViewDefinition view,
                                                       FormComputeRuleDefinition rule) {
        if (field.valueType() == null || field.valueType() == FieldValueType.JSON) {
            throw new IllegalArgumentException("form compute " + role
                    + " field requires a portable non-JSON value type: " + view.viewCode() + "." + rule.code()
                    + "." + field.fieldRef().fieldName());
        }
    }

    private static String assignedTarget(FormulaProgram program) {
        FormulaNode root = program.root();
        if (root.kind() != FormulaNode.Kind.ASSIGN || root.arguments().size() != 2
                || root.arguments().getFirst().kind() != FormulaNode.Kind.FIELD) {
            return null;
        }
        return root.arguments().getFirst().field();
    }

    private static Set<String> valueSideFields(FormulaProgram program) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        collectFields(program.root().arguments().get(1), fields);
        return Set.copyOf(fields);
    }

    private static void collectFields(FormulaNode node, Set<String> fields) {
        if (node == null) return;
        if (node.kind() == FormulaNode.Kind.FIELD && node.field() != null) {
            fields.add(node.field());
        }
        node.arguments().forEach(argument -> collectFields(argument, fields));
    }

    private static ResolvedViewFieldDescriptor compileField(ModuleViewKind viewKind,
                                                            ViewFieldDefinition field,
                                                            Map<String, ResolvedOptionFieldDescriptor> optionFields,
                                                            Map<String, ResolvedReferenceFieldDescriptor> referenceFields,
                                                            Map<String, ResolvedReferenceSummaryFieldDescriptor> referenceSummaryFields,
                                                            Map<ViewFieldRef, FieldValueType> fieldTypes,
                                                            Map<String, ResolvedFieldControlDescriptor> fieldControls) {
        ResolvedReferenceSummaryFieldDescriptor referenceSummary = field.fieldRef().relationCode() == null
                ? referenceSummaryFields.get(field.fieldRef().fieldName()) : null;
        FieldValueType resolvedValueType = valueType(field.fieldRef(), fieldTypes);
        validateBooleanStatus(viewKind, field);
        validateTagList(viewKind, field, referenceSummary);
        validateValuePresentation(viewKind, field, resolvedValueType);
        String resolvedUiType = resolvedUiType(viewKind, field, resolvedValueType);
        return new ResolvedViewFieldDescriptor(
                field.fieldRef(),
                field.label(),
                field.visible(),
                field.required(),
                field.readOnly(),
                resolvedUiType,
                resolveFieldControl(resolvedUiType, resolvedValueType, field.valuePresentation(), fieldControls),
                resolvedValueType,
                field.valuePresentation(),
                field.width(),
                field.columnSpan(),
                field.align(),
                field.fixed(),
                field.booleanStatus(),
                field.fieldRef().relationCode() == null ? optionFields.get(field.fieldRef().fieldName()) : null,
                field.fieldRef().relationCode() == null ? referenceFields.get(field.fieldRef().fieldName()) : null,
                referenceSummary,
                field.maxDisplayLines(),
                field.treeRootTitle()
        );
    }

    /**
     * Static DSL keeps its established {@code uiType} spelling, while the resolved descriptor
     * always carries the adapter-neutral execution fact. A missing or unsupported alias is a
     * compilation error rather than a browser-side text-input fallback.
     */
    private static ResolvedFieldControlDescriptor resolveFieldControl(String uiType,
                                                                       FieldValueType valueType,
                                                                       FieldValuePresentation presentation,
                                                                       Map<String, ResolvedFieldControlDescriptor> fieldControls) {
        if (presentation != null) return null;
        String alias = uiType == null ? inferredControlAlias(valueType) : uiType;
        if (alias == null) return null;
        ResolvedFieldControlDescriptor descriptor = fieldControls.get(alias);
        if (descriptor == null) {
            throw new IllegalArgumentException("unsupported field control alias: " + alias);
        }
        return descriptor;
    }

    private static String inferredControlAlias(FieldValueType valueType) {
        if (valueType == null) return "text";
        return switch (valueType) {
            case BOOLEAN -> "switch";
            case INTEGER -> "integer";
            case LONG, DECIMAL -> "number";
            case DATE -> "date";
            case TIMESTAMP, ZONED_TIMESTAMP -> "datetime";
            case JSON -> "json";
            default -> "text";
        };
    }

    /**
     * Editable business booleans share the standard switch control by default. Lifecycle enablement is a
     * platform capability rather than an ordinary field, so it keeps its dedicated status control.
     */
    private static String resolvedUiType(ModuleViewKind viewKind,
                                         ViewFieldDefinition field,
                                         FieldValueType valueType) {
        if (field.uiType() != null) {
            return field.uiType();
        }
        if (viewKind != ModuleViewKind.FORM || valueType != FieldValueType.BOOLEAN) {
            return null;
        }
        return PlatformAbilityFields.ENABLED_FIELD.equals(field.fieldRef().fieldName()) ? "enabledStatus" : "switch";
    }

    private static void validateValuePresentation(ModuleViewKind viewKind,
                                                  ViewFieldDefinition field,
                                                  FieldValueType valueType) {
        if (field.valuePresentation() != FieldValuePresentation.FILE_SIZE) {
            return;
        }
        if (valueType != null && valueType != FieldValueType.LONG) {
            throw new IllegalArgumentException("file size presentation requires LONG field: "
                    + field.fieldRef().fieldName());
        }
        if (viewKind == ModuleViewKind.FORM && !Boolean.TRUE.equals(field.readOnly().constant())) {
            throw new IllegalArgumentException("file size presentation must be read-only in FORM views: "
                    + field.fieldRef().fieldName());
        }
    }

    private static FieldValueType valueType(ViewFieldRef fieldRef,
                                            Map<ViewFieldRef, FieldValueType> fieldTypes) {
        FieldValueType direct = fieldTypes.get(fieldRef);
        if (direct != null) {
            return direct;
        }
        return fieldTypes.entrySet().stream()
                .filter(entry -> java.util.Objects.equals(entry.getKey().relationCode(), fieldRef.relationCode()))
                .filter(entry -> entry.getKey().fieldName().equals(fieldRef.fieldName()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseGet(() -> STANDARD_FIELD_TYPES.get(fieldRef.fieldName()));
    }

    private static Map<ViewFieldRef, FieldValueType> fieldTypes(List<EntityDefinition> entities) {
        if (entities == null || entities.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<ViewFieldRef, FieldValueType> types = new LinkedHashMap<>();
        for (int index = 0; index < entities.size(); index++) {
            EntityDefinition entity = entities.get(index);
            String relationCode = index == 0 ? null : entity.alias();
            for (FieldDefinition field : entity.fields()) {
                types.put(fieldRef(relationCode, field.fieldName()), FieldValueType.from(field.type()));
            }
            STANDARD_FIELD_TYPES.forEach((fieldName, fieldType) ->
                    types.putIfAbsent(fieldRef(relationCode, fieldName), fieldType));
        }
        return Map.copyOf(types);
    }

    private static ViewFieldRef fieldRef(String relationCode, String fieldName) {
        return relationCode == null ? ViewFieldRef.main(fieldName) : ViewFieldRef.relation(relationCode, fieldName);
    }

    private static List<ResolvedFileReferenceFieldDescriptor> fileReferences(List<EntityDefinition> entities,
                                                                              ModuleUiDefinition uiDefinition) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        java.util.ArrayList<ResolvedFileReferenceFieldDescriptor> resolved = new java.util.ArrayList<>();
        Set<ViewFieldRef> exposedFields = uiDefinition == null ? Set.of() : declaredViews(uiDefinition).stream()
                .flatMap(view -> view.fields().stream()).map(ViewFieldDefinition::fieldRef).collect(java.util.stream.Collectors.toSet());
        for (int index = 0; index < entities.size(); index++) {
            EntityDefinition entity = entities.get(index);
            String relationCode = index == 0 ? null : entity.alias();
            entity.fileReferences().forEach((fieldName, definition) -> {
                ViewFieldRef reference = fieldRef(relationCode, fieldName);
                if (exposedFields.contains(reference)) resolved.add(ResolvedFileReferenceFieldDescriptor.from(reference, definition));
            });
        }
        return List.copyOf(resolved);
    }

    private static Map<String, FieldValueType> standardFieldTypes() {
        return Map.ofEntries(
                Map.entry(StandardEntitySchema.ID_FIELD, FieldValueType.STRING),
                Map.entry(ALIAS_FIELD, FieldValueType.STRING),
                Map.entry(StandardEntitySchema.TENANT_ID_FIELD, FieldValueType.STRING),
                Map.entry(StandardEntitySchema.VERSION_FIELD, FieldValueType.INTEGER),
                Map.entry(StandardEntitySchema.DELETED_FIELD, FieldValueType.BOOLEAN),
                Map.entry(StandardEntitySchema.DELETED_AT_FIELD, FieldValueType.TIMESTAMP),
                Map.entry(StandardEntitySchema.DELETED_BY_FIELD, FieldValueType.STRING),
                Map.entry(StandardEntitySchema.CREATED_BY_FIELD, FieldValueType.STRING),
                Map.entry(StandardEntitySchema.CREATED_AT_FIELD, FieldValueType.TIMESTAMP),
                Map.entry(StandardEntitySchema.UPDATED_BY_FIELD, FieldValueType.STRING),
                Map.entry(StandardEntitySchema.UPDATED_AT_FIELD, FieldValueType.TIMESTAMP)
        );
    }

    private static void validateBooleanStatus(ModuleViewKind viewKind, ViewFieldDefinition field) {
        if (!"booleanStatus".equals(field.uiType()) || viewKind != ModuleViewKind.FORM) {
            return;
        }
        if (!Boolean.TRUE.equals(field.readOnly().constant())) {
            throw new IllegalArgumentException("booleanStatus UI field must be read-only in FORM views: "
                    + field.fieldRef().fieldName());
        }
    }


    private static void validateTagList(ModuleViewKind viewKind,
                                        ViewFieldDefinition field,
                                        ResolvedReferenceSummaryFieldDescriptor referenceSummary) {
        if (!"tagList".equals(field.uiType())) {
            return;
        }
        if (viewKind != ModuleViewKind.LIST) {
            throw new IllegalArgumentException("tagList UI field is only supported in LIST views: "
                    + field.fieldRef().fieldName());
        }
        if (referenceSummary == null) {
            throw new IllegalArgumentException("tagList UI field must be a structured reference summary: "
                    + field.fieldRef().fieldName());
        }
        if (referenceSummary.cardinality() != net.ximatai.muyun.spring.ability.reference.ReferenceCardinality.MANY) {
            throw new IllegalArgumentException("tagList UI field must use a MANY reference summary: "
                    + field.fieldRef().fieldName());
        }
        if (!referenceSummary.includes("title")) {
            throw new IllegalArgumentException("tagList reference summary must include title: "
                    + field.fieldRef().fieldName());
        }
    }

    private static Map<String, ResolvedReferenceFieldDescriptor> staticReferenceFields(Class<?> modelClass,
                                                                                         Function<String, ReferencePickerMode> pickerModeResolver) {
        if (modelClass == null) {
            return Map.of();
        }
        return StaticReferenceResolver.rules(modelClass).stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        rule -> rule.plan().sourceField(),
                        rule -> new ResolvedReferenceFieldDescriptor(rule.target().qualifiedName(), rule.cardinality(),
                                referenceTitleField(modelClass, rule.plan().sourceField()),
                                pickerModeResolver.apply(rule.target().qualifiedName())),
                        (left, right) -> left
                ));
    }

    /**
     * A direct {@code @ReferenceLoad(source = "…", field = "title")} is the canonical
     * read-side label for a scalar reference. Deliver its output field so detail views
     * can render the resolved label without a second client request.
     */
    private static String referenceTitleField(Class<?> modelClass, String sourceField) {
        String directTitle = StaticReferenceResolver.rules(modelClass).stream()
                .filter(rule -> rule.plan().sourceField().equals(sourceField))
                .flatMap(rule -> rule.plan().projections().stream())
                .filter(projection -> "title".equals(projection.targetField()))
                .map(ReferenceProjection::outputField)
                .findFirst()
                .orElse(null);
        if (directTitle != null) {
            return directTitle;
        }
        return StaticReferenceResolver.summaryPlans(modelClass).stream()
                .filter(summary -> summary.sourceField().equals(sourceField) && summary.fields().contains("title"))
                .map(ReferenceSummaryPlan::outputField)
                .findFirst()
                .orElse(null);
    }

    private static Map<String, ResolvedReferenceSummaryFieldDescriptor> staticReferenceSummaryFields(Class<?> modelClass) {
        if (modelClass == null) {
            return Map.of();
        }
        return StaticReferenceResolver.summaryPlans(modelClass).stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        ReferenceSummaryPlan::outputField,
                        summary -> new ResolvedReferenceSummaryFieldDescriptor(summary.sourceField(),
                                summary.target().qualifiedName(), summary.cardinality(), summary.fields()),
                        (left, right) -> left
                ));
    }

    private static Map<String, ResolvedOptionFieldDescriptor> staticOptionFields(Class<?> modelClass) {
        if (modelClass == null) {
            return Map.of();
        }
        return OptionFieldResolver.resolve(modelClass).stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        OptionFieldDefinition::fieldName,
                        definition -> new ResolvedOptionFieldDescriptor(definition.binding(), definition.selectionMode(),
                                optionTitleField(modelClass, definition.fieldName()), inlineOptionItems(definition.binding())),
                        (left, right) -> left
                ));
    }

    /**
     * Enum options are immutable module-definition facts, so deliver them with the descriptor.
     * Dictionary options deliberately remain runtime reads because their contents are scope-sensitive.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<OptionItem> inlineOptionItems(OptionBinding binding) {
        if (!OptionBinding.ENUM_SOURCE.equals(binding.sourceType())) {
            return List.of();
        }
        try {
            Class<?> type = Class.forName(binding.source());
            if (!type.isEnum() || !CodeTitleEnum.class.isAssignableFrom(type)) {
                throw new IllegalArgumentException("enum option binding requires CodeTitleEnum: " + binding.source());
            }
            return java.util.Arrays.stream(type.getEnumConstants())
                    .map(value -> (CodeTitleEnum) value)
                    .map(value -> new OptionItem(value.getCode(), value.getTitle(), true,
                            ((Enum) value).ordinal() + 1, null))
                    .toList();
        } catch (ClassNotFoundException error) {
            throw new IllegalArgumentException("enum option class is unavailable: " + binding.source(), error);
        }
    }

    private static String optionTitleField(Class<?> modelClass, String sourceField) {
        return OptionLoadResolver.resolve(modelClass).stream()
                .filter(definition -> definition.sourceField().equals(sourceField))
                .filter(definition -> definition.optionItemField().equals("title"))
                .map(definition -> definition.outputField())
                .findFirst()
                .orElse(null);
    }

    private static String staticRecordLabelField(StaticModuleDefinition definition) {
        return RecordLabelResolver.resolveFieldName(definition.modelClass()).orElse(null);
    }

    private static void validateFields(ModuleUiDefinition definition, List<EntityDefinition> entityDefinitions) {
        validateFields(definition, entityDefinitions, definition.moduleAlias(), Set.of());
    }

    private static void validateFields(ModuleUiDefinition definition,
                                       List<EntityDefinition> entityDefinitions,
                                       String moduleAlias,
                                       Set<String> readProjectionOutputFields) {
        Map<String, EntityDefinition> entities = entitiesByAlias(entityDefinitions);
        if (entities.isEmpty()) {
            return;
        }
        EntityDefinition mainEntity = entityDefinitions.getFirst();
        for (ViewDefinition view : declaredViews(definition)) {
            for (ViewFieldDefinition field : view.fields()) {
                validateField(moduleAlias, view, field, entities, mainEntity, readProjectionOutputFields);
            }
        }
        for (ViewFieldRef field : pagePresentationFields(definition)) {
            if (readProjectionOutputFields.contains(field.fieldName())
                    || hasField(mainEntity, field.fieldName())
                    || PLATFORM_FIELD_NAMES.contains(field.fieldName())) {
                continue;
            }
            throw new IllegalArgumentException("page explorer field is not declared by model facts: "
                    + moduleAlias + ".explorer." + field.fieldName());
        }
    }

    private static Map<String, EntityDefinition> entitiesByAlias(StaticModuleDefinition definition) {
        return entitiesByAlias(definition.entities());
    }

    private static Map<String, EntityDefinition> entitiesByAlias(List<EntityDefinition> entityDefinitions) {
        LinkedHashMap<String, EntityDefinition> entities = new LinkedHashMap<>();
        for (EntityDefinition entity : entityDefinitions) {
            if (entity.alias() != null && !entity.alias().isBlank()) {
                entities.put(entity.alias(), entity);
            }
        }
        return entities;
    }

    private static void validateField(String moduleAlias,
                                      ViewDefinition view,
                                      ViewFieldDefinition field,
                                      Map<String, EntityDefinition> entities,
                                      EntityDefinition mainEntity,
                                      Set<String> readProjectionOutputFields) {
        ViewFieldRef fieldRef = field.fieldRef();
        if (fieldRef.relationCode() == null && readProjectionOutputFields.contains(fieldRef.fieldName())) {
            return;
        }
        EntityDefinition entity = entity(moduleAlias, view, fieldRef, entities, mainEntity);
        if (entity == null) {
            return;
        }
        if (hasField(entity, fieldRef.fieldName()) || PLATFORM_FIELD_NAMES.contains(fieldRef.fieldName())) {
            return;
        }
        throw new IllegalArgumentException("static module UI field is not declared by model facts: "
                + fieldPath(moduleAlias, view, fieldRef));
    }

    private static EntityDefinition entity(ViewFieldRef fieldRef,
                                           Map<String, EntityDefinition> entities,
                                           EntityDefinition mainEntity) {
        if (fieldRef.relationCode() == null) {
            return mainEntity;
        }
        EntityDefinition entity = entities.get(fieldRef.relationCode());
        if (entity == null) {
            throw new IllegalArgumentException("static module UI relation is not declared by model facts: "
                    + fieldRef.relationCode());
        }
        return entity;
    }

    private static EntityDefinition entity(String moduleAlias,
                                           ViewDefinition view,
                                           ViewFieldRef fieldRef,
                                           Map<String, EntityDefinition> entities,
                                           EntityDefinition mainEntity) {
        if (fieldRef.relationCode() == null) {
            return mainEntity;
        }
        if (fieldRef.relationCode().contains(".")) {
            return null;
        }
        EntityDefinition entity = entities.get(fieldRef.relationCode());
        if (entity == null) {
            throw new IllegalArgumentException("static module UI relation is not declared by model facts: "
                    + moduleAlias + "." + view.viewCode() + "." + fieldRef.relationCode());
        }
        return entity;
    }

    private static String fieldPath(String moduleAlias, ViewDefinition view, ViewFieldRef fieldRef) {
        return moduleAlias + "." + view.viewCode() + "."
                + (fieldRef.relationCode() == null ? "" : fieldRef.relationCode() + ".")
                + fieldRef.fieldName();
    }

    private static boolean hasField(EntityDefinition entity, String fieldName) {
        return entity.fields().stream()
                .map(FieldDefinition::fieldName)
                .anyMatch(fieldName::equals);
    }

    private static ResolvedModuleReadModel readModel(StaticModuleDefinition definition,
                                                     ModuleUiDefinition uiDefinition) {
        if (definition.entities().isEmpty()) {
            return new ResolvedModuleReadModel(definition.moduleAlias(), null, List.of());
        }
        EntityDefinition mainEntity = definition.entities().getFirst();
        LinkedHashMap<String, ResolvedModuleReadField> fields = new LinkedHashMap<>();
        for (EntityDefinition entity : definition.entities()) {
            String relationCode = relationCode(entity, mainEntity);
            for (FieldDefinition field : entity.fields()) {
                putReadField(fields, new ResolvedModuleReadField(
                        entity.alias(),
                        relationCode,
                        field.fieldName(),
                        false
                ));
            }
        }
        for (StaticModuleReadProjectionDefinition projection : definition.readProjections()) {
            putReadField(fields, new ResolvedModuleReadField(
                    mainEntity.alias(),
                    null,
                    projection.outputField(),
                    true
            ));
        }
        for (String outputField : referenceOutputFields(definition)) {
            putReadField(fields, new ResolvedModuleReadField(mainEntity.alias(), null, outputField, true));
        }
        for (ViewDefinition view : declaredViews(uiDefinition)) {
            for (ViewFieldDefinition field : view.fields()) {
                ViewFieldRef fieldRef = field.fieldRef();
                if (fieldRef.relationCode() != null) {
                    putReadField(fields, new ResolvedModuleReadField(
                            fieldRef.relationCode(),
                            fieldRef.relationCode(),
                            fieldRef.fieldName(),
                            true
                    ));
                    continue;
                }
                if (!PLATFORM_FIELD_NAMES.contains(fieldRef.fieldName())) {
                    continue;
                }
                EntityDefinition entity = entity(fieldRef, entitiesByAlias(definition), mainEntity);
                putReadField(fields, new ResolvedModuleReadField(
                        entity == null ? mainEntity.alias() : entity.alias(),
                        fieldRef.relationCode(),
                        fieldRef.fieldName(),
                        true
                ));
            }
        }
        for (ViewFieldRef fieldRef : pagePresentationFields(uiDefinition)) {
            EntityDefinition entity = entity(fieldRef, entitiesByAlias(definition), mainEntity);
            putReadField(fields, new ResolvedModuleReadField(
                    entity == null ? mainEntity.alias() : entity.alias(),
                    fieldRef.relationCode(), fieldRef.fieldName(), true));
        }
        return new ResolvedModuleReadModel(
                definition.moduleAlias(),
                mainEntity.alias(),
                List.copyOf(fields.values())
        );
    }

    private static List<ViewDefinition> declaredViews(ModuleUiDefinition definition) {
        java.util.ArrayList<ViewDefinition> values = new java.util.ArrayList<>();
        if (definition.defaultEditor() != null) values.add(definition.defaultEditor());
        definition.editorSurfaces().stream().map(EditorSurfaceDefinition::editor).forEach(values::add);
        if (definition.page() instanceof FlatManagementPageDefinition flat) {
            if (flat.detail().display() != null) values.add(flat.detail().display());
            if (flat.detail().editor() != null) values.add(flat.detail().editor());
        } else if (definition.page() instanceof ListDetailCardPageDefinition card) {
            values.add(card.list().list());
            if (card.detail().display() != null) values.add(card.detail().display());
            if (card.detail().editor() != null) values.add(card.detail().editor());
        }
        definition.editorContributions().stream()
                .map(PageDetailEditorContribution::editor)
                .forEach(values::add);
        return List.copyOf(values);
    }

    private static List<ViewFieldRef> pagePresentationFields(ModuleUiDefinition definition) {
        if (!(definition.page() instanceof FlatManagementPageDefinition flat)) {
            return List.of();
        }
        java.util.ArrayList<ViewFieldRef> fields = new java.util.ArrayList<>();
        fields.add(new ViewFieldRef(null, flat.explorer().titleField(), null));
        if (flat.explorer().secondaryField() != null) {
            fields.add(new ViewFieldRef(null, flat.explorer().secondaryField(), null));
        }
        if (flat.explorer().mutedWhenDisabled()) {
            fields.add(new ViewFieldRef(null, PlatformAbilityFields.ENABLED_FIELD, null));
        }
        return List.copyOf(fields);
    }

    private static void putReadField(Map<String, ResolvedModuleReadField> fields,
                                     ResolvedModuleReadField field) {
        fields.putIfAbsent((field.entityAlias() == null ? "" : field.entityAlias())
                + ":" + (field.relationCode() == null ? "" : field.relationCode())
                + ":" + field.fieldName(), field);
    }

    private static String relationCode(EntityDefinition entity, EntityDefinition mainEntity) {
        if (entity.alias() == null || entity.alias().equals(mainEntity.alias())) {
            return null;
        }
        return entity.alias();
    }

    private static Set<String> readOutputFields(StaticModuleDefinition definition) {
        LinkedHashMap<String, Boolean> fields = new LinkedHashMap<>();
        definition.readProjections().stream()
                .map(StaticModuleReadProjectionDefinition::outputField)
                .forEach(field -> fields.put(field, Boolean.TRUE));
        referenceOutputFields(definition).forEach(field -> fields.put(field, Boolean.TRUE));
        OptionLoadResolver.resolve(definition.modelClass()).forEach(load -> fields.put(load.outputField(), Boolean.TRUE));
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(fields.keySet()));
    }

    private static Set<String> referenceOutputFields(StaticModuleDefinition definition) {
        if (definition.modelClass() == null) {
            return Set.of();
        }
        LinkedHashMap<String, Boolean> fields = new LinkedHashMap<>();
        for (ReferencePlan plan : StaticReferenceResolver.plans(definition.modelClass())) {
            for (ReferenceProjection projection : plan.projections()) {
                fields.put(projection.outputField(), Boolean.TRUE);
            }
        }
        for (net.ximatai.muyun.spring.ability.reference.ReferenceLoadPath path
                : StaticReferenceResolver.loadPaths(definition.modelClass())) {
            fields.put(path.outputField(), Boolean.TRUE);
        }
        for (net.ximatai.muyun.spring.ability.reference.ReferenceSummaryPlan summary
                : StaticReferenceResolver.summaryPlans(definition.modelClass())) {
            fields.put(summary.outputField(), Boolean.TRUE);
        }
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(fields.keySet()));
    }
}
