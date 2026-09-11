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
        Map<String, FormulaFieldDefinition> childFields = directChildFields(descriptor);
        ResolvedModulePageDescriptor page = descriptor.page();
        if (page != null && page.detail() != null && page.detail().editor() != null) {
            ResolvedViewDescriptor editor = project(page.detail().editor(), rules, childFields);
            page = page.withDetail(new ResolvedPageDetailDescriptor(page.detail().emptyDescription(),
                    page.detail().createTitle(), page.detail().display(), editor, page.detail().workspaceView(),
                    page.detail().showSystemInfo()));
        }
        ResolvedViewDescriptor defaultEditor = descriptor.defaultEditor() == null ? null
                : project(descriptor.defaultEditor(), rules, childFields);
        List<ResolvedEditorSurfaceDescriptor> surfaces = descriptor.editorSurfaces().stream()
                .map(surface -> new ResolvedEditorSurfaceDescriptor(surface.key(), project(surface.editor(), rules, childFields)))
                .toList();
        return descriptor.withEditors(page, defaultEditor, surfaces);
    }

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
                                                  Map<String, FormulaFieldDefinition> childFields) {
        if (view == null || view.viewKind() != ModuleViewKind.FORM || rules == null || rules.isEmpty()) return view;
        Map<String, ResolvedViewFieldDescriptor> fields = visibleMainFields(view);
        List<FormulaFieldDefinition> definitions = new ArrayList<>(fieldDefinitions(fields.values()));
        definitions.addAll(childFields.values());
        List<FormulaRule> enabled = rules.stream().filter(rule -> rule != null && rule.enabled()).toList();
        if (enabled.isEmpty()) return view;
        Map<String, List<FormulaRule>> allWriters = writers(enabled);
        Set<String> serverCalculationTargets = allWriters.keySet();
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
            return withAuthoritativeRules(view, List.of(), validations, serverCalculationTargets);
        }

        Map<String, Candidate> candidatesByTarget = uniqueCandidatesByTarget(candidates);
        Set<String> portableTargets = portableTargets(candidatesByTarget, allWriters);
        List<Candidate> portable = candidates.stream()
                .filter(candidate -> portableTargets.contains(candidate.targetField()))
                .toList();
        if (portable.isEmpty()) return withAuthoritativeRules(view, List.of(), validations, serverCalculationTargets);

        FormulaRuleExecutionPlan plan;
        try {
            plan = FormulaRuleExecutionPlan.forMainRecord(portable.stream().map(Candidate::rule).toList(), definitions);
        } catch (FormulaEvaluationException exception) {
            return withAuthoritativeRules(view, List.of(), validations, serverCalculationTargets);
        }
        Map<String, Candidate> byCode = portable.stream()
                .collect(Collectors.toMap(candidate -> candidate.rule().id(), Function.identity(), (left, right) -> left,
                        LinkedHashMap::new));
        List<ResolvedFormComputeRuleDescriptor> automatic = plan.orderedRules().stream()
                .map(rule -> descriptor(byCode.get(rule.id())))
                .toList();
        return withAuthoritativeRules(view, automatic, validations, serverCalculationTargets);
    }

    private static ResolvedViewDescriptor withAuthoritativeRules(ResolvedViewDescriptor view,
                                                                  List<ResolvedFormComputeRuleDescriptor> automatic,
                                                                  List<ResolvedFormValidationRuleDescriptor> validations,
                                                                  Set<String> serverCalculationTargets) {
        Set<String> automaticTargets = automatic.stream().map(ResolvedFormComputeRuleDescriptor::targetField)
                .collect(Collectors.toSet());
        Set<String> automaticCodes = automatic.stream().map(ResolvedFormComputeRuleDescriptor::code)
                .collect(Collectors.toSet());
        List<ResolvedViewFieldDescriptor> projectedFields = view.fields().stream()
                .map(field -> automaticTargets.contains(field.fieldRef().fieldName()) && field.fieldRef().relationCode() == null
                        ? field.withReadOnly(UiRule.constant(true)) : field)
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
            String target = mainTarget(rule);
            if (target != null) {
                writers.computeIfAbsent(target, ignored -> new ArrayList<>()).add(rule);
            }
        }
        return writers;
    }

    private static String mainTarget(FormulaRule rule) {
        String target = rule.targetField();
        if (target == null) {
            try {
                target = ENGINE.assignedFields(rule.expression()).stream().findFirst().orElse(null);
            } catch (FormulaEvaluationException ignored) {
                return null;
            }
        }
        FormulaFieldPath path = FormulaFieldPath.parse(target);
        return path.tableKey() == null && !path.fieldName().isBlank() ? path.fieldName() : null;
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
                                               Map<String, List<FormulaRule>> allWriters) {
        Set<String> allowed = new LinkedHashSet<>();
        Set<String> visiting = new LinkedHashSet<>();
        for (String target : candidates.keySet()) {
            if (canProject(target, candidates, allWriters, allowed, visiting)) allowed.add(target);
        }
        return allowed;
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
