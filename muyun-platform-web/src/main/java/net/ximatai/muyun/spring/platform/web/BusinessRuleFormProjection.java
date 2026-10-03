package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.formula.FormulaEngine;
import net.ximatai.muyun.spring.common.formula.FormulaEvaluationException;
import net.ximatai.muyun.spring.common.formula.FormulaFieldDefinition;
import net.ximatai.muyun.spring.common.formula.FormulaFieldPath;
import net.ximatai.muyun.spring.common.formula.FormulaNode;
import net.ximatai.muyun.spring.common.formula.FormulaRule;
import net.ximatai.muyun.spring.common.formula.FormulaRuleExecutionPlan;
import net.ximatai.muyun.spring.common.formula.FormulaRuleKind;
import net.ximatai.muyun.spring.common.formula.FormulaRulePhase;
import net.ximatai.muyun.spring.common.formula.FormulaValueType;
import net.ximatai.muyun.spring.common.schema.PlatformFieldPolicy;

import static net.ximatai.muyun.spring.platform.web.ResolvedViewFieldDescriptor.CalculationTiming.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Projects the portable subset of server business rules onto one already-authorised form. */
final class BusinessRuleFormProjection {
    private static final FormulaEngine ENGINE = new FormulaEngine();

    private BusinessRuleFormProjection() {
    }

    /** Converts a static Java DSL's server rule list into the existing form-compute declaration. */
    static List<FormComputeRuleDefinition> compileDslRules(String viewCode,
                                                            List<ViewFieldDefinition> fields,
                                                            List<FormulaRule> rules) {
        if (rules == null || rules.isEmpty()) return List.of();
        List<FormulaRule> calculations = rules.stream()
                .filter(rule -> rule != null && rule.enabled() && rule.kind() == FormulaRuleKind.CALCULATION)
                .toList();
        for (FormulaRule rule : calculations) {
            if (rule.phase() != FormulaRulePhase.BEFORE_SAVE) {
                throw new IllegalArgumentException("only BEFORE_SAVE calculation rules are portable to forms: " + viewCode
                        + "." + rule.id());
            }
        }
        if (calculations.isEmpty()) return List.of();
        List<String> declaredFields = fields == null ? List.of() : fields.stream()
                .filter(field -> field.fieldRef().relationCode() == null)
                .map(field -> field.fieldRef().fieldName()).toList();
        try {
            FormulaRuleExecutionPlan plan = FormulaRuleExecutionPlan.forMainRecord(calculations, declaredFields);
            return plan.orderedRules().stream().map(rule -> new FormComputeRuleDefinition(rule.id(),
                    formComputeExpression(rule, plan.calculationTargetFieldsByRule().get(rule.id())),
                    plan.calculationTargetFieldsByRule().get(rule.id()),
                    List.copyOf(plan.inputFieldsByRule().getOrDefault(rule.id(), Set.of())),
                    FormComputeWritePolicy.ALWAYS)).toList();
        } catch (FormulaEvaluationException exception) {
            throw new IllegalArgumentException("business rule cannot be projected to form: " + viewCode + ", "
                    + exception.getMessage(), exception);
        }
    }

    private static String formComputeExpression(FormulaRule rule, String target) {
        if (!ENGINE.assignedFields(rule.expression()).isEmpty()) return rule.expression();
        return "{" + target + "} = (" + rule.expression() + ")";
    }

    static ResolvedModuleUiDescriptor projectLenient(ResolvedModuleUiDescriptor descriptor, List<FormulaRule> rules) {
        if (descriptor == null || rules == null || rules.isEmpty()) return descriptor;
        ChildProjection childProjection = projectChildren(descriptor, rules);
        ResolvedModuleUiDescriptor projectedDescriptor = descriptor.withEditorContributions(childProjection.contributions());
        Map<String, FormulaFieldDefinition> childFields = directChildFields(projectedDescriptor);
        ResolvedModulePageDescriptor page = descriptor.page();
        if (page != null && page.detail() != null && page.detail().editor() != null) {
            ResolvedViewDescriptor editor = project(page.detail().editor(), rules, childFields, childProjection.immediateTargets());
            page = page.withDetail(new ResolvedPageDetailDescriptor(page.detail().emptyDescription(),
                    page.detail().createTitle(), page.detail().display(), editor, page.detail().workspaceView(),
                    page.detail().showSystemInfo()));
        }
        ResolvedViewDescriptor defaultEditor = descriptor.defaultEditor() == null ? null
                : project(descriptor.defaultEditor(), rules, childFields, childProjection.immediateTargets());
        List<ResolvedEditorSurfaceDescriptor> surfaces = descriptor.editorSurfaces().stream()
                .map(surface -> new ResolvedEditorSurfaceDescriptor(surface.key(), project(surface.editor(), rules, childFields, childProjection.immediateTargets())))
                .toList();
        Set<String> computedTargets = unconditionalTargets(writers(rules.stream()
                .filter(rule -> rule != null && rule.enabled()).toList()));
        List<ResolvedPageDetailEditorContribution> contributions = projectedDescriptor.editorContributions().stream()
                .map(contribution -> {
                    var relation = descriptor.detailRelations().stream()
                            .filter(candidate -> candidate.embeddedField() != null
                                    && candidate.targetEntityAlias().equals(contribution.resource()))
                            .findFirst().orElse(null);
                    if (relation == null) return contribution;
                    Set<String> immediateTargets = new LinkedHashSet<>(contribution.editor().formComputeRules().stream()
                            .map(ResolvedFormComputeRuleDescriptor::targetField).toList());
                    immediateTargets.addAll(relation.formComputeRules().stream()
                            .map(net.ximatai.muyun.spring.platform.ui.ResolvedRelationFormComputeRuleDescriptor::targetField).toList());
                    var fields = contribution.editor().fields().stream().map(field ->
                            computedTargets.contains(relation.parentBinding() + "." + field.fieldRef().fieldName())
                                    ? field.withComputedValue(immediateTargets.contains(field.fieldRef().fieldName())
                                        ? "自动计算，保存时以服务端校验结果为准"
                                        : "保存时自动计算",
                                        immediateTargets.contains(field.fieldRef().fieldName()) ? IMMEDIATE : ON_SAVE) : field).toList();
                    return new ResolvedPageDetailEditorContribution(contribution.resource(),
                            contribution.editor().withFields(fields));
                }).toList();
        return descriptor.withEditors(page, defaultEditor, surfaces).withEditorContributions(contributions);
    }

    private static ChildProjection projectChildren(ResolvedModuleUiDescriptor descriptor, List<FormulaRule> rules) {
        Set<String> immediateTargets = new LinkedHashSet<>();
        Map<String, List<FormulaRule>> allWriters = writers(rules.stream()
                .filter(rule -> rule != null && rule.enabled()).toList());
        List<ResolvedPageDetailEditorContribution> contributions = descriptor.editorContributions().stream()
                .map(contribution -> {
                    var relations = descriptor.detailRelations().stream()
                            .filter(relation -> relation.embeddedField() != null
                                    && !relation.readOnly()
                                    && relation.editing().saveMode() == net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationEditing.SaveMode.AGGREGATE_DRAFT
                                    && relation.targetEntityAlias().equals(contribution.resource())).toList();
                    // A shared child editor cannot carry programs for two different aggregate scopes.
                    if (relations.size() != 1) return contribution;
                    var relation = relations.getFirst();
                    String prefix = relation.parentBinding() + ".";
                    Map<String, ResolvedViewFieldDescriptor> fields = contribution.editor().fields().stream()
                            .filter(field -> field.fieldRef().relationCode() != null)
                            .filter(field -> Boolean.TRUE.equals(field.visible().constant()))
                            .filter(field -> PlatformFieldPolicy.find(field.fieldRef().fieldName()) == null)
                            .collect(Collectors.toMap(field -> field.fieldRef().fieldName(), Function.identity(),
                                    (left, right) -> left, LinkedHashMap::new));
                    Map<String, Candidate> candidates = new LinkedHashMap<>();
                    allWriters.forEach((target, writers) -> {
                        if (!target.startsWith(prefix) || writers.size() != 1) return;
                        FormulaRule rule = writers.getFirst();
                        String localTarget = target.substring(prefix.length());
                        if (!portable(fields.get(localTarget))) return;
                        try {
                            if (!target.equals(ENGINE.unconditionalCalculationTarget(rule))) return;
                            var program = ENGINE.compileRowFormComputeProgram(formComputeExpression(rule, target), relation.parentBinding());
                            Set<String> inputs = ENGINE.valueSideReferencedFields(rule.expression());
                            if (inputs.stream().anyMatch(input -> !input.startsWith(prefix)
                                    || !portable(fields.get(input.substring(prefix.length()))))) return;
                            candidates.put(target, new Candidate(rule, target, inputs, program, fields.get(localTarget).valueType()));
                        } catch (FormulaEvaluationException | IllegalArgumentException ignored) {
                            // Unsupported or unavailable inputs keep the whole dependent chain server-side.
                        }
                    });
                    Set<String> allowed = portableTargets(candidates, allWriters, Set.of());
                    List<Candidate> portable = candidates.values().stream()
                            .filter(candidate -> allowed.contains(candidate.targetField())).toList();
                    List<FormulaFieldDefinition> definitions = fields.values().stream().map(field ->
                            new FormulaFieldDefinition(FormulaFieldPath.parse(prefix + field.fieldRef().fieldName()),
                                    formulaType(field.valueType()), false, true)).toList();
                    FormulaRuleExecutionPlan plan;
                    try {
                        plan = FormulaRuleExecutionPlan.forAggregateRecord(portable.stream().map(Candidate::rule).toList(),
                                definitions, Set.of(relation.parentBinding()));
                    } catch (FormulaEvaluationException exception) {
                        return contribution;
                    }
                    Map<String, Candidate> byCode = portable.stream().collect(Collectors.toMap(
                            candidate -> candidate.rule().id(), Function.identity()));
                    List<ResolvedFormComputeRuleDescriptor> rowRules = plan.calculationRules().stream().map(rule -> {
                        Candidate candidate = byCode.get(rule.id());
                        immediateTargets.add(candidate.targetField());
                        return new ResolvedFormComputeRuleDescriptor(rule.id(), candidate.program(),
                                candidate.targetField().substring(prefix.length()), candidate.targetType(),
                                candidate.inputFields().stream().map(input -> input.substring(prefix.length())).toList(),
                                FormComputeWritePolicy.ALWAYS);
                    }).toList();
                    Set<String> localWriterTargets = allWriters.keySet().stream().filter(target -> target.startsWith(prefix))
                            .map(target -> target.substring(prefix.length())).collect(Collectors.toSet());
                    Set<String> localImmediateTargets = rowRules.stream().map(ResolvedFormComputeRuleDescriptor::targetField)
                            .collect(Collectors.toSet());
                    List<ResolvedFormComputeRuleDescriptor> projected = new ArrayList<>(rowRules);
                    projected.addAll(serverAuthoritativeAuthoredRules(contribution.editor().formComputeRules(),
                            localWriterTargets, localImmediateTargets, rowRules.stream()
                                    .map(ResolvedFormComputeRuleDescriptor::code).collect(Collectors.toSet())));
                    return new ResolvedPageDetailEditorContribution(contribution.resource(),
                            contribution.editor().withFormulaProjection(contribution.editor().fields(), projected));
                }).toList();
        return new ChildProjection(contributions, Set.copyOf(immediateTargets));
    }

    private record ChildProjection(List<ResolvedPageDetailEditorContribution> contributions, Set<String> immediateTargets) {}

    /** Compiles portable static DSL validation rules after the concrete form fields have been resolved. */
    static List<ResolvedFormValidationRuleDescriptor> compileDslValidationRules(
            String viewCode,
            List<ResolvedViewFieldDescriptor> viewFields,
            List<FormulaRule> rules) {
        if (rules == null || rules.isEmpty()) return List.of();
        Map<String, ResolvedViewFieldDescriptor> fields = viewFields == null ? Map.of() : viewFields.stream()
                .filter(field -> field.fieldRef().relationCode() == null)
                .filter(field -> Boolean.TRUE.equals(field.visible().constant()))
                .filter(field -> PlatformFieldPolicy.find(field.fieldRef().fieldName()) == null)
                .collect(Collectors.toMap(field -> field.fieldRef().fieldName(), Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));
        if (fields.isEmpty()) return List.of();
        return validationDescriptors(rules.stream().filter(rule -> rule != null && rule.enabled()).toList(),
                fieldDefinitions(fields.values()), fields);
    }

    private static ResolvedViewDescriptor project(ResolvedViewDescriptor view,
                                                  List<FormulaRule> rules,
                                                  Map<String, FormulaFieldDefinition> childFields,
                                                  Set<String> immediateChildTargets) {
        if (view == null || view.viewKind() != ModuleViewKind.FORM || rules == null || rules.isEmpty()) return view;
        Map<String, ResolvedViewFieldDescriptor> fields = visibleMainFields(view);
        List<FormulaFieldDefinition> definitions = new ArrayList<>(fieldDefinitions(fields.values()));
        definitions.addAll(childFields.values());
        List<FormulaRule> enabled = rules.stream().filter(rule -> rule != null && rule.enabled()).toList();
        if (enabled.isEmpty()) return view;
        Map<String, List<FormulaRule>> allWriters = writers(enabled);
        Set<String> serverCalculationTargets = allWriters.keySet();
        Set<String> computedTargets = unconditionalTargets(allWriters);
        List<ResolvedFormValidationRuleDescriptor> validations = validationDescriptors(enabled, definitions, fields);
        List<Candidate> candidates = new ArrayList<>();
        Set<String> codes = new LinkedHashSet<>();
        for (FormulaRule rule : enabled) {
            if (rule.kind() != FormulaRuleKind.CALCULATION || rule.phase() != FormulaRulePhase.BEFORE_SAVE) continue;
            Candidate candidate = candidate(rule, definitions, fields, childFields, view);
            if (candidate == null || !codes.add(candidate.rule().id())) {
                continue;
            }
            candidates.add(candidate);
        }
        if (candidates.isEmpty()) {
            return withAuthoritativeRules(view, List.of(), validations, serverCalculationTargets, computedTargets);
        }

        Map<String, Candidate> candidatesByTarget = uniqueCandidatesByTarget(candidates);
        Set<String> portableTargets = portableTargets(candidatesByTarget, allWriters, immediateChildTargets);
        List<Candidate> portable = candidates.stream()
                .filter(candidate -> portableTargets.contains(candidate.targetField()))
                .toList();
        if (portable.isEmpty()) return withAuthoritativeRules(view, List.of(), validations, serverCalculationTargets, computedTargets);

        FormulaRuleExecutionPlan plan;
        try {
            plan = FormulaRuleExecutionPlan.forMainRecord(portable.stream().map(Candidate::rule).toList(), definitions);
        } catch (FormulaEvaluationException exception) {
            return withAuthoritativeRules(view, List.of(), validations, serverCalculationTargets, computedTargets);
        }
        Map<String, Candidate> byCode = portable.stream()
                .collect(Collectors.toMap(candidate -> candidate.rule().id(), Function.identity(), (left, right) -> left,
                        LinkedHashMap::new));
        List<ResolvedFormComputeRuleDescriptor> automatic = plan.orderedRules().stream()
                .map(rule -> descriptor(byCode.get(rule.id())))
                .toList();
        return withAuthoritativeRules(view, automatic, validations, serverCalculationTargets, computedTargets);
    }

    private static ResolvedViewDescriptor withAuthoritativeRules(ResolvedViewDescriptor view,
                                                                  List<ResolvedFormComputeRuleDescriptor> automatic,
                                                                  List<ResolvedFormValidationRuleDescriptor> validations,
                                                                  Set<String> serverCalculationTargets,
                                                                  Set<String> computedTargets) {
        Set<String> automaticTargets = automatic.stream().map(ResolvedFormComputeRuleDescriptor::targetField)
                .collect(Collectors.toSet());
        Set<String> automaticCodes = automatic.stream().map(ResolvedFormComputeRuleDescriptor::code)
                .collect(Collectors.toSet());
        List<ResolvedViewFieldDescriptor> projectedFields = view.fields().stream()
                .map(field -> field.fieldRef().relationCode() != null ? field
                        : automaticTargets.contains(field.fieldRef().fieldName())
                            ? field.withComputedValue("自动计算，保存时以服务端校验结果为准", IMMEDIATE)
                            : computedTargets.contains(field.fieldRef().fieldName())
                                ? field.withComputedValue("保存时自动计算", ON_SAVE) : field)
                .toList();
        List<ResolvedFormComputeRuleDescriptor> remainingAuthored = serverAuthoritativeAuthoredRules(
                view.formComputeRules(), serverCalculationTargets, automaticTargets, automaticCodes);
        List<ResolvedFormComputeRuleDescriptor> rulesForView = new ArrayList<>(automatic);
        rulesForView.addAll(remainingAuthored);
        return view.withFormulaProjection(projectedFields, rulesForView, validations);
    }

    /**
     * A browser validation is issued only when it has the same directly visible, primitive inputs
     * as the form. Rejection here intentionally leaves the rule server-authoritative instead of
     * giving the browser a misleading promise.
     */
    private static List<ResolvedFormValidationRuleDescriptor> validationDescriptors(
            List<FormulaRule> rules,
            List<FormulaFieldDefinition> definitions,
            Map<String, ResolvedViewFieldDescriptor> fields) {
        List<ResolvedFormValidationRuleDescriptor> result = new ArrayList<>();
        Set<String> codes = new LinkedHashSet<>();
        for (FormulaRule rule : rules) {
            if (rule.kind() != FormulaRuleKind.VALIDATION || rule.phase() != FormulaRulePhase.BEFORE_SAVE
                    || rule.severity() != net.ximatai.muyun.spring.common.formula.FormulaIssueLevel.ERROR
                    || !rule.stopOnError() || !codes.add(rule.id())) {
                continue;
            }
            try {
                FormulaRuleExecutionPlan plan = FormulaRuleExecutionPlan.forMainRecord(List.of(rule), definitions);
                var program = ENGINE.compileFormValidationProgram(rule.expression());
                Set<String> inputs = plan.inputFieldsByRule().getOrDefault(rule.id(), Set.of());
                if (!program.referencedFields().equals(inputs)
                        || inputs.stream().anyMatch(input -> !fields.containsKey(input) || !portable(fields.get(input)))) {
                    continue;
                }
                String target = rule.targetField();
                if (target != null && (!fields.containsKey(target) || !portable(fields.get(target)))) {
                    continue;
                }
                result.add(new ResolvedFormValidationRuleDescriptor(rule.id(), program, List.copyOf(inputs), target,
                        rule.messageTemplate() == null ? "formula condition is not matched" : rule.messageTemplate()));
            } catch (FormulaEvaluationException | IllegalArgumentException ignored) {
                // The server still executes every governance rule. Only the portable subset gets a form promise.
            }
        }
        return List.copyOf(result);
    }

    private static List<ResolvedFormComputeRuleDescriptor> serverAuthoritativeAuthoredRules(
            List<ResolvedFormComputeRuleDescriptor> authored,
            Set<String> serverCalculationTargets,
            Set<String> automaticTargets,
            Set<String> automaticCodes) {
        Set<String> blockedTargets = new LinkedHashSet<>(serverCalculationTargets);
        blockedTargets.removeAll(automaticTargets);
        Set<String> blockedCodes = new LinkedHashSet<>(automaticCodes);
        List<ResolvedFormComputeRuleDescriptor> remaining = new ArrayList<>(authored);
        boolean changed;
        do {
            changed = false;
            var iterator = remaining.iterator();
            while (iterator.hasNext()) {
                ResolvedFormComputeRuleDescriptor rule = iterator.next();
                if (!blockedCodes.contains(rule.code())
                        && !blockedTargets.contains(rule.targetField())
                        && valueSideFields(rule.program()).stream().noneMatch(blockedTargets::contains)
                        && !serverCalculationTargets.contains(rule.targetField())) {
                    continue;
                }
                iterator.remove();
                if (!automaticTargets.contains(rule.targetField())) {
                    blockedTargets.add(rule.targetField());
                }
                blockedCodes.add(rule.code());
                changed = true;
            }
        } while (changed);
        return List.copyOf(remaining);
    }

    private static Set<String> valueSideFields(net.ximatai.muyun.spring.common.formula.FormulaProgram program) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        if (program != null && program.root().arguments().size() == 2) {
            collectFields(program.root().arguments().get(1), fields);
        }
        return fields;
    }

    private static void collectFields(FormulaNode node, Set<String> fields) {
        if (node == null) return;
        if (node.kind() == FormulaNode.Kind.FIELD && node.field() != null) {
            fields.add(node.field());
        }
        node.arguments().forEach(argument -> collectFields(argument, fields));
    }

    private static Candidate candidate(FormulaRule rule,
                                       List<FormulaFieldDefinition> definitions,
                                       Map<String, ResolvedViewFieldDescriptor> fields,
                                       Map<String, FormulaFieldDefinition> childFields,
                                       ResolvedViewDescriptor view) {
        try {
            FormulaRuleExecutionPlan plan = FormulaRuleExecutionPlan.forMainRecord(List.of(rule), definitions);
            String target = plan.calculationTargetFieldsByRule().get(rule.id());
            if (target == null || !fields.containsKey(target)) throw failure(view, rule, "formula target is not an exposed form field");
            if (!portable(fields.get(target))) throw failure(view, rule, "formula target requires a portable non-JSON type");
            var program = ENGINE.compileFormComputeProgram(rule.expression());
            if (!target.equals(assignedTarget(program))) throw failure(view, rule, "formula target must match root assignment");
            Set<String> inputs = plan.inputFieldsByRule().getOrDefault(rule.id(), Set.of());
            if (inputs.stream().anyMatch(input -> !fields.containsKey(input) && !childFields.containsKey(input))) {
                throw failure(view, rule, "formula input is not an exposed form field");
            }
            if (inputs.stream().anyMatch(input -> fields.containsKey(input) && !portable(fields.get(input)))) {
                throw failure(view, rule, "formula input requires a portable non-JSON type");
            }
            if (inputs.stream().anyMatch(input -> childFields.containsKey(input)
                    && childFields.get(input).type() == FormulaValueType.JSON)) {
                throw failure(view, rule, "formula child input requires a portable non-JSON type");
            }
            return new Candidate(rule, target, inputs, program, fields.get(target).valueType());
        } catch (FormulaEvaluationException | IllegalArgumentException exception) {
            return null;
        }
    }

    private static Map<String, List<FormulaRule>> writers(List<FormulaRule> rules) {
        Map<String, List<FormulaRule>> writers = new LinkedHashMap<>();
        for (FormulaRule rule : rules) {
            if (rule.kind() != FormulaRuleKind.CALCULATION || rule.phase() != FormulaRulePhase.BEFORE_SAVE) continue;
            Set<String> targets = new LinkedHashSet<>();
            if (rule.targetField() != null) targets.add(rule.targetField());
            try { targets.addAll(ENGINE.assignedFields(rule.expression())); }
            catch (FormulaEvaluationException ignored) { /* Invalid rules are never projected. */ }
            for (String target : targets)
                writers.computeIfAbsent(target, ignored -> new ArrayList<>()).add(rule);
        }
        return writers;
    }

    private static Set<String> unconditionalTargets(Map<String, List<FormulaRule>> writers) {
        Set<String> targets = new LinkedHashSet<>();
        writers.forEach((target, rules) -> {
            if (rules.size() != 1) return;
            try {
                if (target.equals(ENGINE.unconditionalCalculationTarget(rules.getFirst()))) targets.add(target);
            } catch (FormulaEvaluationException ignored) {
                // Invalid or ambiguous rules must not take manual input away from the form.
            }
        });
        return Set.copyOf(targets);
    }

    private static Map<String, Candidate> uniqueCandidatesByTarget(List<Candidate> candidates) {
        Map<String, Candidate> result = new LinkedHashMap<>();
        Set<String> duplicateTargets = new LinkedHashSet<>();
        for (Candidate candidate : candidates) {
            if (result.putIfAbsent(candidate.targetField(), candidate) != null) duplicateTargets.add(candidate.targetField());
        }
        duplicateTargets.forEach(result::remove);
        return result;
    }

    private static Set<String> portableTargets(Map<String, Candidate> candidates,
                                               Map<String, List<FormulaRule>> allWriters, Set<String> externalImmediateTargets) {
        Set<String> allowed = new LinkedHashSet<>(externalImmediateTargets);
        Set<String> localAllowed = new LinkedHashSet<>();
        Set<String> visiting = new LinkedHashSet<>();
        for (String target : candidates.keySet()) {
            if (canProject(target, candidates, allWriters, allowed, visiting)) localAllowed.add(target);
        }
        return localAllowed;
    }

    private static boolean canProject(String target,
                                      Map<String, Candidate> candidates,
                                      Map<String, List<FormulaRule>> allWriters,
                                      Set<String> allowed,
                                      Set<String> visiting) {
        if (allowed.contains(target)) return true;
        Candidate candidate = candidates.get(target);
        if (candidate == null || !visiting.add(target)) return false;
        try {
            for (String input : candidate.inputFields()) {
                List<FormulaRule> writers = allWriters.get(input);
                if (writers == null || writers.isEmpty()) continue;
                if (writers.size() != 1 || !canProject(input, candidates, allWriters, allowed, visiting)) return false;
            }
            allowed.add(target);
            return true;
        } finally {
            visiting.remove(target);
        }
    }

    private static ResolvedFormComputeRuleDescriptor descriptor(Candidate candidate) {
        return new ResolvedFormComputeRuleDescriptor(candidate.rule().id(), candidate.program(), candidate.targetField(),
                candidate.targetType(), List.copyOf(candidate.inputFields()), FormComputeWritePolicy.ALWAYS);
    }

    private static Map<String, ResolvedViewFieldDescriptor> visibleMainFields(ResolvedViewDescriptor view) {
        return view.fields().stream()
                .filter(field -> field.fieldRef().relationCode() == null)
                .filter(field -> Boolean.TRUE.equals(field.visible().constant()))
                .filter(field -> PlatformFieldPolicy.find(field.fieldRef().fieldName()) == null)
                .collect(Collectors.toMap(field -> field.fieldRef().fieldName(), Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));
    }

    private static List<FormulaFieldDefinition> fieldDefinitions(Collection<ResolvedViewFieldDescriptor> fields) {
        return fields.stream().map(field -> new FormulaFieldDefinition(FormulaFieldPath.parse(field.fieldRef().fieldName()),
                formulaType(field.valueType()), false, true)).toList();
    }

    /**
     * The parent draft stores an aggregate child's rows under its embedded relation field, whereas
     * FormulaEngine addresses the rows by the metadata relation code ({@code parentBinding}).
     * Preserve that relation-code path here so the form program and the save-time rule use the
     * same field references without exposing child fields as ordinary main-form controls.
     */
    private static Map<String, FormulaFieldDefinition> directChildFields(ResolvedModuleUiDescriptor descriptor) {
        if (descriptor.detailRelations().isEmpty() || descriptor.editorContributions().isEmpty()) return Map.of();
        Map<String, ResolvedPageDetailEditorContribution> editors = descriptor.editorContributions().stream()
                .collect(Collectors.toMap(ResolvedPageDetailEditorContribution::resource, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));
        Map<String, FormulaFieldDefinition> result = new LinkedHashMap<>();
        descriptor.detailRelations().stream()
                .filter(relation -> relation.embeddedField() != null)
                .forEach(relation -> {
                    ResolvedPageDetailEditorContribution editor = editors.get(relation.targetEntityAlias());
                    if (editor == null) return;
                    editor.editor().fields().stream()
                            .filter(field -> field.fieldRef().relationCode() != null)
                            .filter(field -> Boolean.TRUE.equals(field.visible().constant()))
                            .filter(field -> PlatformFieldPolicy.find(field.fieldRef().fieldName()) == null)
                            .filter(field -> field.valueType() != null)
                            .forEach(field -> {
                                String path = relation.parentBinding() + "." + field.fieldRef().fieldName();
                                result.putIfAbsent(path, new FormulaFieldDefinition(FormulaFieldPath.parse(path),
                                        formulaType(field.valueType()), false, false));
                            });
                });
        return Map.copyOf(result);
    }

    private static FormulaValueType formulaType(FieldValueType valueType) {
        if (valueType == null) return FormulaValueType.ANY;
        return switch (valueType) {
            case STRING -> FormulaValueType.STRING;
            case TEXT -> FormulaValueType.TEXT;
            case INTEGER -> FormulaValueType.INTEGER;
            case LONG -> FormulaValueType.LONG;
            case BOOLEAN -> FormulaValueType.BOOLEAN;
            case DATE -> FormulaValueType.DATE;
            case TIMESTAMP, ZONED_TIMESTAMP -> FormulaValueType.TIMESTAMP;
            case DECIMAL -> FormulaValueType.DECIMAL;
            case JSON -> FormulaValueType.JSON;
        };
    }

    private static boolean portable(ResolvedViewFieldDescriptor field) {
        return field != null && field.valueType() != null && field.valueType() != FieldValueType.JSON;
    }

    private static String assignedTarget(net.ximatai.muyun.spring.common.formula.FormulaProgram program) {
        if (program.root().kind() != net.ximatai.muyun.spring.common.formula.FormulaNode.Kind.ASSIGN
                || program.root().arguments().size() != 2
                || program.root().arguments().getFirst().kind() != net.ximatai.muyun.spring.common.formula.FormulaNode.Kind.FIELD) {
            return null;
        }
        return program.root().arguments().getFirst().field();
    }

    private static IllegalArgumentException failure(ResolvedViewDescriptor view, FormulaRule rule, String message) {
        return failure(view, rule, message, null);
    }

    private static IllegalArgumentException failure(ResolvedViewDescriptor view, FormulaRule rule, String message,
                                                    Throwable cause) {
        String suffix = rule == null ? "" : "." + rule.id();
        return new IllegalArgumentException("business rule cannot be projected to form: " + view.viewCode() + suffix
                + ", " + message, cause);
    }

    private record Candidate(FormulaRule rule, String targetField, Set<String> inputFields,
                             net.ximatai.muyun.spring.common.formula.FormulaProgram program,
                             FieldValueType targetType) {
    }
}
